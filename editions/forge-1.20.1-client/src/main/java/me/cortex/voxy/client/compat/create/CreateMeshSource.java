package me.cortex.voxy.client.compat.create;

import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import me.cortex.voxy.client.compat.distant.DistantMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.RenderShape;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

// 保存重建所需的模型输入；内部 NBT 发布后只读，不保留世界或实体引用。
final class CreateMeshSource {
    private final CompoundTag data;
    private final long retainedBytes;

    private CreateMeshSource(CompoundTag data) {
        this(data, 0);
    }

    private CreateMeshSource(CompoundTag data, long retainedBytes) {
        this.data = data;
        this.retainedBytes = retainedBytes;
    }

    long retainedBytes() {
        return this.retainedBytes;
    }

    static CreateMeshSource contraption(Contraption contraption) {
        var data = new CompoundTag();
        data.putString("Kind", "contraption");
        data.put("Contraption", contraption.writeNBT(true).copy());
        return new CreateMeshSource(data);
    }

    static CreateMeshSource blocks(ListTag blocks) {
        var data = new CompoundTag();
        data.putString("Kind", "blocks");
        data.put("Blocks", blocks.copy());
        return new CreateMeshSource(data);
    }

    void write(DataOutput out) throws IOException {
        NbtIo.write(this.data, out);
    }

    static CreateMeshSource read(DataInput in) throws IOException {
        var accounter = new NbtAccounter(64L * 1024 * 1024);
        var data = NbtIo.read(in, accounter);
        if (data == null || !(data.getString("Kind").equals("contraption")
                && data.contains("Contraption", Tag.TAG_COMPOUND)
                || data.getString("Kind").equals("blocks") && data.contains("Blocks", Tag.TAG_LIST))) {
            throw new IOException("Invalid Create mesh source");
        }
        return new CreateMeshSource(data, accounter.getUsage());
    }

    DistantMesh.Built bake(ClientLevel level) {
        if (this.data.getString("Kind").equals("contraption")) {
            var saved = this.data.getCompound("Contraption").copy();
            var contraption = ContraptionType.fromType(saved.getString("Type"));
            contraption.readNBT(level, saved, true);
            // ClientContraption 需要实体的世界；不入世界、不 tick，也不启动碰撞体任务。
            var entity = OrientedContraptionEntity.create(level, contraption, Direction.SOUTH);
            contraption.entity = entity;
            return DistantCreateRenderer.bake(entity);
        }
        var builder = new DistantMesh.Builder();
        try {
            var shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();
            for (var element : this.data.getList("Blocks", Tag.TAG_COMPOUND)) {
                var block = (CompoundTag) element;
                var state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), block.getCompound("State"));
                if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;
                builder.blockModelLit(state, shaper.getBlockModel(state), BlockPos.ZERO,
                        block.getInt("X"), block.getInt("Y"), block.getInt("Z"), 0, 240);
            }
            return builder.buildPersistent();
        } finally {
            builder.discard();
        }
    }
}
