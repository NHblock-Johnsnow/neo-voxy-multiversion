package me.cortex.voxy.common.world;

import org.lwjgl.system.MemoryUtil;

/** 使用元数据的空闲字节和可选尾部位图，保留旧 LUT 与 26 版的 child-format 标记。 */
final class SurfaceDataCodec {
    private static final int RAW = 0xA1;
    private static final int PARTIAL = 0xA2;

    private SurfaceDataCodec() { }

    static long metadata(SurfaceDataCoverage coverage) {
        int mode = coverage.mode();
        return (mode == 0 ? 0L : mode == 1 ? RAW : PARTIAL) << 24;
    }

    static long write(long pointer, SurfaceDataCoverage coverage) {
        for (int i = 0; i < coverage.serializedWordCount(); i++, pointer += 8) {
            MemoryUtil.memPutLong(pointer, coverage.word(i));
        }
        return pointer;
    }

    static int tag(long metadata) {
        int tag = (int) (metadata >>> 24) & 255;
        if (tag != 0 && tag != RAW && tag != PARTIAL) {
            // 未知格式须让 loader 报错，不能落入“损坏记录”分支删除用户缓存。
            throw new UnsupportedOperationException("Unsupported surface data format: " + tag);
        }
        return tag;
    }

    static SurfaceDataCoverage read(long metadata, int level, long pointer, long end) {
        int tag = tag(metadata);
        int words = tag == PARTIAL ? ((1 << (3 * (level + 1))) + 63) >>> 6 : 0;
        if (end - pointer != words * 8L) throw new IllegalArgumentException("Invalid surface data size");
        if (tag == 0) return SurfaceDataCoverage.legacy(level);
        if (tag == RAW) return SurfaceDataCoverage.raw(level);
        long[] bitmap = new long[words];
        for (int i = 0; i < words; i++) bitmap[i] = MemoryUtil.memGetLong(pointer + i * 8L);
        return SurfaceDataCoverage.fromOwnedWords(level, bitmap);
    }
}
