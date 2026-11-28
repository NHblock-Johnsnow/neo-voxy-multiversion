package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.core.util.RenderPassGuard;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelRenderer.class, priority = 50)
public abstract class MixinLevelRenderer implements IGetVoxyRenderSystem {
    @Shadow private @Nullable ClientLevel level;
    @Unique private VoxyRenderSystem renderer;

    @Override
    public VoxyRenderSystem voxy$getRenderSystem() {
        return this.renderer;
    }

    @WrapMethod(method = "renderLevel")
    private void voxy$guardSecondaryRenderPass(
            DeltaTracker tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f positionMatrix,
            Matrix4f projectionMatrix,
            Operation<Void> original) {
        boolean secondary = RenderPassGuard.enterLevelRender(camera);
        try {
            original.call(tickCounter, renderBlockOutline, camera, gameRenderer, lightTexture, positionMatrix, projectionMatrix);
        } finally {
            RenderPassGuard.exitLevelRender(secondary);
        }
    }

    @Inject(method = "allChanged()V", at = @At("RETURN"), order = 900)//We want to inject before sodium
    private void voxy$reloadVoxyRenderer(CallbackInfo ci) {
        me.cortex.voxy.client.compat.LodPipelineHooks.onResourceReload();
        var seasonalView = me.cortex.voxy.client.core.compat.eclipticseasons.SeasonalLod.view;
        if (seasonalView != null) {
            try {
                seasonalView.clearCaches();
            } catch (LinkageError e) {
                me.cortex.voxy.client.core.compat.eclipticseasons.SeasonalLod.disarm(e);
            }
        }
        this.voxy$shutdownRenderer();
        if (this.level != null) {
            this.voxy$createRenderer();
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void voxy$captureSetWorld(ClientLevel world, CallbackInfo ci) {
        if (this.level != world) {
            this.voxy$shutdownRenderer();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void voxy$injectClose(CallbackInfo ci) {
        this.voxy$shutdownRenderer();
    }

    @Override
    public void voxy$shutdownRenderer() {
        me.cortex.voxy.client.core.compat.eclipticseasons.SeasonalSnowRefresher.cancelAndJoin();
        if (this.renderer != null) {
            this.renderer.shutdown();
            this.renderer = null;
        }
    }

    @Override
    public void voxy$createRenderer() {
        if (this.renderer != null) return;
        if (!VoxyConfig.CONFIG.enabled) {
            Logger.info("Not creating renderer due to disabled");
            return;
        }
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            Logger.info("Not creating renderer due to disabled rendering");
            return;
        }
        if (this.level == null) {
            Logger.error("Not creating renderer due to null world");
            return;
        }
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            Logger.error("Not creating renderer due to null instance");
            return;
        }
        WorldEngine world = WorldIdentifier.ofEngine(this.level);
        if (world == null) {
            Logger.error("Null world selected");
            return;
        }
        try {
            this.renderer = new VoxyRenderSystem(world, instance.getServiceManager());
        } catch (RuntimeException e) {
            if (IrisUtil.irisShaderPackEnabled()) {
                IrisUtil.disableIrisShaders();
            } else {
                throw e;
            }
        }
        instance.updateDedicatedThreads();
    }
}
