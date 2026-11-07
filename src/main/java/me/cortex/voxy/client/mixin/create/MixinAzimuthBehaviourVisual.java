package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.cake.azimuth.behaviour.extensions.RenderedBehaviourExtension$BehaviourVisual", remap = false)
public abstract class MixinAzimuthBehaviourVisual {
    @Shadow(remap = false) @Final protected AbstractBlockEntityVisual<?> parentVisual;

    @Shadow(remap = false)
    public abstract void collectCrumblingInstances(java.util.function.Consumer<dev.engine_room.flywheel.api.instance.Instance> consumer);

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void voxy$register(CallbackInfo ci) {
        me.cortex.voxy.client.compat.create.AzimuthBehaviourIndex.register(
                this.parentVisual, this, MixinAzimuthBehaviourVisual::collectCrumblingInstances);
    }
}
