package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.systems.RenderSystem;

@Mixin(value = FogRenderer.class, remap = true)
public class MixinFogRenderer {
    @Inject(
        method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
        at = @At("TAIL")
    )
    private static void voxy$overrideFog(
        Camera camera,
        FogMode fogMode,
        float viewDistance,
        boolean thickFog,
        float tickDelta,
        CallbackInfo ci
    ) {
        var vrs = IGetVoxyRenderSystem.getNullable();
        if (vrs == null) return;

        if (fogMode == FogMode.FOG_TERRAIN
                && (camera.getFluidInCamera() != FogType.NONE
                    || (camera.getEntity() instanceof LivingEntity living
                        && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))))) {
            return;
        }

        if (RenderSystem.getShaderFogEnd() < 10.0f) return;

        // Adjust sky fog so it always looks smooth and doesn't change with render distance
        if (fogMode == FogMode.FOG_SKY) {
            RenderSystem.setShaderFogStart(0);
            RenderSystem.setShaderFogEnd(VoxyConfig.CONFIG.skyFogDistance * 16.0f);
        }

        if (fogMode == FogMode.FOG_TERRAIN) {
            // Do NOT override unique fog, it's always displayed close and meant for restricting vision
            boolean noFogType = camera.getFluidInCamera() == FogType.NONE;

            if (noFogType && !me.cortex.voxy.client.core.VoxyRenderSystem.restrictingMediumPresent()) {
                RenderSystem.setShaderFogStart(999999999);
                RenderSystem.setShaderFogEnd(999999999);
            }
        }
    }
}
