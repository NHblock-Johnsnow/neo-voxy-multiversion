package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;
import org.jetbrains.annotations.Nullable;

public final class ActiveSectionTracker {
   private final AtomicInteger loadedSections = new AtomicInteger();
   private final Long2ObjectOpenHashMap<ActiveSectionTracker.VolatileHolder<WorldSection>>[] loadedSectionCache;
   private final StampedLock[] locks;
   private final ActiveSectionTracker.SectionLoader loader;
   private final int lruSize;
   private final StampedLock lruLock = new StampedLock();
   private final Long2ObjectLinkedOpenHashMap<WorldSection> lruSecondaryCache;
   @Nullable
   public final WorldEngine engine;

   public ActiveSectionTracker(int numSlicesBits, ActiveSectionTracker.SectionLoader loader, int cacheSize) {
      this(numSlicesBits, loader, cacheSize, null);
   }

   public ActiveSectionTracker(int numSlicesBits, ActiveSectionTracker.SectionLoader loader, int cacheSize, WorldEngine engine) {
      this.engine = engine;
      this.loader = loader;
      this.loadedSectionCache = new Long2ObjectOpenHashMap[1 << numSlicesBits];
      this.lruSecondaryCache = new Long2ObjectLinkedOpenHashMap(cacheSize);
      this.locks = new StampedLock[1 << numSlicesBits];
      this.lruSize = cacheSize;

      for (int i = 0; i < this.loadedSectionCache.length; i++) {
         this.loadedSectionCache[i] = new Long2ObjectOpenHashMap(1024);
         this.locks[i] = new StampedLock();
      }
   }

   public WorldSection acquire(int lvl, int x, int y, int z, boolean nullOnEmpty) {
      return this.acquire(WorldEngine.getWorldSectionId(lvl, x, y, z), nullOnEmpty);
   }

    /** 只借用仍由当前 holder 发布的脏区段，不加载或复活旧缓存对象。 */
    WorldSection acquireForSaveRetry(WorldSection candidate) {
        int index = this.getCacheArrayIndex(candidate.key);
        var lock = this.locks[index];
        long stamp = lock.readLock();
        try {
            var holder = this.loadedSectionCache[index].get(candidate.key);
            return holder != null && holder.obj == candidate && candidate.shouldSave()
                    && candidate.tryAcquire() ? candidate : null;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    public WorldSection acquire(long key, boolean nullOnEmpty) {
        //TODO: add optional verification check to ensure this (or other critical systems) arnt being called on the render or server thread
        if (this.engine != null) this.engine.lastActiveTime = System.currentTimeMillis();
        int index = this.getCacheArrayIndex(key);
        var cache = this.loadedSectionCache[index];
        final var lock = this.locks[index];
        VolatileHolder<WorldSection> holder = null;
        boolean isLoader = false;
        WorldSection section = null;

        {
            long stamp = lock.readLock();
            try {
                holder = cache.get(key);
                if (holder != null) {//Return already loaded entry
                    section = holder.obj;
                    if (section != null) {
                        section.acquire();
                        lock.unlockRead(stamp);
                        stamp = 0;
                        return section;
                    }
                    lock.unlockRead(stamp);
                    stamp = 0;
                } else {//Try to create holder
                    holder = new VolatileHolder<>();
                    long ws = lock.tryConvertToWriteLock(stamp);
                    if (ws == 0) {//Failed to convert, unlock read and get write
                        lock.unlockRead(stamp);
                        stamp = lock.writeLock();
                    } else {
                        stamp = ws;
                    }
                    var eHolder = cache.putIfAbsent(key, holder);//We put if absent because on failure to convert to write, it leaves race condition
                    lock.unlockWrite(stamp);
                    stamp = 0;
                    if (eHolder == null) {//We are the loader
                        isLoader = true;
                    } else {
                        holder = eHolder;
                    }
                }
            } finally {
                if (stamp != 0) {
                    lock.unlock(stamp);
                }
            }
        }

        if (!isLoader) {
            VolatileHolder.PRE_ACQUIRE_COUNT.getAndAdd(holder, 1);
            boolean interrupted = false;
            try {
                // 等待发布结果，不占用 CPU；失败也必须唤醒所有等待者。
                synchronized (holder) {
                    while (holder.obj == null && holder.failure == null) {
                        try {
                            holder.wait();
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (holder.failure != null) {
                if (holder.failure instanceof Error error) throw error;
                throw (RuntimeException) holder.failure;
            }
            section = holder.obj;
            if ((int) VolatileHolder.POST_ACQUIRE_COUNT.getAndAdd(holder, -1) > 0) {
                return section;
            }
            // 迟到等待者须核对 holder 身份，避免拿到下一轮尚未发布的 LRU 复用对象。
            long stamp = lock.readLock();
            try {
                if (cache.get(key) == holder && section.tryAcquire()) return section;
            } finally {
                lock.unlockRead(stamp);
            }
            return this.acquire(key, nullOnEmpty);
        }

        this.loadedSections.incrementAndGet();
        try {
            WorldSection removal = null;
            long lruStamp = this.lruLock.writeLock();
            try {
                section = this.lruSecondaryCache.remove(key);
                if (section == null && !this.lruSecondaryCache.isEmpty()
                        && this.lruSize + 100 < this.lruSecondaryCache.size() + this.getLoadedCacheCount()) {
                    removal = this.lruSecondaryCache.removeFirst();
                }
            } finally {
                this.lruLock.unlockWrite(lruStamp);
            }
            if (removal != null) removal._releaseArray();

            int status = 0;
            if (section != null) {
                section.primeForReuse();
            } else {
                section = new WorldSection(WorldEngine.getLevel(key), WorldEngine.getX(key),
                        WorldEngine.getY(key), WorldEngine.getZ(key), this);
                status = this.loader.load(section);
                if (status < 0) {
                    Logger.error("Unable to load section " + section.key + " setting to air");
                    status = 1;
                }
                if (status == 1) {
                    Arrays.fill(section.data, Mapper.composeMappingId((byte) 15, 0, 0));
                }
                section.acquire(1);
            }
            int preAcquireCount = (int) VolatileHolder.PRE_ACQUIRE_COUNT.getAndSet(holder, 0);
            section.acquire(preAcquireCount);
            VolatileHolder.POST_ACQUIRE_COUNT.set(holder, preAcquireCount);
            synchronized (holder) {
                holder.obj = section;
                holder.notifyAll();
            }
            if (nullOnEmpty && status == 1) {
                section.release();
                return null;
            }
            return section;
        } catch (RuntimeException | Error failure) {
            // 仅移除本次未发布的 holder，允许后续调用重新加载。
            if (holder.obj == null) {
                long stamp = lock.writeLock();
                try {
                    cache.remove(key, holder);
                    this.loadedSections.decrementAndGet();
                    synchronized (holder) {
                        holder.failure = failure;
                        holder.notifyAll();
                    }
                } finally {
                    lock.unlockWrite(stamp);
                }
                if (section != null) section._releaseArray();
            }
            throw failure;
        }
    }

   void tryUnload(WorldSection section, int hints) {
      if (this.engine != null) {
         this.engine.lastActiveTime = System.currentTimeMillis();
      }

      if (section.shouldSave() && this.engine != null) {
         if (section.tryAcquire()) {
            VarHandle.loadLoadFence();
            if (section.shouldSave()) {
               if (this.engine.saveSection(section, false, true)) {
                  return;
               }

               Logger.info("section raced to into save queue, we lost");
               section.release(true, hints);
            } else {
               Logger.warn("section raced to save queue, we lost");
               section.release(true, hints);
            }
         } else if (section.shouldSave()) {
            Logger.error("failed to acquire section, but we need to save, this is really bad");
         } else {
            Logger.info("raced section");
         }
      } else if (section.getRefCount() == 0) {
         int index = this.getCacheArrayIndex(section.key);
         Long2ObjectOpenHashMap<ActiveSectionTracker.VolatileHolder<WorldSection>> cache = this.loadedSectionCache[index];
         WorldSection sec = null;
         StampedLock lock = this.locks[index];
         long stamp = lock.writeLock();
         // 保存、校验或缓存操作抛错时也要释放锁。
         try {
            if (section.getRefCount() != 0) {
               lock.unlockWrite(stamp);
               stamp = 0;
            } else {
               boolean shouldRetryExit = false;
               VarHandle.loadLoadFence();
               if (this.engine != null && section.shouldSave()) {
                  if (!section.tryAcquire()) {
                     throw new IllegalStateException("Section was dirty but is also unloaded, this is very bad");
                  }

                  if (!this.engine.saveSection(section, true, true)) {
                     shouldRetryExit |= true;
                     section.release(false, hints);
                  }
               }

               if (shouldRetryExit) {
                  lock.unlockWrite(stamp);
                  stamp = 0;
                  this.tryUnload(section, hints);
               } else {
                  if (section.getRefCount() == 0 && section.trySetFreed()) {
                     ActiveSectionTracker.VolatileHolder<WorldSection> cached = (ActiveSectionTracker.VolatileHolder<WorldSection>)cache.remove(section.key);
                     WorldSection obj = cached.obj;
                     if (obj == null) {
                        throw new IllegalStateException(
                           "This should be impossible: " + WorldEngine.pprintPos(section.key) + " secObj: " + System.identityHashCode(section)
                        );
                     }

                     if (obj != section) {
                        throw new IllegalStateException(
                           "Removed section not the same as the referenced section in the cache: cached: "
                              + obj
                              + " got: "
                              + section
                              + " A: "
                              + (Object)WorldSection.ATOMIC_STATE_HANDLE.get((WorldSection)obj)
                              + " B: "
                              + (Object)WorldSection.ATOMIC_STATE_HANDLE.get((WorldSection)section)
                        );
                     }

                     sec = section;
                  }

                  WorldSection aa = null;
                  if (sec != null && (hints & WorldSection.RELEASE_HINT_DONT_CACHE) != 0) {
                     lock.unlockWrite(stamp);
                     stamp = 0;
                     aa = sec;
                  } else if (sec != null) {
                     long stamp2 = this.lruLock.writeLock();
                     try {
                        lock.unlockWrite(stamp);
                        stamp = 0;
                        WorldSection a = (WorldSection)this.lruSecondaryCache.put(section.key, section);
                        if (a != null) {
                           throw new IllegalStateException("duplicate sections in cache is impossible");
                        }

                        if (this.lruSize < this.lruSecondaryCache.size()) {
                           aa = (WorldSection)this.lruSecondaryCache.removeFirst();
                        }
                     } finally {
                        this.lruLock.unlockWrite(stamp2);
                     }

                  } else {
                     lock.unlockWrite(stamp);
                     stamp = 0;
                  }

                  if (aa != null) {
                     aa._releaseArray();
                  }

                  if (sec != null) {
                     this.loadedSections.decrementAndGet();
                  }
               }
            }
         } finally {
            if (stamp != 0) {
               lock.unlockWrite(stamp);
            }
         }
      }
   }

   private int getCacheArrayIndex(long pos) {
      return (int)(mixStafford13(pos) & this.loadedSectionCache.length - 1);
   }

   public static long mixStafford13(long seed) {
      seed = (seed ^ seed >>> 30) * -4658895280553007687L;
      seed = (seed ^ seed >>> 27) * -7723592293110705685L;
      return seed ^ seed >>> 31;
   }

   public int getLoadedCacheCount() {
      return this.loadedSections.get();
   }

   public int getSecondaryCacheSize() {
      return this.lruSecondaryCache.size();
   }


   public interface SectionLoader {
      int load(WorldSection var1);
   }

   private static final class VolatileHolder<T> {
      private static final VarHandle PRE_ACQUIRE_COUNT;
      private static final VarHandle POST_ACQUIRE_COUNT;
      public volatile int preAcquireCount;
      public volatile int postAcquireCount;
      public volatile T obj;
        public volatile Throwable failure;

      static {
         try {
            PRE_ACQUIRE_COUNT = MethodHandles.lookup().findVarHandle(ActiveSectionTracker.VolatileHolder.class, "preAcquireCount", int.class);
            POST_ACQUIRE_COUNT = MethodHandles.lookup().findVarHandle(ActiveSectionTracker.VolatileHolder.class, "postAcquireCount", int.class);
         } catch (IllegalAccessException | NoSuchFieldException var1) {
            throw new RuntimeException(var1);
         }
      }
   }
}
