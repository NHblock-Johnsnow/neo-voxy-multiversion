package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.common.util.ExceptionCleanup;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;
import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

public class RenderGenerationService {
   private static final int MAX_HOLDING_SECTION_COUNT = 1000;
   public static final AtomicInteger MESH_FAILED_COUNTER = new AtomicInteger();
   private static final AtomicInteger COUNTER = new AtomicInteger();
   private final AtomicInteger holdingSectionCount = new AtomicInteger();
   private final AtomicInteger taskQueueCount = new AtomicInteger();
   private final PriorityBlockingQueue<RenderGenerationService.BuildTask> taskQueue = new PriorityBlockingQueue<>(
      5000, (a, b) -> Long.compareUnsigned(a.priority, b.priority)
   );
   private final StampedLock taskMapLock = new StampedLock();
   private final Long2ObjectOpenHashMap<RenderGenerationService.BuildTask> taskMap = new Long2ObjectOpenHashMap(5000);
   private final WorldEngine world;
   private final ModelBakerySubsystem modelBakery;
   private Consumer<BuiltSection> resultConsumer;
   private final boolean emitMeshlets;
   private final Service service;
   private long lastChangedTime = 0L;

   public RenderGenerationService(WorldEngine world, ModelBakerySubsystem modelBakery, ServiceManager sm, boolean emitMeshlets) {
      this.emitMeshlets = emitMeshlets;
      this.world = world;
      this.modelBakery = modelBakery;
      this.service = sm.createService(() -> {
         RenderDataFactory factory = new RenderDataFactory(this.world, this.modelBakery.factory, this.emitMeshlets);
         IntOpenHashSet seenMissed = new IntOpenHashSet(128);
         return new Pair<>(() -> this.processJob(factory, seenMissed), factory::free);
      }, 10L, "Section mesh generation service");
   }

   public void setResultConsumer(Consumer<BuiltSection> consumer) {
      this.resultConsumer = consumer;
   }

   private void computeAndRequestRequiredModels(IntOpenHashSet seenMissedIds, int bitMsk, long[] auxData) {
      ModelFactory factory = this.modelBakery.factory;

      for (int i = 0; i < 6; i++) {
         if ((bitMsk & 1 << i) != 0) {
            for (int j = 0; j < 1024; j++) {
               int block = Mapper.getBlockId(auxData[j + i * 32 * 32]);
               if (block != 0 && !factory.hasModelForBlockId(block) && seenMissedIds.add(block)) {
                  this.modelBakery.requestBlockBake(block);
               }
            }
         }
      }
   }

   private void computeAndRequestRequiredModels(IntOpenHashSet seenMissedIds, WorldSection section) {
      ModelFactory factory = this.modelBakery.factory;

      for (long state : section._unsafeGetRawDataArray()) {
         int block = Mapper.getBlockId(state);
         if (block != 0 && !factory.hasModelForBlockId(block) && seenMissedIds.add(block)) {
            this.modelBakery.requestBlockBake(block);
         }
      }
   }

   private WorldSection acquireSection(long pos) {
      return this.world.acquireIfExists(pos);
   }

   private static boolean putTaskFirst(long pos) {
      return WorldEngine.getLevel(pos) > 2;
   }

   private void processJob(RenderDataFactory factory, IntOpenHashSet seenMissedIds) {
      RenderGenerationService.BuildTask task = this.taskQueue.poll();
      this.taskQueueCount.decrementAndGet();
      boolean shouldFreeSection = true;
      WorldSection section;
      if (task.section == null) {
         section = this.acquireSection(task.position);
      } else {
         section = task.section;
      }

      long stamp = this.taskMapLock.writeLock();
      RenderGenerationService.BuildTask rtask = (RenderGenerationService.BuildTask)this.taskMap.remove(task.position);
      if (rtask != task) {
         this.taskMapLock.unlockWrite(stamp);
         throw new IllegalStateException();
      } else {
         this.taskMapLock.unlockWrite(stamp);
         if (section == null) {
            if (this.resultConsumer != null) {
               this.resultConsumer.accept(BuiltSection.empty(task.position));
            }
         } else {
            section.assertNotFree();
            BuiltSection mesh = null;

            try {
               mesh = factory.generateMesh(section);
            } catch (IdNotYetComputedException var11) {
               long stampx = this.taskMapLock.writeLock();
               RenderGenerationService.BuildTask other = (RenderGenerationService.BuildTask)this.taskMap.putIfAbsent(task.position, task);
               this.taskMapLock.unlockWrite(stampx);
               if (other != null) {
                  if (var11.isIdBlockId && !this.modelBakery.factory.hasModelForBlockId(var11.id) && seenMissedIds.add(var11.id)) {
                     this.modelBakery.requestBlockBake(var11.id);
                  }

                  if (task.hasDoneModelRequestInner) {
                     other.hasDoneModelRequestInner = true;
                  }

                  if (task.hasDoneModelRequestOuter) {
                     other.hasDoneModelRequestOuter = true;
                  }

                  if (task.section != null) {
                     this.holdingSectionCount.decrementAndGet();
                  }

                  task.section = null;
                  shouldFreeSection = true;
                  task = null;
               }

               if (task != null) {
                  if (var11.isIdBlockId && !this.modelBakery.factory.hasModelForBlockId(var11.id) && seenMissedIds.add(var11.id)) {
                     this.modelBakery.requestBlockBake(var11.id);
                  }

                  if (task.hasDoneModelRequestOuter || task.hasDoneModelRequestInner) {
                     MESH_FAILED_COUNTER.incrementAndGet();
                  }

                  if (task.hasDoneModelRequestInner && task.hasDoneModelRequestOuter) {
                     task.attempts++;
                  } else {
                     if (task.hasDoneModelRequestInner) {
                        task.attempts++;
                     }

                     if (!task.hasDoneModelRequestInner) {
                        if (var11.auxData == null) {
                           this.computeAndRequestRequiredModels(seenMissedIds, section);
                        }

                        task.hasDoneModelRequestInner = true;
                     }

                     if (task.hasDoneModelRequestOuter) {
                        task.attempts++;
                     }

                     if (!task.hasDoneModelRequestOuter && var11.auxData != null) {
                        this.computeAndRequestRequiredModels(seenMissedIds, var11.auxBitMsk, var11.auxData);
                        task.hasDoneModelRequestOuter = true;
                     }

                     task.addin = WorldEngine.getLevel(task.position) > 2 ? 1 : 0;
                  }

                  if (task.section == null) {
                     if (this.holdingSectionCount.get() < 1000) {
                        this.holdingSectionCount.incrementAndGet();
                        task.section = section;
                        shouldFreeSection = false;
                     }
                  } else {
                     shouldFreeSection = false;
                  }

                  task.updatePriority();
                  this.taskQueue.add(task);
                  this.taskQueueCount.incrementAndGet();
                  if (this.service.isLive()) {
                     this.service.execute();
                  }
               }
            }

            if (shouldFreeSection) {
               if (task != null && task.section != null) {
                  this.holdingSectionCount.decrementAndGet();
               }

               section.release();
            }

            if (mesh != null) {
               if (this.resultConsumer != null) {
                  this.resultConsumer.accept(mesh);
               } else {
                  mesh.free();
               }
            }
         }
      }
   }

   public void enqueueTask(long pos) {
      if (this.service.isLive()) {
         boolean[] isOurs = new boolean[1];
         long stamp = this.taskMapLock.writeLock();
         RenderGenerationService.BuildTask task = (RenderGenerationService.BuildTask)this.taskMap.computeIfAbsent(pos, p -> {
            isOurs[0] = true;
            return new RenderGenerationService.BuildTask(p);
         });
         this.taskMapLock.unlockWrite(stamp);
         if (isOurs[0]) {
            task.updatePriority();
            this.taskQueue.add(task);
            this.taskQueueCount.incrementAndGet();
            this.service.execute();
         }
      }
   }

   public void shutdown() {
      var cleanup = new ExceptionCleanup(null);
      // 先停止调度并等待在途任务，剩余队列只需清理一遍。
      cleanup.run(this.service::shutdown);
      long stamp = this.taskMapLock.writeLock();
      try {
          BuildTask task;
          while ((task = this.taskQueue.poll()) != null) {
              this.taskQueueCount.decrementAndGet();
              if (task.section != null) {
                  var section = task.section;
                  task.section = null;
                  this.holdingSectionCount.decrementAndGet();
                  cleanup.run(section::release);
              }
              if (this.taskMap.remove(task.position) != task) {
                  cleanup.run(() -> { throw new IllegalStateException("Mesh task map mismatch"); });
              }
          }
          if (this.taskQueueCount.get() != 0 || !this.taskMap.isEmpty() || this.holdingSectionCount.get() != 0) {
              cleanup.run(() -> { throw new IllegalStateException("Mesh task cleanup incomplete"); });
          }
      } finally {
          this.taskMapLock.unlockWrite(stamp);
      }
      cleanup.rethrow();
   }

   public void addDebugData(List<String> debug) {
      if (System.currentTimeMillis() - this.lastChangedTime > 100L) {
         MESH_FAILED_COUNTER.set(0);
         this.lastChangedTime = System.currentTimeMillis();
      }

      debug.add("RSSQ/TFC: " + this.taskQueueCount.get() + "/" + MESH_FAILED_COUNTER.get());
   }

   public int getTaskCount() {
      return this.taskQueueCount.get();
   }

   private static final class BuildTask {
      WorldSection section;
      final long position;
      boolean hasDoneModelRequestInner;
      boolean hasDoneModelRequestOuter;
      int attempts;
      int addin;
      long priority = Long.MIN_VALUE;

      private BuildTask(long position) {
         this.position = position;
      }

      private void updatePriority() {
         int unique = RenderGenerationService.COUNTER.incrementAndGet();
         int lvl = 4 - WorldEngine.getLevel(this.position);
         lvl = Math.min(lvl, 3);
         this.priority = ((lvl * 3L + Math.min(this.attempts, 3)) * 2L + this.addin << 32) + Integer.toUnsignedLong(unique);
         this.addin = 0;
      }
   }
}
