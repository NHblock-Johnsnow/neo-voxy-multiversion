package me.cortex.voxy.common.world;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import me.cortex.voxy.commonImpl.VoxyCommon;

public final class WorldSection {
   public static final int SECTION_VOLUME = 32768;
   public static final boolean VERIFY_WORLD_SECTION_EXECUTION = VoxyCommon.isVerificationFlagOn("verifyWorldSectionExecution");
   static final VarHandle ATOMIC_STATE_HANDLE;
   private static final VarHandle NON_EMPTY_CHILD_HANDLE;
   private static final VarHandle NON_EMPTY_BLOCK_HANDLE;
   private static final VarHandle IN_SAVE_QUEUE_HANDLE;
   private static final VarHandle IS_DIRTY_HANDLE;
   private static final int ARRAY_REUSE_CACHE_SIZE = 400;
   private static final AtomicInteger ARRAY_REUSE_CACHE_COUNT;
   private static final ConcurrentLinkedDeque<long[]> ARRAY_REUSE_CACHE;
   public final int lvl;
   public final int x;
   public final int y;
   public final int z;
   public final long key;
   long metadata;
   long[] data = null;
   volatile int nonEmptyBlockCount = 0;
   volatile byte nonEmptyChildren;
   final ActiveSectionTracker tracker;
   volatile boolean inSaveQueue;
   volatile boolean isDirty;
   private volatile int atomicState = 1;
   public static int RELEASE_HINT_POSSIBLE_REUSE;
   public static int RELEASE_HINT_DONT_CACHE = 2;

   WorldSection(int lvl, int x, int y, int z, ActiveSectionTracker tracker) {
      this.lvl = lvl;
      this.x = x;
      this.y = y;
      this.z = z;
      this.key = WorldEngine.getWorldSectionId(lvl, x, y, z);
      this.tracker = tracker;
      this.surfaceData = SurfaceDataCoverage.legacy(lvl);
      this.data = ARRAY_REUSE_CACHE.poll();
      if (this.data == null) {
         this.data = new long[32768];
      } else {
         ARRAY_REUSE_CACHE_COUNT.decrementAndGet();
      }
   }

   void primeForReuse() {
      // 复用时直接持有加载者引用，避免迟到卸载回调看到零引用窗口。
      ATOMIC_STATE_HANDLE.set((WorldSection)this, (int)3);
   }

   volatile SurfaceDataCoverage surfaceData;
   volatile long surfaceMigrationRetryAfter;
   volatile boolean legacySurfaceSource;

   public SurfaceDataCoverage getSurfaceData() {
      return this.surfaceData;
   }

   public long[] _unsafeGetRawDataArray() {
      return this.data;
   }

   @Override
   public int hashCode() {
      return ((this.x * 1235641 + this.y) * 8127451 + this.z) * 918267913 + this.lvl;
   }

   public boolean tryAcquire() {
      int prev;
      int next;
      do {
         prev = (int)ATOMIC_STATE_HANDLE.get((WorldSection)this);
         if ((prev & 1) == 0) {
            return false;
         }

         next = prev + 2;
      } while (!ATOMIC_STATE_HANDLE.compareAndSet((WorldSection)this, (int)prev, (int)next));

      return (next & 1) != 0;
   }

   public int acquire() {
      return this.acquire(1);
   }

   public int acquire(int count) {
      int state = (int)ATOMIC_STATE_HANDLE.getAndAdd((WorldSection)this, (int)(count << 1)) + (count << 1);
      if ((state & 1) == 0) {
         throw new IllegalStateException("Tried to acquire unloaded section: " + WorldEngine.pprintPos(this.key) + " obj: " + System.identityHashCode(this));
      } else {
         return state >> 1;
      }
   }

   public int getRefCount() {
      return (int)ATOMIC_STATE_HANDLE.get((WorldSection)this) >> 1;
   }

   public int release() {
      return this.release(true, 0);
   }

   public int release(int hints) {
      return this.release(true, hints);
   }

   int release(boolean unload, int hints) {
      int state = (int)ATOMIC_STATE_HANDLE.getAndAdd((WorldSection)this, (int)-2) - 2;
      if (state < 1) {
         throw new IllegalStateException("Section got into an invalid state");
      } else if ((state & 1) == 0) {
         throw new IllegalStateException("Tried releasing a freed section");
      } else {
         if (state >> 1 == 0 && unload) {
            if (this.tracker != null) {
               this.tracker.tryUnload(this, hints);
            } else if (this.trySetFreed()) {
               this._releaseArray();
            }
         }

         return state >> 1;
      }
   }

   boolean trySetFreed() {
      int witness = (int)ATOMIC_STATE_HANDLE.compareAndExchange((WorldSection)this, (int)1, (int)0);
      if ((witness & 1) == 0 && witness != 0) {
         throw new IllegalStateException("Section marked as free but has refs");
      } else if (witness != 1 || !this.isDirty && !this.inSaveQueue) {
         return witness == 1;
      } else {
         throw new IllegalStateException(
            "Section freed while marked as dirty or in the save queue: " + (this.isDirty ? "dirty, " : "") + (this.inSaveQueue ? "saveQueue" : "")
         );
      }
   }

   void _releaseArray() {
      if (VERIFY_WORLD_SECTION_EXECUTION && this.data == null) {
         throw new IllegalStateException();
      } else {
         if (ARRAY_REUSE_CACHE_COUNT.get() < 400) {
            ARRAY_REUSE_CACHE.add(this.data);
            ARRAY_REUSE_CACHE_COUNT.incrementAndGet();
         }

         this.data = null;
      }
   }

   public void assertNotFree() {
      if (VERIFY_WORLD_SECTION_EXECUTION && ((int)ATOMIC_STATE_HANDLE.get((WorldSection)this) & 1) == 0) {
         throw new IllegalStateException();
      }
   }

   public static int getIndex(int x, int y, int z) {
      int M = 31;
      if (!VERIFY_WORLD_SECTION_EXECUTION || x >= 0 && x <= 31 && y >= 0 && y <= 31 && z >= 0 && z <= 31) {
         return (y & 31) << 10 | (z & 31) << 5 | x & 31;
      } else {
         throw new IllegalArgumentException("Out of bounds: " + x + ", " + y + ", " + z);
      }
   }

   public long set(int x, int y, int z, long id) {
      int idx = getIndex(x, y, z);
      long old = this.data[idx];
      this.data[idx] = id;
      return old;
   }

   public long[] copyData() {
      this.assertNotFree();
      return Arrays.copyOf(this.data, this.data.length);
   }

   public void copyDataTo(long[] cache) {
      this.copyDataTo(cache, 0);
   }

   public void copyDataTo(long[] cache, int dstOffset) {
      this.assertNotFree();
      if (cache.length - dstOffset < this.data.length) {
         throw new IllegalArgumentException();
      } else {
         System.arraycopy(this.data, 0, cache, dstOffset, this.data.length);
      }
   }

   public static int getChildIndex(int x, int y, int z) {
      return x & 1 | (y & 1) << 2 | (z & 1) << 1;
   }

   public byte getNonEmptyChildren() {
      return (byte)NON_EMPTY_CHILD_HANDLE.get((WorldSection)this);
   }

   public int updateEmptyChildState(WorldSection child) {
      int childIdx = getChildIndex(child.x, child.y, child.z);
      byte msk = (byte)(1 << childIdx);

      byte prev;
      byte next;
      do {
         prev = (byte) NON_EMPTY_CHILD_HANDLE.getVolatile(this);
         next = (byte)(prev & ~msk | ((byte) NON_EMPTY_CHILD_HANDLE.getVolatile(child) != 0 ? msk : 0));
         if (prev == next) return 0;
      } while (!NON_EMPTY_CHILD_HANDLE.compareAndSet((WorldSection)this, (byte)prev, (byte)next));

      return prev != 0 ^ next != 0 ? 2 : (prev != next ? 1 : 0);
   }

   public int getNonEmptyBlockCount() {
      return (int)NON_EMPTY_BLOCK_HANDLE.get((WorldSection)this);
   }

   public int addNonEmptyBlockCount(int delta) {
      int count = (int)NON_EMPTY_BLOCK_HANDLE.getAndAdd((WorldSection)this, (int)delta) + delta;
      if (VERIFY_WORLD_SECTION_EXECUTION && count < 0) {
         throw new IllegalStateException("Count is negative!");
      } else {
         return count;
      }
   }

   public boolean updateLvl0State() {
      if (VERIFY_WORLD_SECTION_EXECUTION && this.lvl != 0) {
         throw new IllegalStateException("Tried updating a level 0 lod when its not level 0: " + WorldEngine.pprintPos(this.key));
      } else {
         byte prev;
         byte next;
         do {
            prev = this.getNonEmptyChildren();
            next = (byte)((int)NON_EMPTY_BLOCK_HANDLE.get((WorldSection)this) == 0 ? 0 : 255);
         } while (!NON_EMPTY_CHILD_HANDLE.compareAndSet((WorldSection)this, (byte)prev, (byte)next));

         return prev != next;
      }
   }

   public void _unsafeSetNonEmptyChildren(byte nonEmptyChildren) {
      NON_EMPTY_CHILD_HANDLE.set((WorldSection)this, (byte)nonEmptyChildren);
   }

   public static WorldSection _createRawUntrackedUnsafeSection(int lvl, int x, int y, int z) {
      return new WorldSection(lvl, x, y, z, null);
   }

   public void markDirty() {
      IS_DIRTY_HANDLE.getAndSet((WorldSection)this, (boolean)true);
   }

   public boolean exchangeIsInSaveQueue(boolean state) {
      return (boolean)IN_SAVE_QUEUE_HANDLE.compareAndExchange((WorldSection)this, (boolean)(!state), (boolean)state) == !state;
   }

   public boolean setNotDirty() {
      return (boolean)IS_DIRTY_HANDLE.getAndSet((WorldSection)this, (boolean)false);
   }

   public boolean shouldSave() {
      return this.isDirty && !this.inSaveQueue;
   }

   public boolean isFreed() {
      return ((int)ATOMIC_STATE_HANDLE.get((WorldSection)this) & 1) == 0;
   }

   static {
      try {
         ATOMIC_STATE_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "atomicState", int.class);
         NON_EMPTY_CHILD_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "nonEmptyChildren", byte.class);
         NON_EMPTY_BLOCK_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "nonEmptyBlockCount", int.class);
         IN_SAVE_QUEUE_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "inSaveQueue", boolean.class);
         IS_DIRTY_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "isDirty", boolean.class);
      } catch (IllegalAccessException | NoSuchFieldException var1) {
         throw new RuntimeException(var1);
      }

      ARRAY_REUSE_CACHE_COUNT = new AtomicInteger(0);
      ARRAY_REUSE_CACHE = new ConcurrentLinkedDeque<>();
      RELEASE_HINT_POSSIBLE_REUSE = 1;
   }
}
