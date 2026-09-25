package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = {GameRenderer.class}, priority = 1100)
public class MixinGameRenderer {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void voxy$beginHandoffFrame(net.minecraft.client.DeltaTracker deltaTracker, CallbackInfo ci) {
        me.cortex.voxy.client.compat.SectionHandoff.beginFrame();
    }

    @WrapMethod(method = "getDepthFar()F")
    public float getDepthFar(Operation<Float> original) {
        if (VoxyConfig.CONFIG.isRenderingEnabled()
                && !me.cortex.voxy.client.core.VoxyRenderSystem.visionEffectPresent()) {
            return Math.max(original.call(), VoxyConfig.CONFIG.sectionRenderDistance * 32F * 4F);
        }
        return original.call();
    }
}
