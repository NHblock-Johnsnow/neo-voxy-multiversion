package me.cortex.voxy.client.core.model;

import me.cortex.voxy.common.util.ExceptionCleanup;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;

public class ModelBakerySubsystem {
   private final ModelStore storage = new ModelStore();
   public final ModelFactory factory;
   private final Mapper mapper;
   private final Thread processingThread;
   private volatile boolean isRunning = true;
   private volatile Throwable processingThreadException;
   private final ReentrantLock seenIdsLock = new ReentrantLock();
   private final ReentrantLock enqueueLock = new ReentrantLock();
   private final IntOpenHashSet seenIds = new IntOpenHashSet(6000);

   public ModelBakerySubsystem(Mapper mapper) {
        this.mapper = mapper;
        try {
            this.factory = new ModelFactory(mapper, this.storage);
            this.processingThread = new Thread(() -> {
               while (this.isRunning) {
                  while (this.factory.processAllThings()) {
                  }

                  LockSupport.park();
               }
            }, "Model factory processor");
            this.processingThread.setUncaughtExceptionHandler((t, e) -> {
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

   public void tick(long totalBudget) {
      if (this.processingThreadException != null) {
         throw new RuntimeException(this.processingThreadException);
      } else {
         this.factory.processUploads(totalBudget);
      }
   }

   public void drainBlendPalette() { this.factory.drainBlendPalette(); }

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

   public void requestBlockBake(int blockId) {
      if (this.mapper.getBlockStateCount() <= blockId) {
         Logger.error(
            "Error, got bakeing request for out of range state id. StateId: " + blockId + " max id: " + this.mapper.getBlockStateCount(), new Exception()
         );
      } else {
         this.seenIdsLock.lock();
         if (!this.seenIds.add(blockId)) {
            this.seenIdsLock.unlock();
         } else {
            this.seenIdsLock.unlock();
            this.enqueueLock.lock();
            this.factory.addEntry(blockId);
            this.enqueueLock.unlock();
            LockSupport.unpark(this.processingThread);
         }
      }
   }

   public void addBiome(Mapper.BiomeEntry biomeEntry) {
      this.factory.addBiome(biomeEntry);
      LockSupport.unpark(this.processingThread);
   }

   public void addDebugData(List<String> debug) {
      debug.add(String.format("IF/MC: %03d, %04d", this.factory.getInflightCount(), this.factory.getBakedCount()));
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
