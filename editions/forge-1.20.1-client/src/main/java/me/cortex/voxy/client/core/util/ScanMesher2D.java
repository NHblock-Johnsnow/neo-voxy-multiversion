package me.cortex.voxy.client.core.util;


public abstract class ScanMesher2D {

    private static final int MAX_SIZE = 16;

    // is much faster if implemented inline into parent
    private final long[] rowData = new long[32];
    private final int[] rowLength = new int[32];//How long down does a row entry go
    private final int[] rowDepth = new int[32];//How many rows does it cover
    private int rowBitset = 0;

    private int currentIndex = 0;
    private int currentSum = 0;
    private long currentData = 0;

    //Two different ways to do it, scanline then only merge on change, or try to merge with previous row at every step
    // or even can also attempt to merge previous but if the lengths are different split the current one and merge to previous
    public final void putNext(long data) {
        this.putNext0(data);
    }

    private void putNext0(long data) {
        int idx = (this.currentIndex++)&31;//Mask to current row, but keep total so can compute actual indexing

        //If we are on the zero index, ignore it as we are going from empty state to maybe something state
        // setup data
        if (idx == 0) {
            //If the previous data is not zero, that means it was not merge-able, so emit it at the pos
            if (this.currentData!=0) {
                if ((this.rowBitset&(1<<31))!=0) {
                    this.emitQuad(31, ((this.currentIndex-1)>>5)-1, this.rowLength[31], this.rowDepth[31], this.rowData[31]);
                }
                this.rowBitset |= 1<<31;
                this.rowLength[31] = this.currentSum;
                this.rowDepth[31] = 1;
                this.rowData[31] = this.currentData;
            }

            //Set the data to the first element
            this.currentData = data;
            this.currentSum = 0;
        }

        //If we are different from previous (this can never happen if previous is index 0)
        if (data != this.currentData || this.currentSum == MAX_SIZE) {
            //write out previous data if its a non sentinel, it is guarenteed to not have a row bit set
            if (this.currentData != 0) {
                int prev = idx-1;//We need to write in the previous entry
                this.rowDepth[prev] = 1;
                this.rowLength[prev] = this.currentSum;
                this.rowData[prev] = this.currentData;
                this.rowBitset |= 1<<prev;
            }

            this.currentData = data;
            this.currentSum = 0;
        }
        this.currentSum++;


        boolean isSet = (this.rowBitset&(1<<idx))!=0;
        boolean causedByDepthMax = false;
        //Greadily merge with previous row if possible
        if (this.currentData != 0 &&//Ignore sentinel empty
                isSet &&
                this.rowLength[idx] == this.currentSum &&
                this.rowData[idx] == this.currentData) {//Can merge with previous row
            int depth = ++this.rowDepth[idx];
            this.currentSum = 0;//Clear sum since we went down
            this.currentData = 0;//Zero is sentinel value for absent
            if (depth != MAX_SIZE) {
                return;
            }
            causedByDepthMax = true;
        }

        if (isSet) {
            this.emitQuad(idx&31, ((this.currentIndex-1)>>5)-(causedByDepthMax?0:1), this.rowLength[idx], this.rowDepth[idx], this.rowData[idx]);
            this.rowBitset &= ~(1<<idx);
        }
    }

    //Emits quads that exist at the mask pos and clear
    private void emitRanged(int msk) {
        {//Emit quads that cover the previous indices
            int rowSet = this.rowBitset&msk;
            this.rowBitset &= ~msk;
            while (rowSet!=0) {//Need to emit quads that would have skipped, note that this does not include the current index
                int index = Integer.numberOfTrailingZeros(rowSet);
                rowSet &= ~Integer.lowestOneBit(rowSet);

                //Emit the quad, dont need to clear the data since it not existing in the bitmask is implicit no data
                this.emitQuad(index, (this.currentIndex>>5)-1, this.rowLength[index], this.rowDepth[index], this.rowData[index]);
            }
        }
    }

    public final void skip(int count) {
        /*
        if (count == 0) return;
        if (this.currentData != 0) {
            this.putNext0(0); count--;
        }
        if (count != 0) {
            this.emitRanged(((1 << Math.min(count, 31)) - 1) << (this.currentIndex & 31));
        }
        this.currentIndex += count;
         */
        if (count == 0) return;
        if (this.currentData!=0) {
            this.putNext0(0);
            count--;
        }
        if (0<count) {
            int msk = (int) ((1L<<Math.min(32, count))-1) << (this.currentIndex & 31);
            this.emitRanged(msk);
            this.currentIndex += count;
        }

    }

    public final void reset() {
        this.rowBitset = 0;
        this.currentSum = 0;
        this.currentData = 0;
        this.currentIndex = 0;
    }

    public final void endRow() {
        if ((this.currentIndex&31)!=0) {
            this.skip(32-(this.currentIndex&31));
        }
    }

    public final void finish() {
        /*
        if ((this.currentIndex&31)!=0) {
            this.skip(32-(this.currentIndex&31));
        } else {
            this.putNext0(0);
            this.currentIndex--;//HACK to reset currentIndex&31 to 0
        }
        this.currentIndex++;
        for (int i = 0; i < 32; i++) {
            this.putNext0(0);
        }*/
        //TODO: check this is correct
        if (this.currentIndex != 0) {
            this.skip(32 - (this.currentIndex & 31));
            this.emitRanged(-1);
        }

        this.reset();
    }

    protected abstract void emitQuad(int x, int z, int length, int width, long data);


}
