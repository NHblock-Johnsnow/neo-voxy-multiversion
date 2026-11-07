package me.cortex.voxy.client.mixin.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ContraptionEntityRenderer.class)
public class MixinContraptionEntityRenderer {
    @WrapOperation(
            method = "render(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Ldev/engine_room/flywheel/api/visualization/VisualizationManager;supportsVisualization(Lnet/minecraft/world/level/LevelAccessor;)Z"))
    private boolean voxy$renderProxyWithoutVisualization(LevelAccessor level, Operation<Boolean> original,
                                                         @Local(argsOnly = true) AbstractContraptionEntity entity) {
        // 代理未加入Flywheel实例管理，使用Create的普通模型绘制。
        return !me.cortex.voxy.compat.far.FarEntityClient.isVehicleProxy(entity) && original.call(level);
    }

    @Inject(
            method = "render(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private void voxy$cullBeyondRenderDistance(AbstractContraptionEntity entity, float yaw, float partialTicks,
                                               PoseStack poseStack, MultiBufferSource buffers, int light,
                                               CallbackInfo ci) {
        // 远景代理的距离由远实体渲染器控制，不能套用活体剔除。
        if (me.cortex.voxy.compat.far.FarEntityClient.isVehicleProxy(entity)) return;
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return;
        }
        if (me.cortex.voxy.client.compat.ShipBorne.isShipBorne(entity.getX(), entity.getZ())) {
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        if (entity instanceof com.simibubi.create.content.trains.entity.CarriageContraptionEntity carriage) {
            if (me.cortex.voxy.client.compat.create.TrainHandover.shouldCullLive(carriage, cam)) {
                ci.cancel();
            }
            return;
        }
        double reach = mc.options.getEffectiveRenderDistance() * 16.0;
        if (entity.position().distanceToSqr(cam) > reach * reach) {
            ci.cancel();
        }
    }
}
