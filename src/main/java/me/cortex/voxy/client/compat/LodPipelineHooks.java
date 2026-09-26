package me.cortex.voxy.client.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyProfile;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_FUNC;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_WRITEMASK;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glDepthFunc;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glIsEnabled;
import static org.lwjgl.opengl.GL11C.glReadPixels;
import static org.lwjgl.opengl.GL14C.GL_BLEND_DST_ALPHA;
import static org.lwjgl.opengl.GL14C.GL_BLEND_DST_RGB;
import static org.lwjgl.opengl.GL14C.GL_BLEND_SRC_ALPHA;
import static org.lwjgl.opengl.GL14C.GL_BLEND_SRC_RGB;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL20C.GL_BLEND_EQUATION_ALPHA;
import static org.lwjgl.opengl.GL20C.GL_BLEND_EQUATION_RGB;
import static org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20C.glBlendEquationSeparate;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING;

/**
 * 可选联动渲染器的统一调度入口。
 *
 * 所有联动都在原版不透明和半透明阶段之间执行，因此这里负责两件事：保持注册顺序，
 * 以及在每个渲染器返回后恢复 OpenGL 状态，避免一个联动污染下一个渲染阶段。
 */
public final class LodPipelineHooks {
    public interface Renderer {
        // depthFunc 是管线使用的近距离比较函数：反向 Z 为 GEQUAL，否则为 LEQUAL。
        void render(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc);
    }

    public interface TranslucentRenderer {
        void renderTranslucent(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc);
    }

    /** 调试探针记录联动绘制前后的深度/模板状态。 */
    public interface FrameDebugProbe {
        void begin(AbstractRenderPipeline pipeline, Viewport<?> viewport);

        void end(AbstractRenderPipeline pipeline, Viewport<?> viewport);
    }

    public static volatile FrameDebugProbe frameDebugProbe;
    public static volatile boolean distantTrackMeshesReady;

    private static final List<Renderer> RENDERERS = new CopyOnWriteArrayList<>();
    private static final List<TranslucentRenderer> TRANSLUCENT_RENDERERS = new CopyOnWriteArrayList<>();
    private static boolean errored;

    // /voxy 调试命令使用的一次性深度探针；读取结束后立即清除请求。
    public static volatile boolean depthProbeRequested;
    public static volatile String depthProbeResult;

    private LodPipelineHooks() {
    }

    public static void register(Renderer renderer) {
        RENDERERS.add(renderer);
    }

    public static void registerTranslucent(TranslucentRenderer renderer) {
        TRANSLUCENT_RENDERERS.add(renderer);
    }

    public static void translucent(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        if (TRANSLUCENT_RENDERERS.isEmpty()) {
            return;
        }

        renderStateGuarded(() -> {
            for (TranslucentRenderer renderer : TRANSLUCENT_RENDERERS) {
                try {
                    long start = VoxyProfile.begin();
                    renderer.renderTranslucent(pipeline, viewport, depthFunc);
                    VoxyProfile.end("render/" + renderer.getClass().getSimpleName(), start);
                } catch (Throwable error) {
                    logRendererFailure("LOD translucent render hook failed (logged once)", error);
                }
            }
        });
    }

    public static void beforeTranslucent(AbstractRenderPipeline pipeline,
                                         Viewport<?> viewport,
                                         int depthFunc) {
        me.cortex.voxy.client.compat.create.DistantMesh.beginFrame();
        if (RENDERERS.isEmpty()) {
            return;
        }

        // 联动绘制位于原版不透明和半透明阶段之间，不能把状态泄漏给下一阶段。
        renderStateGuarded(() -> {
            var probe = beginProbe(pipeline, viewport);
            for (Renderer renderer : RENDERERS) {
                try {
                    long start = VoxyProfile.begin();
                    renderer.render(pipeline, viewport, depthFunc);
                    VoxyProfile.end("render/" + renderer.getClass().getSimpleName(), start);
                } catch (Throwable error) {
                    logRendererFailure("LOD pipeline render hook failed (logged once)", error);
                }
            }
            endProbe(probe, pipeline, viewport);

            if (depthProbeRequested) {
                depthProbeRequested = false;
                depthProbeResult = captureDepthProbe(viewport, depthFunc);
                Logger.info("Depth probe: " + depthProbeResult);
            }
        });
    }

    private static FrameDebugProbe beginProbe(AbstractRenderPipeline pipeline, Viewport<?> viewport) {
        var probe = frameDebugProbe;
        if (probe == null) {
            return null;
        }
        try {
            probe.begin(pipeline, viewport);
        } catch (Throwable ignored) {
            // 调试探针不能影响正常渲染。
        }
        return probe;
    }

    private static void endProbe(FrameDebugProbe probe, AbstractRenderPipeline pipeline, Viewport<?> viewport) {
        if (probe == null) {
            return;
        }
        try {
            probe.end(pipeline, viewport);
        } catch (Throwable ignored) {
            // 调试探针不能影响正常渲染。
        }
    }

    private static void logRendererFailure(String message, Throwable error) {
        if (!errored) {
            errored = true;
            Logger.error(message, error);
        }
    }

    private static String captureDepthProbe(Viewport<?> viewport, int expectedFunc) {
        try {
            boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
            boolean depthMask = glGetInteger(GL_DEPTH_WRITEMASK) != 0;
            int depthFunc = glGetInteger(GL_DEPTH_FUNC);
            int drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            float[] pixels = new float[9];
            glReadPixels(
                    Math.max(0, viewport.width / 2 - 1),
                    Math.max(0, viewport.height / 2 - 1),
                    3,
                    3,
                    org.lwjgl.opengl.GL11C.GL_DEPTH_COMPONENT,
                    org.lwjgl.opengl.GL11C.GL_FLOAT,
                    pixels);

            var result = new StringBuilder("fbo=").append(drawFbo)
                    .append(" depthTest=").append(depthTest)
                    .append(" mask=").append(depthMask)
                    .append(" func=0x").append(Integer.toHexString(depthFunc))
                    .append(" expectedFunc=0x").append(Integer.toHexString(expectedFunc))
                    .append(" centreDepth=[");
            for (int index = 0; index < pixels.length; index++) {
                result.append(String.format("%.5f", pixels[index]));
                if (index < pixels.length - 1) {
                    result.append(' ');
                }
            }
            return result.append(']').toString();
        } catch (Throwable error) {
            return "probe failed: " + error;
        }
    }

    /** 捕获并恢复联动渲染器可能修改的 OpenGL 状态。 */
    public static void renderStateGuarded(Runnable body) {
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int previousActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        int[] previousTextures = new int[4];
        for (int unit = 0; unit < previousTextures.length; unit++) {
            glActiveTexture(GL_TEXTURE0 + unit);
            previousTextures[unit] = glGetInteger(GL_TEXTURE_BINDING_2D);
        }
        glActiveTexture(previousActiveTexture);

        boolean previousDepthTest = glIsEnabled(GL_DEPTH_TEST);
        int previousDepthFunc = glGetInteger(GL_DEPTH_FUNC);
        boolean previousDepthMask = glGetInteger(GL_DEPTH_WRITEMASK) != 0;
        boolean previousCull = glIsEnabled(GL_CULL_FACE);
        boolean previousBlend = glIsEnabled(GL_BLEND);
        int previousBlendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
        int previousBlendDstRgb = glGetInteger(GL_BLEND_DST_RGB);
        int previousBlendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
        int previousBlendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        int previousBlendEquationRgb = glGetInteger(GL_BLEND_EQUATION_RGB);
        int previousBlendEquationAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);

        try {
            body.run();
        } finally {
            // 原生绑定用于同步 GlStateManager 的缓存和实际 OpenGL 状态。
            for (int unit = 0; unit < previousTextures.length; unit++) {
                glActiveTexture(GL_TEXTURE0 + unit);
                glBindTexture(GL_TEXTURE_2D, previousTextures[unit]);
            }
            glActiveTexture(previousActiveTexture);
            GlStateManager._glUseProgram(previousProgram);
            GlStateManager._glBindVertexArray(previousVao);
            setEnabled(GL_DEPTH_TEST, previousDepthTest);
            glDepthFunc(previousDepthFunc);
            glDepthMask(previousDepthMask);
            setEnabled(GL_CULL_FACE, previousCull);
            setEnabled(GL_BLEND, previousBlend);
            glBlendFuncSeparate(
                    previousBlendSrcRgb,
                    previousBlendDstRgb,
                    previousBlendSrcAlpha,
                    previousBlendDstAlpha);
            glBlendEquationSeparate(previousBlendEquationRgb, previousBlendEquationAlpha);
        }
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            glEnable(capability);
        } else {
            glDisable(capability);
        }
    }

    /** 清空 Minecraft 状态缓存中与联动渲染有关的绑定。 */
    public static void invalidateGlCaches() {
        for (int unit = 0; unit < 4; unit++) {
            GlStateManager._activeTexture(GL_TEXTURE0 + unit);
            GlStateManager._bindTexture(0);
        }
        GlStateManager._activeTexture(GL_TEXTURE0);
        GlStateManager._glUseProgram(0);
        GlStateManager._glBindVertexArray(0);
    }
}
