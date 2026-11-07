package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IGetVoxyPatchData;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline implements IGetVoxyPatchData, IGetIrisVoxyPipelineData {
    @Shadow @Final private CustomUniforms customUniforms;
    @Shadow private ShaderStorageBufferHolder shaderStorageBufferHolder;
    @Unique IrisShaderPatch patchData;
    @Unique
    IrisVoxyRenderPipelineData pipeline;
    @Unique private GlSampler voxy$depthSampler;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/uniforms/custom/CustomUniforms;optimise()V"))
    private void voxy$optimiseCustomUniforms(CustomUniforms customUniforms, ProgramSet programSet) {
        IrisShaderPatch.beginUniformRetention(((IGetVoxyPatchData) programSet).voxy$getPatchData());
        try {
            customUniforms.optimise();
        } finally {
            IrisShaderPatch.endUniformRetention();
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxy$initializePipeline(ProgramSet programSet, CallbackInfo ci) {
        if (IrisUtil.SHADER_SUPPORT) {
            this.patchData = ((IGetVoxyPatchData) programSet).voxy$getPatchData();
        }
        if (this.patchData != null) {
            this.pipeline = IrisVoxyRenderPipelineData.buildPipeline((IrisRenderingPipeline)(Object)this, this.patchData, this.customUniforms, this.shaderStorageBufferHolder);
        }
    }

    @Inject(method = "beginLevelRendering", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;activeTexture(I)V", shift = At.Shift.BEFORE), remap = false)
    private void voxy$injectViewportSetup(CallbackInfo ci) {
        if (IrisUtil.CAPTURED_VIEWPORT_PARAMETERS != null) {
            var renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
            if (renderer != null) {
                IrisUtil.CAPTURED_VIEWPORT_PARAMETERS.apply(renderer);
            }
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"), remap = false)
    private void voxy$releaseRendererBeforeTargets(CallbackInfo ci) {
        IrisUtil.CAPTURED_VIEWPORT_PARAMETERS = null;
        var levelRenderer = Minecraft.getInstance().levelRenderer;
        if (levelRenderer != null) {
            ((IGetVoxyRenderSystem) levelRenderer).voxy$shutdownRenderer();
        }
    }

    @Override
    public IrisShaderPatch voxy$getPatchData() {
        return this.patchData;
    }

    @Override
    public IrisVoxyRenderPipelineData voxy$getPipelineData() {
        return this.pipeline;
    }

    @Override
    public GlSampler voxy$getDepthSampler() {
        if (this.voxy$depthSampler == null) {
            this.voxy$depthSampler = new GlSampler(false, true, false, false);
        }
        return this.voxy$depthSampler;
    }

    @Inject(method = "destroy", at = @At("TAIL"), remap = false)
    private void voxy$releaseDepthSampler(CallbackInfo ci) {
        if (this.voxy$depthSampler != null) {
            this.voxy$depthSampler.destroy();
            this.voxy$depthSampler = null;
        }
    }
}
