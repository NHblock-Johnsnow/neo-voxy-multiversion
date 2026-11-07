package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.common.util.ExceptionCleanup;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.GeometryCache;
import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.lwjgl.opengl.ARBUniformBufferObject;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

public class AsyncNodeManager {
   private static final boolean VERIFY_NODE_MANAGER = VoxyCommon.isVerificationFlagOn("verifyNodeManager");
   private static final VarHandle RESULT_HANDLE;
   private static final VarHandle RESULT_CACHE_1_HANDLE;
   private static final VarHandle RESULT_CACHE_2_HANDLE;
   private final Thread thread;
   public final int maxNodeCount;
   private final long geometryCapacity;
   private boolean stopped;
    private volatile boolean running = true;
   private volatile Throwable uncaughtException;
   private final NodeManager manager;
   private final BasicAsyncGeometryManager geometryManager;
   private final IGeometryData geometryData;
   private final SectionUpdateRouter router;
   private final GeometryCache geometryCache = new GeometryCache(4294967296L);
   private final AtomicInteger workCounter = new AtomicInteger();
   private volatile AsyncNodeManager.SyncResults results = null;
   private volatile AsyncNodeManager.SyncResults resultCache1 = new AsyncNodeManager.SyncResults();
   private volatile AsyncNodeManager.SyncResults resultCache2 = new AsyncNodeManager.SyncResults();
   private final IntOpenHashSet tlnIdChange = new IntOpenHashSet();
   private final IntOpenHashSet cleanerIdResetClear = new IntOpenHashSet();
   private boolean needsWaitForSync = false;
   private final Shader scatterWrite = Shader.make()
      .define("INPUT_BUFFER_BINDING", 0)
      .define("OUTPUT_BUFFER1_BINDING", 1)
      .define("OUTPUT_BUFFER2_BINDING", 2)
      .add(ShaderType.COMPUTE, "voxy:util/scatter.comp")
      .compile();
   private final Shader multiMemcpy = Shader.make()
      .define("INPUT_HEADER_BUFFER_BINDING", 0)
      .define("INPUT_DATA_BUFFER_BINDING", 1)
      .define("OUTPUT_BUFFER_BINDING", 2)
      .add(ShaderType.COMPUTE, "voxy:util/memcpy.comp")
      .compile();
   private IntConsumer tlnAddCallback;
   private IntConsumer tlnRemoveCallback;
   private int currentMaxNodeId = 0;
   private long usedGeometryAmount = 0L;
   private final ConcurrentLinkedDeque<MemoryBuffer> requestBatchQueue = new ConcurrentLinkedDeque<>();
   private final ConcurrentLinkedDeque<WorldSection> childUpdateQueue = new ConcurrentLinkedDeque<>();
   private final ConcurrentLinkedDeque<BuiltSection> geometryUpdateQueue = new ConcurrentLinkedDeque<>();
   private final ConcurrentLinkedDeque<MemoryBuffer> removeBatchQueue = new ConcurrentLinkedDeque<>();
   private final StampedLock tlnLock = new StampedLock();
   private final LongOpenHashSet tlnAdd = new LongOpenHashSet();
   private final LongOpenHashSet tlnRem = new LongOpenHashSet();

   public AsyncNodeManager(int maxNodeCount, IGeometryData geometryData, RenderGenerationService renderService) {
      this.geometryData = geometryData;
      this.geometryCapacity = ((BasicSectionGeometryData)geometryData).getGeometryCapacityBytes();
      this.maxNodeCount = maxNodeCount;
      this.thread = new Thread(() -> {
         try {
            while (this.running) {
               this.run();
            }
         } catch (Exception var2x) {
            Logger.error("Critical error occurred in async processor, things will be broken", var2x);
            throw var2x;
         }
      });
      this.thread.setUncaughtExceptionHandler((t, e) -> {
         if (e == null) {
            e = new RuntimeException("null throwable");
         }

         this.uncaughtException = e;
         this.running = false;
      });
      this.thread.setName("Async Node Manager");
      this.geometryManager = new BasicAsyncGeometryManager(((BasicSectionGeometryData)geometryData).getMaxSectionCount(), this.geometryCapacity);
      this.router = new SectionUpdateRouter();
      this.router.setCallbacks(pos -> {
         BuiltSection cachedGeometry = this.geometryCache.remove(pos);
         if (cachedGeometry != null) {
            this.submitGeometryResult(cachedGeometry);
         } else {
            renderService.enqueueTask(pos);
         }
      }, renderService::enqueueTask, this::submitChildChange);
      renderService.setResultConsumer(this::submitGeometryResult);
      this.manager = new NodeManager(maxNodeCount, this.geometryManager, this.router);
      this.manager.setClear(new NodeManager.ICleaner() {
         {
            Objects.requireNonNull(AsyncNodeManager.this);
         }

         @Override
         public void alloc(int id) {
            AsyncNodeManager.this.cleanerIdResetClear.remove(id);
            AsyncNodeManager.this.cleanerIdResetClear.add(id | -2147483648);
         }

         @Override
         public void move(int from, int to) {
         }

         @Override
         public void free(int id) {
            AsyncNodeManager.this.cleanerIdResetClear.remove(id | -2147483648);
            AsyncNodeManager.this.cleanerIdResetClear.add(id);
         }
      });
      this.manager.setTLNCallbacks(id -> {
         if (!this.tlnIdChange.remove(id) && !this.tlnIdChange.add(id | -2147483648)) {
            throw new IllegalStateException();
         }
      }, id -> {
         if (!this.tlnIdChange.remove(id | -2147483648) && !this.tlnIdChange.add(id)) {
            throw new IllegalStateException();
         }
      });
   }

   private AsyncNodeManager.SyncResults getMakeResultObject() {
      AsyncNodeManager.SyncResults resultSet = (AsyncNodeManager.SyncResults)RESULT_CACHE_1_HANDLE.getAndSet((AsyncNodeManager)this, (Void)null);
      if (resultSet == null) {
         resultSet = (AsyncNodeManager.SyncResults)RESULT_CACHE_2_HANDLE.getAndSet((AsyncNodeManager)this, (Void)null);
      }

      if (resultSet == null) {
         throw new IllegalStateException("There should always be an object in the result set cache pair");
      } else {
         resultSet.reset();
         return resultSet;
      }
   }

   private void run() {
      if (this.workCounter.get() <= 0) {
         LockSupport.park();
         if (this.workCounter.get() <= 0 || !this.running) {
            return;
         }

         try {
            Thread.sleep(10L);
         } catch (InterruptedException var16) {
            throw new RuntimeException(var16);
         }
      }

      if (this.running) {
         int workDone = 0;
         LongOpenHashSet add = null;
         LongOpenHashSet rem = null;
         long stamp = this.tlnLock.writeLock();
         if (!this.tlnAdd.isEmpty()) {
            add = new LongOpenHashSet(this.tlnAdd);
            this.tlnAdd.clear();
         }

         if (!this.tlnRem.isEmpty()) {
            rem = new LongOpenHashSet(this.tlnRem);
            this.tlnRem.clear();
         }

         this.tlnLock.unlockWrite(stamp);
         int work = 0;
         if (rem != null) {
            for (LongIterator iter = rem.longIterator(); iter.hasNext(); work++) {
               this.manager.removeTopLevelNode(iter.nextLong());
            }
         }

         if (add != null) {
            for (LongIterator iter = add.longIterator(); iter.hasNext(); work++) {
               this.manager.insertTopLevelNode(iter.nextLong());
            }
         }

         workDone += work;

         while (true) {
            WorldSection job = this.childUpdateQueue.poll();
            if (job == null) {
               long estimatedGeometryUploadAmount = 0L;

               for (int limit = 0;
                  limit < 300 && this.geometryCapacity - this.geometryManager.getGeometryUsedBytes() > 50000000L && estimatedGeometryUploadAmount < 1024000L;
                  limit++
               ) {
                  BuiltSection jobx = this.geometryUpdateQueue.poll();
                  if (jobx == null) {
                     break;
                  }

                  workDone++;
                  this.manager.processGeometryResult(jobx);
                  if (jobx.geometryBuffer != null) {
                     estimatedGeometryUploadAmount += jobx.geometryBuffer.size;
                  }
               }

               while (true) {
                  MemoryBuffer jobxx = this.requestBatchQueue.poll();
                  if (jobxx == null) {
                     while (true) {
                        MemoryBuffer jobxxx = this.removeBatchQueue.poll();
                        if (jobxxx == null) {
                           if (this.workCounter.addAndGet(-workDone) < 0) {
                              try {
                                 Thread.sleep(1000L);
                              } catch (InterruptedException var15) {
                                 throw new RuntimeException(var15);
                              }

                              if (this.workCounter.get() < 0) {
                                 Logger.error("Work counter less than zero, hope it fixes itself...");
                              }
                           }

                           if (workDone == 0) {
                              // 容量不足时等待新事件，移除批次和停机会唤醒。
                              LockSupport.park();
                              return;
                           }

                           if (this.needsWaitForSync) {
                              while ((Object)RESULT_HANDLE.get((AsyncNodeManager)this) != null && this.running) {
                                 try {
                                    Thread.sleep(10L);
                                 } catch (InterruptedException var14) {
                                    throw new RuntimeException(var14);
                                 }
                              }
                           }

                           AsyncNodeManager.SyncResults prev = (AsyncNodeManager.SyncResults)RESULT_HANDLE.getAndSet((AsyncNodeManager)this, (Void)null);
                           AsyncNodeManager.SyncResults results = null;
                           if (prev == null) {
                              this.needsWaitForSync = false;
                              results = this.getMakeResultObject();
                              results.tlnDelta.addAll(this.tlnIdChange);
                              this.tlnIdChange.clear();
                              if (!this.geometryManager.getUploads().isEmpty()) {
                                 ObjectIterator<Entry<MemoryBuffer>> iter = this.geometryManager.getUploads().int2ObjectEntrySet().fastIterator();

                                 while (iter.hasNext()) {
                                    Entry<MemoryBuffer> val = (Entry<MemoryBuffer>)iter.next();
                                    results.geometryUpload.upload(val.getIntKey(), (MemoryBuffer)val.getValue());
                                    ((MemoryBuffer)val.getValue()).free();
                                 }

                                 this.geometryManager.getUploads().clear();
                              }

                              this.geometryManager.getHeapRemovals().clear();
                              results.cleanerOperations.addAll(this.cleanerIdResetClear);
                              this.cleanerIdResetClear.clear();
                           } else {
                              results = prev;
                              if (!this.tlnIdChange.isEmpty()) {
                                 IntIterator iter = this.tlnIdChange.intIterator();

                                 while (iter.hasNext()) {
                                    int val = iter.nextInt();
                                    if (!results.tlnDelta.remove(val ^ Integer.MIN_VALUE)) {
                                       results.tlnDelta.add(val);
                                    }
                                 }

                                 this.tlnIdChange.clear();
                              }

                              if (!this.cleanerIdResetClear.isEmpty()) {
                                 IntIterator iter = this.cleanerIdResetClear.intIterator();

                                 while (iter.hasNext()) {
                                    int val = iter.nextInt();
                                    results.cleanerOperations.remove(val ^ Integer.MIN_VALUE);
                                    results.cleanerOperations.add(val);
                                 }

                                 this.cleanerIdResetClear.clear();
                              }

                              if (!this.geometryManager.getHeapRemovals().isEmpty()) {
                                 IntOpenHashSet remx = this.geometryManager.getHeapRemovals();
                                 IntIterator iter = remx.intIterator();

                                 while (iter.hasNext()) {
                                    results.geometryUpload.remove(iter.nextInt());
                                 }

                                 remx.clear();
                              }

                              if (!this.geometryManager.getUploads().isEmpty()) {
                                 Int2ObjectOpenHashMap<MemoryBuffer> addx = this.geometryManager.getUploads();
                                 ObjectIterator<Entry<MemoryBuffer>> iter = addx.int2ObjectEntrySet().fastIterator();

                                 while (iter.hasNext()) {
                                    Entry<MemoryBuffer> val = (Entry<MemoryBuffer>)iter.next();
                                    results.geometryUpload.upload(val.getIntKey(), (MemoryBuffer)val.getValue());
                                    ((MemoryBuffer)val.getValue()).free();
                                 }

                                 addx.clear();
                              }
                           }

                           if (!this.geometryManager.getUpdateIds().isEmpty()) {
                              IntOpenHashSet ids = this.geometryManager.getUpdateIds();
                              IntIterator iter = ids.intIterator();

                              while (iter.hasNext()) {
                                 int val = iter.nextInt();
                                 int scatterAddr = val << 1 | -2147483648;
                                 long ptrA = results.getScatterWritePtr(scatterAddr + 0, 1);
                                 long ptrB = results.getScatterWritePtr(scatterAddr + 1, 0);
                                 this.geometryManager.writeMetadataSplit(val, ptrA, ptrB);
                              }

                              ids.clear();
                           }

                           if (!this.manager.getNodeUpdates().isEmpty()) {
                              IntOpenHashSet ids = this.manager.getNodeUpdates();
                              IntIterator iter = ids.intIterator();

                              while (iter.hasNext()) {
                                 int val = iter.nextInt();
                                 long ptr = results.getScatterWritePtr(val);
                                 this.manager.writeNode(val, ptr);
                              }

                              ids.clear();
                           }

                           results.geometrySectionCount = this.geometryManager.getSectionCount();
                           results.usedGeometry = this.geometryManager.getGeometryUsedBytes();
                           results.currentMaxNodeId = this.manager.getCurrentMaxNodeId();
                           this.needsWaitForSync = this.needsWaitForSync | results.geometryUpload.currentElemCopyAmount * 8L > 2097152L;
                           this.needsWaitForSync = this.needsWaitForSync | results.cleanerOperations.size() > 1024;
                           this.needsWaitForSync = this.needsWaitForSync | results.scatterWriteLocationMap.size() > 4096;
                           this.needsWaitForSync = this.needsWaitForSync | results.tlnDelta.size() > 10;
                           if (!RESULT_HANDLE.compareAndSet((AsyncNodeManager)this, (Void)null, (AsyncNodeManager.SyncResults)results)) {
                              throw new IllegalArgumentException("Should always have null");
                           }

                           if (VERIFY_NODE_MANAGER) {
                              this.manager.verifyIntegrity();
                           }

                           return;
                        }

                        workDone++;
                        long ptr = jobxxx.address;
                        int zeroCount = 0;

                        for (int i = 0; i < 256; i++) {
                           long pos = (long)MemoryUtil.memGetInt(ptr) << 32;
                           ptr += 4L;
                           pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr));
                           ptr += 4L;
                           if (pos != -1L) {
                              if (pos == 0L && zeroCount++ > 0) {
                                 Logger.error("Remove node pos is 0 " + zeroCount + " times, this is really bad, please report");
                              } else {
                                 this.manager.removeNodeGeometry(pos);
                              }
                           }
                        }

                        jobxxx.free();
                     }
                  }

                  workDone++;
                  long ptr = jobxx.address;
                  int count = MemoryUtil.memGetInt(ptr);
                  ptr += 8L;
                  if (jobxx.size < count * 8L + 8L) {
                     throw new IllegalStateException();
                  }

                  for (int ix = 0; ix < count; ix++) {
                     long pos = (long)MemoryUtil.memGetInt(ptr) << 32;
                     ptr += 4L;
                     pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr));
                     ptr += 4L;
                     this.manager.processRequest(pos);
                  }

                  jobxx.free();
               }
            }

            workDone++;
            this.manager.processChildChange(job.key, job.getNonEmptyChildren());
            job.release();
         }
      }
   }

   public void tick(GlBuffer nodeBuffer, NodeCleaner cleaner) {
      if (this.uncaughtException != null) {
         throw new RuntimeException(this.uncaughtException);
      } else {
         AsyncNodeManager.SyncResults results = (AsyncNodeManager.SyncResults)RESULT_HANDLE.getAndSet((AsyncNodeManager)this, (Void)null);
         if (results != null) {
            if (!results.tlnDelta.isEmpty()) {
               IntIterator iter = results.tlnDelta.intIterator();

               while (iter.hasNext()) {
                  int val = iter.nextInt();
                  if ((val & -2147483648) != 0) {
                     this.tlnAddCallback.accept(val & 2147483647);
                  } else {
                     this.tlnRemoveCallback.accept(val);
                  }
               }
            }

            BasicSectionGeometryData store = (BasicSectionGeometryData)this.geometryData;
            store.setSectionCount(results.geometrySectionCount);
            AsyncNodeManager.ComputeMemoryCopy upload = results.geometryUpload;
            if (!upload.dataUploadPoints.isEmpty()) {
               ((BasicSectionGeometryData)this.geometryData).ensureAccessable(upload.maxElementAccess);
               TimingStatistics.A.start();
               int copies = upload.dataUploadPoints.size();
               int upCopies = UploadStream.alignUpAlloc(copies * 16);
               int scratchSize = (int)upload.arena.getSize() * 8;
               int upScratchSize = UploadStream.alignUpAlloc(scratchSize);
               long ptr = UploadStream.INSTANCE.rawUploadAddress(upScratchSize + upCopies);
               UnsafeUtil.memcpy(upload.scratchHeaderBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr, copies * 16L);
               UnsafeUtil.memcpy(upload.scratchDataBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr + upCopies, scratchSize);
               UploadStream.INSTANCE.commit();
               this.multiMemcpy.bind();
               GL43C.glBindBufferRange(37074, 0, UploadStream.INSTANCE.getRawBufferId(), ptr, upCopies);
               GL43C.glBindBufferRange(37074, 1, UploadStream.INSTANCE.getRawBufferId(), ptr + upCopies, upScratchSize);
               ARBUniformBufferObject.glBindBufferBase(37074, 2, ((BasicSectionGeometryData)this.geometryData).getGeometryBuffer().id);
               if (copies > 500) {
                  Logger.warn("Large amount of copies, lag will probably happen: " + copies);
               }

               GL42C.glMemoryBarrier(8192);
               GL43C.glDispatchCompute(copies, 1, 1);
               GL42C.glMemoryBarrier(8192);
               TimingStatistics.A.stop();
            }

            TimingStatistics.B.start();
            if (!results.scatterWriteLocationMap.isEmpty()) {
               int count = results.scatterWriteLocationMap.size();
               int chunks = (count + 3) / 4;
               int streamSize = chunks * 80;
               long ptr = UploadStream.INSTANCE.rawUploadAddress(streamSize);
               MemoryUtil.memCopy(results.scatterWriteBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr, streamSize);
               UploadStream.INSTANCE.commit();
               this.scatterWrite.bind();
               GL43C.glBindBufferRange(37074, 0, UploadStream.INSTANCE.getRawBufferId(), ptr, UploadStream.alignUpAlloc(streamSize));
               ARBUniformBufferObject.glBindBufferBase(37074, 1, nodeBuffer.id);
               ARBUniformBufferObject.glBindBufferBase(37074, 2, ((BasicSectionGeometryData)this.geometryData).getMetadataBuffer().id);
               GL30C.glUniform1ui(0, count);
               GL42C.glMemoryBarrier(8196);
               GL43C.glDispatchCompute((count + 127) / 128, 1, 1);
               GL42C.glMemoryBarrier(8196);
            }

            TimingStatistics.B.stop();
            TimingStatistics.C.start();
            if (!results.cleanerOperations.isEmpty()) {
               cleaner.updateIds(results.cleanerOperations);
            }

            TimingStatistics.C.stop();
            this.currentMaxNodeId = results.currentMaxNodeId;
            this.usedGeometryAmount = results.usedGeometry;
            if (!RESULT_CACHE_1_HANDLE.compareAndSet((AsyncNodeManager)this, (Void)null, (AsyncNodeManager.SyncResults)results)
               && !RESULT_CACHE_2_HANDLE.compareAndSet((AsyncNodeManager)this, (Void)null, (AsyncNodeManager.SyncResults)results)) {
               throw new IllegalStateException("Could not insert result into cache");
            }
         }
      }
   }

   public void setTLNAddRemoveCallbacks(IntConsumer add, IntConsumer remove) {
      this.tlnAddCallback = add;
      this.tlnRemoveCallback = remove;
   }

   public int getCurrentMaxNodeId() {
      return this.currentMaxNodeId;
   }

   public long getUsedGeometryCapacity() {
      return this.usedGeometryAmount;
   }

   public long getGeometryCapacity() {
      return this.geometryCapacity;
   }

   private void addWork() {
      if (!this.running) {
         if (this.uncaughtException != null) {
            throw new RuntimeException(this.uncaughtException);
         } else {
            throw new IllegalStateException("Not running");
         }
      } else {
         this.workCounter.getAndIncrement();
         LockSupport.unpark(this.thread);
      }
   }

   public void submitRequestBatch(MemoryBuffer batch) {
      this.requestBatchQueue.add(batch);
      this.addWork();
   }

   private void submitChildChange(WorldSection section) {
      if (this.running) {
         section.acquire();
         this.childUpdateQueue.add(section);
         this.addWork();
      }
   }

   private void submitGeometryResult(BuiltSection geometry) {
      if (!this.running) {
         geometry.free();
      } else {
         this.geometryUpdateQueue.add(geometry);
         this.addWork();
      }
   }

   public void submitRemoveBatch(MemoryBuffer batch) {
      this.removeBatchQueue.add(batch);
      this.addWork();
   }

   public void addTopLevel(long section) {
      if (!this.running) {
         throw new IllegalStateException("Not running");
      } else {
         long stamp = this.tlnLock.writeLock();
         int state = 0;
         if (!this.tlnRem.remove(section)) {
            state += this.tlnAdd.add(section) ? 1 : 0;
         } else {
            state--;
         }

         if (state != 0) {
            this.workCounter.getAndAdd(state);
            LockSupport.unpark(this.thread);
         }

         this.tlnLock.unlockWrite(stamp);
      }
   }

   public void removeTopLevel(long section) {
      if (!this.running) {
         throw new IllegalStateException("Not running");
      } else {
         long stamp = this.tlnLock.writeLock();
         int state = 0;
         if (!this.tlnAdd.remove(section)) {
            state += this.tlnRem.add(section) ? 1 : 0;
         } else {
            state--;
         }

         if (state != 0) {
            this.workCounter.getAndAdd(state);
            LockSupport.unpark(this.thread);
         }

         this.tlnLock.unlockWrite(stamp);
      }
   }

   public void start() {
      this.thread.start();
   }

   public void stop() {
        this.stop(() -> {});
    }

    public void stop(Runnable stopProducers) {
        if (this.stopped) throw new IllegalStateException("Node manager already stopped");
        this.stopped = true;
        this.running = false;
        boolean interrupted = Thread.interrupted();
        // 必须等线程实际退出；保留中断状态，但不能留下仍访问待释放资源的线程。
        while (this.thread.isAlive()) {
            LockSupport.unpark(this.thread);
            try {
                this.thread.join(1000);
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        try {
            var cleanup = new ExceptionCleanup(null);
            // 先终止网格结果的生产，再清空队列，避免晚到的结果遗留。
            cleanup.run(stopProducers);
            for (var item = this.requestBatchQueue.poll(); item != null; item = this.requestBatchQueue.poll()) {
                cleanup.run(item::free);
            }
            for (var item = this.removeBatchQueue.poll(); item != null; item = this.removeBatchQueue.poll()) {
                cleanup.run(item::free);
            }
            for (var item = this.geometryUpdateQueue.poll(); item != null; item = this.geometryUpdateQueue.poll()) {
                cleanup.run(item::free);
            }
            for (var item = this.childUpdateQueue.poll(); item != null; item = this.childUpdateQueue.poll()) {
                cleanup.run(item::release);
            }
            {
                var result = (SyncResults)RESULT_HANDLE.getAndSet(this, null);
                if (result != null) {
                    cleanup.run(result.geometryUpload::free);
                    cleanup.run(result.scatterWriteBuffer::free);
                }
            }
            {
                var result = (SyncResults)RESULT_CACHE_1_HANDLE.getAndSet(this, null);
                if (result != null) {
                    cleanup.run(result.geometryUpload::free);
                    cleanup.run(result.scatterWriteBuffer::free);
                }
            }
            {
                var result = (SyncResults)RESULT_CACHE_2_HANDLE.getAndSet(this, null);
                if (result != null) {
                    cleanup.run(result.geometryUpload::free);
                    cleanup.run(result.scatterWriteBuffer::free);
                }
            }
            cleanup.run(this.scatterWrite::free);
            cleanup.run(this.multiMemcpy::free);
            cleanup.run(this.geometryCache::free);
            cleanup.rethrow();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

   public void addDebug(List<String> debug) {
      debug.add(
         "UC/GC,#N: " + this.getUsedGeometryCapacity() / 1048576L + "/" + this.getGeometryCapacity() / 1048576L + "," + this.geometryData.getSectionCount()
      );
   }

   public boolean hasWork() {
      return this.workCounter.get() != 0 || (Object)RESULT_HANDLE.get((AsyncNodeManager)this) != null;
   }

   public void worldEvent(WorldSection section, int flags, int neighborMask) {
        this.router.forwardEvent(section, flags);
        if ((neighborMask & 1) != 0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y - 1, section.z));
        if ((neighborMask & 2) != 0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y + 1, section.z));
        int radius = Math.min(7, Math.max(0, me.cortex.voxy.client.config.VoxyConfig.CONFIG.biomeBlendRadius));
        int horizontal = me.cortex.voxy.common.world.WorldUpdater.horizontalHaloMask(neighborMask, radius);
        for (int dz = -1; dz <= 1; dz++) {
            if (dz != 0 && (horizontal & (dz < 0 ? 16 : 32)) == 0) continue;
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                if (dx != 0 && (horizontal & (dx < 0 ? 4 : 8)) == 0) continue;
                this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x + dx, section.y, section.z + dz));
                // 派生几何越界时使用完整 halo；否则只有流体上环的真实接壤边角依赖。
                if ((neighborMask & 1) != 0
                        && ((neighborMask & (1 << 18)) != 0
                            || ((dx == 0 || (neighborMask & (dx < 0 ? 4 : 8)) != 0)
                                && (dz == 0 || (neighborMask & (dz < 0 ? 16 : 32)) != 0)))) {
                    this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x + dx, section.y - 1, section.z + dz));
                }
                if ((neighborMask & (1 << 19)) != 0) {
                    this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x + dx, section.y + 1, section.z + dz));
                }
            }
        }
    }

   static {
      try {
         RESULT_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "results", AsyncNodeManager.SyncResults.class);
         RESULT_CACHE_1_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "resultCache1", AsyncNodeManager.SyncResults.class);
         RESULT_CACHE_2_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "resultCache2", AsyncNodeManager.SyncResults.class);
      } catch (IllegalAccessException | NoSuchFieldException var1) {
         throw new RuntimeException(var1);
      }
   }

   private static class ComputeMemoryCopy {
      public int currentElemCopyAmount;
      public int maxElementAccess;
      private MemoryBuffer scratchHeaderBuffer = new MemoryBuffer(65536L);
      private MemoryBuffer scratchDataBuffer = new MemoryBuffer(1048576L);
      private final AllocationArena arena = new AllocationArena();
      private final Int2IntOpenHashMap dataUploadPoints = new Int2IntOpenHashMap();

      private ComputeMemoryCopy() {
         this.dataUploadPoints.defaultReturnValue(-1);
      }

      public void remove(int point) {
         int header = this.dataUploadPoints.remove(point);
         if (header != -1) {
            int size = MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header * 16L + 8L);
            this.currentElemCopyAmount -= size;
            if (this.arena.free(MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header * 16L)) != size) {
               throw new IllegalStateException("Freed memory not same size as expected");
            } else if (MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header * 16L + 4L) != point) {
               throw new IllegalStateException("Destination not the same as point");
            } else if (header == this.dataUploadPoints.size()) {
               long A = this.scratchHeaderBuffer.address + header * 16L;
               MemoryUtil.memPutLong(A, 0L);
               MemoryUtil.memPutLong(A + 8L, 0L);
            } else {
               int endingPoint = MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + this.dataUploadPoints.size() * 16L + 4L);
               if (this.dataUploadPoints.get(endingPoint) != this.dataUploadPoints.size()) {
                  throw new IllegalStateException("ending header not pointing at end point");
               } else {
                  long A = this.scratchHeaderBuffer.address + this.dataUploadPoints.size() * 16L;
                  long B = this.scratchHeaderBuffer.address + header * 16L;
                  MemoryUtil.memPutLong(B, MemoryUtil.memGetLong(A));
                  MemoryUtil.memPutLong(A, 0L);
                  MemoryUtil.memPutLong(B + 8L, MemoryUtil.memGetLong(A + 8L));
                  MemoryUtil.memPutLong(A + 8L, 0L);
                  this.dataUploadPoints.put(endingPoint, header);
               }
            }
         }
      }

      public void upload(int point, MemoryBuffer data) {
         if (data.size % 8L != 0L) {
            throw new IllegalStateException("Data must be of size multiple 8");
         } else {
            int elemSize = (int)(data.size / 8L);
            this.maxElementAccess = Math.max(this.maxElementAccess, point + elemSize);
            int header = this.dataUploadPoints.get(point);
            if (header != -1) {
               long headerPtr = this.scratchHeaderBuffer.address + header * 16L;
               if (MemoryUtil.memGetInt(headerPtr + 4L) != point) {
                  throw new IllegalStateException("Existing destination not the point");
               }

               int pSize = MemoryUtil.memGetInt(headerPtr + 8L);
               if (pSize == elemSize) {
                  data.cpyTo(this.scratchDataBuffer.address + MemoryUtil.memGetInt(headerPtr) * 8L);
               } else {
                  if (this.arena.free(MemoryUtil.memGetInt(headerPtr)) != pSize) {
                     throw new IllegalStateException("Freed allocation not size as expected");
                  }

                  this.currentElemCopyAmount -= pSize;
                  this.currentElemCopyAmount += elemSize;
                  int alloc = this.allocScratchDataPos(elemSize);
                  data.cpyTo(this.scratchDataBuffer.address + alloc * 8L);
                  MemoryUtil.memPutInt(headerPtr, alloc);
                  MemoryUtil.memPutInt(headerPtr + 8L, elemSize);
               }
            } else {
               header = this.dataUploadPoints.size();
               this.dataUploadPoints.put(point, header);
               if (this.scratchHeaderBuffer.size <= header * 16L) {
                  long newSize = Math.max(this.scratchHeaderBuffer.size * 2L, header * 16L);
                  Logger.info("Resizing scratch header buffer to: " + newSize);
                  MemoryBuffer newScratch = new MemoryBuffer(newSize);
                  this.scratchHeaderBuffer.cpyTo(newScratch.address);
                  this.scratchHeaderBuffer.free();
                  this.scratchHeaderBuffer = newScratch;
               }

               long headerPtrx = this.scratchHeaderBuffer.address + header * 16L;
               this.currentElemCopyAmount += elemSize;
               int alloc = this.allocScratchDataPos(elemSize);
               data.cpyTo(this.scratchDataBuffer.address + alloc * 8L);
               MemoryUtil.memPutInt(headerPtrx, alloc);
               MemoryUtil.memPutInt(headerPtrx + 4L, point);
               MemoryUtil.memPutInt(headerPtrx + 8L, elemSize);
            }
         }
      }

      private int allocScratchDataPos(int size) {
         int pos = (int)this.arena.alloc(size);
         if (this.scratchDataBuffer.size <= (pos + size) * 8L) {
            long newSize = Math.max(this.scratchDataBuffer.size * 2L, (pos + size) * 8L);
            Logger.info("Resizing scratch data buffer to: " + newSize);
            MemoryBuffer newScratch = new MemoryBuffer(newSize);
            this.scratchDataBuffer.cpyTo(newScratch.address);
            this.scratchDataBuffer.free();
            this.scratchDataBuffer = newScratch;
         }

         return pos;
      }

      public void reset() {
         this.maxElementAccess = 0;
         this.currentElemCopyAmount = 0;
         this.dataUploadPoints.clear();
         this.arena.reset();
      }

      public void free() {
         this.scratchHeaderBuffer.free();
         this.scratchHeaderBuffer = null;
         this.scratchDataBuffer.free();
         this.scratchDataBuffer = null;
      }
   }

   private static final class SyncResults {
      private int currentMaxNodeId;
      private final IntOpenHashSet tlnDelta = new IntOpenHashSet();
      private int geometrySectionCount;
      private long usedGeometry;
      private final AsyncNodeManager.ComputeMemoryCopy geometryUpload = new AsyncNodeManager.ComputeMemoryCopy();
      private MemoryBuffer scatterWriteBuffer = new MemoryBuffer(16384L);
      private final Int2IntOpenHashMap scatterWriteLocationMap = new Int2IntOpenHashMap(1024);
      private final IntOpenHashSet cleanerOperations;

      private SyncResults() {
         this.scatterWriteLocationMap.defaultReturnValue(-1);
         this.cleanerOperations = new IntOpenHashSet();
      }

      public void reset() {
         this.cleanerOperations.clear();
         this.scatterWriteLocationMap.clear();
         this.currentMaxNodeId = 0;
         this.tlnDelta.clear();
         this.geometrySectionCount = 0;
         this.usedGeometry = 0L;
         this.geometryUpload.reset();
      }

      public long getScatterWritePtr(int location) {
         return this.getScatterWritePtr(location, 0);
      }

      public long getScatterWritePtr(int location, int ensureExtra) {
         int loc = this.scatterWriteLocationMap.get(location);
         if (loc == -1) {
            this.ensureScatterBufferCapacity(1 + ensureExtra);
            int baseId = this.scatterWriteLocationMap.size();
            int chunkBase = baseId / 4 * 5;
            int innerId = baseId & 3;
            MemoryUtil.memPutInt(this.scatterWriteBuffer.address + chunkBase * 16L + innerId * 4L, location);
            int writeLocation = chunkBase + 1 + innerId;
            this.scatterWriteLocationMap.put(location, writeLocation);
            return this.scatterWriteBuffer.address + writeLocation * 16L;
         } else {
            return this.scatterWriteBuffer.address + 16L * loc;
         }
      }

      private void ensureScatterBufferCapacity(int extra) {
         int requiredChunks = (this.scatterWriteLocationMap.size() + extra + 3) / 4;
         long requiredSize = requiredChunks * 5L * 16L;
         if (this.scatterWriteBuffer.size <= requiredSize) {
            long newSize = (long)(this.scatterWriteBuffer.size * 1.5 + extra * 80L);
            newSize = (newSize + 79L) / 80L * 80L;
            Logger.info("Expanding scatter update buffer to " + newSize);
            MemoryBuffer newBuffer = new MemoryBuffer(newSize);
            this.scatterWriteBuffer.cpyTo(newBuffer.address);
            this.scatterWriteBuffer.free();
            this.scatterWriteBuffer = newBuffer;
         }
      }
   }
}
