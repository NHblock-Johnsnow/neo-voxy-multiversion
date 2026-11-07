package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.LockSupport;

// save to the db, this can be useful for just reducing the amount of thread pools in total
// might have some issues with threading if the same section is saved from multiple threads?
public class SectionSavingService {
    private static final int SOFT_MAX_QUEUE_SIZE = 5_000;

    private final Service service;
    private record SaveEntry(WorldEngine engine, WorldSection section) {}
    private final ConcurrentLinkedDeque<SaveEntry> saveQueue = new ConcurrentLinkedDeque<>();
    private volatile long retryAtNanos;
    private volatile Throwable lastFailure;
    private static final long RETRY_DELAY_NANOS = 100_000_000L;
    private static final long SHUTDOWN_RETRY_NANOS = 5_000_000_000L;

    public SectionSavingService(ServiceManager sm) {
        this.service = sm.createService(() -> new Pair<>(this::processJob, () -> {}),
                100, "Section saving service", this::canRetry);
    }

    private boolean canRetry() {
        long deadline = this.retryAtNanos;
        return deadline == 0 || System.nanoTime() - deadline >= 0;
    }

    private void failed(Throwable failure) {
        this.lastFailure = failure;
        this.retryAtNanos = System.nanoTime() + RETRY_DELAY_NANOS;
    }

    public boolean isLive() {
        return this.service.isLive();
    }

    public Throwable getLastFailure() {
        return this.lastFailure;
    }

    private void awaitRetry() {
        boolean interrupted = Thread.interrupted();
        try {
            while (!this.canRetry()) {
                LockSupport.parkNanos(this.retryAtNanos - System.nanoTime());
                interrupted |= Thread.interrupted();
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    //Batch bounds: sections are uncompressed ~66KB each on the wire, so cap by count AND bytes or a
    //world import piles hundreds of MB of native memory into one write.
    private static final int MAX_BATCH_SECTIONS = 64;
    private static final long MAX_BATCH_BYTES = 4L << 20;

    private static final ThreadLocal<Boolean> DRAINING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private void processJob() {
        this.processJob(true);
    }

    //consumePermits=false is only for the shutdown drain: Service.shutdown() has already zeroed the
    //permits, so steal() would always fail and the big shutdown save would degrade to one at a time -
    //exactly the case batching is worth the most.
    private void processJob(boolean consumePermits) {
        this.awaitRetry();
        int remaining = consumePermits ? Math.min(MAX_BATCH_SECTIONS, this.service.numJobs() + 1)
                : MAX_BATCH_SECTIONS;
        var task = this.saveQueue.poll();
        if (task == null) return;
        boolean outermost = false;
        boolean abort = false;
        ArrayList<WorldSection> staged = null;
        WorldEngine batchEngine = null;
        SectionStorage.SectionSaveBatch batch = null;
        try {
            outermost = !DRAINING.get();
            if (outermost) DRAINING.set(Boolean.TRUE);
            staged = new ArrayList<>(MAX_BATCH_SECTIONS);
            while (true) {
                if (batchEngine != task.engine()) {
                    if (batch != null) {
                        var finished = batch;
                        batch = null;
                        try {
                            this.finishBatch(finished, staged);
                        } finally {
                            this.closeBatch(finished);
                        }
                        if (!this.canRetry()) break;
                    }
                    batchEngine = task.engine();
                    batch = batchEngine.storage.createSaveBatch();
                }
                // 从此调用开始，区段引用由 staged 或 stageSection 的 finally 负责。
                var section = task.section();
                task = null;
                this.stageSection(batch, staged, section);
                if (staged.size() >= MAX_BATCH_SECTIONS || batch.dataSize() >= MAX_BATCH_BYTES) {
                    this.finishBatch(batch, staged);
                }
                if (--remaining == 0 || !this.canRetry() || (consumePermits && !this.service.steal())) break;
                task = this.saveQueue.poll();
                if (task == null) break;
            }
        } catch (Throwable failure) {
            // add/dataSize 失败后批次可能已部分写入，整批撤回，不能再尝试 commit。
            abort = true;
            this.failed(failure);
            Logger.error("Voxy saver failed to prepare a batch; dirty data retained", failure);
        } finally {
            try {
                try {
                    if (task != null) {
                        this.saveQueue.addFirst(task);
                        if (this.service.isLive()) this.service.execute();
                    }
                } finally {
                    if (batch != null) {
                        try {
                            if (abort) this.releaseStaged(staged, false);
                            else this.finishBatch(batch, staged);
                        } finally {
                            this.closeBatch(batch);
                        }
                    }
                }
            } finally {
                if (outermost) DRAINING.set(Boolean.FALSE);
            }
        }
    }

    private void stageSection(SectionStorage.SectionSaveBatch batch, List<WorldSection> staged, WorldSection section) {
        boolean retained = false;
        try {
            section.assertNotFree();
            if (section.exchangeIsInSaveQueue(false)) {
                // 先记下引用，再进入可能部分完成后抛错的存储代码。
                staged.add(section);
                retained = true;
                section.setNotDirty();
                batch.add(section);
            }
        } finally {
            if (!retained) section.release();
        }
    }

    private void finishBatch(SectionStorage.SectionSaveBatch batch, List<WorldSection> staged) {
        if (staged.isEmpty()) return;
        boolean committed = false;
        try {
            batch.commit();
            committed = true;
            me.cortex.voxy.commonImpl.PerfStats.saveBatchCommits.increment();
            me.cortex.voxy.commonImpl.PerfStats.saveBatchSections.add(staged.size());
            this.lastFailure = null;
        } catch (Throwable failure) {
            this.failed(failure);
            Logger.error("Voxy saver failed to commit a batch of " + staged.size() + " sections", failure);
        } finally {
            this.releaseStaged(staged, committed);
        }
    }

    private void releaseStaged(List<WorldSection> staged, boolean committed) {
        Throwable failure = null;
        try {
            for (int i = 0; i < staged.size(); i++) {
                var section = staged.get(i);
                try {
                    // 失败只恢复 dirty，保留保存期间新任务取得的 flag。
                    if (!committed) section.markDirty();
                    section.release();
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                    else if (failure != error) failure.addSuppressed(error);
                }
            }
        } finally {
            staged.clear();
        }
        if (failure != null) {
            this.failed(failure);
            Logger.error("Voxy saver failed to release saved sections; remaining references released", failure);
        }
    }

    private void closeBatch(SectionStorage.SectionSaveBatch batch) {
        try {
            batch.close();
        } catch (Throwable failure) {
            this.failed(failure);
            Logger.error("Voxy saver failed to close a batch", failure);
        }
    }

    public boolean enqueueSave(WorldEngine in, WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
        //If its not enqueued for saving then enqueue it
        // flag 与引用仅在发布成功后交给队列；异常前回滚不得触发保存递归。
        if (!section.exchangeIsInSaveQueue(true)) return false;
        boolean acquired = false;
        boolean published = false;
        try {
            if (!sectionAlreadyAcquired) {
                section.acquire(); //Acquire the section for use
                acquired = true;
            }

            //Hard limit the save count to prevent OOM. Skipped while this thread is already draining -
            //see DRAINING.
            if ((!nonBlocking) && !DRAINING.get() && this.getTaskCount() > SOFT_MAX_QUEUE_SIZE) {
                //wait a bit
                Thread.yield();
                //If we are still full, process entries in the queue ourselves instead of waiting for the service
                while (this.getTaskCount() > SOFT_MAX_QUEUE_SIZE && this.service.isLive() && this.canRetry()) {
                    if (!this.service.steal()) {
                        break;
                    }
                    this.processJob();
                }
            }

            this.saveQueue.add(new SaveEntry(in, section));
            published = true;
            if (this.service.isLive()) this.service.execute();
            return true;
        } catch (RuntimeException | Error failure) {
            if (published) throw new WorldEngine.SaveEnqueueException(failure);
            section.exchangeIsInSaveQueue(false);
            if (acquired) WorldEngine.releaseFailedSave(section, failure);
            throw failure;
        }
    }

    public void shutdown() {
        // 先等待在途调用结束并收回 permits，再进行有界的手动 drain。
        if (this.service.isLive()) this.service.shutdown();
        //Manually save any remaining entries. Permits are already zeroed by shutdown(), so drain
        //without stealing them - otherwise this degrades to one section per batch.
        long started = System.nanoTime();
        while (!this.saveQueue.isEmpty()) {
            this.processJob(false);
            if (!this.saveQueue.isEmpty() && System.nanoTime() - started >= SHUTDOWN_RETRY_NANOS) {
                throw new IllegalStateException("Voxy saves are still failing; unsaved sections and storage remain open", this.lastFailure);
            }
        }
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }
}
