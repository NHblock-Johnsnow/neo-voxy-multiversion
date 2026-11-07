package me.cortex.voxy.common.thread;

import it.unimi.dsi.fastutil.HashCommon;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.Pair;

public class ServiceManager {
   private final IntConsumer jobRelease;
   private final ThreadLocal<ServiceManager.ThreadCtx> accelerationContext = ThreadLocal.withInitial(ServiceManager.ThreadCtx::new);
   private final AtomicInteger totalJobs = new AtomicInteger();
   private volatile Service[] services = new Service[0];
   private volatile boolean isShutdown = false;

   public ServiceManager(IntConsumer jobRelease) {
      this.jobRelease = jobRelease;
   }

   public Service createServiceNoCleanup(Supplier<Runnable> ctxFactory, long weight) {
      return this.createService(() -> new Pair<>(ctxFactory.get(), () -> {}), weight, "");
   }

   public Service createServiceNoCleanup(Supplier<Runnable> ctxFactory, long weight, String name) {
      return this.createService(() -> new Pair<>(ctxFactory.get(), () -> {}), weight, name);
   }

   public Service createService(Supplier<Pair<Runnable, Runnable>> ctxFactory, long weight) {
      return this.createService(ctxFactory, weight, "");
   }

   public Service createService(Supplier<Pair<Runnable, Runnable>> ctxFactory, long weight, String name) {
      return this.createService(ctxFactory, weight, name, null);
   }

   public synchronized Service createService(Supplier<Pair<Runnable, Runnable>> ctxFactory, long weight, String name, BooleanSupplier limiter) {
      Service newService = new Service(ctxFactory, this, weight, name, limiter);
      Service[] newServices = Arrays.copyOf(this.services, this.services.length + 1);
      newServices[newServices.length - 1] = newService;
      this.services = newServices;
      return newService;
   }

   public int tryRunAJob() {
      return this.services.length != 0 && this.totalJobs.get() != 0 ? this.runAJob0() : 1;
   }

   private int runAJob0() {
      if (this.services.length == 0) {
         return 1;
      } else {
         ServiceManager.ThreadCtx ctx = this.accelerationContext.get();

         label103:
         while (true) {
            long skipMsk = 0L;
            Service[] services = this.services;
            if (services.length == 0) {
               return 1;
            }

            if (this.totalJobs.get() == 0) {
               return 1;
            }

            long totalWeight = 0L;
            int shiftFactor = (ctx.shiftFactor++ & 2147483647) % services.length;
            Service selectedService = null;

            for (int i = 0; i < services.length; i++) {
               Service service = services[(i + shiftFactor) % services.length];
               if (!service.isLive()) {
                  Thread.yield();
                  continue label103;
               }

               if (service.limiter != null && !service.limiter.getAsBoolean()) {
                  skipMsk |= 1L << i;
               } else {
                  long jc = service.numJobs();
                  if (jc != 0L && selectedService == null) {
                     selectedService = service;
                  }

                  totalWeight += jc * service.weight;
               }
            }

            if (totalWeight == 0L) {
               return skipMsk != 0L ? 3 : 2;
            }

            long sample = ctx.rand(totalWeight);
            int i = 0;

            while (true) {
               label85:
               if (i < services.length) {
                  int idx = (i + shiftFactor) % services.length;
                  Service servicex = services[idx];
                  if (servicex.limiter == null || (skipMsk & 1L << i) == 0L && servicex.limiter.getAsBoolean()) {
                     sample -= servicex.numJobs() * servicex.weight;
                     if (sample <= 0L) {
                        selectedService = servicex;
                        break label85;
                     }
                  } else {
                     skipMsk |= 1L << i;
                  }

                  i++;
                  continue;
               }

               if (selectedService == null) {
                  return skipMsk != 0L ? 3 : 2;
               }

               if (selectedService.isLive() && selectedService.runJob()) {
                  // 任务许可由 Service 在 finally 中结算，包含异常退出。

                  return 0;
               }
               break;
            }
         }
      }
   }

   public void shutdown() {
      if (this.isShutdown) {
         throw new IllegalStateException("Service manager already shutdown");
      } else {
         this.isShutdown = true;

         while (this.services.length != 0) {
            Thread.yield();
            synchronized (this) {
               for (Service s : this.services) {
                  if (s.isLive()) {
                     throw new IllegalStateException("Service '" + s.name + "' was not in shutdown when manager shutdown");
                  }
               }
            }
         }

         while (this.totalJobs.get() != 0) {
            Thread.yield();
         }
      }
   }

   synchronized void removeService(Service service) {
      Service[] services = this.services;
      Service[] newServices = new Service[services.length - 1];
      int j = 0;

      for (int i = 0; i < services.length; i++) {
         if (services[i] != service) {
            newServices[j++] = services[i];
         }
      }

      if (j != newServices.length) {
         throw new IllegalStateException("Could not find the service in the services array");
      } else {
         this.services = newServices;
      }
   }

   void execute(Service service) {
      this.totalJobs.incrementAndGet();
      this.jobRelease.accept(1);
   }

   void remJobs(int remaining) {
      if (this.totalJobs.addAndGet(-remaining) < 0) {
         throw new IllegalStateException("total jobs <0");
      }
   }

   void handleException(Service service, Exception exception) {
      Logger.error("Service '" + service.name + "' on thread '" + Thread.currentThread().getName() + "' had an exception", exception);
   }

   private static final class ThreadCtx {
      int shiftFactor = 0;
      long seed = HashCommon.murmurHash3(System.nanoTime() ^ System.identityHashCode(this));

      ThreadCtx() {
      }

      long rand(long size) {
         return (this.seed = HashCommon.mix(this.seed)) % size;
      }
   }
}
