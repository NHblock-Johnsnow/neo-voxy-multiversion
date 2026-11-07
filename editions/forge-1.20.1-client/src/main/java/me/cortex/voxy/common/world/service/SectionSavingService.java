package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.LockSupport;
import me.cortex.voxy.common.util.Pair;

//TODO: add an option for having synced saving, that is when call enqueueSave, that will instead, instantly
// save to the db, this can be useful for just reducing the amount of thread pools in total
// might have some issues with threading if the same section is saved from multiple threads?
public class SectionSavingService {
    private static final int SOFT_MAX_QUEUE_SIZE = 5_000;

    private final Service service;
    private record SaveEntry(WorldEngine engine, WorldSection section) {}
    private final ConcurrentLinkedDeque<SaveEntry> saveQueue = new ConcurrentLinkedDeque<>();

    private static final long RETRY_DELAY_NANOS = 100_000_000L;
    private static final long SHUTDOWN_RETRY_NANOS = 5_000_000_000L;
    private static final ThreadLocal<Boolean> DRAINING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private volatile long retryAtNanos;
    private volatile Throwable lastFailure;

    public SectionSavingService(ServiceManager sm) {
        this.service = sm.createService(() -> new Pair<>(this::processJob, () -> {}),
        100, "Section saving service", this::canRetry);
    }

    private boolean canRetry() {
        long deadline = this.retryAtNanos;
        return deadline == 0 || System.nanoTime() - deadline >= 0;
    }

    public Throwable getLastFailure() {
        return this.lastFailure;
    }

    public boolean isLive() {
        return this.service.isLive();
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

    private void processJob() {
        this.awaitRetry();
        var task = this.saveQueue.poll();
        if (task == null) return;
        var section = task.section;
        boolean outermost = !DRAINING.get();
        try {
            if (outermost) DRAINING.set(Boolean.TRUE);
            section.assertNotFree();
            if (section.exchangeIsInSaveQueue(false)) {
                section.setNotDirty();
                task.engine.storage.saveSection(section);
                this.lastFailure = null;
            }
        } catch (Throwable failure) {
            // 只恢复脏标记，不能清除保存期间新任务取得的队列标记。
            section.markDirty();
            this.lastFailure = failure;
            this.retryAtNanos = System.nanoTime() + RETRY_DELAY_NANOS;
            Logger.error("Voxy saver failed to save a section; dirty data retained for retry", failure);
        } finally {
            try {
                // 最后一次释放可能再次入队；禁止递归进入同一线程的背压 drain。
                section.release();
            } finally {
                if (outermost) DRAINING.set(Boolean.FALSE);
            }
        }
    }

    /*
    public void enqueueSave(WorldSection section) {
        if (section._getSectionTracker() != null && section._getSectionTracker().engine != null) {
            this.enqueueSave(section._getSectionTracker().engine, section);
        } else {
            Logger.error("Tried saving world section, but did not have world associated");
        }
    }*/

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

            //Hard limit the save count to prevent OOM
            if ((!nonBlocking) && !DRAINING.get() && this.getTaskCount() > SOFT_MAX_QUEUE_SIZE) {
                //wait a bit
                Thread.yield();
                /*
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }*/
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
        // 先等在途任务退出，再有界重试；失败时保留队列及其引用，允许再次调用。
        if (this.service.isLive()) this.service.shutdown();
        long started = System.nanoTime();
        while (!this.saveQueue.isEmpty()) {
            this.processJob();
            if (!this.saveQueue.isEmpty() && System.nanoTime() - started >= SHUTDOWN_RETRY_NANOS) {
                throw new IllegalStateException("Voxy saves are still failing; unsaved sections and storage remain open", this.lastFailure);
            }
        }
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }
}
