package me.cortex.voxy.commonImpl.mixin.create;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import me.cortex.voxy.commonImpl.compat.create.CarriageShapeRevision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CarriageContraptionEntity.class, remap = false)
public abstract class MixinCarriageBlockChange {
    @Shadow public abstract Carriage getCarriage();

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo;)V",
            at = @At("RETURN"))
    private void voxy$blockChanged(BlockPos pos, StructureBlockInfo block, CallbackInfo ci) {
        if (this.getCarriage() instanceof CarriageShapeRevision source) {
            source.voxy$invalidateShape();
        }
    }
}
