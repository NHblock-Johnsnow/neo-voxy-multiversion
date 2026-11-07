package me.cortex.voxy.common.world;

import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 按可见旧段迁移；每次仅处理一个 L0 段，缺源时保留旧显示。 */
public final class SurfaceMigrationService {
    private static final int MAX_PENDING = 64;
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final Service service;
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final Map<WorldEngine, Map<Long, Job>> pending = new IdentityHashMap<>();
    private int count;
    private volatile boolean accepting = true;
    private volatile boolean paused;

    private static final class Job {
        final WorldEngine world;
        final WorldSection target;
        int cursor;

        Job(WorldEngine world, WorldSection target) {
            this.world = world;
            this.target = target;
        }
    }

    public SurfaceMigrationService(ServiceManager pool) {
        this.service = pool.createServiceNoCleanup(() -> {
            var scratch = VoxelizedSection.createEmpty();
            return () -> this.process(scratch);
        }, 100, "Surface cache migration");
    }

    public synchronized void request(WorldEngine world, WorldSection target) {
        if (!this.accepting || this.paused || !world.isLive() || target.lvl <= 0 || target.lvl > WorldEngine.MAX_LOD_LAYER
                || target.getSurfaceData().mode() == 1 || this.count == MAX_PENDING) return;
        long retry = target.surfaceMigrationRetryAfter;
        if (retry != 0 && System.nanoTime() - retry < 0) return;
        var jobs = this.pending.get(world);
        if (jobs != null && jobs.containsKey(target.key)) return;
        // 请求来自持有主段引用的建面任务，世界关闭前必须先关闭本服务。
        world.acquireRef();
        try {
            target.acquire();
        } catch (RuntimeException | Error error) {
            world.releaseRef();
            throw error;
        }
        var job = new Job(world, target);
        if (jobs == null) {
            jobs = new HashMap<>();
            this.pending.put(world, jobs);
        }
        jobs.put(target.key, job);
        this.count++;
        this.queue.addLast(job);
        this.service.execute();
    }

    public void setPaused(boolean paused) {
        // 已发布的 coverage 即为检查点；暂停后队列有界退出，恢复时跳过已完成输入。
        this.paused = paused;
    }

    private void process(VoxelizedSection scratch) {
        Job job;
        synchronized (this) {
            job = this.queue.removeFirst();
        }
        boolean more = false;
        try {
            if (this.accepting && !this.paused && !Thread.currentThread().isInterrupted()
                    && job.target.getSurfaceData().mode() != 1) more = this.step(job, scratch);
        } finally {
            boolean release;
            synchronized (this) {
                release = !more || !this.accepting || this.paused;
                if (release) {
                    var jobs = this.pending.get(job.world);
                    jobs.remove(job.target.key);
                    if (jobs.isEmpty()) this.pending.remove(job.world);
                    this.count--;
                    job.target.surfaceMigrationRetryAfter = this.paused ? 0 : System.nanoTime() + RETRY_NANOS;
                } else {
                    // 每处理一个 L0 段回到队尾，避免某个 LOD4 独占工作线程。
                    this.queue.addLast(job);
                    this.service.execute();
                }
            }
            if (release) release(job);
        }
    }

    private boolean step(Job job, VoxelizedSection scratch) {
        var target = job.target;
        int level = target.lvl, side = 1 << level, mask = side - 1;
        int index = job.cursor++;
        int x = (target.x << level) + (index & mask);
        int z = (target.z << level) + ((index >> level) & mask);
        int y = (target.y << level) + (index >> (level * 2));
        if (needsSource(target, x, y, z)) {
            var source = job.world.acquireIfExists(0, x, y, z);
            if (source != null) {
                try {
                    synchronized (source) {
                        for (int tile = 0; tile < 8; tile++) {
                            if (!this.accepting || this.paused || Thread.currentThread().isInterrupted()) return false;
                            int dx = tile & 1, dz = (tile >> 1) & 1, dy = tile >> 2;
                            if (!source.legacySurfaceSource && !source.getSurfaceData().isKnownVoxel(
                                    WorldSection.getIndex(dx * 16, dy * 16, dz * 16))) continue;
                            scratch.setPosition((x << 1) + dx, (y << 1) + dy, (z << 1) + dz);
                            if (target.getSurfaceData().isKnownVoxel(inputIndex(scratch, level))) continue;
                            for (int sy = 0; sy < 16; sy++) for (int sz = 0; sz < 16; sz++) {
                                int from = WorldSection.getIndex(dx * 16, dy * 16 + sy, dz * 16 + sz);
                                int to = (sy << 8) | (sz << 4);
                                for (int sx = 0; sx < 16; sx++) scratch.section[to + sx] = source.data[from + sx];
                            }
                            WorldVoxilizedSectionMipper.mipSection(scratch, job.world.getMapper());
                            WorldUpdater.migrateInput(job.world, scratch, target);
                        }
                    }
                } finally {
                    source.release(WorldSection.RELEASE_HINT_DONT_CACHE);
                }
            }
        }
        return job.cursor < side * side * side;
    }

    private static int inputIndex(VoxelizedSection input, int level) {
        int mask = (1 << (level + 1)) - 1;
        return WorldSection.getIndex((input.x & mask) << (4 - level),
                (input.y & mask) << (4 - level), (input.z & mask) << (4 - level));
    }

    private static boolean needsSource(WorldSection target, int x, int y, int z) {
        var coverage = target.getSurfaceData();
        int level = target.lvl, step = 16 >> level;
        int ox = ((x << 1) & ((1 << (level + 1)) - 1)) * step;
        int oy = ((y << 1) & ((1 << (level + 1)) - 1)) * step;
        int oz = ((z << 1) & ((1 << (level + 1)) - 1)) * step;
        for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
            if (!coverage.isKnownVoxel(WorldSection.getIndex(ox + dx * step, oy + dy * step, oz + dz * step))) return true;
        }
        return false;
    }

    public void shutdown() {
        synchronized (this) {
            this.accepting = false;
        }
        this.service.shutdown();
        // 运行任务已退出；剩余任务未改数据，不需要等待整片迁移完成。
        Throwable failure = null;
        Job job;
        while ((job = this.queue.pollFirst()) != null) {
            try {
                release(job);
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        synchronized (this) {
            this.pending.clear();
            this.count = 0;
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private static void release(Job job) {
        try {
            job.target.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
        } finally {
            job.world.releaseRef();
        }
    }
}
