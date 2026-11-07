package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.util.ExceptionCleanup;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.util.TrackedObject;

public class PerThreadContextExecutor extends TrackedObject {
   private static final ThreadLocal<PerThreadContextExecutor.ThreadObj> THREAD_CTX = ThreadLocal.withInitial(PerThreadContextExecutor.ThreadObj::new);
   private final WeakConcurrentCleanableHashMap<PerThreadContextExecutor.ThreadObj, PerThreadContextExecutor.ThreadContext> contexts = new WeakConcurrentCleanableHashMap<>(
      this::ctxCleaner
   );
   private final Supplier<PerThreadContextExecutor.ThreadContext> contextFactory;
   private final Consumer<Exception> exceptionHandler;
   private final AtomicInteger currentRunning = new AtomicInteger();
   private volatile boolean isLive = true;

   PerThreadContextExecutor(Supplier<Pair<Runnable, Runnable>> ctxFactory) {
      this(ctxFactory, e -> Logger.error("Executor had the following exception", e));
   }

   PerThreadContextExecutor(Supplier<Pair<Runnable, Runnable>> ctxFactory, Consumer<Exception> exceptionHandler) {
      this.contextFactory = () -> new PerThreadContextExecutor.ThreadContext(ctxFactory.get());
      this.exceptionHandler = exceptionHandler;
   }

   private void ctxCleaner(PerThreadContextExecutor.ThreadContext ctx) {
      try {
         ctx.cleanup.run();
      } catch (Exception var3) {
         this.exceptionHandler.accept(var3);
      }
   }

   boolean run() {
      this.currentRunning.incrementAndGet();
      try {
          if (!this.isLive) {
              this.exceptionHandler.accept(new IllegalStateException("Executor is in shutdown"));
              return false;
          }
          try {
              var ctx = this.contexts.computeIfAbsent(THREAD_CTX.get(), this.contextFactory);
              ctx.execute.run();
          } catch (Exception failure) {
              this.exceptionHandler.accept(failure);
          }
          return true;
      } finally {
          // 工厂、任务和异常回调均可能失败，不能留下永久运行计数。
          this.currentRunning.decrementAndGet();
      }
   }

   public void shutdown() {
      if (!this.isLive) throw new IllegalStateException("Tried shutting down an executor twice");
      this.isLive = false;
      boolean interrupted = Thread.interrupted();
      try {
          while (this.currentRunning.get() != 0) {
              LockSupport.parkNanos(1_000_000L);
              interrupted |= Thread.interrupted();
          }
          var cleanup = new ExceptionCleanup(null);
          for (var ctx : this.contexts.clear()) cleanup.run(ctx.cleanup);
          cleanup.run(this::free0);
          cleanup.rethrow();
      } finally {
          if (interrupted) Thread.currentThread().interrupt();
      }
   }

   @Override
   public void free() {
      this.shutdown();
   }

   public boolean isLive() {
      return this.isLive;
   }

   private static void inner(PerThreadContextExecutor s) throws InterruptedException {
      Thread[] t = new Thread[256];
      Random r = new Random(19874396L);

      for (int i = 0; i < t.length; i++) {
         long rs = r.nextLong();
         t[i] = new Thread(() -> {
            s.run();
            Random lr = new Random(rs);

            while (lr.nextFloat() < 0.9) {
               s.run();

               try {
                  Thread.sleep((long)(100.0F * lr.nextFloat()));
               } catch (InterruptedException var5) {
                  throw new RuntimeException(var5);
               }
            }
         });
         t[i].start();
      }

      for (Thread tt : t) {
         tt.join();
      }
   }


   private static final class ThreadContext {
      private final Runnable execute;
      private final Runnable cleanup;

      private ThreadContext(Pair<Runnable, Runnable> wrap) {
         this(wrap.left(), wrap.right());
      }

      private ThreadContext(Runnable execute, Runnable cleanup) {
         this.execute = execute;
         this.cleanup = cleanup;
      }
   }

   private record ThreadObj(long id) implements LongSupplier {
      private static final AtomicLong IDENTIFIER = new AtomicLong();

      public ThreadObj() {
         this(IDENTIFIER.getAndIncrement());
      }

      @Override
      public long getAsLong() {
         return this.id;
      }
   }
}
