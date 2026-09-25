package me.cortex.voxy.common.config.storage.rocksdb;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.rocksdb.*;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

public class RocksDBStorageBackend extends StorageBackend {
    private static final String WORLD_SECTIONS_CF = "world_sections";
    private static final String ID_MAPPINGS_CF = "id_mappings";

    private final RocksDB db;
    private final ColumnFamilyHandle worldSections;
    private final ColumnFamilyHandle idMappings;
    //Aux families by cf name, opened from what the store already held and extended on first write
    private final Map<String, ColumnFamilyHandle> auxHandles = new HashMap<>();
    private final ReadOptions sectionReadOps;
    private final WriteOptions sectionWriteOps;

    //NOTE: closes in order
    private final List<AbstractImmutableNativeReference> closeList = new ArrayList<>();
    private volatile boolean closed;

    private void requireOpen() {
        if (this.closed) throw new IllegalStateException("RocksDB storage is closed");
    }

    public RocksDBStorageBackend(String path) {
        RocksDB.loadLibrary();

        final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions()
                .setCompressionType(CompressionType.ZSTD_COMPRESSION)
                .optimizeForSmallDb();

        final ColumnFamilyOptions cfWorldSecOpts = new ColumnFamilyOptions()
                .setCompressionType(CompressionType.NO_COMPRESSION)
                .setCompactionPriority(CompactionPriority.MinOverlappingRatio)
                .setLevelCompactionDynamicLevelBytes(true)
                .optimizeForPointLookup(128);

        var bCache = new HyperClockCache(128*1024L*1024L,0, 4, false);
        var filter = new BloomFilter(10);
        cfWorldSecOpts.setTableFormatConfig(new BlockBasedTableConfig()
                .setCacheIndexAndFilterBlocksWithHighPriority(true)
                .setBlockCache(bCache)
                .setDataBlockHashTableUtilRatio(0.75)
                //.setIndexType(IndexType.kHashSearch)//Maybe?
                .setDataBlockIndexType(DataBlockIndexType.kDataBlockBinaryAndHash)
                .setFilterPolicy(filter)
        );

        final List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
        cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));
        cfDescriptors.add(new ColumnFamilyDescriptor(WORLD_SECTIONS_CF.getBytes(), cfWorldSecOpts));
        cfDescriptors.add(new ColumnFamilyDescriptor(ID_MAPPINGS_CF.getBytes(), cfOpts));
        for (String extra : listExtraColumnFamilies(path)) {
            cfDescriptors.add(new ColumnFamilyDescriptor(extra.getBytes(), cfOpts));
        }

        final DBOptions options = new DBOptions()
                //.setUnorderedWrite(true)
                .setAvoidUnnecessaryBlockingIO(true)
                .setIncreaseParallelism(2)
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setMaxTotalWalSize(1024*1024*128);//128 mb max WAL size

        List<ColumnFamilyHandle> handles = new ArrayList<>();

        try {

            this.db = RocksDB.open(options,
                    path, cfDescriptors,
                    handles);

            this.sectionReadOps = new ReadOptions();
            this.sectionWriteOps = new WriteOptions();
            this.sectionWriteOps.setDisableWAL(true);

            this.closeList.add(options);
            this.closeList.add(cfOpts);
            this.closeList.add(cfWorldSecOpts);
            this.closeList.add(this.sectionReadOps);
            this.closeList.add(this.sectionWriteOps);
            this.closeList.add(filter);
            this.closeList.add(bCache);
            this.closeList.addAll(handles);

            //Handles come back positionally against cfDescriptors, and the two we use are added there
            //before any discovered family, so these indices hold whatever else the store contains.
            this.worldSections = handles.get(1);
            this.idMappings = handles.get(2);

            //Discovered families past the known three, in the order they were appended above. Aux tables
            //the store already carries have to land here or a reopen would create a second family under
            //a name that already exists.
            for (int i = 3; i < cfDescriptors.size(); i++) {
                this.auxHandles.put(new String(cfDescriptors.get(i).getName()), handles.get(i));
            }

            this.db.flushWal(true);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    //Aux tables are column families named "aux_<table>". They are created on open when the store already
    //has them and on demand when it does not, so a world gains one the first time something writes to it
    //rather than on every open.
    private static String auxCfName(String table) {
        return "aux_" + table;
    }

    private ColumnFamilyHandle auxHandle(String table, boolean createIfAbsent) {
        String name = auxCfName(table);
        synchronized (this.auxHandles) {
            ColumnFamilyHandle existing = this.auxHandles.get(name);
            if (existing != null || !createIfAbsent) {
                return existing;
            }
            try {
                var handle = this.db.createColumnFamily(
                        new ColumnFamilyDescriptor(name.getBytes(), new ColumnFamilyOptions()
                                .setCompressionType(CompressionType.ZSTD_COMPRESSION)
                                .optimizeForSmallDb()));
                this.auxHandles.put(name, handle);
                this.closeList.add(handle);
                return handle;
            } catch (RocksDBException e) {
                throw new RuntimeException("Creating aux column family " + name, e);
            }
        }
    }

    @Override
    public boolean supportsAuxTable(String table) {
        return !this.closed;
    }

    @Override
    public synchronized void putAux(String table, long key, byte[] value) {
        this.requireOpen();
        long t = me.cortex.voxy.commonImpl.VoxyProfile.begin();
        try {
            //Aux entries are derived data and regenerate on re-ingest like sections do, but they are far
            //rarer than section writes, so they take the WAL rather than a shutdown-time flush.
            this.db.put(this.auxHandle(table, true), longKey(key), value);
        } catch (RocksDBException e) {
            throw new RuntimeException("Writing aux entry", e);
        } finally {
            me.cortex.voxy.commonImpl.VoxyProfile.end("storage/putAux", t);
        }
    }

    @Override
    public synchronized byte[] getAux(String table, long key) {
        this.requireOpen();
        var handle = this.auxHandle(table, false);
        if (handle == null) {
            return null;
        }
        try {
            return this.db.get(handle, longKey(key));
        } catch (RocksDBException e) {
            throw new RuntimeException("Reading aux entry", e);
        }
    }

    @Override
    public synchronized void deleteAux(String table, long key) {
        this.requireOpen();
        var handle = this.auxHandle(table, false);
        if (handle == null) {
            return;
        }
        try {
            this.db.delete(handle, longKey(key));
        } catch (RocksDBException e) {
            throw new RuntimeException("Deleting aux entry", e);
        }
    }

    @Override
    public synchronized void forEachAux(String table, AuxEntryConsumer consumer) {
        this.requireOpen();
        var handle = this.auxHandle(table, false);
        if (handle == null) {
            return;
        }
        try (var iter = this.db.newIterator(handle)) {
            for (iter.seekToFirst(); iter.isValid(); iter.next()) {
                byte[] key = iter.key();
                if (key.length != 8) {
                    continue;
                }
                consumer.accept(ByteBuffer.wrap(key).getLong(0), iter.value());
            }
        }
    }

    private static byte[] longKey(long key) {
        return ByteBuffer.allocate(8).putLong(0, key).array();
    }

    //Families the store holds beyond the ones this build uses. A store that does not exist yet, or that
    //cannot be read here, yields nothing: open() is then creating it, and createMissingColumnFamilies
    //puts the known set in place.
    private static List<String> listExtraColumnFamilies(String path) {
        if (!new File(path).exists()) {
            return List.of();
        }
        try (var probeOpts = new Options()) {
            List<String> extras = new ArrayList<>();
            for (byte[] name : RocksDB.listColumnFamilies(probeOpts, path)) {
                String cf = new String(name);
                if (cf.equals(new String(RocksDB.DEFAULT_COLUMN_FAMILY))
                        || cf.equals(WORLD_SECTIONS_CF) || cf.equals(ID_MAPPINGS_CF)) {
                    continue;
                }
                extras.add(cf);
            }
            return extras;
        } catch (RocksDBException e) {
            //Not a readable store - let open() produce the real error instead of masking it here
            return List.of();
        }
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
        // This is a one-shot full walk; do not evict useful data by admitting every scanned block.
        try (var stack = MemoryStack.stackPush();
             var scanOps = new ReadOptions().setFillCache(false)) {
            try (var iter = this.db.newIterator(this.worldSections, scanOps)) {
                ByteBuffer keyBuff = stack.calloc(8);
                long keyBuffPtr = MemoryUtil.memAddress(keyBuff);

                if (level != -1) {//-1 means iterate all
                    var seekBuff = stack.calloc(8);
                    MemoryUtil.memPutLong(MemoryUtil.memAddress(seekBuff), Long.reverseBytes(Integer.toUnsignedLong(level) << 60));
                    iter.seek(seekBuff);//we seak to the first level
                } else {
                    iter.seekToFirst();
                }
                while (iter.isValid()) {
                    keyBuff.clear();
                    iter.key(keyBuff);
                    long key = Long.reverseBytes(MemoryUtil.memGetLong(keyBuffPtr));
                    if (level != -1 && WorldEngine.getLevel(key) != level) {
                        break;
                    }
                    consumer.accept(key);
                    iter.next();
                }
            }
        }
    }

    @Override
    public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        try (var stack = MemoryStack.stackPush()){
            var buffer = stack.malloc(8);
            //HATE JAVA HATE JAVA HATE JAVA, Long.reverseBytes()
            //THIS WILL ONLY WORK ON LITTLE ENDIAN SYSTEM AAAAAAAAA ;-;

            MemoryUtil.memPutLong(MemoryUtil.memAddress(buffer), Long.reverseBytes(swizzlePos(key)));

            var result = this.db.get(this.worldSections,
                    this.sectionReadOps,
                    buffer,
                    MemoryUtil.memByteBuffer(scratch.address, (int) (scratch.size)));

            if (result == RocksDB.NOT_FOUND) {
                return null;
            }

            return scratch.subSize(result);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setSectionData(long key, MemoryBuffer data) {
        try (var stack = MemoryStack.stackPush()) {
            var keyBuff = stack.calloc(8);
            MemoryUtil.memPutLong(MemoryUtil.memAddress(keyBuff), Long.reverseBytes(swizzlePos(key)));
            this.db.put(this.worldSections, this.sectionWriteOps, keyBuff, data.asByteBuffer());
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    //One db.write for a group of sections instead of one JNI put + write-group + memtable lock each.
    //Most valuable on the shutdown flush and on imports, where sections arrive in the thousands.
    private final class RocksSectionWriteBatch implements SectionWriteBatch {
        private final WriteBatch batch = new WriteBatch();
        private int count;
        private long bytes;

        @Override
        public void put(long key, MemoryBuffer data) {
            try (var stack = MemoryStack.stackPush()) {
                var keyBuff = stack.calloc(8);
                MemoryUtil.memPutLong(MemoryUtil.memAddress(keyBuff), Long.reverseBytes(swizzlePos(key)));
                //WriteBatch copies the value here, so the caller's scratch buffer is free after this
                this.batch.put(RocksDBStorageBackend.this.worldSections, keyBuff, data.asByteBuffer());
            } catch (RocksDBException e) {
                throw new RuntimeException(e);
            }
            this.count++;
            this.bytes += data.size;
        }

        @Override public long dataSize() { return this.bytes; }

        @Override
        public void commit() {
            if (this.count == 0) {
                return;
            }
            try {
                //MUST be sectionWriteOps: it carries setDisableWAL(true). A fresh WriteOptions here
                //would silently push every section write back through the WAL.
                RocksDBStorageBackend.this.db.write(RocksDBStorageBackend.this.sectionWriteOps, this.batch);
            } catch (RocksDBException e) {
                throw new RuntimeException(e);
            } finally {
                //Drop the contents even on failure so a retry cannot double-write
                this.batch.clear();
                this.count = 0;
                this.bytes = 0;
            }
        }

        @Override
        public void close() {
            this.batch.close();
        }
    }

    @Override
    public SectionWriteBatch createSectionWriteBatch() {
        return new RocksSectionWriteBatch();
    }

    @Override
    public void deleteSectionData(long key) {
        try {
            this.db.delete(this.worldSections, longToBytes(swizzlePos(key)));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        try {
            var buffer = new byte[data.remaining()];
            data.get(buffer);
            data.rewind();
            this.db.put(this.idMappings, intToBytes(id), buffer);
        } catch (
                RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        var out = new Int2ObjectOpenHashMap<byte[]>();
        try (var iterator = this.db.newIterator(this.idMappings)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                out.put(bytesToInt(iterator.key()), iterator.value());
            }
        }
        return out;
    }

    @Override
    public synchronized void flush() {
        this.requireOpen();
        try {
            this.db.flushWal(true);
            try (var flushOpts = new FlushOptions().setWaitForFlush(true)) {
                this.db.flush(flushOpts, this.worldSections);
            }
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.flush();
        // 辅助表访问与关闭共用锁，不能把失效的 native handle 传入 JNI。
        this.closed = true;
        this.closeList.forEach(AbstractImmutableNativeReference::close);
        try {
            this.db.closeE();
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] intToBytes(int i) {
        return new byte[] {(byte)(i>>24), (byte)(i>>16), (byte)(i>>8), (byte) i};
    }
    private static int bytesToInt(byte[] i) {
        return (Byte.toUnsignedInt(i[0])<<24)|(Byte.toUnsignedInt(i[1])<<16)|(Byte.toUnsignedInt(i[2])<<8)|(Byte.toUnsignedInt(i[3]));
    }

    private static byte[] longToBytes(long l) {
        byte[] result = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            result[i] = (byte)(l & 0xFF);
            l >>= Byte.SIZE;
        }
        return result;
    }

    private static long bytesToLong(final byte[] b) {
        long result = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            result <<= Byte.SIZE;
            result |= (b[i] & 0xFF);
        }
        return result;
    }

    public static class Config extends StorageConfig {
        @Override
        public StorageBackend build(ConfigBuildCtx ctx) {
            return new RocksDBStorageBackend(ctx.ensurePathExists(ctx.substituteString(ctx.resolvePath())));
        }

        public static String getConfigTypeName() {
            return "RocksDB";
        }
    }

    private static long swizzlePos(long key) {
        if (true) {
            return key;
        }
        if (WorldEngine.POS_FORMAT_VERSION != 1) {
            throw new IllegalStateException("Unsupported world position format");
        }
        return  (key&(0xFL<<60)) |
                Long.expand((key>>> 4)&((1L<<24)-1), 0b01010101010101010101010101010101_001001001001001001001001L) |
                Long.expand((key>>>52)&0xFF,         0b00000000000000000000000000000000_100100100100100100100100L) |
                Long.expand((key>>>28)&((1L<<24)-1), 0b10101010101010101010101010101010_010010010010010010010010L);
    }
}
