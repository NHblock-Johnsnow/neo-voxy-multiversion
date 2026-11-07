package me.cortex.voxy.client.core;

import me.cortex.voxy.common.util.ExceptionCleanup;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL45C.*;

public class IrisVoxyRenderPipeline extends AbstractRenderPipeline {
    private final IrisVoxyRenderPipelineData data;
    private final FullscreenBlit depthBlit;
    public final DepthFramebuffer fbTranslucent;

    private final FullscreenBlit shaderDepthHackFixTransformBlit;

    private final GlBuffer shaderUniforms;
    private final Matrix4f targetTransform = new Matrix4f();

    public IrisVoxyRenderPipeline(RenderProperties properties, IrisVoxyRenderPipelineData data, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(properties, nodeManager, nodeCleaner, traversal, frexSupplier, data.shouldDeferTranslucency());
        try {
            this.fbTranslucent = new DepthFramebuffer(this.fb.getFormat());
            this.data = data;
            if (this.data.thePipeline != null) {
                throw new IllegalStateException("Pipeline data already bound");
            }
            this.data.thePipeline = this;

            //Bind the drawbuffers
            var oDT = this.data.opaqueDrawTargets;
            Logger.info("Iris LOD framebuffer: " + oDT.length + " opaque draw targets, "
                    + this.data.translucentDrawTargets.length + " translucent");
            int[] binding = new int[oDT.length];
            for (int i = 0; i < oDT.length; i++) {
                binding[i] = GL30.GL_COLOR_ATTACHMENT0+i;
                glNamedFramebufferTexture(this.fb.framebuffer.id, GL30.GL_COLOR_ATTACHMENT0+i, oDT[i], 0);
            }
            glNamedFramebufferDrawBuffers(this.fb.framebuffer.id, binding);

            var tDT = this.data.translucentDrawTargets;
            binding = new int[tDT.length];
            for (int i = 0; i < tDT.length; i++) {
                binding[i] = GL30.GL_COLOR_ATTACHMENT0+i;
                glNamedFramebufferTexture(this.fbTranslucent.framebuffer.id, GL30.GL_COLOR_ATTACHMENT0+i, tDT[i], 0);
            }
            glNamedFramebufferDrawBuffers(this.fbTranslucent.framebuffer.id, binding);

            this.fb.framebuffer.verify();
            this.fbTranslucent.framebuffer.verify();

            if (data.getUniforms() != null) {
                this.shaderUniforms = new GlBuffer(data.getUniforms().size());
            } else {
                this.shaderUniforms = null;
            }

            if (!this.data.skipShaderDepthHackFix) {
                this.shaderDepthHackFixTransformBlit = new FullscreenBlit(properties, "voxy:post/fullscreen2.vert", "voxy:post/noop.frag");
            } else {
                this.shaderDepthHackFixTransformBlit = null;
            }

            this.depthBlit = new FullscreenBlit(properties, "voxy:post/blit_texture_depth_cutout.frag");
        } catch (RuntimeException | Error failure) {
            this.releaseResources(new ExceptionCleanup(failure));
            throw failure;
        }
    }

    @Override
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
        modelService.factory.setCustomBlockStateMapping(WorldRenderingSettings.INSTANCE.getBlockStateIds());
    }

    @Override
    public void free() {
        this.assertNotFreed();
        var cleanup = new ExceptionCleanup(null);
        this.releaseResources(cleanup);
        cleanup.rethrow();
    }

    private void releaseResources(ExceptionCleanup cleanup) {
        if (this.data != null && this.data.thePipeline == this) this.data.thePipeline = null;
        if (this.depthBlit != null) cleanup.run(this.depthBlit::delete);
        if (this.shaderDepthHackFixTransformBlit != null) cleanup.run(this.shaderDepthHackFixTransformBlit::delete);
        if (this.shaderUniforms != null) cleanup.run(this.shaderUniforms::free);
        if (this.fbTranslucent != null) cleanup.run(this.fbTranslucent::free);
        cleanup.run(super::free0);
    }

    @Override
    public void preSetup(Viewport<?> viewport) {
        super.preSetup(viewport);
        if (this.shaderUniforms != null) {
            //Update the uniforms
            long ptr = UploadStream.INSTANCE.uploadTo(this.shaderUniforms);
            this.data.getUniforms().updater().accept(ptr);
            UploadStream.INSTANCE.commit();
        }
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceFramebuffer, int srcWidth, int srcHeight) {
        this.fb.resize(viewport.width, viewport.height);
        this.fbTranslucent.resize(viewport.width, viewport.height);

        if (!this.data.useViewportDims) {
            srcWidth = viewport.width;
            srcHeight = viewport.height;
        }
        this.initDepthStencil(viewport, sourceFramebuffer, this.fb.framebuffer.id, srcWidth, srcHeight, viewport.width, viewport.height);
        return this.fb.getDepthTex().id;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport, int sourceFrameBuffer) {
        if (this.shaderDepthHackFixTransformBlit != null) {
            this.fb.bind();
            glEnable(GL_DEPTH_TEST);
            glColorMask(false, false, false, false);
            glDepthFunc(GL_ALWAYS);
            glStencilFunc(GL_EQUAL, 0, 0xFF);//set the depth to 1 where the mask is 0 (hook-tagged pixels keep theirs)
            this.shaderDepthHackFixTransformBlit.blit();
            glStencilFunc(GL_EQUAL, 1, 0x1);//revert to the bit0 contract test
            glDepthFunc(this.properties.closerEqualDepthCompare());
            glColorMask(true, true, true, true);
        } else {
            //pixels; the setup pass stamped reprojected depth there for the hook geometry, so
            //restore the value they expect
            this.fb.bind();
            this.restoreSentinelDepth();
        }

        glTextureBarrier();

        int msk = GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT;
        glBlitNamedFramebuffer(this.fb.framebuffer.id, this.fbTranslucent.framebuffer.id, 0,0, viewport.width, viewport.height, 0,0, viewport.width, viewport.height, msk, GL_NEAREST);
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        // Iris owns the source depth buffer unless the shader pack explicitly
        // opts in to distant-horizon depth. Writing Voxy's opaque depth into it
        // unconditionally makes several packs reject/overwrite the later LOD
        // water composite.
        if (this.data.renderToVanillaDepth && srcWidth == viewport.width  && srcHeight == viewport.height) {//We can only depthblit out if destination size is the same
            glColorMask(false, false, false, false);
            AbstractRenderPipeline.transformBlitDepth(this.depthBlit,
                    this.fbTranslucent.getDepthTex().id, sourceFrameBuffer,
                    viewport, this.targetTransform.set(viewport.vanillaProjection).mul(viewport.modelView));
            glColorMask(true, true, true, true);
        } else {
            if (net.neoforged.fml.ModList.get().isLoaded("create")) {
                me.cortex.voxy.client.compat.create.DistantTrainRenderer.replayDepthToSource(
                        this, viewport, this.fb.getDepthTex().id, sourceFrameBuffer,
                        srcWidth, srcHeight, this.properties.closerEqualDepthCompare());
            }
            // normally disabled by AbstractRenderPipeline but since we are skipping it we do it here
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
        }
    }


    @Override
    public void bindUniforms() {
        this.bindUniforms(UNIFORM_BINDING_POINT);
    }

    @Override
    public void bindUniforms(int bindingPoint) {
        if (this.shaderUniforms != null) {
            GL30.glBindBufferBase(GL_UNIFORM_BUFFER, bindingPoint, this.shaderUniforms.id);
        }
    }

    private void doBindings() {
        this.bindUniforms();
        if (this.data.getSsboSet() != null) {
            this.data.getSsboSet().bindingFunction().accept(FORWARDED_SSBO_BINDING_BASE);
        }
        if (this.data.getImageSet() != null) {
            this.data.getImageSet().bindingFunction().accept(6);
        }
    }
    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        this.fb.bind();
        this.doBindings();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        this.fbTranslucent.bind();
        this.doBindings();
        if (this.data.getBlender() != null) {
            this.data.getBlender().run();
        }
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Using: " + this.getClass().getSimpleName());
        super.addDebug(debug);
    }

    @Override
    public int getSableOcclusionDepthTexture() {
        if (this.data.renderToVanillaDepth || this.fbTranslucent.getDepthTex() == null) {
            return 0;
        }
        return this.fbTranslucent.getDepthTex().id;
    }

    private static final int UNIFORM_BINDING_POINT = 7;
    private static final int FORWARDED_SSBO_BINDING_BASE = 6;

    private StringBuilder buildGenericShaderHeader(AbstractSectionRenderer<?, ?> renderer, String input) {
        StringBuilder builder = new StringBuilder(input).append("\n\n\n");

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = "+UNIFORM_BINDING_POINT+", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        if (this.data.getSsboSet() != null) {
            builder.append("#define BUFFER_BINDING_INDEX_BASE ").append(FORWARDED_SSBO_BINDING_BASE).append("\n");
            builder.append(this.data.getSsboSet().layout()).append("\n\n");
        }

        if (this.data.getImageSet() != null) {
            builder.append("#define BASE_SAMPLER_BINDING_INDEX 6\n");
            builder.append(this.data.getImageSet().layout()).append("\n\n");
        }

        return builder.append("\n\n");
    }



    @Override
    public String patchOpaqueShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        var builder = this.buildGenericShaderHeader(renderer, input);

        builder.append(this.data.opaqueFragPatch());

        return builder.toString();
    }

    @Override
    public String patchTranslucentShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        if (this.data.translucentFragPatch() == null) return null;

        var builder = this.buildGenericShaderHeader(renderer, input);
        builder.append(this.data.translucentFragPatch());
        return builder.toString();
    }

    @Override
    public boolean hasTAA() {
        return this.data.TAA != null;
    }

    @Override
    public String taaFunction(String functionName) {
        return this.taaFunction(UNIFORM_BINDING_POINT, functionName);
    }

    @Override
    public String taaFunction(int uboBindingPoint, String functionName) {
        if (this.data.TAA == null) {
            return null;
        }

        var builder = new StringBuilder();

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = "+uboBindingPoint+", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        builder.append("vec2 ").append(functionName).append("()\n");
        builder.append(this.data.TAA);
        builder.append("\n");
        return builder.toString();
    }

    @Override
    public float[] getRenderScalingFactor() {
        return this.data.resolutionScale;
    }

    @Override
    public boolean useDynamicFarPlane() {
        return this.data.useDynamicFarPlane;
    }


    @Override
    protected boolean useBoundaryGuardPass() {
        return false;
    }
}
