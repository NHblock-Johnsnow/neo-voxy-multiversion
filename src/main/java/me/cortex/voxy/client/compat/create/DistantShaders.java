package me.cortex.voxy.client.compat.create;

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
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;

/** 管理兼容模组远景绘制使用的 shader，并按管线缓存补丁结果。 */
public final class DistantShaders {
    private static Shader vertexLight;
    private static Shader uniformLight;
    private static Shader depthOnly;
    private static AbstractRenderPipeline depthOwner;

    private static Shader patchedVertexLight;
    private static Shader patchedUniformLight;
    private static Shader translucentVertexLight;
    private static Shader patchedTranslucentVertexLight;
    private static AbstractRenderPipeline translucentPatchedOwner;
    private static boolean translucentPatchFailed;
    private static AbstractRenderPipeline patchedOwner;
    private static boolean patchAvailable;
    private static boolean patchFailed;
    private static AbstractRenderPipeline copycatOwner;
    private static Shader copycatOpaque, copycatTranslucent;

    private DistantShaders() {}

    public static Shader forCopycatPipeline(AbstractRenderPipeline pipeline, boolean translucent) {
        if (copycatOwner != pipeline) {
            if (copycatOpaque != null) copycatOpaque.free();
            if (copycatTranslucent != null) copycatTranslucent.free();
            copycatOpaque = copycatTranslucent = null;
            copycatOwner = pipeline;
        }
        Shader shader = translucent ? copycatTranslucent : copycatOpaque;
        if (shader == null) {
            String source = ShaderLoader.parse("voxy:compat/distant.frag");
            String fragment = translucent ? pipeline.patchTranslucentShader(null, source)
                    : pipeline.patchOpaqueShader(null, source);
            if (translucent && fragment == null) fragment = pipeline.patchOpaqueShader(null, source);
            shader = Shader.make().define("COPYCAT_OCCLUSION")
                    .defineIf("TRANSLUCENT", translucent).defineIf("PATCHED_SHADER", fragment != null)
                    .addSource(ShaderType.VERTEX, fragment != null ? patchedVertex(pipeline)
                            : ShaderLoader.parse("voxy:compat/distant.vert"))
                    .addSource(ShaderType.FRAGMENT, fragment != null ? fragment : source)
                    .compile().name(translucent ? "copycat_translucent" : "copycat_opaque");
            if (translucent) copycatTranslucent = shader;
            else copycatOpaque = shader;
        }
        return shader;
    }

    public static void warmup(AbstractRenderPipeline pipeline) {
        if (!net.neoforged.fml.ModList.get().isLoaded("create")) {
            return;
        }
        try {
            forPipeline(pipeline, false);
            forPipeline(pipeline, true);
        } catch (Throwable e) {
            Logger.error("Distant shader warmup failed; they will compile on first use instead", e);
        }
    }

    //uniformLightVariant: per-draw light uniform (moving carriages) vs per-vertex baked light (tracks)
    public static Shader forPipeline(AbstractRenderPipeline pipeline, boolean uniformLightVariant) {
        if (patchedOwner != pipeline) {
            freePatched();
            patchedOwner = pipeline;
            patchFailed = false;
            String probe = null;
            try {
                probe = pipeline.patchOpaqueShader(null, "");
            } catch (Throwable ignored) {
            }
            patchAvailable = probe != null;
        }
        if (!patchAvailable || patchFailed) {
            return uniformLightVariant ? uniformLight() : vertexLight();
        }
        try {
            if (uniformLightVariant) {
                if (patchedUniformLight == null) {
                    patchedUniformLight = compilePatched(pipeline, true);
                }
                return patchedUniformLight;
            }
            if (patchedVertexLight == null) {
                patchedVertexLight = compilePatched(pipeline, false);
            }
            return patchedVertexLight;
        } catch (Throwable e) {
            patchFailed = true;
            Logger.error("Failed to compile shader-pack patched distant shader; falling back to plain (visuals degraded under shaders)", e);
            return uniformLightVariant ? uniformLight() : vertexLight();
        }
    }

    public static Shader forTranslucentPipeline(AbstractRenderPipeline pipeline) {
        if (translucentPatchedOwner != pipeline) {
            if (patchedTranslucentVertexLight != null) patchedTranslucentVertexLight.free();
            patchedTranslucentVertexLight = null;
            translucentPatchedOwner = pipeline;
            translucentPatchFailed = false;
        }
        if (!translucentPatchFailed) {
            try {
                if (patchedTranslucentVertexLight == null) {
                    String source = ShaderLoader.parse("voxy:compat/distant.frag");
                    String fragment = pipeline.patchTranslucentShader(null, source);
                    if (fragment == null) fragment = pipeline.patchOpaqueShader(null, source);
                    if (fragment != null) {
                        patchedTranslucentVertexLight = Shader.make()
                                .define("PATCHED_SHADER").define("TRANSLUCENT")
                                .addSource(ShaderType.VERTEX, patchedVertex(pipeline))
                                .addSource(ShaderType.FRAGMENT, fragment)
                                .compile().name("distant_patched_translucent_vertex");
                    }
                }
                if (patchedTranslucentVertexLight != null) return patchedTranslucentVertexLight;
            } catch (Throwable e) {
                translucentPatchFailed = true;
                Logger.error("Failed to compile shader-pack patched translucent distant shader", e);
            }
        }
        if (translucentVertexLight == null) {
            translucentVertexLight = Shader.make().define("TRANSLUCENT")
                    .add(ShaderType.VERTEX, "voxy:compat/distant.vert")
                    .add(ShaderType.FRAGMENT, "voxy:compat/distant.frag")
                    .compile().name("distant_translucent_vertex_light");
        }
        return translucentVertexLight;
    }

    private static Shader compilePatched(AbstractRenderPipeline pipeline, boolean uniformLightVariant) {
        String frag = pipeline.patchOpaqueShader(null, ShaderLoader.parse("voxy:compat/distant.frag"));
        return Shader.make()
                .define("PATCHED_SHADER")
                .defineIf("UNIFORM_LIGHT", uniformLightVariant)
                .addSource(ShaderType.VERTEX, patchedVertex(pipeline))
                .addSource(ShaderType.FRAGMENT, frag)
                .compile().name(uniformLightVariant ? "distant_patched_uniform" : "distant_patched_vertex");
    }

    public static String patchedVertex(AbstractRenderPipeline pipeline) {
        String source = ShaderLoader.parse("voxy:compat/distant.vert");
        String taa = pipeline.taaFunction("distantTaaShift");
        return source + "\n" + (taa != null ? taa : "vec2 distantTaaShift() { return vec2(0.0); }");
    }

    private static void freePatched() {
        if (patchedVertexLight != null) {
            patchedVertexLight.free();
            patchedVertexLight = null;
        }
        if (patchedUniformLight != null) {
            patchedUniformLight.free();
            patchedUniformLight = null;
        }
    }

    private static Shader vertexLight() {
        if (vertexLight == null) {
            vertexLight = Shader.make()
                    .add(ShaderType.VERTEX, "voxy:compat/distant.vert")
                    .add(ShaderType.FRAGMENT, "voxy:compat/distant.frag")
                    .compile().name("distant_vertex_light");
        }
        return vertexLight;
    }

    private static Shader uniformLight() {
        if (uniformLight == null) {
            uniformLight = Shader.make()
                    .define("UNIFORM_LIGHT")
                    .add(ShaderType.VERTEX, "voxy:compat/distant.vert")
                    .add(ShaderType.FRAGMENT, "voxy:compat/distant.frag")
                    .compile().name("distant_uniform_light");
        }
        return uniformLight;
    }

    public static Shader depthOnly(AbstractRenderPipeline pipeline) {
        if (depthOwner != pipeline) {
            if (depthOnly != null) depthOnly.free();
            depthOnly = null;
            depthOwner = pipeline;
        }
        if (depthOnly == null) {
            depthOnly = Shader.make()
                    .define("TRAIN_DEPTH_REPLAY")
                    .addSource(ShaderType.VERTEX, patchedVertex(pipeline))
                    .add(ShaderType.FRAGMENT, "voxy:compat/distant_depth.frag")
                    .compile().name("distant_depth_only");
        }
        return depthOnly;
    }

    //Raw binds; the surrounding renderStateGuarded restores whatever was here before
    public static void bindTextures() {
        int atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId();
        glBindTextureUnit(0, atlas);
        glBindSampler(0, 0);
        LightMapHelper.bind(1);
    }

    public static void uploadTransform(Matrix4f transform) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var buf = stack.mallocFloat(16);
            transform.get(buf);
            nglUniformMatrix4fv(0, 1, false, org.lwjgl.system.MemoryUtil.memAddress(buf));
        }
    }

    //Nearest quarter turn of a model yaw (degrees, +Y right-handed) for the vertex shader's baked
    //face re-aim; UNIFORM_LIGHT variants only
    public static void uploadFaceRotation(float yawDegrees) {
        int steps = Math.round(yawDegrees / 90.0f) & 3;
        org.lwjgl.opengl.GL30C.glUniform1ui(5, steps);
    }
}
