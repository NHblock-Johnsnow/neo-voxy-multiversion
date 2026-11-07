package me.cortex.voxy.commonImpl.compat;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import net.minecraft.core.BlockPos;

import java.nio.ByteBuffer;

public final class DisguiseStore {
    private static final byte FORMAT = 1;
    //A section cannot hold more than this many blocks, so a larger count is corrupt
    private static final int MAX_ENTRIES = 4096;
    private static final int HEADER = 3;
    private static final int ENTRY = 6;

    private DisguiseStore() {}

    public static long keyOf(int sx, int sy, int sz) {
        return BlockPos.asLong(sx, sy, sz);
    }

    //packed: pairs of (local index within the section, variant id), length must be even
    public static void save(SectionStorage storage, String table, int sx, int sy, int sz, int[] packed, int count) {
        if (storage == null || !storage.supportsAuxTable(table)) {
            return;
        }
        if (count <= 0) {
            return;
        }
        //byte 0 format, bytes 1-2 count, then count * (u16 local index, i32 variant id)
        try {
            var buff = ByteBuffer.allocate(HEADER + count * ENTRY);
            buff.put(FORMAT);
            buff.putShort((short) count);
            for (int i = 0; i < count; i++) {
                buff.putShort((short) packed[i * 2]);
                buff.putInt(packed[i * 2 + 1]);
            }
            storage.putAux(table, keyOf(sx, sy, sz), buff.array());
        } catch (Throwable t) {
            Logger.error("Storing disguise materials for section " + sx + "," + sy + "," + sz, t);
        }
    }

    public static void clear(SectionStorage storage, String table, int sx, int sy, int sz) {
        if (storage != null && storage.supportsAuxTable(table)) {
            storage.deleteAux(table, keyOf(sx, sy, sz));
        }
    }

    public static boolean clearIfPresent(SectionStorage storage, String table, int sx, int sy, int sz) {
        if (storage == null || !storage.supportsAuxTable(table)) return false;
        long key = keyOf(sx, sy, sz);
        if (storage.getAux(table, key) == null) return false;
        storage.deleteAux(table, key);
        return true;
    }

    public interface EntryConsumer {
        void accept(int localIndex, int variantId);
    }

    public static int decode(byte[] value, EntryConsumer out) {
        if (value == null || value.length < HEADER || value[0] != FORMAT) {
            return 0;
        }
        var buff = ByteBuffer.wrap(value);
        buff.position(1);
        int count = buff.getShort() & 0xFFFF;
        if (count > MAX_ENTRIES || value.length < HEADER + count * ENTRY) {
            return 0;
        }
        int applied = 0;
        for (int i = 0; i < count; i++) {
            int index = buff.getShort() & 0xFFFF;
            int id = buff.getInt();
            if (index < MAX_ENTRIES && id != 0) {
                out.accept(index, id);
                applied++;
            }
        }
        return applied;
    }

    //Feeds back what was stored for this section. Returns how many entries were applied.
    public static int load(SectionStorage storage, String table, int sx, int sy, int sz, EntryConsumer out) {
        if (storage == null || !storage.supportsAuxTable(table)) {
            return 0;
        }
        return decode(storage.getAux(table, keyOf(sx, sy, sz)), out);
    }
}
