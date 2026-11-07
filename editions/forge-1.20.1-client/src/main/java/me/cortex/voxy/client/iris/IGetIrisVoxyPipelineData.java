package me.cortex.voxy.client.iris;

import net.irisshaders.iris.gl.sampler.GlSampler;

public interface IGetIrisVoxyPipelineData {
    IrisVoxyRenderPipelineData voxy$getPipelineData();

    GlSampler voxy$getDepthSampler();
}
