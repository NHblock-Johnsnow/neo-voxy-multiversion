package me.cortex.voxy.common.world.other;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.Logger;
import net.minecraft.core.BlockPos;

import java.nio.ByteBuffer;

public final class BeaconIndex {
    public static final String TABLE = "beacons";
    private static final byte FORMAT = 2;

    private final SectionStorage storage;
    private final boolean persistent;
    //Section key -> packed local positions. Written from ingest workers, read from the render thread.
    private final Long2ObjectMap<short[]> sections = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
    private final Object[] updateLocks = new Object[16];

    public BeaconIndex(SectionStorage storage) {
        java.util.Arrays.setAll(this.updateLocks, i -> new Object());
        this.storage = storage;
        this.persistent = storage.supportsAuxTable(TABLE);
        if (this.persistent) {
            this.load();
        }
    }

    private void load() {
        int[] count = new int[1];
        try {
            this.storage.forEachAux(TABLE, (key, value) -> {
                short[] locals = decode(value);
                if (locals != null && locals.length != 0) {
                    this.sections.put(key, locals);
                    count[0] += locals.length;
                }
            });
        } catch (Throwable t) {
            Logger.error("Reading the beacon index; continuing without it", t);
            this.sections.clear();
            return;
        }
        if (count[0] != 0) {
            Logger.info("Loaded " + count[0] + " beacon(s) across " + this.sections.size() + " section(s)");
        }
    }

    //Membership changes, delivered as world positions on the ingest worker that made them. The
    //listener must not call back into this index and must not block - it runs inside the scan path.
    public interface ChangeListener {
        void onBeaconAdded(int x, int y, int z);

        void onBeaconRemoved(int x, int y, int z);
    }

    private volatile ChangeListener listener;

    public void setListener(ChangeListener listener) {
        this.listener = listener;
    }

    /** 初始化快照和安装监听器之间不允许区段发布；锁顺序始终为区段锁在前。 */
    public void initializeListener(ChangeListener listener, Runnable initializeSnapshot) {
        this.initializeListener(0, listener, initializeSnapshot);
    }

    private void initializeListener(int stripe, ChangeListener listener, Runnable initializeSnapshot) {
        if (stripe == this.updateLocks.length) {
            initializeSnapshot.run();
            this.listener = listener;
            return;
        }
        synchronized (this.updateLocks[stripe]) {
            this.initializeListener(stripe + 1, listener, initializeSnapshot);
        }
    }

    public void setSection(int sx, int sy, int sz, short[] packedLocals) {
        long key = BlockPos.asLong(sx, sy, sz);
        Object updateLock = this.updateLocks[Long.hashCode(key ^ (key >>> 17)) & (this.updateLocks.length - 1)];
        synchronized (updateLock) {
            short[] old = this.sections.get(key);
            short[] next = packedLocals == null || packedLocals.length == 0 ? null : packedLocals;
            if (java.util.Arrays.equals(old, next)) return;
            // 同一区段的写盘、发布和通知保持顺序；失败时保留旧值供下次重试。
            if (next == null) {
                if (this.persistent) {
                    this.storage.deleteAux(TABLE, key);
                }
                this.sections.remove(key);
                this.fireDiff(sx, sy, sz, old, null);
            } else {
                next = next.clone();
                if (this.persistent) {
                    this.storage.putAux(TABLE, key, encode(next));
                }
                this.sections.put(key, next);
                this.fireDiff(sx, sy, sz, old, next);
            }
        }
    }

    private void fireDiff(int sx, int sy, int sz, short[] old, short[] now) {
        ChangeListener listener = this.listener;
        if (listener == null) {
            return;
        }
        int ox = sx << 4, oy = sy << 4, oz = sz << 4;
        if (old != null) {
            for (short packed : old) {
                if (now == null || !contains(now, packed)) {
                    listener.onBeaconRemoved(ox + ((packed >> 8) & 0xF), oy + ((packed >> 4) & 0xF), oz + (packed & 0xF));
                }
            }
        }
        if (now != null) {
            for (short packed : now) {
                if (old == null || !contains(old, packed)) {
                    listener.onBeaconAdded(ox + ((packed >> 8) & 0xF), oy + ((packed >> 4) & 0xF), oz + (packed & 0xF));
                }
            }
        }
    }

    private static boolean contains(short[] locals, short packed) {
        for (short local : locals) {
            if (local == packed) {
                return true;
            }
        }
        return false;
    }

    public void forEach(BeaconConsumer consumer) {
        long[] keys;
        short[][] values;
        synchronized (this.sections) {
            int n = this.sections.size();
            keys = new long[n];
            values = new short[n][];
            int i = 0;
            for (var entry : this.sections.long2ObjectEntrySet()) {
                keys[i] = entry.getLongKey();
                values[i] = entry.getValue();
                i++;
            }
        }
        for (int i = 0; i < keys.length; i++) {
            long key = keys[i];
            int ox = BlockPos.getX(key) << 4;
            int oy = BlockPos.getY(key) << 4;
            int oz = BlockPos.getZ(key) << 4;
            for (short packed : values[i]) {
                consumer.accept(ox + ((packed >> 8) & 0xF), oy + ((packed >> 4) & 0xF), oz + (packed & 0xF));
            }
        }
    }

    public int count() {
        int total = 0;
        synchronized (this.sections) {
            for (var locals : this.sections.values()) {
                total += locals.length;
            }
        }
        return total;
    }

    public boolean isPersistent() {
        return this.persistent;
    }

    public static short packLocal(int x, int y, int z) {
        return (short) ((x << 8) | (y << 4) | z);
    }

    private static byte[] encode(short[] locals) {
        if (locals.length > 4096) throw new IllegalArgumentException("Too many beacons in a section");
        var buff = ByteBuffer.allocate(3 + locals.length * 2);
        buff.put(FORMAT);
        buff.putShort((short) locals.length);
        for (short local : locals) {
            buff.putShort(local);
        }
        return buff.array();
    }

    private static short[] decode(byte[] value) {
        if (value == null || value.length < 2) {
            return null;
        }
        int offset;
        int count;
        if (value[0] == 1) {
            offset = 2;
            // 旧格式数量被截到 255，但完整 payload 仍在，按长度恢复。
            count = (value.length - offset) / 2;
            if ((value[1] & 0xFF) != Math.min(count, 255)) return null;
        } else if (value[0] == FORMAT && value.length >= 3) {
            offset = 3;
            count = Short.toUnsignedInt(ByteBuffer.wrap(value).getShort(1));
        } else {
            return null;
        }
        if (count > 4096 || value.length != offset + count * 2) return null;
        var buff = ByteBuffer.wrap(value, offset, count * 2);
        short[] locals = new short[count];
        for (int i = 0; i < count; i++) {
            locals[i] = buff.getShort();
        }
        return locals;
    }

    public interface BeaconConsumer {
        void accept(int x, int y, int z);
    }
}
