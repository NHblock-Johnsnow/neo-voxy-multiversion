package me.cortex.voxy.client.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.*;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING;
import static org.lwjgl.opengl.GL30C.glGetIntegeri;
import static org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING;
import static org.lwjgl.opengl.GL33C.glBindSampler;

public final class LodPipelineHooks {
    public interface Renderer {
        void render(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc);

        default void onResourceReload() {}
    }

    private static final List<Renderer> RENDERERS = new CopyOnWriteArrayList<>();
    private static boolean errored;

    private LodPipelineHooks() {}

    public static void register(Renderer renderer) {
        RENDERERS.add(renderer);
    }

    public static void onResourceReload() {
        for (Renderer renderer : RENDERERS) {
            try {
                renderer.onResourceReload();
            } catch (Throwable error) {
                Logger.error("LOD compatibility resource invalidation failed", error);
            }
        }
    }

    public static void beforeTranslucent(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        if (RENDERERS.isEmpty()) return;
        renderStateGuarded(() -> {
            for (Renderer renderer : RENDERERS) {
                try {
                    renderer.render(pipeline, viewport, depthFunc);
                } catch (Throwable e) {
                    if (!errored) {
                        errored = true;
                        Logger.error("LOD compatibility renderer failed (logged once)", e);
                    }
                }
            }
        });
    }

    private static void renderStateGuarded(Runnable body) {
        int program = glGetInteger(GL_CURRENT_PROGRAM);
        int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        long[] bindings = new long[2];
        for (int unit = 0; unit < bindings.length; unit++) {
            glActiveTexture(GL_TEXTURE0 + unit);
            // 一个槽保存纹理和 sampler，避免额外分配数组。
            bindings[unit] = Integer.toUnsignedLong(glGetInteger(GL_TEXTURE_BINDING_2D))
                    | ((long) glGetIntegeri(GL_SAMPLER_BINDING, unit) << 32);
        }
        glActiveTexture(activeTexture);
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        int depthFunc = glGetInteger(GL_DEPTH_FUNC);
        boolean depthMask = glGetInteger(GL_DEPTH_WRITEMASK) != 0;
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean blend = glIsEnabled(GL_BLEND);
        int blendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
        int blendDstRgb = glGetInteger(GL_BLEND_DST_RGB);
        int blendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
        int blendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        try {
            body.run();
        } finally {
            for (int unit = 0; unit < bindings.length; unit++) {
                glActiveTexture(GL_TEXTURE0 + unit);
                glBindTexture(GL_TEXTURE_2D, (int) bindings[unit]);
                glBindSampler(unit, (int) (bindings[unit] >>> 32));
            }
            glActiveTexture(activeTexture);
            GlStateManager._glUseProgram(program);
            GlStateManager._glBindVertexArray(vao);
            if (depthTest) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
            glDepthFunc(depthFunc);
            glDepthMask(depthMask);
            if (cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
            if (blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
            glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        }
    }
}
