package me.cortex.voxy.client.compat.powergrid;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PowerGridWireStore {
    static final String TABLE = "powergrid_wires";
    private static final byte FORMAT = 2;

    private PowerGridWireStore() {}

    static boolean save(SectionStorage storage, UUID id, PowerGridWireRenderer.Source source) {
        if (storage == null || !storage.supportsAuxTable(TABLE)) return false;
        try {
            var bytes = new ByteArrayOutputStream(128);
            var out = new DataOutputStream(bytes);
            out.writeByte(FORMAT);
            out.writeLong(id.getMostSignificantBits());
            out.writeLong(id.getLeastSignificantBits());
            out.writeDouble(source.x1()); out.writeDouble(source.y1()); out.writeDouble(source.z1());
            out.writeDouble(source.x2()); out.writeDouble(source.y2()); out.writeDouble(source.z2());
            out.writeDouble(source.length());
            out.writeFloat(source.thickness());
            out.writeInt(source.color());
            out.writeByte(source.shape());
            double[] points = source.points();
            out.writeShort(points == null ? 0 : points.length);
            if (points != null) for (double point : points) out.writeDouble(point);
            out.flush();
            storage.putAux(TABLE, key(id), bytes.toByteArray());
            return true;
        } catch (Throwable t) {
            Logger.error("Storing PowerGrid wire LOD " + id, t);
            return false;
        }
    }

    static void remove(SectionStorage storage, UUID id) {
        if (storage != null && storage.supportsAuxTable(TABLE)) storage.deleteAux(TABLE, key(id));
    }

    static List<Stored> loadAll(SectionStorage storage) {
        var result = new ArrayList<Stored>();
        if (storage == null || !storage.supportsAuxTable(TABLE)) return result;
        try {
            storage.forEachAux(TABLE, (key, bytes) -> {
                Stored stored = decode(bytes);
                if (stored != null) result.add(stored);
            });
        } catch (Throwable t) {
            Logger.error("Reading stored PowerGrid wire LODs", t);
            result.clear();
        }
        return result;
    }

    private static Stored decode(byte[] bytes) {
        if (bytes == null || bytes.length < 2 || (bytes[0] != 1 && bytes[0] != FORMAT)) return null;
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes, 1, bytes.length - 1))) {
            var id = new UUID(in.readLong(), in.readLong());
            var source = new PowerGridWireRenderer.Source(
                    in.readDouble(), in.readDouble(), in.readDouble(),
                    in.readDouble(), in.readDouble(), in.readDouble(),
                    in.readDouble(), in.readFloat(), in.readInt(),
                    bytes[0] >= 2 ? in.readUnsignedByte() : PowerGridWireRenderer.SHAPE_CATENARY,
                    bytes[0] >= 2 ? readPoints(in) : null);
            return source.valid() ? new Stored(id, source) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static double[] readPoints(DataInputStream in) throws Exception {
        int count = in.readUnsignedShort();
        if (count == 0) return null;
        if (count < 6 || count > (PowerGridWireRenderer.MAX_POLYLINE_POINTS * 3) || count % 3 != 0) {
            throw new IllegalArgumentException("Invalid PowerGrid wire point count " + count);
        }
        double[] points = new double[count];
        for (int i = 0; i < count; i++) points[i] = in.readDouble();
        return points;
    }

    private static long key(UUID id) {
        return id.getMostSignificantBits() * 31L + id.getLeastSignificantBits();
    }

    record Stored(UUID id, PowerGridWireRenderer.Source source) {}
}
