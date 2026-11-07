package me.cortex.voxy.client.core.rendering.building;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

//16x16x16 occupancy set
public class OccupancySet2 {
    private long topLvl;//4x4x4
    private final long[] bottomLvl = new long[4*4*4];
    public void set(final int pos) {
        final long topBit = 1L<<me.cortex.voxy.common.util.BitMath.compress(pos, 0b1100_1100_1100);
        final int  botIdx =     me.cortex.voxy.common.util.BitMath.compress(pos, 0b0011_0011_0011);

        int baseBotIdx = Long.bitCount(this.topLvl&(topBit-1));
        if ((this.topLvl & topBit) == 0) {
            //we need to shuffle up all the bottomlvl
            long toMove = this.topLvl & (~((topBit << 1) - 1));
            if (toMove != 0) {
                int base = baseBotIdx+1;//+1 cause were bubbling
                int count = Long.bitCount(toMove);
                for (int i = base+count-1; base<=i; i--) {
                    this.bottomLvl[i] = this.bottomLvl[i-1];
                }
                this.bottomLvl[baseBotIdx] = 0;
            }

            this.topLvl |= topBit;
        }

        this.bottomLvl[baseBotIdx+(botIdx>>6)] |= 1L<<(botIdx&63);
    }

    private boolean get(int pos) {
        final long topBit = 1L<<me.cortex.voxy.common.util.BitMath.compress(pos, 0b1100_1100_1100);
        final int  botIdx =     me.cortex.voxy.common.util.BitMath.compress(pos, 0b0011_0011_0011);
        if ((this.topLvl & topBit) == 0) {
            return false;
        }
        int baseBotIdx = Long.bitCount(this.topLvl&(topBit-1));

        return (this.bottomLvl[baseBotIdx+(botIdx>>6)]&(1L<<(botIdx&63)))!=0;
    }

    public void reset() {
        if (this.topLvl != 0) {
            Arrays.fill(this.bottomLvl, 0);
        }
        this.topLvl = 0;
    }

    public int writeSize() {
        return 8+Long.bitCount(this.topLvl)*8;
    }

    public boolean isEmpty() {
        return this.topLvl == 0;
    }

    public void write(long ptr, boolean asLongs) {
        if (asLongs) {
            MemoryUtil.memPutLong(ptr, this.topLvl); ptr += 8;
            int cnt = Long.bitCount(this.topLvl);
            for (int i = 0; i < cnt; i++) {
                MemoryUtil.memPutLong(ptr, this.bottomLvl[i]); ptr += 8;
            }
        } else {
            MemoryUtil.memPutInt(ptr, (int) (this.topLvl>>>32)); ptr += 4;
            MemoryUtil.memPutInt(ptr, (int) this.topLvl); ptr += 4;
            int cnt = Long.bitCount(this.topLvl);
            for (int i = 0; i < cnt; i++) {
                long v = this.bottomLvl[i];
                MemoryUtil.memPutInt(ptr, (int) (v>>>32)); ptr += 4;
                MemoryUtil.memPutInt(ptr, (int) v); ptr += 4;
            }
        }
    }


}
