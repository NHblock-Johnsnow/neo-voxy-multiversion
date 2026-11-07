package me.cortex.voxy.common.thread;

import java.util.ArrayList;
import java.util.List;

public class UnifiedServiceThreadPool {
   public final ServiceManager serviceManager;
   public final MultiThreadPrioritySemaphore groupSemaphore;
   private final MultiThreadPrioritySemaphore.Block selfBlock;
   private final ThreadGroup dedicatedPool;
   private final List<Thread> threads = new ArrayList<>();
   private int threadId = 0;

   public UnifiedServiceThreadPool() {
      this.dedicatedPool = new ThreadGroup("Voxy Dedicated Service");
      this.serviceManager = new ServiceManager(this::release);
      this.groupSemaphore = new MultiThreadPrioritySemaphore(this.serviceManager::tryRunAJob);
      this.selfBlock = this.groupSemaphore.createBlock();
   }

   private final void release(int i) {
      this.groupSemaphore.pooledRelease(i);
   }

   public boolean setNumThreads(int threads) {
      synchronized (this.threads) {
         int diff = threads - this.threads.size();
         if (diff == 0) {
            return false;
         }

         if (diff < 0) {
            this.selfBlock.release(-diff);
         } else {
            for (int i = 0; i < diff; i++) {
               Thread t = new Thread(this.dedicatedPool, this::workerThread, "Dedicated Voxy Worker #" + this.threadId++);
               t.setPriority(3);
               t.setDaemon(true);
               this.threads.add(t);
               t.start();
            }
         }
      }

      while (true) {
         synchronized (this.threads) {
            if (this.threads.size() == threads) {
               return true;
            }
         }

         try {
            Thread.sleep(50L);
         } catch (InterruptedException var8) {
            throw new RuntimeException(var8);
         }
      }
   }

   private void workerThread() {
      this.selfBlock.acquire();
      synchronized (this.threads) {
         this.threads.remove(Thread.currentThread());
      }
   }

   public void shutdown() {
      this.serviceManager.shutdown();
      this.selfBlock.release(10000);

      while (true) {
         synchronized (this.threads) {
            if (this.threads.isEmpty()) {
               break;
            }
         }

         try {
            Thread.sleep(100L);
         } catch (InterruptedException var3) {
            throw new RuntimeException(var3);
         }
      }

      this.selfBlock.free();
   }


}
