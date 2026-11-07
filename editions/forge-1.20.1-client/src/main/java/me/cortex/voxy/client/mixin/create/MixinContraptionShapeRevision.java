package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.contraptions.Contraption;
import me.cortex.voxy.commonImpl.compat.create.CreateShapeRevision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Contraption.class, remap = false)
public abstract class MixinContraptionShapeRevision implements CreateShapeRevision {
    @Unique private volatile long voxy$shapeRevision;

    @Override
    public long voxy$shapeRevision() {
        return this.voxy$shapeRevision;
    }

    @Override
    public void voxy$invalidateShape() {
        this.voxy$shapeRevision++;
    }

    @Inject(method = {"resetClientContraption()V", "invalidateClientContraptionStructure()V"},
            at = @At("RETURN"))
    private void voxy$structureChanged(CallbackInfo ci) {
        // 模型源变化时推进版本，稳定 tick 不再遍历整个车体。
        this.voxy$invalidateShape();
    }
}
