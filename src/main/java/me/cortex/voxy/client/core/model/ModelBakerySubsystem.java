package me.cortex.voxy.client.core.model;

import me.cortex.voxy.common.util.ExceptionCleanup;


import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class ModelBakerySubsystem {
    //Redo to just make it request the block faces with the async texture download stream which
    // basicly solves all the render stutter due to the baking

    private final ModelStore storage = new ModelStore();
    public final ModelFactory factory;
    private final Mapper mapper;

    private final Thread processingThread;
    private volatile boolean isRunning = true;
    private volatile Throwable processingThreadException;
    public ModelBakerySubsystem(Mapper mapper) {
        this.mapper = mapper;
        try {
            this.factory = new ModelFactory(mapper, this.storage);
            this.processingThread = new Thread(()->{
                while (this.isRunning) {
                    while (this.factory.processAllThings());
                    if (Thread.interrupted()) {
                        break;
                    }
                    LockSupport.park();
                }
            }, "Model factory processor");
            this.processingThread.setUncaughtExceptionHandler((t,e)->{
                this.isRunning = false;
                if (e == null) {
                    e = new RuntimeException("unhandled excpetion not added");
                }
                this.processingThreadException = e;
            });
            this.processingThread.start();
        } catch (RuntimeException | Error failure) {
            this.releaseResources(new ExceptionCleanup(failure));
            throw failure;
        }
    }

    public void drainBlendPalette() {
        this.factory.drainBlendPalette();
    }

    public void tick(long totalBudget) {
        if (this.processingThreadException != null) {
            Logger.error(this.processingThreadException.getStackTrace().toString(), this.processingThreadException);
            throw new RuntimeException(this.processingThreadException);
        }
        this.factory.processUploads(totalBudget);
    }

    public void shutdown() {
        var cleanup = new ExceptionCleanup(null);
        this.releaseResources(cleanup);
        cleanup.rethrow();
    }

    private void releaseResources(ExceptionCleanup cleanup) {
        this.isRunning = false;
        boolean interrupted = Thread.interrupted();
        try {
            if (this.processingThread != null) {
                this.processingThread.interrupt();
                while (this.processingThread.isAlive()) {
                    LockSupport.unpark(this.processingThread);
                    try {
                        this.processingThread.join();
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    }
                }
            }
            if (this.factory != null) cleanup.run(this.factory::free);
            cleanup.run(this.storage::free);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    //This is on this side only and done like this as only worker threads call this code
    private final ReentrantLock seenIdsLock = new ReentrantLock();
    private final ReentrantLock enqueueLock = new ReentrantLock();
    private final IntOpenHashSet seenIds = new IntOpenHashSet(6000);
    public void requestBlockBake(int blockId) {
        if (this.mapper.getBlockStateCount() <= blockId
                && !me.cortex.voxy.common.world.other.SeasonalIdSpace.resolvesToState(this.mapper, blockId)) {
            Logger.error("Error, got bakeing request for out of range state id. StateId: " + blockId + " max id: " + this.mapper.getBlockStateCount(), new Exception());
            return;
        }
        this.seenIdsLock.lock();
        if (!this.seenIds.add(blockId)) {
            this.seenIdsLock.unlock();
            return;
        }
        this.seenIdsLock.unlock();
        this.enqueueLock.lock();
        try {
            this.factory.addEntry(blockId);
        } catch (Throwable t) {
            this.seenIdsLock.lock();
            try {
                this.seenIds.remove(blockId);
            } finally {
                this.seenIdsLock.unlock();
            }
            throw t;
        } finally {
            this.enqueueLock.unlock();
        }
        LockSupport.unpark(this.processingThread);
    }

    public void addBiome(Mapper.BiomeEntry biomeEntry) {
        this.factory.addBiome(biomeEntry);
        LockSupport.unpark(this.processingThread);
    }

    public void addDebugData(List<String> debug) {
        debug.add(String.format("IF/MC: %03d, %04d", this.factory.getInflightCount(),  this.factory.getBakedCount()));//Model bake queue/in flight/model baked count
    }

    public ModelStore getStore() {
        return this.storage;
    }

    public boolean areQueuesEmpty() {
        return this.factory.getInflightCount() == 0;
    }

    public int getProcessingCount() {
        return this.factory.getInflightCount();
    }
}
