package me.cortex.voxy.common.util;


public class HierarchicalBitSet {
    public static final int SET_FULL = -1;
    private final int limit;
    private int cnt;
    //If a bit is 1 it means all children are also set
    private long A = 0;
    private final long[] B = new long[64];
    private final long[] C = new long[64*64];
    private final long[] D = new long[64*64*64];
    public HierarchicalBitSet(int limit) {//Fixed size of 64^4
        this.limit = limit;
        if (limit > (1<<(6*4))) {
            throw new IllegalArgumentException("Limit greater than capacity");
        }
    }

    private int endId = -1;

    public HierarchicalBitSet() {
        this(1<<(6*4));
    }

    public int allocateNext() {
        if (this.A==-1) {
            return -1;
        }
        if (this.cnt+1>this.limit) {
            return -1;//Limit reached
        }
        int idx = Long.numberOfTrailingZeros(~this.A);
        long bp = this.B[idx];
        idx = Long.numberOfTrailingZeros(~bp) + 64*idx;
        long cp = this.C[idx];
        idx = Long.numberOfTrailingZeros(~cp) + 64*idx;
        long dp = this.D[idx];
        idx =  Long.numberOfTrailingZeros(~dp) + 64*idx;
        int ret = idx;

        //if (this.isSet(ret)) {
        //    throw new IllegalStateException();
        //}

        dp |= 1L<<(idx&0x3f);
        this.D[idx>>6] = dp;
        if (dp==-1) {
            idx >>= 6;
            cp |= 1L<<(idx&0x3f);
            this.C[idx>>6] = cp;
            if (cp==-1) {
                idx >>= 6;
                bp |= 1L<<(idx&0x3f);
                this.B[idx>>6] = bp;
                if (bp==-1) {
                    idx >>= 6;
                    this.A |= 1L<<(idx&0x3f);
                }
            }
        }
        this.cnt++;
        this.endId += ret==(this.endId+1)?1:0;
        return ret;
    }

    private void set(int idx) {
        //if (this.isSet(idx)) {
        //    throw new IllegalStateException();
        //}

        this.endId += idx==(this.endId+1)?1:0;
        long dp = this.D[idx>>6] |= 1L<<(idx&0x3f);
        if (dp==-1) {
            idx >>= 6;
            long cp = (this.C[idx>>6] |= 1L<<(idx&0x3f));
            if (cp==-1) {
                idx >>= 6;
                long bp = this.B[idx>>6] |= 1L<<(idx&0x3f);
                if (bp==-1) {
                    idx >>= 6;
                    this.A |= 1L<<(idx&0x3f);
                }
            }
        }
        this.cnt++;
    }

    //Returns the next free index from idx
    private int findNextFree(int idx) {
        int pos;
        do {
            pos = Long.numberOfTrailingZeros((~this.A) & -(1L << (idx >> 18)));
            idx = Math.max(pos << 18, idx);

            pos = Long.numberOfTrailingZeros((~this.B[idx >> 18]) & -(1L << ((idx >> 12) & 0x3F)));
            idx = Math.max((pos + ((idx >> 18) << 6)) << 12, idx);
            if (pos == 64) continue;//Try again

            pos = Long.numberOfTrailingZeros((~this.C[idx >> 12]) & -(1L << ((idx >> 6) & 0x3F)));
            idx = Math.max((pos + ((idx >> 12) << 6)) << 6, idx);
            if (pos == 64) continue;//Try again

            pos = Long.numberOfTrailingZeros(((~this.D[idx >> 6]) & -(1L << (idx & 0x3F))));
            idx = Math.max(pos + ((idx >> 6) << 6), idx);
        } while (pos == 64);
        //TODO: fixme: this is due to the fact of the acceleration structure
        return idx;
    }


    //TODO: FIXME: THIS IS SLOW AS SHIT
    public int allocateNextConsecutiveCounted(int count) {
        if (count > 64) {
            throw new IllegalStateException("Count to large for current implementation which has fastpath");
        }
        if (this.A==-1) {
            return -1;
        }
        if (this.cnt+count>=this.limit) {
            return -2;//Limit reached
        }
        long chkMsk = ((1L<<count)-1);
        int i = this.findNextFree(0);
        while (true) {
            long fusedValue = this.D[i>>6]>>>(i&63);
            if (64-(i&63) < count) {
                fusedValue |= this.D[(i>>6)+1] << (64-(i&63));
            }

            if ((fusedValue&chkMsk) != 0) {
                //Space does not contain enough empty value
                i += Long.numberOfTrailingZeros(fusedValue);//Skip as much as possible (i.e. skip to the next 1 bit)
                i = this.findNextFree(i);

                continue;
            }

            //TODO: optimize this laziness
            // (can  do it by first setting/updating the lower D index and propagating, then the upper D index (if it has/needs one))
            for (int j = 0; j < count; j++) {
                this.set(j + i);
            }
            return i;
        }
    }


    public boolean free(int idx) {
        long v = this.D[idx>>6];
        boolean wasSet = (v&(1L<<(idx&0x3f)))!=0;
        this.cnt -= wasSet?1:0;

        if (wasSet && idx == this.endId) {
            //Need to go back until we find the endIdx bit
            for (this.endId--; this.endId>=0 && !this.isSet(this.endId); this.endId--);
            //this.endId++;
        }

        this.D[idx>>6] = v&~(1L<<(idx&0x3f));
        idx >>= 6;
        this.C[idx>>6] &= ~(1L<<(idx&0x3f));
        idx >>= 6;
        this.B[idx>>6] &= ~(1L<<(idx&0x3f));
        idx >>= 6;
        this.A &= ~(1L<<(idx&0x3f));

        return wasSet;
    }

    public int getCount() {
        return this.cnt;
    }
    public int getLimit() {
        return this.limit;
    }

    public boolean isSet(int idx) {
        return (this.D[idx>>6]&(1L<<(idx&0x3f)))!=0;
    }


    public int getMaxIndex() {
        return this.endId;
    }


}
