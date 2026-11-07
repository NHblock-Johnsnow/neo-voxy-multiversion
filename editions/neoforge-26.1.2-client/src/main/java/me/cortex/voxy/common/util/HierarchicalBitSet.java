package me.cortex.voxy.common.util;


public class HierarchicalBitSet {
   public static final int SET_FULL = -1;
   private final int limit;
   private int cnt;
   private long A = 0L;
   private final long[] B = new long[64];
   private final long[] C = new long[4096];
   private final long[] D = new long[262144];
   private int endId = -1;

   public HierarchicalBitSet(int limit) {
      this.limit = limit;
      if (limit > 16777216) {
         throw new IllegalArgumentException("Limit greater than capacity");
      }
   }

   public HierarchicalBitSet() {
      this(16777216);
   }

   public int allocateNext() {
      if (this.A == -1L) {
         return -1;
      } else if (this.cnt + 1 > this.limit) {
         return -1;
      } else {
         int idx = Long.numberOfTrailingZeros(~this.A);
         long bp = this.B[idx];
         idx = Long.numberOfTrailingZeros(~bp) + 64 * idx;
         long cp = this.C[idx];
         idx = Long.numberOfTrailingZeros(~cp) + 64 * idx;
         long dp = this.D[idx];
         idx = Long.numberOfTrailingZeros(~dp) + 64 * idx;
         int allocatedId = idx;
         dp |= 1L << (idx & 63);
         this.D[idx >> 6] = dp;
         if (dp == -1L) {
            idx >>= 6;
            cp |= 1L << (idx & 63);
            this.C[idx >> 6] = cp;
            if (cp == -1L) {
               idx >>= 6;
               bp |= 1L << (idx & 63);
               this.B[idx >> 6] = bp;
               if (bp == -1L) {
                  idx >>= 6;
                  this.A |= 1L << (idx & 63);
               }
            }
         }

         this.cnt++;
         this.endId = this.endId + (allocatedId == this.endId + 1 ? 1 : 0);
         return allocatedId;
      }
   }

   private void set(int idx) {
      this.endId = this.endId + (idx == this.endId + 1 ? 1 : 0);
      long dp = this.D[idx >> 6] = this.D[idx >> 6] | 1L << (idx & 63);
      if (dp == -1L) {
         idx >>= 6;
         long cp = this.C[idx >> 6] = this.C[idx >> 6] | 1L << (idx & 63);
         if (cp == -1L) {
            idx >>= 6;
            long bp = this.B[idx >> 6] = this.B[idx >> 6] | 1L << (idx & 63);
            if (bp == -1L) {
               idx >>= 6;
               this.A |= 1L << (idx & 63);
            }
         }
      }

      this.cnt++;
   }

   private int findNextFree(int idx) {
      int var4;
      do {
         int pos = Long.numberOfTrailingZeros(~this.A & -(1L << (idx >> 18)));
         idx = Math.max(pos << 18, idx);
         var4 = Long.numberOfTrailingZeros(~this.B[idx >> 18] & -(1L << (idx >> 12 & 63)));
         idx = Math.max(var4 + (idx >> 18 << 6) << 12, idx);
         if (var4 != 64) {
            var4 = Long.numberOfTrailingZeros(~this.C[idx >> 12] & -(1L << (idx >> 6 & 63)));
            idx = Math.max(var4 + (idx >> 12 << 6) << 6, idx);
            if (var4 != 64) {
               var4 = Long.numberOfTrailingZeros(~this.D[idx >> 6] & -(1L << (idx & 63)));
               idx = Math.max(var4 + (idx >> 6 << 6), idx);
            }
         }
      } while (var4 == 64);

      return idx;
   }

   public int allocateNextConsecutiveCounted(int count) {
      if (count > 64) {
         throw new IllegalStateException("Count to large for current implementation which has fastpath");
      } else if (this.A == -1L) {
         return -1;
      } else if (this.cnt + count >= this.limit) {
         return -2;
      } else {
         long chkMsk = (1L << count) - 1L;
         int i = this.findNextFree(0);

         while (true) {
            long fusedValue = this.D[i >> 6] >>> (i & 63);
            if (64 - (i & 63) < count) {
               fusedValue |= this.D[(i >> 6) + 1] << 64 - (i & 63);
            }

            if ((fusedValue & chkMsk) == 0L) {
               for (int j = 0; j < count; j++) {
                  this.set(j + i);
               }

               return i;
            }

            i += Long.numberOfTrailingZeros(fusedValue);
            i = this.findNextFree(i);
         }
      }
   }

   public boolean free(int idx) {
      long v = this.D[idx >> 6];
      boolean wasSet = (v & 1L << (idx & 63)) != 0L;
      this.cnt -= wasSet ? 1 : 0;
      if (wasSet && idx == this.endId) {
         this.endId--;

         while (this.endId >= 0 && !this.isSet(this.endId)) {
            this.endId--;
         }
      }

      this.D[idx >> 6] = v & ~(1L << (idx & 63));
      idx >>= 6;
      this.C[idx >> 6] = this.C[idx >> 6] & ~(1L << (idx & 63));
      idx >>= 6;
      this.B[idx >> 6] = this.B[idx >> 6] & ~(1L << (idx & 63));
      idx >>= 6;
      this.A &= ~(1L << (idx & 63));
      return wasSet;
   }

   public int getCount() {
      return this.cnt;
   }

   public int getLimit() {
      return this.limit;
   }

   public boolean isSet(int idx) {
      return (this.D[idx >> 6] & 1L << (idx & 63)) != 0L;
   }

   public int getMaxIndex() {
      return this.endId;
   }


}
