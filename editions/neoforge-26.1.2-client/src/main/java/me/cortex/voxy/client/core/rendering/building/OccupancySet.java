package me.cortex.voxy.client.core.rendering.building;

import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;

public class OccupancySet {
   private long topLvl;
   private final long[] bottomLvl = new long[512];

   public void set(int pos) {
      long topBit = 1L << Integer.compress(pos, 25368);
      int botIdx = Integer.compress(pos, 7399);
      int baseBotIdx = Long.bitCount(this.topLvl & topBit - 1L) * 8;
      if ((this.topLvl & topBit) == 0L) {
         long toMove = this.topLvl & ~((topBit << 1) - 1L);
         if (toMove != 0L) {
            int base = baseBotIdx + 8;
            int count = Long.bitCount(toMove);

            for (int i = base + count * 8 - 1; base <= i; i--) {
               this.bottomLvl[i] = this.bottomLvl[i - 8];
            }

            for (int i = baseBotIdx; i < baseBotIdx + 8; i++) {
               this.bottomLvl[i] = 0L;
            }
         }

         this.topLvl |= topBit;
      }

      this.bottomLvl[baseBotIdx + (botIdx >> 6)] = this.bottomLvl[baseBotIdx + (botIdx >> 6)] | 1L << (botIdx & 63);
   }

   private boolean get(int pos) {
      long topBit = 1L << Integer.compress(pos, 25368);
      int botIdx = Integer.compress(pos, 7399);
      if ((this.topLvl & topBit) == 0L) {
         return false;
      } else {
         int baseBotIdx = Long.bitCount(this.topLvl & topBit - 1L) * 8;
         return (this.bottomLvl[baseBotIdx + (botIdx >> 6)] & 1L << (botIdx & 63)) != 0L;
      }
   }

   public void reset() {
      if (this.topLvl != 0L) {
         Arrays.fill(this.bottomLvl, 0L);
      }

      this.topLvl = 0L;
   }

   public int writeSize() {
      return 8 + Long.bitCount(this.topLvl) * 8 * 8;
   }

   public boolean isEmpty() {
      return this.topLvl == 0L;
   }

   public void write(long ptr, boolean asLongs) {
      if (asLongs) {
         MemoryUtil.memPutLong(ptr, this.topLvl);
         ptr += 8L;
         int cnt = Long.bitCount(this.topLvl);

         for (int i = 0; i < cnt; i++) {
            for (int j = 0; j < 8; j++) {
               MemoryUtil.memPutLong(ptr, this.bottomLvl[i * 8 + j]);
               ptr += 8L;
            }
         }
      } else {
         MemoryUtil.memPutInt(ptr, (int)(this.topLvl >>> 32));
         ptr += 4L;
         MemoryUtil.memPutInt(ptr, (int)this.topLvl);
         ptr += 4L;
         int cnt = Long.bitCount(this.topLvl);

         for (int i = 0; i < cnt; i++) {
            for (int j = 0; j < 8; j++) {
               long v = this.bottomLvl[i * 8 + j];
               MemoryUtil.memPutInt(ptr, (int)(v >>> 32));
               ptr += 4L;
               MemoryUtil.memPutInt(ptr, (int)v);
               ptr += 4L;
            }
         }
      }
   }


}
