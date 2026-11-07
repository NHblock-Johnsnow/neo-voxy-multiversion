package me.cortex.voxy.client.compat.distant;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.LightMapHelper;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL20C.nglUniformMatrix4fv;
import static org.lwjgl.opengl.GL20C.glUniform2f;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;

public final class DistantBlockShader {
    private static Shader shader;
    private static Shader uniformShader;
    private static Shader patchedShader;
    private static Shader patchedUniformShader;
    private static AbstractRenderPipeline patchedOwner;
    private static boolean patchFailed;

    private DistantBlockShader() {}

    public static Shader get(AbstractRenderPipeline pipeline) {
        return get(pipeline, false);
    }

    public static Shader get(AbstractRenderPipeline pipeline, boolean uniformLight) {
        if (patchedOwner != pipeline) {
            if (patchedShader != null) patchedShader.free();
            if (patchedUniformShader != null) patchedUniformShader.free();
            patchedShader = null;
            patchedUniformShader = null;
            patchedOwner = pipeline;
            patchFailed = false;
        }
        if (!patchFailed) {
            try {
                // 稳定管线直接复用，避免每次绘制重复读源码和生成补丁。
                Shader selected = uniformLight ? patchedUniformShader : patchedShader;
                if (selected != null) return selected;
                String fragment = pipeline.patchOpaqueShader(null,
                        ShaderLoader.parse("voxy:compat/distant_block.frag"));
                if (fragment != null) {
                    String vertex = ShaderLoader.parse("voxy:compat/distant_block.vert");
                    String taa = pipeline.taaFunction("distantTaaShift");
                    vertex += "\n" + (taa != null ? taa
                            : "vec2 distantTaaShift() { return vec2(0.0); }");
                    selected = Shader.make().define("PATCHED_SHADER").defineIf("UNIFORM_LIGHT", uniformLight)
                            .addSource(ShaderType.VERTEX, vertex)
                            .addSource(ShaderType.FRAGMENT, fragment)
                            .compile().name(uniformLight ? "distant_block_patched_uniform" : "distant_block_patched");
                    if (uniformLight) patchedUniformShader = selected;
                    else patchedShader = selected;
                    return selected;
                }
                // 不支持补丁的结果也缓存，切换管线时重置。
                patchFailed = true;
            } catch (Throwable error) {
                patchFailed = true;
                Logger.error("Failed to compile shader-pack patched 1.20.1 distant block shader", error);
            }
        }
        Shader selected = uniformLight ? uniformShader : shader;
        if (selected == null) {
            selected = Shader.make().defineIf("UNIFORM_LIGHT", uniformLight)
                    .add(ShaderType.VERTEX, "voxy:compat/distant_block.vert")
                    .add(ShaderType.FRAGMENT, "voxy:compat/distant_block.frag")
                    .compile().name(uniformLight ? "distant_block_uniform" : "distant_block");
            if (uniformLight) uniformShader = selected;
            else shader = selected;
        }
        return selected;
    }

    public static void bindTextures() {
        glBindTextureUnit(0, Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getId());
        glBindSampler(0, 0);
        LightMapHelper.bind(1);
    }

    public static void uploadTransform(Matrix4f transform) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var data = stack.mallocFloat(16);
            transform.get(data);
            nglUniformMatrix4fv(0, 1, false, org.lwjgl.system.MemoryUtil.memAddress(data));
        }
    }

    public static void uploadLight(int packedLight) {
        float block = Math.min(255, (packedLight & 0xFFFF) + 8) / 256.0f;
        float sky = Math.min(255, ((packedLight >>> 16) & 0xFFFF) + 8) / 256.0f;
        glUniform2f(4, block, sky);
    }
}
