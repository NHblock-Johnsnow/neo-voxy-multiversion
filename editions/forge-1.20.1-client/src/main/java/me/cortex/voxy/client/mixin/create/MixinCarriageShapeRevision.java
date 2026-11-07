package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import me.cortex.voxy.commonImpl.compat.create.CreateShapeRevision;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Carriage.class, remap = false)
public abstract class MixinCarriageShapeRevision implements CreateShapeRevision {
    @Unique private volatile long voxy$shapeRevision;

    @Override
    public long voxy$shapeRevision() {
        return this.voxy$shapeRevision;
    }

    @Override
    public void voxy$invalidateShape() {
        this.voxy$shapeRevision++;
    }

    @Inject(method = "setContraption(Lnet/minecraft/world/level/Level;Lcom/simibubi/create/content/trains/entity/CarriageContraption;)V",
            at = @At("RETURN"))
    private void voxy$shapeChanged(Level level, CarriageContraption contraption, CallbackInfo ci) {
        this.voxy$invalidateShape();
    }
}
