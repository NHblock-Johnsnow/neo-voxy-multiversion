package me.cortex.voxy.common.world;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

/** 标记哪些 16³ 输入已保留原始 LOD；全旧、全新状态不分配位图。 */
public final class SurfaceDataCoverage {
    public static final int MAX_BYTES = 4096;
    private static final VarHandle WORD = MethodHandles.arrayElementVarHandle(long[].class);
    private static final SurfaceDataCoverage[] LEGACY = new SurfaceDataCoverage[5];
    private static final SurfaceDataCoverage[] RAW = new SurfaceDataCoverage[5];

    static {
        for (int level = 0; level <= 4; level++) {
            LEGACY[level] = new SurfaceDataCoverage(level, false, null, 0);
            RAW[level] = new SurfaceDataCoverage(level, true, null, tileCount(level));
        }
    }

    private final int level;
    private final boolean full;
    private final long[] known;
    private volatile int count;

    private SurfaceDataCoverage(int level, boolean full, long[] known, int count) {
        this.level = level;
        this.full = full;
        this.known = known;
        this.count = count;
    }

    private static int checkedLevel(int level) {
        if (level < 0 || level > 4) throw new IllegalArgumentException("Invalid LOD: " + level);
        return level;
    }

    private static int tileCount(int level) {
        return 1 << (3 * (level + 1));
    }

    private static int wordsForLevel(int level) {
        return (tileCount(level) + 63) >>> 6;
    }

    public static SurfaceDataCoverage legacy(int level) {
        return LEGACY[checkedLevel(level)];
    }

    public static SurfaceDataCoverage raw(int level) {
        return RAW[checkedLevel(level)];
    }

    public int level() { return this.level; }
    public int mode() { return this.full ? 1 : this.known == null ? 0 : 2; }
    public int knownInputCount() { return this.count; }

    /** 写方须持有所属 WorldSection 的锁；重复输入复用原状态和数组。 */
    public SurfaceDataCoverage withKnownInput(int x, int y, int z) {
        if (this.full) return this;
        int shift = this.level + 1;
        int mask = (1 << shift) - 1;
        int tile = (x & mask) | ((z & mask) << shift) | ((y & mask) << (shift * 2));
        var result = this.known == null
                ? new SurfaceDataCoverage(this.level, false, new long[wordsForLevel(this.level)], 0)
                : this;
        int index = tile >>> 6;
        long old = (long) WORD.getAcquire(result.known, index);
        long bit = 1L << (tile & 63);
        if ((old & bit) != 0) return result;
        WORD.setRelease(result.known, index, old | bit);
        if (++result.count == tileCount(this.level)) return raw(this.level);
        return result;
    }

    public boolean isKnownVoxel(int index) {
        if (index < 0 || index >= 32768) throw new IndexOutOfBoundsException(index);
        if (this.known == null) return this.full;
        int shrink = 4 - this.level;
        int shift = this.level + 1;
        int x = (index & 31) >>> shrink;
        int z = ((index >>> 5) & 31) >>> shrink;
        int y = (index >>> 10) >>> shrink;
        int tile = x | (z << shift) | (y << (shift * 2));
        return ((long) WORD.getAcquire(this.known, tile >>> 6) & (1L << (tile & 63))) != 0;
    }

    public int serializedWordCount() {
        return this.known == null ? 0 : this.known.length;
    }

    public long word(int index) {
        if (this.known == null) throw new IllegalStateException("Coverage has no bitmap");
        return (long) WORD.getAcquire(this.known, index);
    }

    public static SurfaceDataCoverage fromWords(int level, long[] words) {
        return fromOwnedWords(level, Arrays.copyOf(words, words.length));
    }

    // 反序列化器交出新数组的所有权，避免载入时再复制一次位图。
    static SurfaceDataCoverage fromOwnedWords(int level, long[] words) {
        checkedLevel(level);
        if (words.length != wordsForLevel(level)) throw new IllegalArgumentException("Invalid coverage length");
        int tail = tileCount(level) & 63;
        if (tail != 0 && (words[words.length - 1] >>> tail) != 0) {
            throw new IllegalArgumentException("Invalid coverage padding");
        }
        int count = 0;
        for (long word : words) count += Long.bitCount(word);
        if (count == 0) return legacy(level);
        if (count == tileCount(level)) return raw(level);
        return new SurfaceDataCoverage(level, false, words, count);
    }
}
