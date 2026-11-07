package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.thread.UnifiedServiceThreadPool;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.SurfaceMigrationService;
import me.cortex.voxy.common.world.service.SectionSavingService;
import me.cortex.voxy.common.world.service.VoxelIngestService;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public abstract class VoxyInstance {
    // 世界引擎由引用和空闲时间共同决定生命周期，锁只保护 activeWorlds 本身。
    private volatile boolean isRunning = true;
    private final Thread worldCleaner;
    public final BooleanSupplier savingServiceRateLimiter;
    protected final UnifiedServiceThreadPool threadPool;
    protected final SectionSavingService savingService;
    protected final VoxelIngestService ingestService;
    private final SurfaceMigrationService surfaceMigrationService;

    private final StampedLock activeWorldLock = new StampedLock();
    private final HashMap<WorldIdentifier, WorldEngine> activeWorlds = new HashMap<>();

    protected final ImportManager importManager;

    public VoxyInstance() {
        Logger.info("Initializing voxy instance");
        this.threadPool = new UnifiedServiceThreadPool();
        this.savingService = new SectionSavingService(this.getServiceManager());
        this.ingestService = new VoxelIngestService(this.getServiceManager());
        this.surfaceMigrationService = new SurfaceMigrationService(this.getServiceManager());
        this.importManager = this.createImportManager();
        this.savingServiceRateLimiter = () -> this.savingService.getTaskCount() < 1200;
        this.worldCleaner = this.createWorldCleaner();
        this.worldCleaner.start();
    }

    private Thread createWorldCleaner() {
        var cleaner = new Thread(() -> {
            try {
                while (this.isRunning) {
                    Thread.sleep(1000);
                    this.cleanIdle();
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                Logger.error("Exception in world cleaner", e);
            }
        });
        cleaner.setPriority(Thread.MIN_PRIORITY);
        cleaner.setName("Active world cleaner");
        cleaner.setDaemon(true);
        return cleaner;
    }

    protected void setNumThreads(int threads) {
        if (threads < 0) {
            throw new IllegalArgumentException("Thread count must not be negative");
        }
        if (this.threadPool.setNumThreads(threads)) {
            Logger.info("Dedicated voxy thread pool size: " + threads);
        }
    }

    public void updateDedicatedThreads() {
        this.setNumThreads(3);
    }

    protected ImportManager createImportManager() {
        return new ImportManager();
    }

    public ServiceManager getServiceManager() {
        return this.threadPool.serviceManager;
    }

    public UnifiedServiceThreadPool getThreadPool() {
        return this.threadPool;
    }

    public VoxelIngestService getIngestService() {
        return this.ingestService;
    }

    public SurfaceMigrationService getSurfaceMigrationService() {
        return this.surfaceMigrationService;
    }

    public ImportManager getImportManager() {
        return this.importManager;
    }

    public WorldEngine getNullable(WorldIdentifier identifier) {
        if (!this.isRunning) {
            return null;
        }
        var cache = identifier.cachedEngineObject;
        WorldEngine world;
        if (cache == null) {
            world = null;
        } else {
            world = cache.get();
            if (world == null) {
                identifier.cachedEngineObject = null;
            } else {
                if (world.isLive()) {
                    if (world.instanceIn != this) {
                        throw new IllegalStateException("World cannot be in identifier cache, alive and not part of this instance");
                    }
                } else {
                    identifier.cachedEngineObject = null;
                    world = null;
                }
            }
        }
        if (world == null) {
            long stamp = this.activeWorldLock.readLock();
            world = this.activeWorlds.get(identifier);
            this.activeWorldLock.unlockRead(stamp);
            if (world != null) {
                identifier.cachedEngineObject = new WeakReference<>(world);
            }
        }
        if (world != null) {
            world.markActive();
        }
        return world;
    }

    public WorldEngine getOrCreate(WorldIdentifier identifier) {
        return this.getOrCreate(identifier, false);
    }

    public WorldEngine getOrCreate(WorldIdentifier identifier, boolean incrementRef) {
        if (!this.isRunning) {
            Logger.error("Tried getting world object on voxy instance but its not running");
            return null;
        }
        var world = this.getNullable(identifier);
        if (world != null) {
            world.markActive();
            if (incrementRef) {
                world.acquireRef();
            }
            return world;
        }
        long stamp = this.activeWorldLock.writeLock();

        if (!this.isRunning) {
            Logger.error("Tried getting world object on voxy instance but its not running");
            this.activeWorldLock.unlockWrite(stamp);
            return null;
        }

        world = this.activeWorlds.get(identifier);
        if (world == null) {
            world = this.createWorld(identifier);
        }
        world.markActive();

        if (incrementRef) {
            world.acquireRef();
        }

        this.activeWorldLock.unlockWrite(stamp);
        identifier.cachedEngineObject = new WeakReference<>(world);
        return world;
    }


    protected abstract SectionStorage createStorage(WorldIdentifier identifier);

    private WorldEngine createWorld(WorldIdentifier identifier) {
        if (!this.isRunning) {
            throw new IllegalStateException("Cannot create world while not running");
        }
        if (this.activeWorlds.containsKey(identifier)) {
            throw new IllegalStateException("Existing world with identifier");
        }
        Logger.info("Creating new world engine: " + identifier.getLongHash() + "@" + System.identityHashCode(this));
        var world = new WorldEngine(this.createStorage(identifier), this);
        world.setSaveCallback(this.savingService::enqueueSave);
        this.activeWorlds.put(identifier, world);
        return world;
    }

    public void cleanIdle() {
        List<WorldIdentifier> idleWorlds = null;
        List<WorldEngine> retryWorlds = null;
        long readStamp = this.activeWorldLock.readLock();
        try {
            for (var entry : this.activeWorlds.entrySet()) {
                if (entry.getValue().hasPendingSaveRetries()) {
                    if (retryWorlds == null) retryWorlds = new ArrayList<>();
                    retryWorlds.add(entry.getValue());
                }
                if (entry.getValue().isWorldIdle()) {
                    if (idleWorlds == null) idleWorlds = new ArrayList<>();
                    idleWorlds.add(entry.getKey());
                }
            }
        } finally {
            this.activeWorldLock.unlockRead(readStamp);
        }

        // 回调可能取得世界写锁，不能在 activeWorldLock 内重试。
        if (retryWorlds != null) {
            for (var world : retryWorlds) world.retryFailedSaves();
        }
        if (idleWorlds == null) return;

        long writeStamp = this.activeWorldLock.writeLock();
        try {
            for (var id : idleWorlds) {
                var world = this.activeWorlds.remove(id);
                if (world == null) continue;
                if (!world.isWorldIdle()) {
                    this.activeWorlds.put(id, world);
                    continue;
                }
                Logger.info("Shutting down idle world: " + id.getLongHash());
                world.free();
            }
        } finally {
            this.activeWorldLock.unlockWrite(writeStamp);
        }
    }

    public void addDebug(List<String> debug) {
        debug.add("MemoryBuffer, Count/Size (MB): " + MemoryBuffer.getCount() + "/" + (MemoryBuffer.getTotalSize() / 1_000_000));
        String sectionCounts = this.snapshotWorlds().stream()
                .map(world -> Integer.toString(world.getActiveSectionCount()))
                .collect(Collectors.joining(", "));
        debug.add("I/S/AWSC: " + this.ingestService.getTaskCount() + "/" + this.savingService.getTaskCount() + "/[" + sectionCounts + "]");
    }

    private List<WorldEngine> snapshotWorlds() {
        long stamp = this.activeWorldLock.readLock();
        try {
            return new ArrayList<>(this.activeWorlds.values());
        } finally {
            this.activeWorldLock.unlockRead(stamp);
        }
    }

    private void awaitWorldQuiescence(List<WorldEngine> worlds) {
        boolean interrupted = Thread.interrupted();
        try {
            long failureSince = 0;
            long nextReport = System.nanoTime() + 2_000_000_000L;
            while (true) {
                int busyWorlds = 0;
                int loadedSections = 0;
                Throwable publicationFailure = null;
                for (var world : worlds) {
                    world.retryFailedSaves();
                    Throwable enqueueFailure = world.getLastSaveEnqueueFailure();
                    if (enqueueFailure != null) publicationFailure = enqueueFailure;
                    if (world.isLive() && world.isWorldUsed()) {
                        busyWorlds++;
                        loadedSections += world.getActiveSectionCount();
                    }
                }
                if (busyWorlds == 0) {
                    return;
                }

                if (!this.savingService.isLive()) this.savingService.shutdown();
                long now = System.nanoTime();
                Throwable failure = publicationFailure != null ? publicationFailure : this.savingService.getLastFailure();
                if (failure == null) failureSince = 0;
                else if (failureSince == 0) failureSince = now;
                if (failureSince != 0 && now - failureSince >= 5_000_000_000L) {
                    throw new IllegalStateException("Voxy saves are failing; storage remains open for retry",
                            failure);
                }
                if (now >= nextReport) {
                    Logger.warn("Waiting for " + busyWorlds + " Voxy world engine(s) to release; loaded sections: " + loadedSections);
                    nextReport = now + 2_000_000_000L;
                }
                interrupted |= Thread.interrupted();
                LockSupport.parkNanos(1_000_000L);
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        Logger.info("Shutting down voxy instance");
        this.isRunning = false;
        this.stopWorldCleaner();

        this.cleanIdle();
        var worlds = this.snapshotWorlds();
        this.cancelWorldImports(worlds);

        // Keep the saver alive until final section releases have queued their writes.
        shutdownService("surface migration", this.surfaceMigrationService::shutdown);
        shutdownService("ingest", this.ingestService::shutdown);
        if (!this.savingService.isLive()) this.savingService.shutdown();
        this.awaitWorldQuiescence(worlds);
        this.savingService.shutdown();

        this.freeWorlds();
        shutdownService("thread pool", this.threadPool::shutdown);
        Logger.info("Instance shutdown");
    }

    private void stopWorldCleaner() {
        this.worldCleaner.interrupt();
        try {
            this.worldCleaner.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.error("Interrupted while stopping the Voxy world cleaner", e);
        }
    }

    private void cancelWorldImports(List<WorldEngine> worlds) {
        for (var world : worlds) {
            this.importManager.cancelImport(world);
        }
    }

    private void freeWorlds() {
        long stamp = this.activeWorldLock.writeLock();
        try {
            for (var entry : this.activeWorlds.entrySet()) {
                entry.getKey().cachedEngineObject = null;
                var world = entry.getValue();
                if (world.isLive()) {
                    world.free();
                }
            }
            this.activeWorlds.clear();
        } finally {
            this.activeWorldLock.unlockWrite(stamp);
        }
    }

    private void shutdownService(String name, Runnable shutdown) {
        try {
            shutdown.run();
        } catch (Exception e) {
            Logger.error("Failed to shut down " + name + " service", e);
        }
    }

    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return true;
    }

    public boolean isRunning() {
        return this.isRunning;
    }
}
