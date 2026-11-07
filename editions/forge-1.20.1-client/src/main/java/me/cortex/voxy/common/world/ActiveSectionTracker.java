package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

public final class ActiveSectionTracker {

    //Deserialize into the supplied section, returns true on success, false on failure
    public interface SectionLoader {int load(WorldSection section);}

    //Loaded section world cache, TODO: get rid of VolatileHolder and use something more sane
    private static final class VolatileHolder <T> {
        private static final VarHandle PRE_ACQUIRE_COUNT;
        private static final VarHandle POST_ACQUIRE_COUNT;
        static {
            try {
                PRE_ACQUIRE_COUNT = MethodHandles.lookup().findVarHandle(VolatileHolder.class, "preAcquireCount", int.class);
                POST_ACQUIRE_COUNT = MethodHandles.lookup().findVarHandle(VolatileHolder.class, "postAcquireCount", int.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        public volatile int preAcquireCount;
        public volatile int postAcquireCount;
        public volatile T obj;
        public volatile Throwable failure;
    }

    private final AtomicInteger loadedSections = new AtomicInteger();
    private final Long2ObjectOpenHashMap<VolatileHolder<WorldSection>>[] loadedSectionCache;
    private final StampedLock[] locks;
    private final SectionLoader loader;

    private final int lruSize;
    private final StampedLock lruLock = new StampedLock();
    private final Long2ObjectLinkedOpenHashMap<WorldSection> lruSecondaryCache;//TODO: THIS NEEDS TO BECOME A GLOBAL STATIC CACHE

    @Nullable
    public final WorldEngine engine;

    public ActiveSectionTracker(int numSlicesBits, SectionLoader loader, int cacheSize) {
        this(numSlicesBits, loader, cacheSize, null);
    }

    @SuppressWarnings("unchecked")
    public ActiveSectionTracker(int numSlicesBits, SectionLoader loader, int cacheSize, WorldEngine engine) {
        this.engine = engine;

        this.loader = loader;
        this.loadedSectionCache = new Long2ObjectOpenHashMap[1<<numSlicesBits];
        this.lruSecondaryCache = new Long2ObjectLinkedOpenHashMap<>(cacheSize);
        this.locks = new StampedLock[1<<numSlicesBits];
        this.lruSize = cacheSize;
        for (int i = 0; i < this.loadedSectionCache.length; i++) {
            this.loadedSectionCache[i] = new Long2ObjectOpenHashMap<>(1024);
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
        if (this.engine != null) this.engine.lastActiveTime = System.currentTimeMillis();
        if (section.shouldSave()&&this.engine!=null) {
            if (section.tryAcquire()) {
                VarHandle.loadLoadFence();
                if (section.shouldSave()) {//If we should try enqueue
                    if (!this.engine.saveSection(section, false, true)) {
                        //we didnt enqueue the section in the save queue so we must unload it manually
                        Logger.info("section raced to into save queue, we lost");
                        section.release(true, hints);//We need to try unload cause else we may loose state
                    } else {
                        //section is queued, and we gave it the acquired section, so we can just return
                        return;//We just return
                    }
                } else {
                    Logger.warn("section raced to save queue, we lost");
                    section.release(true, hints);//Unload cause we need to retry the whole thing again
                }
            } else {
                if (section.shouldSave()) {
                    //This is bad
                    Logger.error("failed to acquire section, but we need to save, this is really bad");
                } else {
                    Logger.info("raced section");
                }
            }
            return;//If we reach here, we need to just return, unload pipeline will be taken care of elsewhere
        }

        if (section.getRefCount() != 0) {
            return;
        }
        int index = this.getCacheArrayIndex(section.key);
        final var cache = this.loadedSectionCache[index];
        WorldSection sec = null;
        final var lock = this.locks[index];
        long stamp = lock.writeLock();
        // 保存、校验或缓存操作抛错时也要释放锁。
        try {
            if (section.getRefCount() != 0) {
                lock.unlockWrite(stamp);
                stamp = 0;
                return;
            }
            boolean shouldRetryExit = false;
            {
                VarHandle.loadLoadFence();
                if (this.engine != null && section.shouldSave()) {//Last call for saving
                    if (section.tryAcquire()) {
                        if (!this.engine.saveSection(section, true, true)) {//not allowed to block as we are in a lock
                            //We didnt enqueue the save here, so we must unload
                            // but unload in a recursive
                            //VarHandle.fullFence();
                            //shouldRetryExit |= section.getRefCount()!=1;//if we arnt the only ref
                            //VarHandle.fullFence();
                            //shouldRetryExit |= section.isDirty;//or if the section is now dirty, note this must go AFTER the ref check, since you can only mark live sections as dirty

                            shouldRetryExit |= true;//Always force retry when/if we hit this case
                            section.release(false, hints);//Special, we cannot unload here else we deadlock
                            //we can do a no-unload since we are guarenteed to retry
                        }


                        //NOTE: think have since fixed this issue
                        //In theory there can be a race condition here, where if this thread is paused
                        // the save queue fully finishes, the state is dirty == false inSaveQueue == false
                        // but the acquire count is at least 1
                        //if another thread marks this chunk as dirty (it would have acquired it after the inital `section.getRefCount() != 0`
                        // return check) and releases it, since the acquire count is still 1 (acquired here)
                        // then it doesnt trigger a save attempt but the dirty flag is set
                        //then this code continues and it causes badness cause its now in an invalid state
                    } else {
                        throw new IllegalStateException("Section was dirty but is also unloaded, this is very bad");
                    }
                }

                //This is a painful case, we need to abort here if there was a funky thing that happened
                if (shouldRetryExit) {
                    lock.unlockWrite(stamp);
                    stamp = 0;
                    //retry
                    this.tryUnload(section, hints);
                    return;
                }

                if (section.getRefCount() == 0 && section.trySetFreed()) {
                    var cached = cache.remove(section.key);
                    var obj = cached.obj;
                    if (obj == null) {
                        throw new IllegalStateException("This should be impossible: " + WorldEngine.pprintPos(section.key) + " secObj: " + System.identityHashCode(section));
                    }
                    if (obj != section) {
                        throw new IllegalStateException("Removed section not the same as the referenced section in the cache: cached: " + obj + " got: " + section + " A: " + WorldSection.ATOMIC_STATE_HANDLE.get(obj) + " B: " +WorldSection.ATOMIC_STATE_HANDLE.get(section));
                    }
                    sec = section;
                }
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
                    WorldSection a = this.lruSecondaryCache.put(section.key, section);
                    if (a != null) {
                        throw new IllegalStateException("duplicate sections in cache is impossible");
                    }
                    //If cache is bigger than its ment to be, remove the least recently used and free it
                    if (this.lruSize < this.lruSecondaryCache.size()) {
                        aa = this.lruSecondaryCache.removeFirst();
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
        } finally {
            if (stamp != 0) {
                lock.unlockWrite(stamp);
            }
        }
    }

    private int getCacheArrayIndex(long pos) {
        return (int) (mixStafford13(pos) & (this.loadedSectionCache.length-1));
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


}
