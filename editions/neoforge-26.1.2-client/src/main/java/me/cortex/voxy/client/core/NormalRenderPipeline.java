package me.cortex.voxy.client.core;

import me.cortex.voxy.common.util.ExceptionCleanup;

import java.util.List;
import java.util.function.BooleanSupplier;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.LodBoundaryFade;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.util.GPUTiming;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;

public class NormalRenderPipeline extends AbstractRenderPipeline {
   private static final float[] CLEAR_COLOUR = {0.0F, 0.0F, 0.0F, 0.0F};
   private GlTexture colourTex;
   private GlTexture colourSSAOTex;
   private final GlFramebuffer fbSSAO;
   private final FullscreenBlit finalBlit;
   private final SSAO ssao;

   protected NormalRenderPipeline(
      RenderProperties properties,
      AsyncNodeManager nodeManager,
      NodeCleaner nodeCleaner,
      HierarchicalOcclusionTraverser traversal,
      BooleanSupplier frexSupplier
   ) {
      super(properties, nodeManager, nodeCleaner, traversal, frexSupplier, false);
        try {
            this.fbSSAO = new GlFramebuffer();
            this.finalBlit = new FullscreenBlit(
               properties, "voxy:post/blit_texture_depth_cutout.frag", a -> a.define("USE_ENV_FOG").define("EMIT_COLOUR")
            );
            this.ssao = SSAO.createSSAO(properties, VoxyConfig.CONFIG.getSSAOMode());
        } catch (RuntimeException | Error failure) {
            this.releaseResources(new ExceptionCleanup(failure));
            throw failure;
        }
    }

   @Override
   protected int setup(Viewport<?> viewport, int sourceDepthTex, int srcWidth, int srcHeight) {
      if (this.colourTex == null || this.colourTex.getHeight() != viewport.height || this.colourTex.getWidth() != viewport.width) {
         if (this.colourTex != null) {
            this.colourTex.free();
            this.colourSSAOTex.free();
         }

         this.fb.resize(viewport.width, viewport.height);
         this.colourTex = new GlTexture().store(32856, 1, viewport.width, viewport.height);
         this.colourSSAOTex = new GlTexture().store(32856, 1, viewport.width, viewport.height);
         this.fb.framebuffer.bind(36064, this.colourTex).verify();
         this.fbSSAO.bind(this.fb.getDepthAttachmentType(), this.fb.getDepthTex()).bind(36064, this.colourSSAOTex).verify();
         GL45C.glTextureParameterf(this.colourTex.id, 10241, 9728.0F);
         GL45C.glTextureParameterf(this.colourTex.id, 10240, 9728.0F);
         GL45C.glTextureParameterf(this.colourSSAOTex.id, 10241, 9728.0F);
         GL45C.glTextureParameterf(this.colourSSAOTex.id, 10240, 9728.0F);
         GL45C.glTextureParameterf(this.fb.getDepthTex().id, 37098, 6402.0F);
      }

      GL45C.glClearNamedFramebufferfv(this.fb.framebuffer.id, GL11C.GL_COLOR, 0, CLEAR_COLOUR);
      this.initDepthStencil(viewport, sourceDepthTex, this.fb.framebuffer.id,
         srcWidth, srcHeight, viewport.width, viewport.height);
      return this.fb.getDepthTex().id;
   }

   @Override
   protected void postOpaquePreTranslucent(Viewport<?> viewport, int sourceDepthTexture) {
      GPUTiming.INSTANCE.marker("ao");
      this.ssao.computeSSAO(viewport, this.colourSSAOTex, this.colourTex, this.fb.getDepthTex(), sourceDepthTexture);
      GL30C.glBindFramebuffer(36160, this.fbSSAO.id);
   }

   @Override
   protected void finish(Viewport<?> viewport, int sourceDepthTexture, int outputFramebuffer, int srcWidth, int srcHeight) {
      this.finalBlit.bind();
      float fogStart = viewport.fogParameters.environmentalStart();
      float fogEnd = viewport.fogParameters.environmentalEnd();
      boolean requiredFog = Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE
         || fogEnd < 10.0F;
      boolean optionalFog = VoxyConfig.CONFIG.useEnvironmentalFog && VoxyConfig.CONFIG.fogIntensity > 0.0F;
      if (!requiredFog && optionalFog) {
         fogEnd = Math.max(16.0F, VoxyConfig.CONFIG.skyFogDistance * 16.0F
            * (VoxyConfig.CONFIG.fogDistancePercent / 100.0F));
         fogStart = fogEnd * 0.5F;
      }

      float fogRange = Math.abs(fogEnd - fogStart);
      boolean useFog = requiredFog ? fogRange > 1.0E-4F : optionalFog && fogRange > 1.0F;
      if (useFog) {
         GL30C.glUniform2f(4, fogStart, fogEnd);
         GL30C.glUniform4f(5, viewport.fogParameters.red(), viewport.fogParameters.green(),
            viewport.fogParameters.blue(), 1.0F);
         GL30C.glUniform1f(6, requiredFog ? 1.0F : Math.clamp(VoxyConfig.CONFIG.fogIntensity, 0.0F, 1.0F));
         GL30C.glUniform1f(7, requiredFog ? 0.0F : Math.clamp(VoxyConfig.CONFIG.fogDensity, 0.0F, 1.0F));
         GL30C.glUniform1i(8, requiredFog ? 1 : 0);
      } else {
         GL30C.glUniform2f(4, 0.0F, 0.0F);
         GL30C.glUniform4f(5, 0.0F, 0.0F, 0.0F, 0.0F);
         GL30C.glUniform1f(6, 0.0F);
         GL30C.glUniform1f(7, 0.0F);
         GL30C.glUniform1i(8, 0);
      }

      GL45C.glBindTextureUnit(3, this.colourSSAOTex.id);

      GL30C.glEnable(3042);
      GL30C.glBlendFuncSeparate(770, 771, 1, 771);
      boolean circularHandoff = LodBoundaryFade.getDistances().enabled();
      if (circularHandoff) {
         GL30C.glDepthFunc(GL11C.GL_ALWAYS);
      }
      AbstractRenderPipeline.transformBlitDepth(
         this.finalBlit, this.fb.getDepthTex().id, outputFramebuffer, viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView)
      );
      if (circularHandoff) {
         GL30C.glDepthFunc(this.properties.closerEqualDepthCompare());
      }
      GL30C.glDisable(3042);
   }

   @Override
   public void setupAndBindOpaque(Viewport<?> viewport) {
      this.fb.bind();
   }

   @Override
   public void setupAndBindTranslucent(Viewport<?> viewport) {
      GL30C.glBindFramebuffer(36160, this.fbSSAO.id);
   }

   @Override
   public void free() {
        this.assertNotFreed();
        var cleanup = new ExceptionCleanup(null);
        this.releaseResources(cleanup);
        cleanup.rethrow();
    }

    private void releaseResources(ExceptionCleanup cleanup) {
        if (this.ssao != null) cleanup.run(this.ssao::free);
        if (this.finalBlit != null) cleanup.run(this.finalBlit::delete);
        if (this.colourTex != null) cleanup.run(this.colourTex::free);
        if (this.colourSSAOTex != null) cleanup.run(this.colourSSAOTex::free);
        if (this.fbSSAO != null) cleanup.run(this.fbSSAO::free);
        cleanup.run(super::free0);
    }

   @Override
   public void addDebug(List<String> debug) {
      super.addDebug(debug);
      this.ssao.addDebugInfo(debug);
   }
}
