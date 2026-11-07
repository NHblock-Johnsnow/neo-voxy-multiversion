package me.cortex.voxy.client.iris;

import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;

public class VoxySamplers {
    public static void addSamplers(IrisRenderingPipeline pipeline, SamplerHolder samplers) {
        // Oculus 在构造管线时注册 sampler，此时 Voxy 的 pipeline data 尚未建立。
        boolean opaque = samplers.hasSampler("vxDepthTexOpaque");
        boolean translucent = samplers.hasSampler("vxDepthTexTrans");
        if (!opaque && !translucent) return;
        var sampler = ((IGetIrisVoxyPipelineData) pipeline).voxy$getDepthSampler();
        if (opaque) {
            samplers.addDynamicSampler(TextureType.TEXTURE_2D, () -> depthTexture(pipeline, false),
                    sampler, "vxDepthTexOpaque");
        }
        if (translucent) {
            samplers.addDynamicSampler(TextureType.TEXTURE_2D, () -> depthTexture(pipeline, true),
                    sampler, "vxDepthTexTrans");
        }
    }

    private static int depthTexture(IrisRenderingPipeline pipeline, boolean translucent) {
        var data = ((IGetIrisVoxyPipelineData) pipeline).voxy$getPipelineData();
        if (data == null || data.thePipeline == null) return 0;
        var framebuffer = translucent ? data.thePipeline.fbTranslucent : data.thePipeline.fb;
        var depth = framebuffer.getDepthTex();
        return depth == null ? 0 : depth.id;
    }
}
