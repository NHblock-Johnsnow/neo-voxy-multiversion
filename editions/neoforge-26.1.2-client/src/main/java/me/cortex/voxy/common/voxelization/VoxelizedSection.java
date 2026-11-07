package me.cortex.voxy.common.voxelization;

import java.util.Arrays;

public class VoxelizedSection {
   public int x;
   public int y;
   public int z;
   public int lvl0NonAirCount;
   public final long[] section;
    private long[] mipScratch;

   public VoxelizedSection(long[] section) {
      this.section = section;
   }

   long[] mipScratch() {
        if (this.mipScratch == null) this.mipScratch = new long[512 + 64 + 8];
        return this.mipScratch;
    }

    public static int getBaseIndexForLevel(int lvl) {
      int offset = lvl == 1 ? 4096 : 0;
      offset |= lvl == 2 ? 4608 : 0;
      offset |= lvl == 3 ? 4672 : 0;
      return offset | (lvl == 4 ? 4680 : 0);
   }

   public VoxelizedSection setPosition(int x, int y, int z) {
      this.x = x;
      this.y = y;
      this.z = z;
      return this;
   }

   private static int getIdx(int x, int y, int z, int shiftBy, int size) {
      int M = (1 << size) - 1;
      x = x >> shiftBy & M;
      y = y >> shiftBy & M;
      z = z >> shiftBy & M;
      return y << (size << 1) | z << size | x;
   }

   public long get(int lvl, int x, int y, int z) {
      int offset = lvl == 1 ? 4096 : 0;
      offset |= lvl == 2 ? 4608 : 0;
      offset |= lvl == 3 ? 4672 : 0;
      offset |= lvl == 4 ? 4680 : 0;
      return this.section[getIdx(x, y, z, 0, 4 - lvl) + offset];
   }

   public static VoxelizedSection createEmpty() {
      return new VoxelizedSection(new long[4681]);
   }

   public VoxelizedSection zero() {
      this.lvl0NonAirCount = 0;
      Arrays.fill(this.section, 0L);
      return this;
   }
}
