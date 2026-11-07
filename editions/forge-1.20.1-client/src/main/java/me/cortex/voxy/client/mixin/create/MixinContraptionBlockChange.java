package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import me.cortex.voxy.commonImpl.compat.create.CreateShapeRevision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractContraptionEntity.class, remap = false)
public abstract class MixinContraptionBlockChange {
    @Shadow public abstract Contraption getContraption();

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo;)V",
            at = @At("RETURN"))
    private void voxy$serverBlockChanged(BlockPos pos, StructureBlockInfo block, CallbackInfo ci) {
        this.voxy$invalidateShape();
    }

    @Inject(method = "handleBlockChange(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("RETURN"))
    private void voxy$clientBlockChanged(BlockPos pos, BlockState state, CallbackInfo ci) {
        this.voxy$invalidateShape();
    }

    @Unique
    private void voxy$invalidateShape() {
        if (this.getContraption() instanceof CreateShapeRevision source) source.voxy$invalidateShape();
    }
}
