package me.cortex.voxy.client.compat.simulated;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;

final class DistantLaserShaders {
    private static Shader plain;
    private static Shader patched;
    private static AbstractRenderPipeline owner;
    private static boolean patchFailed;

    private DistantLaserShaders() {}

    static Shader forPipeline(AbstractRenderPipeline pipeline) {
        if (owner != pipeline) {
            if (patched != null) patched.free();
            patched = null;
            owner = pipeline;
            patchFailed = false;
        }
        if (!patchFailed) {
            try {
                if (patched == null) {
                    String source = ShaderLoader.parse("voxy:compat/distant_laser.frag");
                    String fragment = pipeline.patchTranslucentShader(null, source);
                    if (fragment == null) fragment = pipeline.patchOpaqueShader(null, source);
                    if (fragment != null) {
                        patched = Shader.make().define("PATCHED_SHADER").define("TRANSLUCENT")
                                .addSource(ShaderType.VERTEX,
                                        me.cortex.voxy.client.compat.create.DistantShaders.patchedVertex(pipeline))
                                .addSource(ShaderType.FRAGMENT, fragment)
                                .compile().name("distant_laser_patched");
                    }
                }
                if (patched != null) return patched;
            } catch (Throwable ignored) {
                patchFailed = true;
            }
        }
        if (plain == null) {
            plain = Shader.make()
                    .add(ShaderType.VERTEX, "voxy:compat/distant.vert")
                    .add(ShaderType.FRAGMENT, "voxy:compat/distant_laser.frag")
                    .compile().name("distant_laser");
        }
        return plain;
    }
}
