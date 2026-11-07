package me.cortex.voxy.client.core;

import me.cortex.voxy.common.util.ExceptionCleanup;

import java.util.List;
import java.util.function.BooleanSupplier;
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
import org.lwjgl.opengl.GL45C;

public class IrisVoxyRenderPipeline extends AbstractRenderPipeline {
   private static final int UNIFORM_BINDING_POINT = 7;
   private static final int BASE_BUFFER_BINDING_INDEX = 10;
   private static final int BASE_SAMPLER_BINDING_INDEX = 6;
   private final IrisVoxyRenderPipelineData data;
   private final FullscreenBlit depthBlit;
   public final DepthFramebuffer fbTranslucent;
   private final FullscreenBlit shaderDepthHackFixTransformBlit;
   private final GlBuffer shaderUniforms;

   public IrisVoxyRenderPipeline(
      RenderProperties properties,
      IrisVoxyRenderPipelineData data,
      AsyncNodeManager nodeManager,
      NodeCleaner nodeCleaner,
      HierarchicalOcclusionTraverser traversal,
      BooleanSupplier frexSupplier
   ) {
      super(properties, nodeManager, nodeCleaner, traversal, frexSupplier, data.shouldDeferTranslucency());
        try {
            this.fbTranslucent = new DepthFramebuffer(this.fb.getFormat());
            this.data = data;
            if (this.data.thePipeline != null) {
               throw new IllegalStateException("Pipeline data already bound");
            } else {
               this.data.thePipeline = this;
               int[] oDT = this.data.opaqueDrawTargets;
               int[] binding = new int[oDT.length];

               for (int i = 0; i < oDT.length; i++) {
                  binding[i] = 36064 + i;
                  GL45C.glNamedFramebufferTexture(this.fb.framebuffer.id, 36064 + i, oDT[i], 0);
               }

               GL45C.glNamedFramebufferDrawBuffers(this.fb.framebuffer.id, binding);
               int[] tDT = this.data.translucentDrawTargets;
               binding = new int[tDT.length];

               for (int i = 0; i < tDT.length; i++) {
                  binding[i] = 36064 + i;
                  GL45C.glNamedFramebufferTexture(this.fbTranslucent.framebuffer.id, 36064 + i, tDT[i], 0);
               }

               GL45C.glNamedFramebufferDrawBuffers(this.fbTranslucent.framebuffer.id, binding);
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
            }
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
         long ptr = UploadStream.INSTANCE.uploadTo(this.shaderUniforms);
         this.data.getUniforms().updater().accept(ptr);
         UploadStream.INSTANCE.commit();
      }
   }

   @Override
   protected int setup(Viewport<?> viewport, int sourceDepthTexture, int srcWidth, int srcHeight) {
      this.fb.resize(viewport.width, viewport.height);
      this.fbTranslucent.resize(viewport.width, viewport.height);
      if (!this.data.useViewportDims) {
         srcWidth = viewport.width;
         srcHeight = viewport.height;
      }

      this.initDepthStencil(viewport, sourceDepthTexture, this.fb.framebuffer.id,
         srcWidth, srcHeight, viewport.width, viewport.height);
      return this.fb.getDepthTex().id;
   }

   @Override
   protected void postOpaquePreTranslucent(Viewport<?> viewport, int sourceDepthTexture) {
      if (this.shaderDepthHackFixTransformBlit != null) {
         this.fb.bind();
         GL45C.glEnable(2929);
         GL45C.glColorMask(false, false, false, false);
         GL45C.glDepthFunc(519);
         GL45C.glStencilFunc(514, 0, 255);
         this.shaderDepthHackFixTransformBlit.blit();
         GL45C.glStencilFunc(514, 1, 255);
         GL45C.glDepthFunc(this.properties.closerEqualDepthCompare());
         GL45C.glColorMask(true, true, true, true);
      }

      GL45C.glTextureBarrier();
      int msk = 1280;
      GL45C.glBlitNamedFramebuffer(
         this.fb.framebuffer.id, this.fbTranslucent.framebuffer.id, 0, 0, viewport.width, viewport.height, 0, 0, viewport.width, viewport.height, msk, 9728
      );
   }

   @Override
   protected void finish(Viewport<?> viewport, int sourceDepthTexture, int outputFramebuffer, int srcWidth, int srcHeight) {
      if (this.data.renderToVanillaDepth) {
         boolean mustFiddledViewport = srcWidth != viewport.width || srcHeight != viewport.height;
         if (this.data.useViewportDims || !mustFiddledViewport) {
            GL45C.glColorMask(false, false, false, false);
            if (mustFiddledViewport) {
               GL45C.glViewport(0, 0, viewport.width, viewport.height);
            }

            AbstractRenderPipeline.transformBlitDepth(
               this.depthBlit,
               this.fbTranslucent.getDepthTex().id,
               outputFramebuffer,
               viewport,
               new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView)
            );
            if (mustFiddledViewport) {
               GL45C.glViewport(0, 0, srcWidth, srcHeight);
            }

            GL45C.glColorMask(true, true, true, true);
         }
      } else {
         GL45C.glDisable(2960);
         GL45C.glDisable(2929);
      }
   }

   @Override
   public void bindUniforms() {
      this.bindUniforms(7);
   }

   @Override
   public void bindUniforms(int bindingPoint) {
      if (this.shaderUniforms != null) {
         GL30.glBindBufferBase(35345, bindingPoint, this.shaderUniforms.id);
      }
   }

   private void doBindings() {
      this.bindUniforms();
      if (this.data.getSsboSet() != null) {
         this.data.getSsboSet().bindingFunction().accept(10);
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

   private StringBuilder buildGenericShaderHeader(AbstractSectionRenderer<?, ?> renderer, String input) {
      StringBuilder builder = new StringBuilder(input).append("\n\n\n");
      if (this.data.getUniforms() != null) {
         builder.append("layout(binding = 7, std140) uniform ShaderUniformBindings ").append(this.data.getUniforms().layout()).append(";\n\n");
      }

      if (this.data.getSsboSet() != null) {
         builder.append("#define BUFFER_BINDING_INDEX_BASE 10\n");
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
      StringBuilder builder = this.buildGenericShaderHeader(renderer, input);
      builder.append(this.data.opaqueFragPatch());
      return builder.toString();
   }

   @Override
   public String patchTranslucentShader(AbstractSectionRenderer<?, ?> renderer, String input) {
      if (this.data.translucentFragPatch() == null) {
         return null;
      } else {
         StringBuilder builder = this.buildGenericShaderHeader(renderer, input);
         builder.append(this.data.translucentFragPatch());
         return builder.toString();
      }
   }

   @Override
   public boolean hasTAA() {
      return this.data.TAA != null;
   }

   @Override
   public String taaFunction(String functionName) {
      return this.taaFunction(7, functionName);
   }

   @Override
   public String taaFunction(int uboBindingPoint, String functionName) {
      if (this.data.TAA == null) {
         return null;
      } else {
         StringBuilder builder = new StringBuilder();
         if (this.data.getUniforms() != null) {
            builder.append("layout(binding = " + uboBindingPoint + ", std140) uniform ShaderUniformBindings ")
               .append(this.data.getUniforms().layout())
               .append(";\n\n");
         }

         builder.append("vec2 ").append(functionName).append("()\n");
         builder.append(this.data.TAA);
         builder.append("\n");
         return builder.toString();
      }
   }

   @Override
   public float[] getRenderScalingFactor() {
      return this.data.resolutionScale;
   }
}
