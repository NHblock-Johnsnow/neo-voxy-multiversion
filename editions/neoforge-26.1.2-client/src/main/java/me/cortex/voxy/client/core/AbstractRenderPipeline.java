package me.cortex.voxy.client.core;

import me.cortex.voxy.common.util.ExceptionCleanup;

import java.util.List;
import java.util.function.BooleanSupplier;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.LodBoundaryFade;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.common.util.TrackedObject;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public abstract class AbstractRenderPipeline extends TrackedObject {
   public final RenderProperties properties;
   private final BooleanSupplier frexStillHasWork;
   private final AsyncNodeManager nodeManager;
   private final NodeCleaner nodeCleaner;
   private final HierarchicalOcclusionTraverser traversal;
   protected AbstractSectionRenderer<?, ?> sectionRenderer;
   private final FullscreenBlit depthStencilSetup;
   public final DepthFramebuffer fb;
   private final GlFramebuffer scratchFramebuffer;
   protected final boolean deferTranslucency;
   private static final int DEPTH_SAMPLER = GL42.glGenSamplers();
   private static final long SCRATCH = MemoryUtil.nmemAlloc(64L);
   private static final Matrix4f INVERSE_MVP = new Matrix4f();

   protected AbstractRenderPipeline(
      RenderProperties properties,
      AsyncNodeManager nodeManager,
      NodeCleaner nodeCleaner,
      HierarchicalOcclusionTraverser traversal,
      BooleanSupplier frexSupplier,
      boolean deferTranslucency
   ) {
      this.properties = properties;
      this.frexStillHasWork = frexSupplier;
      this.nodeManager = nodeManager;
      this.nodeCleaner = nodeCleaner;
      this.traversal = traversal;
      this.deferTranslucency = deferTranslucency;
      try {
            this.fb = new DepthFramebuffer(35056);
            this.scratchFramebuffer = new GlFramebuffer();
            this.depthStencilSetup = new FullscreenBlit(properties, "voxy:post/fullscreen2.vert", "voxy:post/setup_stencil_depth.frag");
        } catch (RuntimeException | Error failure) {
            this.releaseBaseResources(new ExceptionCleanup(failure));
            throw failure;
        }
    }

   public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
   }

   public final void setSectionRenderer(AbstractSectionRenderer<?, ?> sectionRenderer) {
      if (this.sectionRenderer != null) {
         throw new IllegalStateException();
      } else {
         this.sectionRenderer = sectionRenderer;
      }
   }

   public void preSetup(Viewport<?> viewport) {
   }

   protected abstract int setup(Viewport<?> var1, int var2, int var3, int var4);

   protected abstract void postOpaquePreTranslucent(Viewport<?> var1, int var2);

   protected void finish(Viewport<?> viewport, int sourceDepthTexture, int outputFramebuffer, int srcWidth, int srcHeight) {
      GL11C.glDisable(2960);
   }

   public void runPipeline(Viewport<?> viewport, int sourceDepthTexture, int sourceColourTexture, int srcWidth, int srcHeight) {
      int depthTexture = this.setup(viewport, sourceDepthTexture, srcWidth, srcHeight);
      AbstractSectionRenderer rs = this.sectionRenderer;
      GPUTiming.INSTANCE.marker("RO");
      rs.renderOpaque(viewport);
      int occlusionDebug = VoxyClient.getOcclusionDebugState();
      if (occlusionDebug == 0) {
         GPUTiming.INSTANCE.marker("I");
         this.innerPrimaryWork(viewport, depthTexture);
         GPUTiming.INSTANCE.marker();
      }

      if (occlusionDebug <= 1) {
         TimingStatistics.G.start();
         rs.buildDrawCalls(viewport);
         TimingStatistics.G.stop();
      }

      GPUTiming.INSTANCE.marker("TP");
      rs.renderTemporal(viewport);
      rs.postOpaquePreperation(viewport);
      this.postOpaquePreTranslucent(viewport, sourceDepthTexture);
      GPUTiming.INSTANCE.marker("RT");
      if (!this.deferTranslucency) {
         rs.renderTranslucent(viewport);
      }

      GPUTiming.INSTANCE.marker();
      this.scratchFramebuffer.bind(36096, sourceDepthTexture).bind(36064, sourceColourTexture);
      this.finish(viewport, sourceDepthTexture, this.scratchFramebuffer.id, srcWidth, srcHeight);
      GL30C.glBindFramebuffer(36160, 0);
   }

   protected void initDepthStencil(Viewport<?> viewport, int sourceDepthTexture, int targetFb,
                                   int srcWidth, int srcHeight, int width, int height) {
      GL45.glClearNamedFramebufferfi(targetFb, 34041, 0, this.properties.clearDepth(), 1);
      GL30C.glBindFramebuffer(36160, targetFb);
      GL11C.glEnable(2929);
      GL42.glDepthFunc(519);
      GL11C.glEnable(2960);
      GL11C.glStencilOp(7680, 7680, 7681);
      GL11C.glStencilFunc(519, 0, 255);
      GL11C.glStencilMask(255);
      this.depthStencilSetup.bind();
      GL45C.glBindTextureUnit(0, sourceDepthTexture);
      GL42.glBindSampler(0, DEPTH_SAMPLER);
      GL42.glUniform2f(1, (float)width / srcWidth, (float)height / srcHeight);

      LodBoundaryFade.Distances boundary = LodBoundaryFade.getDistances();
      INVERSE_MVP.set(viewport.vanillaProjection).mul(viewport.modelView).invert().getToAddress(SCRATCH);
      GL42.nglUniformMatrix4fv(2, 1, false, SCRATCH);
      GL42.glUniform1f(6, boundary.fadeStart());
      GL42.glUniform1f(7, boundary.fadeEnd());
      int cameraBlockX = (int)Math.floor(viewport.cameraX);
      int cameraBlockY = (int)Math.floor(viewport.cameraY);
      int cameraBlockZ = (int)Math.floor(viewport.cameraZ);
      GL42.glUniform3i(8, cameraBlockX, cameraBlockY, cameraBlockZ);
      GL42.glUniform3f(9,
         (float)(viewport.cameraX - cameraBlockX),
         (float)(viewport.cameraY - cameraBlockY),
         (float)(viewport.cameraZ - cameraBlockZ));
      GL42.glUniform1i(10, 0);
      GL42.glDepthMask(true);
      GL11C.glColorMask(false, false, false, false);
      this.depthStencilSetup.blit();

      if (boundary.enabled()) {
         // Preserve conservative vanilla depth only for transition pixels.
         // This prevents a missing/coarser LOD sample from exposing caves.
         GL11C.glStencilMask(0);
         GL42.glUniform1i(10, 1);
         viewport.MVP.getToAddress(SCRATCH);
         GL42.nglUniformMatrix4fv(11, 1, false, SCRATCH);
         this.depthStencilSetup.blit();
         GL42.glUniform1i(10, 0);
         GL11C.glStencilMask(255);
      }

      GL42.glDepthFunc(this.properties.closerEqualDepthCompare());
      GL11C.glColorMask(true, true, true, true);
      GL11C.glStencilOp(7680, 7680, 7680);
      GL11C.glStencilFunc(514, 1, 255);
   }

   protected static void transformBlitDepth(FullscreenBlit blitShader, int srcDepthTex, int dstFB, Viewport<?> viewport, Matrix4f targetTransform) {
      GL11C.glDisable(2960);
      GL30C.glBindFramebuffer(36160, dstFB);
      blitShader.bind();
      GL45C.glBindTextureUnit(0, srcDepthTex);
      new Matrix4f(viewport.MVP).invert().getToAddress(SCRATCH);
      GL42.nglUniformMatrix4fv(1, 1, false, SCRATCH);
      targetTransform.getToAddress(SCRATCH);
      GL42.nglUniformMatrix4fv(2, 1, false, SCRATCH);
      GL11C.glEnable(2929);
      blitShader.blit();
      GL11C.glDisable(2960);
      GL11C.glDisable(2929);
   }

   protected void innerPrimaryWork(Viewport<?> viewport, int depthBuffer) {
      viewport.hiZBuffer.buildMipChain(depthBuffer, viewport.width, viewport.height);

      do {
         TimingStatistics.main.stop();
         TimingStatistics.dynamic.start();
         TimingStatistics.D.start();
         DownloadStream.INSTANCE.tick();
         TimingStatistics.D.stop();
         this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner);
         this.nodeCleaner.tick(this.traversal.getNodeBuffer());
         TimingStatistics.dynamic.stop();
         TimingStatistics.main.start();
         GL42.glMemoryBarrier(1152);
         TimingStatistics.F.start();
         this.traversal.doTraversal(viewport);
         TimingStatistics.F.stop();
      } while (this.frexStillHasWork.getAsBoolean());
   }

   @Override
   protected void free0() {
        var cleanup = new ExceptionCleanup(null);
        this.releaseBaseResources(cleanup);
        cleanup.rethrow();
    }

    private void releaseBaseResources(ExceptionCleanup cleanup) {
        if (this.sectionRenderer != null) cleanup.run(this.sectionRenderer::free);
        if (this.depthStencilSetup != null) cleanup.run(this.depthStencilSetup::delete);
        if (this.scratchFramebuffer != null) cleanup.run(this.scratchFramebuffer::free);
        if (this.fb != null) cleanup.run(this.fb::free);
        cleanup.run(super::free0);
    }

   public void addDebug(List<String> debug) {
      this.sectionRenderer.addDebug(debug);
      this.traversal.addDebug(debug);
      RenderStatistics.addDebug(debug);
   }

   public abstract void setupAndBindOpaque(Viewport<?> var1);

   public abstract void setupAndBindTranslucent(Viewport<?> var1);

   public void bindUniforms() {
      this.bindUniforms(-1);
   }

   public void bindUniforms(int index) {
   }

   public boolean hasTAA() {
      return false;
   }

   public String taaFunction(String functionName) {
      return this.taaFunction(-1, functionName);
   }

   public String taaFunction(int uboBindingPoint, String functionName) {
      return null;
   }

   public String patchOpaqueShader(AbstractSectionRenderer<?, ?> renderer, String input) {
      return null;
   }

   public String patchTranslucentShader(AbstractSectionRenderer<?, ?> renderer, String input) {
      return null;
   }

   public float[] getRenderScalingFactor() {
      return null;
   }

   static {
      GL42.glSamplerParameteri(DEPTH_SAMPLER, 10240, 9728);
      GL42.glSamplerParameteri(DEPTH_SAMPLER, 10241, 9728);
   }
}
