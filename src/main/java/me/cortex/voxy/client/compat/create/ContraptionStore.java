package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBlock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public final class ContraptionStore {
    public static final String TABLE = "create_contraptions";
    // 格式 3 保留渲染 NBT，格式 4 支持超过 u16 容量的材质表。
    private static final byte FORMAT = 4;
    //A contraption is bounded by the +-127 local coordinate packing, so its block count cannot approach
    //this; the cap only stops a corrupt length from allocating wildly
    private static final int MAX_BLOCKS = 1 << 20;

    private ContraptionStore() {}

    public record Stored(UUID id, DistantContraptionManager.Source source,
                         Matrix4f pose, double x, double y, double z, ResourceLocation dim,
                         double trackingBlocks, double boundRadius, long estimatedBytes) {}

    //UUIDs do not fit a long key, so the two halves are mixed. A collision would show one contraption in
    //place of another, which is why the record carries its own id and the loader checks it.
    private static long keyOf(UUID id) {
        return id.getMostSignificantBits() * 31L + id.getLeastSignificantBits();
    }

    public static void save(SectionStorage storage, UUID id, DistantContraptionManager.Snapshot snap) {
        if (!storage.supportsAuxTable(TABLE) || snap.source() == null || snap.dim() == null) {
            return;
        }
        try {
            storage.putAux(TABLE, keyOf(id), encode(id, snap));
        } catch (Throwable t) {
            Logger.error("Storing contraption snapshot " + id, t);
        }
    }

    public static void remove(SectionStorage storage, UUID id) {
        if (storage.supportsAuxTable(TABLE)) {
            storage.deleteAux(TABLE, keyOf(id));
        }
    }

    public static List<Stored> loadAll(SectionStorage storage) {
        var out = new ArrayList<Stored>();
        if (!storage.supportsAuxTable(TABLE)) {
            return out;
        }
        try {
            storage.forEachAux(TABLE, (key, value) -> {
                var stored = decode(value);
                if (stored != null) {
                    out.add(stored);
                }
            });
        } catch (Throwable t) {
            Logger.error("Reading stored contraption snapshots; continuing without them", t);
            out.clear();
        }
        return out;
    }

    private static byte[] encode(UUID id, DistantContraptionManager.Snapshot snap) throws Exception {
        var blocks = snap.source().blocks();
        //One entry per distinct state; a contraption of five hundred blocks is usually a few dozen
        var paletteIndex = new HashMap<BlockState, Integer>();
        var palette = new ArrayList<BlockState>();
        for (var block : blocks) {
            paletteIndex.computeIfAbsent(block.state(), s -> {
                palette.add(s);
                return palette.size() - 1;
            });
        }

        boolean intIndex = palette.size() > 65536;
        var bytes = new ByteArrayOutputStream(blocks.size() * (intIndex ? 7 : 5) + 512);
        var out = new DataOutputStream(bytes);
        out.writeByte(intIndex ? FORMAT : 3);
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
        out.writeDouble(snap.x());
        out.writeDouble(snap.y());
        out.writeDouble(snap.z());
        out.writeDouble(snap.trackingBlocks());
        out.writeUTF(snap.dim().toString());
        float[] pose = new float[16];
        snap.local().get(pose);
        for (float f : pose) {
            out.writeFloat(f);
        }

        var root = new CompoundTag();
        var paletteTag = new ListTag();
        for (var state : palette) {
            paletteTag.add(BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state)
                    .getOrThrow(e -> new IllegalStateException("Encoding block state: " + e)));
        }
        root.put("palette", paletteTag);
        var renderNbt = snap.source().renderNbt();
        if (renderNbt != null && !renderNbt.isEmpty()) {
            var renderData = new ListTag();
            for (var entry : renderNbt.entrySet()) {
                var item = new CompoundTag();
                item.putByte("x", (byte) entry.getKey().getX());
                item.putByte("y", (byte) entry.getKey().getY());
                item.putByte("z", (byte) entry.getKey().getZ());
                item.put("data", entry.getValue().copy());
                renderData.add(item);
            }
            root.put("render_data", renderData);
        }
        var paletteBytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, paletteBytes);
        out.writeInt(paletteBytes.size());
        out.write(paletteBytes.toByteArray());

        out.writeInt(blocks.size());
        boolean wide = palette.size() > 255;
        for (var block : blocks) {
            out.writeByte(block.x());
            out.writeByte(block.y());
            out.writeByte(block.z());
            int idx = paletteIndex.get(block.state());
            if (intIndex) {
                out.writeInt(idx);
            } else if (wide) {
                out.writeShort(idx);
            } else {
                out.writeByte(idx);
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    private static Stored decode(byte[] value) {
        return decode(value, null);
    }

    static Stored decode(byte[] value, ResourceLocation dimension) {
        if (value == null || value.length < 2 || value[0] < 1 || value[0] > FORMAT) {
            return null;
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(value, 1, value.length - 1))) {
            var id = new UUID(in.readLong(), in.readLong());
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            double trackingBlocks = value[0] >= 2 ? in.readDouble() : 80.0;
            var dim = ResourceLocation.parse(in.readUTF());
            if (dimension != null && (!dimension.equals(dim) || Math.abs(x) > 1.0e6 || Math.abs(z) > 1.0e6)) {
                return null;
            }
            var pose = new Matrix4f();
            float[] raw = new float[16];
            for (int i = 0; i < 16; i++) {
                raw[i] = in.readFloat();
            }
            pose.set(raw);

            int paletteBytes = in.readInt();
            if (paletteBytes < 0 || paletteBytes > value.length) {
                return null;
            }
            byte[] paletteRaw = new byte[paletteBytes];
            in.readFully(paletteRaw);
            var accounter = NbtAccounter.unlimitedHeap();
            var root = NbtIo.readCompressed(new ByteArrayInputStream(paletteRaw), accounter);
            var paletteTag = root.getList("palette", 10);
            var palette = new ArrayList<BlockState>(paletteTag.size());
            for (int i = 0; i < paletteTag.size(); i++) {
                //A block whose mod is gone decodes to nothing; it drops out of the shape rather than
                //taking the whole snapshot with it
                palette.add(BlockState.CODEC.parse(NbtOps.INSTANCE, paletteTag.get(i)).result().orElse(null));
            }

            int count = in.readInt();
            if (count < 0 || count > MAX_BLOCKS) {
                return null;
            }
            boolean wide = palette.size() > 255;
            var blocks = new ArrayList<ShapeBlock>(count);
            var states = new HashMap<net.minecraft.core.BlockPos, BlockState>(count * 2);
            int furthestSq = 0;
            for (int i = 0; i < count; i++) {
                byte bx = in.readByte(), by = in.readByte(), bz = in.readByte();
                int idx = value[0] >= 4 ? in.readInt() : wide ? in.readUnsignedShort() : in.readUnsignedByte();
                if (idx < 0 || idx >= palette.size()) {
                    return null;
                }
                var state = palette.get(idx);
                if (state != null) {
                    blocks.add(new ShapeBlock(bx, by, bz, state));
                    states.put(new net.minecraft.core.BlockPos(bx, by, bz), state);
                    int ax = Math.abs(bx) + 1, ay = Math.abs(by) + 1, az = Math.abs(bz) + 1;
                    furthestSq = Math.max(furthestSq, ax * ax + ay * ay + az * az);
                }
            }
            if (blocks.isEmpty()) {
                return null;
            }
            HashMap<net.minecraft.core.BlockPos, CompoundTag> renderNbt = null;
            HashMap<net.minecraft.core.BlockPos, net.neoforged.neoforge.client.model.data.ModelData> modelData = null;
            if (value[0] >= 3 && root.contains("render_data", Tag.TAG_LIST)) {
                var renderData = root.getList("render_data", Tag.TAG_COMPOUND);
                if (renderData.size() > count) return null;
                renderNbt = new HashMap<>(renderData.size() * 2);
                modelData = new HashMap<>(renderData.size() * 2);
                for (int i = 0; i < renderData.size(); i++) {
                    var item = renderData.getCompound(i);
                    var pos = new net.minecraft.core.BlockPos(item.getByte("x"), item.getByte("y"), item.getByte("z"));
                    var state = states.get(pos);
                    if (state == null || !item.contains("data", Tag.TAG_COMPOUND)) continue;
                    var data = item.getCompound("data").copy();
                    renderNbt.put(pos, data);
                    var resolved = me.cortex.voxy.commonImpl.compat.CreateCopycatCompat
                            .materialFromContraptionNbt(state, data);
                    if (resolved != null) modelData.put(pos, resolved);
                }
                if (renderNbt.isEmpty()) renderNbt = null;
                if (modelData.isEmpty()) modelData = null;
            }
            return new Stored(id, new DistantContraptionManager.Source(blocks, modelData, renderNbt), pose, x, y, z, dim,
                    trackingBlocks, Math.sqrt(furthestSq), 256L + blocks.size() * 128L + accounter.getUsage() * 2L);
        } catch (Throwable t) {
            Logger.error("Decoding a stored contraption snapshot; dropping it", t);
            return null;
        }
    }
}
