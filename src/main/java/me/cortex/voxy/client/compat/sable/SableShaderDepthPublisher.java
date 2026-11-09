package me.cortex.voxy.client.compat.sable;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;

import static org.lwjgl.opengl.GL45C.*;

/** 在光影读取深度前，补入 LOD 之后才画出的远处不透明船体。 */
public final class SableShaderDepthPublisher {
    private final SableNativeDepthProtection.Prepared prepared = new SableNativeDepthProtection.Prepared();
    private final State state = new State();
    private final float[] matrix = new float[16];
    private GlFramebuffer opaqueView, translucentView;
    private GlTexture opaqueDepth, translucentDepth;
    private int sampler;

    public void publish(Viewport<?> viewport, FullscreenBlit setup,
                        int sourceDepth, int sourceWidth, int sourceHeight,
                        GlTexture opaqueDepth, GlTexture translucentDepth,
                        RenderProperties properties) {
        if (sourceDepth == 0 || sourceWidth <= 0 || sourceHeight <= 0
                || !this.prepared.prepare(viewport, VoxyRenderSystem.getRenderDistance())) return;

        if (this.opaqueView == null) {
            this.opaqueView = new GlFramebuffer().name("Sable opaque depth view");
            glNamedFramebufferDrawBuffer(this.opaqueView.id, GL_NONE);
            glNamedFramebufferReadBuffer(this.opaqueView.id, GL_NONE);
        }
        if (this.translucentView == null) {
            this.translucentView = new GlFramebuffer().name("Sable translucent depth view");
            glNamedFramebufferDrawBuffer(this.translucentView.id, GL_NONE);
            glNamedFramebufferReadBuffer(this.translucentView.id, GL_NONE);
        }
        // 只挂接已有纹理，不复制深度，也不触碰 Iris 的颜色附件及逐附件写掩码。
        // 按纹理对象而非 GL 名称判断重建，覆盖 resize 后名称复用的情况。
        if (this.opaqueDepth != opaqueDepth) {
            this.opaqueView.bind(GL_DEPTH_ATTACHMENT, opaqueDepth).verify();
            this.opaqueDepth = opaqueDepth;
        }
        if (this.translucentDepth != translucentDepth) {
            this.translucentView.bind(GL_DEPTH_ATTACHMENT, translucentDepth).verify();
            this.translucentDepth = translucentDepth;
        }
        if (this.sampler == 0) {
            this.sampler = glGenSamplers();
            glSamplerParameteri(this.sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glSamplerParameteri(this.sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glSamplerParameteri(this.sampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        }

        this.state.capture();
        try {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, this.opaqueView.id);
            glViewport(0, 0, viewport.width, viewport.height);
            glDisable(GL_STENCIL_TEST);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(properties.closerEqualDepthCompare());
            glDepthMask(true);
            setup.bind();
            glBindTextureUnit(0, sourceDepth);
            glBindSampler(0, this.sampler);
            glUniform2f(1, (float) viewport.width / sourceWidth, (float) viewport.height / sourceHeight);
            glUniformMatrix4fv(2, false, this.prepared.inverseNativeMvp.get(this.matrix));
            glUniformMatrix4fv(3, false, viewport.MVP.get(this.matrix));
            boolean halfNdc = RenderProperties.windowIsHalfNdc();
            glUniform4f(4, halfNdc ? 0.5f : 1, halfNdc ? 0.5f : 0,
                    halfNdc ? 2 : 1, halfNdc ? -1 : 0);
            this.prepared.draw(setup, this.opaqueView.id, this.translucentView.id,
                    this.state.scissorTest, this.state.scissor);
        } finally {
            this.state.restore();
        }
    }

    public void free() {
        if (this.opaqueView != null) this.opaqueView.free();
        if (this.translucentView != null) this.translucentView.free();
        if (this.sampler != 0) glDeleteSamplers(this.sampler);
        this.opaqueView = this.translucentView = null;
        this.opaqueDepth = this.translucentDepth = null;
        this.sampler = 0;
    }

    // 仅保存本次补写会改变的状态；数组在管线存续期间复用。
    private static final class State {
        final int[] viewport = new int[4], scissor = new int[4];
        int framebuffer, program, vao, activeTexture, texture, sampler, depthFunc;
        boolean depthMask, depthTest, stencilTest, scissorTest;

        void capture() {
            glGetIntegerv(GL_VIEWPORT, this.viewport);
            glGetIntegerv(GL_SCISSOR_BOX, this.scissor);
            this.framebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            this.program = glGetInteger(GL_CURRENT_PROGRAM);
            this.vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            this.activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
            glActiveTexture(GL_TEXTURE0);
            this.texture = glGetInteger(GL_TEXTURE_BINDING_2D);
            glActiveTexture(this.activeTexture);
            this.sampler = glGetIntegeri(GL_SAMPLER_BINDING, 0);
            this.depthFunc = glGetInteger(GL_DEPTH_FUNC);
            this.depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
            this.depthTest = glIsEnabled(GL_DEPTH_TEST);
            this.stencilTest = glIsEnabled(GL_STENCIL_TEST);
            this.scissorTest = glIsEnabled(GL_SCISSOR_TEST);
        }

        void restore() {
            glUseProgram(this.program);
            glBindVertexArray(this.vao);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, this.texture);
            glBindSampler(0, this.sampler);
            glActiveTexture(this.activeTexture);
            if (this.depthTest) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
            if (this.stencilTest) glEnable(GL_STENCIL_TEST); else glDisable(GL_STENCIL_TEST);
            if (this.scissorTest) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
            glScissor(this.scissor[0], this.scissor[1], this.scissor[2], this.scissor[3]);
            glDepthFunc(this.depthFunc);
            glDepthMask(this.depthMask);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, this.framebuffer);
            glViewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
        }
    }
}
