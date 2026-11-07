package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.other.Mapper;
import org.lwjgl.system.MemoryUtil;

public class SaveLoadSystem3 {
    public static final int STORAGE_VERSION = 0;

    private record SerializationCache(Long2ShortOpenHashMap lutMapCache, MemoryBuffer memoryBuffer) {
        public SerializationCache() {
            this(new Long2ShortOpenHashMap(1024), ThreadLocalMemoryBuffer.create(WorldSection.SECTION_VOLUME*2+WorldSection.SECTION_VOLUME*8+1024+SurfaceDataCoverage.MAX_BYTES));
            this.lutMapCache.defaultReturnValue((short) -1);
        }
    }
    public static int lin2z(int i) {//y,z,x
        int x = i&0x1F;
        int y = (i>>10)&0x1F;
        int z = (i>>5)&0x1F;
        return Integer.expand(x,0b1001001001001)|Integer.expand(y,0b10010010010010)|Integer.expand(z,0b100100100100100);

        //zyxzyxzyxzyxzyx
    }

    public static int z2lin(int i) {
        int x = Integer.compress(i, 0b1001001001001);
        int y = Integer.compress(i, 0b10010010010010);
        int z = Integer.compress(i, 0b100100100100100);
        return x|(y<<10)|(z<<5);
    }

    private static final ThreadLocal<SerializationCache> CACHE = ThreadLocal.withInitial(SerializationCache::new);

    public static MemoryBuffer serialize(WorldSection section) {
        synchronized (section) {
            return serializeLocked(section);
        }
    }

    private static MemoryBuffer serializeLocked(WorldSection section) {
        var cache = CACHE.get();
        var data = section._rawOrNull();
        var coverage = section.getSurfaceData();

        Long2ShortOpenHashMap LUT = cache.lutMapCache; LUT.clear();

        MemoryBuffer buffer = cache.memoryBuffer().createUntrackedUnfreeableReference();
        long ptr = buffer.address;

        MemoryUtil.memPutLong(ptr, section.key); ptr += 8;
        long metadataPtr = ptr; ptr += 8;

        if (data == null) {
            //Uniform: a single LUT entry and an all-zero index region. Byte-for-byte the same output the
            //dense path would produce, so this is not a format change - it just skips materialising.
            long value = section.getUniformValue();
            long blockIdxPtr = ptr; ptr += WorldSection.SECTION_VOLUME*2;
            MemoryUtil.memSet(blockIdxPtr, 0, WorldSection.SECTION_VOLUME*2);
            MemoryUtil.memPutLong(ptr, value); ptr += 8;

            long uniformMetadata = 0;
            uniformMetadata |= 1L;//LUT size
            uniformMetadata |= Byte.toUnsignedLong(section.getNonEmptyChildren())<<16;
            uniformMetadata |= SurfaceDataCodec.metadata(coverage);
            MemoryUtil.memPutLong(metadataPtr, uniformMetadata);
            ptr = SurfaceDataCodec.write(ptr, coverage);
            return buffer.subSize(ptr - buffer.address);
        }

        long blockPtr = ptr; ptr += WorldSection.SECTION_VOLUME*2;
        long prev = data[0]; MemoryUtil.memPutLong(ptr, prev); ptr+=8; LUT.put(prev, (short) 0);
        short mapping = 0;
        for (long block : data) {
            if (prev != block) {
                prev = block;
                mapping = LUT.putIfAbsent(block, (short) LUT.size());
                if (mapping == -1) {
                    mapping = (short) (LUT.size()-1);
                    MemoryUtil.memPutLong(ptr, block); ptr+=8;
                }
            }
            MemoryUtil.memPutShort(blockPtr, mapping); blockPtr+=2;
        }
        if (LUT.size() >= 1<<16) {
            throw new IllegalStateException();
        }

        long metadata = 0;
        metadata |= Integer.toUnsignedLong(LUT.size());//Bottom 2 bytes
        metadata |= Byte.toUnsignedLong(section.getNonEmptyChildren())<<16;//Next byte
        // 下一字节保存表层数据格式。

        metadata |= SurfaceDataCodec.metadata(coverage);
        MemoryUtil.memPutLong(metadataPtr, metadata);

        ptr = SurfaceDataCodec.write(ptr, coverage);

        return buffer.subSize(ptr - buffer.address);//Does not get freed
    }

    public static boolean deserialize(WorldSection section, MemoryBuffer data) {
        if (data.size < 16) return false;
        long ptr = data.address;
        long key = MemoryUtil.memGetLong(ptr); ptr += 8;

        if (section.key != key) {
            Logger.error("Decompressed section not the same as requested. got: " + key + " expected: " + section.key);
            return false;
        }

        final long metadata = MemoryUtil.memGetLong(ptr); ptr += 8;
        SurfaceDataCodec.tag(metadata);
        if (data.size < 16L + WorldSection.SECTION_VOLUME * 2L + 8L) return false;
        final int lutSize = (int) (metadata & 0xFFFF);
        if (lutSize < 1 || lutSize > WorldSection.SECTION_VOLUME) return false;
        long lutEnd = data.address + 16L + WorldSection.SECTION_VOLUME * 2L + lutSize * 8L;
        SurfaceDataCoverage coverage;
        try {
            coverage = SurfaceDataCodec.read(metadata, section.lvl, lutEnd, data.address + data.size);
        } catch (IllegalArgumentException invalid) {
            Logger.error("Invalid surface data in section " + section.key + ": " + invalid.getMessage());
            return false;
        }
        section.nonEmptyChildren = (byte) ((metadata>>>16)&0xFF);
        final long lutBasePtr = ptr + WorldSection.SECTION_VOLUME * 2;

        if (lutSize == 1) {
            long value = MemoryUtil.memGetLong(lutBasePtr);
            section.setUniform(value);
            if (section.lvl == 0) {
                section.nonEmptyBlockCount = Mapper.isAir(value) ? 0 : WorldSection.SECTION_VOLUME;
            }
            me.cortex.voxy.commonImpl.PerfStats.sectionUniformKept.increment();
            section.legacySurfaceSource = section.lvl == 0 && coverage.mode() == 0;
            section.surfaceData = coverage;
            return true;
        }

        final var blockData = section.materialize();
        for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
            int index = Short.toUnsignedInt(MemoryUtil.memGetShort(ptr));
            if (index >= lutSize) return false;
            blockData[i] = MemoryUtil.memGetLong(lutBasePtr + index * 8L); ptr += 2;
        }

        if (section.lvl == 0) {
            int nonEmptyBlockCount = 0;
            for (long block : blockData) {
                nonEmptyBlockCount += Mapper.isNotAirInt(block);
            }
            section.nonEmptyBlockCount = nonEmptyBlockCount;
        }

        ptr = lutBasePtr + (metadata & 0xFFFF) * 8L;
        section.legacySurfaceSource = section.lvl == 0 && coverage.mode() == 0;
        section.surfaceData = coverage;
        return true;
    }
}
