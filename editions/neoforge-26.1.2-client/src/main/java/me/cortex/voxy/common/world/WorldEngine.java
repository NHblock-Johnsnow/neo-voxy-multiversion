package me.cortex.voxy.common.world;

import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.util.TrackedObject;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyInstance;
import org.jetbrains.annotations.Nullable;

public final class WorldEngine {
   public static final int MAX_LOD_LAYER = 4;
   public static final int UPDATE_TYPE_BLOCK_BIT = 1;
   public static final int UPDATE_TYPE_CHILD_EXISTENCE_BIT = 2;
   public static final int UPDATE_TYPE_DONT_SAVE = 4;
   public static final int DEFAULT_UPDATE_FLAGS = 3;
   private final TrackedObject thisTracker = TrackedObject.createTrackedObject(this);
   public final SectionStorage storage;
   private final Mapper mapper;
   private final ActiveSectionTracker sectionTracker;
   private WorldEngine.ISectionChangeCallback dirtyCallback;
   private WorldEngine.ISectionSaveCallback saveCallback;

    // 仅入队异常时创建；原始对象引用不改变区段引用计数。
    private volatile ConcurrentLinkedQueue<WorldSection> failedSaves;
    private ConcurrentHashMap<WorldSection, Boolean> failedSaveKeys;
    private volatile Throwable failedSaveCause;
    private long failedSaveVersion;
    private long nextSaveRetryNanos;
    private boolean retryingFailedSaves;

   volatile boolean isLive = true;
   @Nullable
   public final VoxyInstance instanceIn;
   private final AtomicInteger refCount = new AtomicInteger();
   volatile long lastActiveTime = System.currentTimeMillis();
   public static final int POS_FORMAT_VERSION = 1;
   private static final long TIMEOUT_MILLIS = 10000L;

   public void setDirtyCallback(WorldEngine.ISectionChangeCallback callback) {
      this.dirtyCallback = callback;
   }

   public void setSaveCallback(WorldEngine.ISectionSaveCallback callback) {
      this.saveCallback = callback;
   }

   public Mapper getMapper() {
      return this.mapper;
   }

   public void requestSurfaceMigration(WorldSection section) {
      if (this.instanceIn != null) this.instanceIn.getSurfaceMigrationService().request(this, section);
   }

   public boolean isLive() {
      return this.isLive;
   }

   public WorldEngine(SectionStorage storage) {
      this(storage, null);
   }

   public WorldEngine(SectionStorage storage, @Nullable VoxyInstance instance) {
      this.instanceIn = instance;
      int cacheSize = 1024;
      if (Runtime.getRuntime().maxMemory() >= 4085252096L) {
         cacheSize = 2048;
      }

      this.storage = storage;
      this.mapper = new Mapper(this.storage);
      this.sectionTracker = new ActiveSectionTracker(6, storage::loadSection, cacheSize, this);
   }

   public WorldSection acquireIfExists(int lvl, int x, int y, int z) {
      if (!this.isLive) {
         throw new IllegalStateException("World is not live");
      } else {
         return this.sectionTracker.acquire(lvl, x, y, z, true);
      }
   }

   public WorldSection acquire(int lvl, int x, int y, int z) {
      if (!this.isLive) {
         throw new IllegalStateException("World is not live");
      } else {
         return this.sectionTracker.acquire(lvl, x, y, z, false);
      }
   }

   public WorldSection acquire(long pos) {
      if (!this.isLive) {
         throw new IllegalStateException("World is not live");
      } else {
         return this.sectionTracker.acquire(pos, false);
      }
   }

   public WorldSection acquireIfExists(long pos) {
      if (!this.isLive) {
         throw new IllegalStateException("World is not live");
      } else {
         return this.sectionTracker.acquire(pos, true);
      }
   }

   public static long getWorldSectionId(int lvl, int x, int y, int z) {
      return (long)lvl << 60 | (long)(y & 0xFF) << 52 | (long)(z & 16777215) << 28 | (long)(x & 16777215) << 4;
   }

   public static int getLevel(long id) {
      return (int)(id >> 60 & 15L);
   }

   public static int getX(long id) {
      return (int)(id << 36 >> 40);
   }

   public static int getY(long id) {
      return (int)(id << 4 >> 56);
   }

   public static int getZ(long id) {
      return (int)(id << 12 >> 40);
   }

   public static String pprintPos(long pos) {
      return getLevel(pos) + "@[" + getX(pos) + ", " + getY(pos) + ", " + getZ(pos) + "]";
   }

   public void markDirty(WorldSection section) {
      this.markDirty(section, 3, 0);
   }

   public void markDirty(WorldSection section, int changeState, int neighborMsk) {
      if (!this.isLive) {
         throw new IllegalStateException("World is not live");
      } else if (section.tracker != this.sectionTracker) {
         throw new IllegalStateException("Section is not from here");
      } else {
         if ((changeState & 4) == 0) {
            section.markDirty();
         }
         if (this.dirtyCallback != null) {
            this.dirtyCallback.accept(section, changeState, neighborMsk);
         }
      }
   }

   public void addDebugData(List<String> debug) {
      debug.add("ACC/SCC: " + this.sectionTracker.getLoadedCacheCount() + "/" + this.sectionTracker.getSecondaryCacheSize());
   }

   public int getActiveSectionCount() {
      return this.sectionTracker.getLoadedCacheCount();
   }

   public void free() {
      if (!this.isLive) {
         throw new IllegalStateException();
      } else {
         this.isLive = false;
         VarHandle.fullFence();
         if (this.sectionTracker.getLoadedCacheCount() != 0) {
            throw new IllegalStateException();
         } else {
            this.thisTracker.free();

            try {
               this.mapper.close();
            } catch (Exception var4) {
               Logger.error(var4);
            }

            try {
               this.storage.flush();
            } catch (Exception var3) {
               Logger.error(var3);
            }

            try {
               this.storage.close();
            } catch (Exception var2) {
               Logger.error(var2);
            }
         }
      }
   }

   public boolean isWorldUsed() {
      if (!this.isLive) {
         throw new IllegalStateException();
      } else {
         return this.refCount.get() != 0 || this.sectionTracker.getLoadedCacheCount() != 0;
      }
   }

   public boolean isWorldIdle() {
      if (this.isWorldUsed()) {
         this.lastActiveTime = System.currentTimeMillis();
         VarHandle.fullFence();
         return false;
      } else {
         return 10000L < System.currentTimeMillis() - this.lastActiveTime;
      }
   }

   public void markActive() {
      if (!this.isLive) {
         throw new IllegalStateException();
      } else {
         this.lastActiveTime = System.currentTimeMillis();
      }
   }

   public void acquireRef() {
      if (!this.isLive) {
         throw new IllegalStateException();
      } else {
         this.refCount.incrementAndGet();
         this.lastActiveTime = System.currentTimeMillis();
      }
   }

   public void releaseRef() {
      if (!this.isLive) {
         throw new IllegalStateException();
      } else if (this.refCount.decrementAndGet() < 0) {
         throw new IllegalStateException("ref count less than 0");
      } else {
         this.lastActiveTime = System.currentTimeMillis();
      }
   }

   public boolean saveSection(WorldSection section) {
      return this.saveSection(section, false, false);
   }

   /** 仅保存任务已发布后使用；原始异常由 saveSection 原样重新抛出。 */
   public static final class SaveEnqueueException extends RuntimeException {
       public SaveEnqueueException(Throwable cause) {
           super(null, cause, false, false);
       }
   }

   /** 入队失败只归还引用，不递归触发同一个失败的保存回调。 */
   public static void releaseFailedSave(WorldSection section, Throwable failure) {
       try {
           section.release(false, 0);
       } catch (RuntimeException | Error cleanup) {
           if (cleanup != failure) failure.addSuppressed(cleanup);
       }
   }


    private synchronized void rememberFailedSave(WorldSection section, Throwable failure) {
        if (!section.shouldSave()) return;
        if (this.failedSaves == null) {
            var queue = new ConcurrentLinkedQueue<WorldSection>();
            this.failedSaveKeys = new ConcurrentHashMap<>();
            this.failedSaves = queue;
        }
        this.failedSaveCause = failure;
        this.failedSaveVersion++;
        if (this.failedSaveKeys.putIfAbsent(section, Boolean.TRUE) == null) {
            try {
                this.failedSaves.add(section);
            } catch (RuntimeException | Error enqueueFailure) {
                this.failedSaveKeys.remove(section);
                throw enqueueFailure;
            }
        }
    }

    public boolean hasPendingSaveRetries() {
        return this.failedSaves != null && !this.failedSaveKeys.isEmpty();
    }

    public Throwable getLastSaveEnqueueFailure() {
        return this.hasPendingSaveRetries() ? this.failedSaveCause : null;
    }

    public void retryFailedSaves() {
        if (this.failedSaves == null || this.saveCallback == null || !this.isLive) return;
        long version;
        synchronized (this) {
            long now = System.nanoTime();
            if (this.retryingFailedSaves || this.failedSaveKeys.isEmpty()
                    || (this.nextSaveRetryNanos != 0 && now - this.nextSaveRetryNanos < 0)) return;
            this.retryingFailedSaves = true;
            this.nextSaveRetryNanos = now + 100_000_000L;
            version = this.failedSaveVersion;
        }
        boolean progressed = false;
        try {
            for (int remaining = 64; remaining > 0; remaining--) {
                WorldSection candidate;
                synchronized (this) {
                    candidate = this.failedSaves.poll();
                    if (candidate == null) break;
                    this.failedSaveKeys.remove(candidate);
                }
                // 先核对当前缓存身份，再借引用；锁外调用保存和释放。
                var section = this.sectionTracker.acquireForSaveRetry(candidate);
                if (section == null) {
                    progressed = true;
                    continue;
                }
                try {
                    if (!this.saveSection(section, true, true)) section.release();
                    progressed = true;
                } catch (RuntimeException | Error failure) {
                    // saveSection 已按发布状态接管或归还引用，并登记未发布任务。
                    break;
                }
            }
        } finally {
            synchronized (this) {
                if (progressed && this.failedSaveVersion == version) this.failedSaveCause = null;
                this.retryingFailedSaves = false;
            }
        }
    }

   public boolean saveSection(WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
       if (this.saveCallback == null) return false;
       try {
           return this.saveCallback.save(this, section, nonBlocking, sectionAlreadyAcquired);
       } catch (SaveEnqueueException accepted) {
           // 已发布的任务保留队列引用，不能按未接管失败回滚。
           if (accepted.getCause() instanceof RuntimeException failure) throw failure;
           if (accepted.getCause() instanceof Error failure) throw failure;
           throw accepted;
       } catch (RuntimeException | Error failure) {
           if (sectionAlreadyAcquired) releaseFailedSave(section, failure);
            try {
                this.rememberFailedSave(section, failure);
            } catch (RuntimeException | Error registrationFailure) {
                if (registrationFailure != failure) failure.addSuppressed(registrationFailure);
            }
           throw failure;
       }
   }

   public interface ISectionChangeCallback {
      void accept(WorldSection var1, int var2, int var3);
   }

   public interface ISectionSaveCallback {
      boolean save(WorldEngine var1, WorldSection var2, boolean var3, boolean var4);
   }
}
