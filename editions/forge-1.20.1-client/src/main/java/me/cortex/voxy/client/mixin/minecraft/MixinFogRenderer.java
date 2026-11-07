package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;

import net.minecraft.world.level.material.FogType;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
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
        if (vrs == null || IrisUtil.irisShaderPackEnabled()) return;
        // 状态效果雾由原版维护；LOD 停绘时也不能移除它。
        if (camera.getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) return;

        float originalEnd = RenderSystem.getShaderFogEnd();
        boolean shortRangeFog = originalEnd < 10.0f;

        // Adjust sky fog so it always looks smooth and doesn't change with render distance
        if (fogMode == FogMode.FOG_SKY && !shortRangeFog) {
            RenderSystem.setShaderFogStart(0.0f);
            RenderSystem.setShaderFogEnd(VoxyConfig.CONFIG.skyFogDistance * 16.0f);
            return;
        }

        if (fogMode != FogMode.FOG_TERRAIN) return;

        boolean normalTerrainFog = camera.getFluidInCamera() == FogType.NONE && !shortRangeFog && !thickFog;
        float capturedEnd = normalTerrainFog
                ? Math.max(RenderSystem.getShaderFogStart() + 1.01f,
                        VoxyConfig.CONFIG.skyFogDistance * 16.0f)
                : originalEnd;
        float[] capturedColor = RenderSystem.getShaderFogColor();
        if (camera.getFluidInCamera() == FogType.WATER) {
            capturedEnd = Math.max(capturedEnd, originalEnd * 1.18f);
            float average = (capturedColor[0] + capturedColor[1] + capturedColor[2]) / 3.0f;
            capturedColor = new float[]{
                    capturedColor[0] * 0.86f + average * 0.14f,
                    capturedColor[1] * 0.86f + average * 0.14f,
                    capturedColor[2] * 0.86f + average * 0.14f,
                    capturedColor.length > 3 ? capturedColor[3] : 1.0f
            };
        }
        vrs.setCapturedFog(RenderSystem.getShaderFogStart(), capturedEnd, capturedColor, !normalTerrainFog);

        if (normalTerrainFog) {
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        }
    }
}
