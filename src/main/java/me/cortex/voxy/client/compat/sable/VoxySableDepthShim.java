package me.cortex.voxy.client.compat.sable;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11C.GL_COLOR_WRITEMASK;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_FUNC;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_WRITEMASK;
import static org.lwjgl.opengl.GL11C.GL_FALSE;
import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_LEQUAL;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NONE;
import static org.lwjgl.opengl.GL11C.GL_SCISSOR_BOX;
import static org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11C.glColorMask;
import static org.lwjgl.opengl.GL11C.glDepthFunc;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.glIsEnabled;
import static org.lwjgl.opengl.GL11C.glScissor;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE2;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL14C.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL14C.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20C.GL_DRAW_BUFFER0;
import static org.lwjgl.opengl.GL20C.glUniform2f;
import static org.lwjgl.opengl.GL20C.nglUniformMatrix4fv;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30C.GL_MAX_COLOR_ATTACHMENTS;
import static org.lwjgl.opengl.GL30C.GL_MAX_DRAW_BUFFERS;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glGetIntegeri;
import static org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL33C.glGenSamplers;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;
import static org.lwjgl.opengl.GL45C.glCheckNamedFramebufferStatus;
import static org.lwjgl.opengl.GL45C.glGetNamedFramebufferAttachmentParameteri;
import static org.lwjgl.opengl.GL45C.glNamedFramebufferDrawBuffers;
import static org.lwjgl.opengl.GL45C.glNamedFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL45C.glNamedFramebufferTexture;

public final class VoxySableDepthShim {
    private static final DepthFramebuffer COMBINED_DEPTH = new DepthFramebuffer(GL_DEPTH_COMPONENT24);
    private static final DepthFramebuffer BEFORE_SABLE_DEPTH = new DepthFramebuffer(GL_DEPTH_COMPONENT24);
    private static final GlFramebuffer DRAW_FRAMEBUFFER = new GlFramebuffer().name("Sable Voxy depth shim");

    //In-place variant scratch (see beginInPlace): snapshots of the target's own depth texture
    private static final DepthFramebuffer IN_PLACE_BEFORE = new DepthFramebuffer(GL_DEPTH_COMPONENT24);
    private static final DepthFramebuffer IN_PLACE_MERGED = new DepthFramebuffer(GL_DEPTH_COMPONENT24);
    private static final DepthFramebuffer IN_PLACE_AFTER = new DepthFramebuffer(GL_DEPTH_COMPONENT24);

    private static final FullscreenBlit COPY_DEPTH = new FullscreenBlit(RenderProperties.getRenderProperties(), "voxy:post/depth_copy.frag");
    private static final FullscreenBlit TRANSFORM_DEPTH = new FullscreenBlit(RenderProperties.getRenderProperties(), "voxy:post/blit_texture_depth_cutout.frag");
    private static final FullscreenBlit COPY_CHANGED_DEPTH = new FullscreenBlit(RenderProperties.getRenderProperties(), "voxy:post/depth_copy_changed.frag");
    private static final FullscreenBlit RESTORE_UNCHANGED_DEPTH = new FullscreenBlit(RenderProperties.getRenderProperties(), "voxy:post/depth_restore_unchanged.frag");

    private static final int DEPTH_SAMPLER = glGenSamplers();
    private static final long MATRIX_SCRATCH = MemoryUtil.nmemAlloc(16L * Float.BYTES);

    private static State activeState;
    private static boolean loggedEnabled;
    private static boolean loggedUnsupportedFramebuffer;
    //Diagnostics for /voxy debug ship: why the last begin bailed. Per path, because the two bail for
    //different reasons and a shared field can only report whichever ran last.
    public static volatile String lastSkipReason = "never called";
    public static volatile String lastInPlaceSkipReason = "never called";
    //Diagnostics for /voxy debug ship: how much fill the shim is costing. Per path, because the
    //section-layer one runs about five times a frame and the Flywheel one once.
    public static volatile long bracketedPasses;
    public static volatile long bracketedInPlacePasses;
    public static volatile long shadowPassesSkipped;
    public static volatile long nearPassesSkipped;
    public static volatile long offscreenPassesSkipped;
    public static volatile long blitMegapixels;
    public static volatile long blitInPlaceMegapixels;
    public static volatile String lastScissor = "none";
    public static volatile String lastInPlaceScissor = "none";

    static {
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_COMPARE_MODE, GL_NONE);
    }

    private VoxySableDepthShim() {
    }

    public static void begin(Matrix4f modelView, Matrix4f projection) {
        begin(modelView, projection, SableScreenBounds.FULLSCREEN);
    }

    /**
     * @param ndcBounds screen extent the bracketed pass can touch, {minX, minY, maxX, maxY} in NDC.
     *                  Everything the shim does is confined to it - the blits and the pass itself.
     */
    private static boolean isReverseZ(Matrix4f projection) {
        return Math.abs(projection.m22()) < 0.1f;
    }

    public static void begin(Matrix4f modelView, Matrix4f projection, float[] ndcBounds) {
        if (activeState != null) {
            activeState.nesting++;
            return;
        }
        if (isReverseZ(projection)) {
            lastSkipReason = "reverse-Z projection";
            return;
        }

        VoxyRenderSystem renderer = IGetVoxyRenderSystem.getNullable();
        if (renderer == null) {
            lastSkipReason = "no renderer";
            return;
        }

        int voxyDepthTexture = renderer.getSableOcclusionDepthTexture();
        if (voxyDepthTexture == 0) {
            lastSkipReason = "no LOD depth texture";
            return;
        }

        Viewport<?> viewport = renderer.getViewport();
        if (viewport == null || viewport.width <= 0 || viewport.height <= 0) {
            lastSkipReason = "no viewport";
            return;
        }

        State state = State.capture();
        if (state.drawFramebuffer == 0 || state.width <= 0 || state.height <= 0 || state.x != 0 || state.y != 0) {
            lastSkipReason = "bad GL state: fb=" + state.drawFramebuffer + " viewport=" + state.x + ',' + state.y + ' ' + state.width + 'x' + state.height;
            return;
        }

        int vanillaDepthType = glGetNamedFramebufferAttachmentParameteri(state.drawFramebuffer, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int vanillaDepthTexture = glGetNamedFramebufferAttachmentParameteri(state.drawFramebuffer, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (vanillaDepthType != GL_TEXTURE || vanillaDepthTexture == 0) {
            lastSkipReason = "depth attachment not a texture";
            if (!loggedUnsupportedFramebuffer) {
                Logger.warn("Skipping Sable/Voxy depth shim because the active depth attachment is not a texture");
                loggedUnsupportedFramebuffer = true;
            }
            return;
        }

        if (!state.setScissor(ndcBounds)) {
            lastSkipReason = "pass covers no pixels";
            offscreenPassesSkipped++;
            return;
        }

        COMBINED_DEPTH.resize(state.width, state.height);
        BEFORE_SABLE_DEPTH.resize(state.width, state.height);

        if (!prepareDrawFramebuffer(state.drawFramebuffer)) {
            lastSkipReason = "draw framebuffer prepare failed";
            state.restoreAll();
            return;
        }
        lastSkipReason = "ok";
        bracketedPasses++;
        //Four blits bracket the pass; report what they actually cover so a regression here is visible
        blitMegapixels += (4L * state.scissorWidth * state.scissorHeight) / 1_000_000L;
        lastScissor = state.scissorWidth + "x" + state.scissorHeight + " of " + state.width + "x" + state.height;

        state.applyScissor();
        copyDepth(vanillaDepthTexture, COMBINED_DEPTH.framebuffer.id, state.width, state.height);
        mergeVoxyDepth(voxyDepthTexture, COMBINED_DEPTH.framebuffer.id, state.width, state.height, viewport, modelView, projection);
        copyDepth(COMBINED_DEPTH.getDepthTex().id, BEFORE_SABLE_DEPTH.framebuffer.id, state.width, state.height);

        //Scissor stays armed through the bracketed pass: the bounds enclose every pixel it can draw, and
        //leaving it on keeps the pass from depth-testing against the stale COMBINED region outside them
        state.restoreForSableRender(DRAW_FRAMEBUFFER.id);
        activeState = state;

        if (!loggedEnabled) {
            Logger.info("Enabled Sable/Voxy combined depth shim");
            loggedEnabled = true;
        }
    }

    public static void end() {
        State state = activeState;
        if (state == null) {
            return;
        }
        if (--state.nesting > 0) {
            return;
        }

        //The bracketed pass is free to have touched the scissor itself, so re-arm rather than assume
        state.applyScissor();
        copyChangedDepth(BEFORE_SABLE_DEPTH.getDepthTex().id, COMBINED_DEPTH.getDepthTex().id, state);
        state.restoreAll();
        activeState = null;
    }

    private static State activeInPlaceState;
    private static int inPlaceDepthTexture;

    public static void beginInPlace(Matrix4f modelView, Matrix4f projection, float[] ndcBounds) {
        if (activeInPlaceState != null) {
            activeInPlaceState.nesting++;
            return;
        }
        if (isReverseZ(projection)) {
            lastInPlaceSkipReason = "reverse-Z projection";
            return;
        }

        VoxyRenderSystem renderer = IGetVoxyRenderSystem.getNullable();
        if (renderer == null) {
            lastInPlaceSkipReason = "no renderer";
            return;
        }

        int voxyDepthTexture = renderer.getSableOcclusionDepthTexture();
        if (voxyDepthTexture == 0) {
            lastInPlaceSkipReason = "no LOD depth texture";
            return;
        }

        Viewport<?> viewport = renderer.getViewport();
        if (viewport == null || viewport.width <= 0 || viewport.height <= 0) {
            lastInPlaceSkipReason = "no viewport";
            return;
        }

        State state = State.capture();
        if (state.drawFramebuffer == 0 || state.width <= 0 || state.height <= 0 || state.x != 0 || state.y != 0) {
            lastInPlaceSkipReason = "bad GL state: fb=" + state.drawFramebuffer + " viewport=" + state.x + ',' + state.y + ' ' + state.width + 'x' + state.height;
            return;
        }

        int depthType = glGetNamedFramebufferAttachmentParameteri(state.drawFramebuffer, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int depthTexture = glGetNamedFramebufferAttachmentParameteri(state.drawFramebuffer, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (depthType != GL_TEXTURE || depthTexture == 0) {
            lastInPlaceSkipReason = "depth attachment not a texture";
            return;
        }

        IN_PLACE_BEFORE.resize(state.width, state.height);
        IN_PLACE_MERGED.resize(state.width, state.height);
        IN_PLACE_AFTER.resize(state.width, state.height);

        if (!state.setScissor(ndcBounds)) {
            lastInPlaceSkipReason = "pass covers no pixels";
            offscreenPassesSkipped++;
            return;
        }

        //Five blits across begin/end, bounded to the ships this wrap exists for
        bracketedInPlacePasses++;
        blitInPlaceMegapixels += (5L * state.scissorWidth * state.scissorHeight) / 1_000_000L;
        lastInPlaceScissor = state.scissorWidth + "x" + state.scissorHeight + " of " + state.width + "x" + state.height;

        state.applyScissor();
        copyDepth(depthTexture, IN_PLACE_BEFORE.framebuffer.id, state.width, state.height);
        mergeVoxyDepth(voxyDepthTexture, state.drawFramebuffer, state.width, state.height, viewport, modelView, projection);
        copyDepth(depthTexture, IN_PLACE_MERGED.framebuffer.id, state.width, state.height);

        //restoreAll puts the caller's scissor back; the wrapped pass runs under it, which is correct -
        //everything it draws outside the ships tests against depth this wrap never touched
        state.restoreAll();
        inPlaceDepthTexture = depthTexture;
        activeInPlaceState = state;
        lastInPlaceSkipReason = "ok";

        if (!loggedEnabled) {
            Logger.info("Enabled Sable/Voxy combined depth shim");
            loggedEnabled = true;
        }
    }

    public static void endInPlace() {
        State state = activeInPlaceState;
        if (state == null) {
            return;
        }
        if (--state.nesting > 0) {
            return;
        }
        activeInPlaceState = null;

        //State only tracks units 0/1; the restore blit also uses unit 2
        State current = State.capture();
        int prevActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE2);
        int savedTexture2 = glGetInteger(GL_TEXTURE_BINDING_2D);
        glActiveTexture(prevActive);
        int savedSampler2 = glGetIntegeri(GL_SAMPLER_BINDING, 2);

        state.applyScissor();
        copyDepth(inPlaceDepthTexture, IN_PLACE_AFTER.framebuffer.id, state.width, state.height);
        restoreUnchangedDepth(state);

        glBindTextureUnit(2, savedTexture2);
        glBindSampler(2, savedSampler2);
        current.restoreAll();
    }

    private static void restoreUnchangedDepth(State state) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, state.drawFramebuffer);
        glViewport(0, 0, state.width, state.height);
        glDisable(GL_STENCIL_TEST);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);
        glDepthMask(true);
        glColorMask(false, false, false, false);

        RESTORE_UNCHANGED_DEPTH.bind();
        glBindTextureUnit(0, IN_PLACE_BEFORE.getDepthTex().id);
        glBindSampler(0, DEPTH_SAMPLER);
        glBindTextureUnit(1, IN_PLACE_MERGED.getDepthTex().id);
        glBindSampler(1, DEPTH_SAMPLER);
        glBindTextureUnit(2, IN_PLACE_AFTER.getDepthTex().id);
        glBindSampler(2, DEPTH_SAMPLER);
        RESTORE_UNCHANGED_DEPTH.blit();
    }

    private static boolean prepareDrawFramebuffer(int sourceFramebuffer) {
        int maxDrawBuffers = glGetInteger(GL_MAX_DRAW_BUFFERS);
        int maxColorAttachments = glGetInteger(GL_MAX_COLOR_ATTACHMENTS);

        for (int i = 0; i < maxColorAttachments; i++) {
            glNamedFramebufferTexture(DRAW_FRAMEBUFFER.id, GL_COLOR_ATTACHMENT0 + i, 0, 0);
        }

        int[] drawBuffers = new int[maxDrawBuffers];
        boolean hasColorTarget = false;
        for (int i = 0; i < maxDrawBuffers; i++) {
            int drawBuffer = glGetInteger(GL_DRAW_BUFFER0 + i);
            drawBuffers[i] = drawBuffer;

            if (drawBuffer == GL_NONE) {
                continue;
            }

            int attachmentIndex = drawBuffer - GL_COLOR_ATTACHMENT0;
            if (attachmentIndex < 0 || attachmentIndex >= maxColorAttachments) {
                if (!loggedUnsupportedFramebuffer) {
                    Logger.warn("Skipping Sable/Voxy depth shim because the active draw buffer is not a color attachment");
                    loggedUnsupportedFramebuffer = true;
                }
                return false;
            }

            int attachment = GL_COLOR_ATTACHMENT0 + attachmentIndex;
            int attachmentType = glGetNamedFramebufferAttachmentParameteri(sourceFramebuffer, attachment, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int attachmentName = glGetNamedFramebufferAttachmentParameteri(sourceFramebuffer, attachment, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if (attachmentName == 0) {
                continue;
            }

            if (attachmentType == GL_TEXTURE) {
                glNamedFramebufferTexture(DRAW_FRAMEBUFFER.id, attachment, attachmentName, 0);
            } else if (attachmentType == GL_RENDERBUFFER) {
                glNamedFramebufferRenderbuffer(DRAW_FRAMEBUFFER.id, attachment, GL_RENDERBUFFER, attachmentName);
            } else {
                if (!loggedUnsupportedFramebuffer) {
                    Logger.warn("Skipping Sable/Voxy depth shim because an active color attachment is unsupported");
                    loggedUnsupportedFramebuffer = true;
                }
                return false;
            }
            hasColorTarget = true;
        }

        if (!hasColorTarget) {
            return false;
        }

        glNamedFramebufferTexture(DRAW_FRAMEBUFFER.id, GL_DEPTH_ATTACHMENT, COMBINED_DEPTH.getDepthTex().id, 0);
        glNamedFramebufferDrawBuffers(DRAW_FRAMEBUFFER.id, drawBuffers);

        int status = glCheckNamedFramebufferStatus(DRAW_FRAMEBUFFER.id, GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            if (!loggedUnsupportedFramebuffer) {
                Logger.warn("Skipping Sable/Voxy depth shim because the temporary framebuffer is incomplete: " + status);
                loggedUnsupportedFramebuffer = true;
            }
            return false;
        }

        return true;
    }

    private static void copyDepth(int sourceDepthTexture, int destinationFramebuffer, int width, int height) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, destinationFramebuffer);
        glViewport(0, 0, width, height);
        glDisable(GL_STENCIL_TEST);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);
        glDepthMask(true);
        glColorMask(false, false, false, false);

        COPY_DEPTH.bind();
        glBindTextureUnit(0, sourceDepthTexture);
        glBindSampler(0, DEPTH_SAMPLER);
        glUniform2f(1, 1.0f, 1.0f);
        COPY_DEPTH.blit();
    }

    private static void mergeVoxyDepth(int voxyDepthTexture, int destinationFramebuffer, int width, int height, Viewport<?> viewport, Matrix4f modelView, Matrix4f projection) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, destinationFramebuffer);
        glViewport(0, 0, width, height);
        glDisable(GL_STENCIL_TEST);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true);
        glColorMask(false, false, false, false);

        TRANSFORM_DEPTH.bind();
        glBindTextureUnit(0, voxyDepthTexture);
        glBindSampler(0, DEPTH_SAMPLER);

        new Matrix4f(viewport.MVP).invert().getToAddress(MATRIX_SCRATCH);
        nglUniformMatrix4fv(1, 1, false, MATRIX_SCRATCH);
        new Matrix4f(projection).mul(modelView).getToAddress(MATRIX_SCRATCH);
        nglUniformMatrix4fv(2, 1, false, MATRIX_SCRATCH);

        TRANSFORM_DEPTH.blit();
    }

    private static void copyChangedDepth(int beforeDepthTexture, int afterDepthTexture, State state) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, state.drawFramebuffer);
        glViewport(state.x, state.y, state.width, state.height);
        glDisable(GL_STENCIL_TEST);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);
        glDepthMask(true);
        glColorMask(false, false, false, false);

        COPY_CHANGED_DEPTH.bind();
        glBindTextureUnit(0, beforeDepthTexture);
        glBindSampler(0, DEPTH_SAMPLER);
        glBindTextureUnit(1, afterDepthTexture);
        glBindSampler(1, DEPTH_SAMPLER);
        COPY_CHANGED_DEPTH.blit();
    }

    private static final class State {
        private final int drawFramebuffer;
        private final int readFramebuffer;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final int currentProgram;
        private final int vertexArray;
        private final int activeTexture;
        private final int texture0;
        private final int texture1;
        private final int sampler0;
        private final int sampler1;
        private final int depthFunc;
        private final int depthMask;
        private final int[] colorMask;
        private final boolean depthTest;
        private final boolean stencilTest;
        private final boolean scissorTest;
        private final int[] scissorBox;

        //Pixel rect the shim confines itself to, derived from the pass's own bounds
        private int scissorX;
        private int scissorY;
        private int scissorWidth;
        private int scissorHeight;

        private int nesting = 1;

        private State(
                int drawFramebuffer,
                int readFramebuffer,
                int x,
                int y,
                int width,
                int height,
                int currentProgram,
                int vertexArray,
                int activeTexture,
                int texture0,
                int texture1,
                int sampler0,
                int sampler1,
                int depthFunc,
                int depthMask,
                int[] colorMask,
                boolean depthTest,
                boolean stencilTest,
                boolean scissorTest,
                int[] scissorBox
        ) {
            this.scissorTest = scissorTest;
            this.scissorBox = scissorBox;
            this.drawFramebuffer = drawFramebuffer;
            this.readFramebuffer = readFramebuffer;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.currentProgram = currentProgram;
            this.vertexArray = vertexArray;
            this.activeTexture = activeTexture;
            this.texture0 = texture0;
            this.texture1 = texture1;
            this.sampler0 = sampler0;
            this.sampler1 = sampler1;
            this.depthFunc = depthFunc;
            this.depthMask = depthMask;
            this.colorMask = colorMask;
            this.depthTest = depthTest;
            this.stencilTest = stencilTest;
        }

        private static State capture() {
            int[] viewport = new int[4];
            int[] colorMask = new int[4];
            int[] scissorBox = new int[4];
            glGetIntegerv(GL_VIEWPORT, viewport);
            glGetIntegerv(GL_COLOR_WRITEMASK, colorMask);
            glGetIntegerv(GL_SCISSOR_BOX, scissorBox);

            int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
            glActiveTexture(GL_TEXTURE0);
            int texture0 = glGetInteger(GL_TEXTURE_BINDING_2D);
            glActiveTexture(GL_TEXTURE1);
            int texture1 = glGetInteger(GL_TEXTURE_BINDING_2D);
            glActiveTexture(activeTexture);

            return new State(
                    glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),
                    glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
                    viewport[0],
                    viewport[1],
                    viewport[2],
                    viewport[3],
                    glGetInteger(GL_CURRENT_PROGRAM),
                    glGetInteger(GL_VERTEX_ARRAY_BINDING),
                    activeTexture,
                    texture0,
                    texture1,
                    glGetIntegeri(GL_SAMPLER_BINDING, 0),
                    glGetIntegeri(GL_SAMPLER_BINDING, 1),
                    glGetInteger(GL_DEPTH_FUNC),
                    glGetInteger(GL_DEPTH_WRITEMASK),
                    colorMask,
                    glIsEnabled(GL_DEPTH_TEST),
                    glIsEnabled(GL_STENCIL_TEST),
                    glIsEnabled(GL_SCISSOR_TEST),
                    scissorBox
            );
        }

        /** @return false when the bounds cover no pixels, meaning there is nothing to bracket */
        private boolean setScissor(float[] ndcBounds) {
            if (ndcBounds == null) {
                return false;
            }
            int x0 = (int) Math.floor((ndcBounds[0] * 0.5f + 0.5f) * this.width);
            int y0 = (int) Math.floor((ndcBounds[1] * 0.5f + 0.5f) * this.height);
            int x1 = (int) Math.ceil((ndcBounds[2] * 0.5f + 0.5f) * this.width);
            int y1 = (int) Math.ceil((ndcBounds[3] * 0.5f + 0.5f) * this.height);
            this.scissorX = Math.max(0, x0 - 1);
            this.scissorY = Math.max(0, y0 - 1);
            this.scissorWidth = Math.min(this.width, x1 + 1) - this.scissorX;
            this.scissorHeight = Math.min(this.height, y1 + 1) - this.scissorY;
            return this.scissorWidth > 0 && this.scissorHeight > 0;
        }

        private void applyScissor() {
            glEnable(GL_SCISSOR_TEST);
            glScissor(this.scissorX, this.scissorY, this.scissorWidth, this.scissorHeight);
        }

        private void restoreForSableRender(int shimFramebuffer) {
            this.restoreMutableState();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, shimFramebuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, this.readFramebuffer);
            glViewport(0, 0, this.width, this.height);
            //restoreMutableState just put the caller's scissor back; the bracketed pass runs under ours
            this.applyScissor();

            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glDepthMask(true);
        }

        private void restoreAll() {
            this.restoreMutableState();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, this.drawFramebuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, this.readFramebuffer);
            glViewport(this.x, this.y, this.width, this.height);
        }

        private void restoreMutableState() {
            glUseProgram(this.currentProgram);
            glBindVertexArray(this.vertexArray);
            glBindTextureUnit(0, this.texture0);
            glBindSampler(0, this.sampler0);
            glBindTextureUnit(1, this.texture1);
            glBindSampler(1, this.sampler1);
            glActiveTexture(this.activeTexture);

            if (this.depthTest) {
                glEnable(GL_DEPTH_TEST);
            } else {
                glDisable(GL_DEPTH_TEST);
            }
            if (this.stencilTest) {
                glEnable(GL_STENCIL_TEST);
            } else {
                glDisable(GL_STENCIL_TEST);
            }
            if (this.scissorTest) {
                glEnable(GL_SCISSOR_TEST);
            } else {
                glDisable(GL_SCISSOR_TEST);
            }
            glScissor(this.scissorBox[0], this.scissorBox[1], this.scissorBox[2], this.scissorBox[3]);

            glDepthFunc(this.depthFunc);
            glDepthMask(this.depthMask != GL_FALSE);
            glColorMask(this.colorMask[0] != GL_FALSE, this.colorMask[1] != GL_FALSE, this.colorMask[2] != GL_FALSE, this.colorMask[3] != GL_FALSE);
        }
    }
}
