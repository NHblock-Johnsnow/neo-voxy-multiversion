package me.cortex.voxy.client.core;

import me.cortex.voxy.common.util.ExceptionCleanup;

import com.mojang.blaze3d.opengl.GlStateManager;
import java.util.Arrays;
import java.util.List;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.bounding.BoundRenderer;
import me.cortex.voxy.client.core.rendering.bounding.ColumnStreamedBoundStore;
import me.cortex.voxy.client.core.rendering.bounding.IBoundStore;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.IUsesMeshlets;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33;

public class VoxyRenderSystem {
   private static final long[] MODEL_BAKE_BUDGET_LOW_FPS = {75000L, 150000L, 250000L, 450000L, 900000L};
   private static final long[] MODEL_BAKE_BUDGET_BUSY = {150000L, 300000L, 500000L, 750000L, 1200000L};
   private static final long[] MODEL_BAKE_BUDGET_IDLE = {300000L, 550000L, 900000L, 1350000L, 2000000L};
   private static final int[] TOP_LEVEL_NODE_PROCESS_RATE = {4, 8, 12, 24, 40};
   private final WorldEngine worldIn;
   private final ModelBakerySubsystem modelService;
   private final RenderGenerationService renderGen;
   private final IGeometryData geometryData;
   private final AsyncNodeManager nodeManager;
   private final NodeCleaner nodeCleaner;
   private final HierarchicalOcclusionTraverser traversal;
   private final RenderDistanceTracker renderDistanceTracker;
   private final BoundRenderer boundOutlineRenderer;
   public final StreamedBoundStore visbleSectionStream;
   @Nullable
   private ColumnStreamedBoundStore columnStreamedBoundStore;
   private final ViewportSelector<?> viewportSelector;
   private final AbstractRenderPipeline pipeline;
   private final RenderProperties properties;

   private static AbstractSectionRenderer.Factory<?, ? extends IGeometryData> getRenderBackendFactory() {
      return MDICSectionRenderer.FACTORY;
   }

   public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
      this.worldIn = world;
      Logger.info("Creating Voxy render system");
      System.gc();
      if ((Integer)Minecraft.getInstance().options.renderDistance().get() < 3) {
         String msg = "Voxy: Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more";
         Logger.warn(msg);
         Minecraft.getInstance().getChatListener().handleSystemMessage(Component.literal(msg), false);
      }

      int[] oldBufferBindings = new int[10];

      for (int i = 0; i < oldBufferBindings.length; i++) {
         oldBufferBindings[i] = GL30C.glGetIntegeri(37075, i);
      }

      GlBuffer borrowedGeometry = null;
        world.acquireRef();
        try {
            this.visbleSectionStream = new StreamedBoundStore();
         GL30C.glFinish();
         GL30C.glFinish();

         this.properties = RenderProperties.getRenderProperties();
         AbstractSectionRenderer.Factory<? extends Viewport<?>, ?> backendFactory = (AbstractSectionRenderer.Factory<? extends Viewport<?>, ?>)getRenderBackendFactory();
         this.modelService = new ModelBakerySubsystem(world.getMapper());
         this.renderGen = new RenderGenerationService(world, this.modelService, sm, IUsesMeshlets.class.isAssignableFrom(backendFactory.clz()));
         borrowedGeometry = RenderResourceReuse.getOrCreateGeometryBuffer();
                this.geometryData = new BasicSectionGeometryData(1048576, borrowedGeometry);
         this.nodeManager = new AsyncNodeManager(2097152, this.geometryData, this.renderGen);
         this.nodeCleaner = new NodeCleaner(this.nodeManager);
         this.traversal = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);
         world.setDirtyCallback(this.nodeManager::worldEvent);
         Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
         world.getMapper().setBiomeCallback(this.modelService::addBiome);
         this.nodeManager.start();
         this.pipeline = RenderPipelineFactory.createPipeline(this.properties, this.nodeManager, this.nodeCleaner, this.traversal, this::frexStillHasWork);
         this.pipeline.setupExtraModelBakeryData(this.modelService);
         this.traversal.lateStageCompile(this.pipeline);
         AbstractSectionRenderer<? extends Viewport<?>, ?> sectionRenderer = (AbstractSectionRenderer<? extends Viewport<?>, ?>)backendFactory.create(
            this.pipeline, this.modelService.getStore(), this.geometryData
         );
         this.pipeline.setSectionRenderer(sectionRenderer);
         this.viewportSelector = new ViewportSelector<>(sectionRenderer::createViewport);
         int minSec = Minecraft.getInstance().level.getMinSectionY() >> 5;
         int maxSec = Minecraft.getInstance().level.getMaxSectionY() - 1 >> 5;
         this.renderDistanceTracker = new RenderDistanceTracker(this.getTopLevelNodeProcessRate(), minSec, maxSec, this.nodeManager::addTopLevel, this.nodeManager::removeTopLevel);
         this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
         this.boundOutlineRenderer = new BoundRenderer(this.pipeline);
         Logger.info(
            "Voxy render system created with "
               + this.geometryData.getMaxCapacity()
               + " geometry capacity, using pipeline '"
               + this.pipeline.getClass().getSimpleName()
               + "' with renderer '"
               + sectionRenderer.getClass().getSimpleName()
               + "'"
         );

            this.restoreConstructorBindings(oldBufferBindings);
        } catch (RuntimeException | Error failure) {
            var cleanup = new ExceptionCleanup(failure);
            this.releaseResources(cleanup, borrowedGeometry);
            cleanup.run(() -> this.restoreConstructorBindings(oldBufferBindings));
            throw failure;
        }
    }

    private void restoreConstructorBindings(int[] bindings) {
        for (int i = 0; i < bindings.length; i++) {
           GL30C.glBindBufferBase(37074, i, bindings[i]);
        }

        for (int i = 0; i < 12; i++) {
           GlStateManager._activeTexture(33984 + i);
           GlStateManager._bindTexture(0);
           GL33.glBindSampler(i, 0);
        }
    }

    public Viewport<?> setupViewport(
      Matrix4fc vanillaProjection, Matrix4fc modelView, FogParameters fogParameters, int width, int height, double cameraX, double cameraY, double cameraZ
   ) {
      Viewport<? extends Viewport<?>> viewport = (Viewport<? extends Viewport<?>>)this.getViewport();
      if (viewport == null) {
         return null;
      } else {
         Matrix4f voxyProjection = computeProjectionMat(this.properties, vanillaProjection);
         float[] factor = this.pipeline.getRenderScalingFactor();
         if (factor != null) {
            width = (int)(width * factor[0]);
            height = (int)(height * factor[1]);
         }

         if (width != 0 && height != 0) {
            viewport.setVanillaProjection(vanillaProjection)
               .setProjection(voxyProjection)
               .setModelView(new Matrix4f(modelView))
               .setCamera(cameraX, cameraY, cameraZ)
               .setScreenSize(width, height)
               .setFogParameters(fogParameters)
               .update();
            if (VoxyClient.getOcclusionDebugState() == 0) {
               viewport.frameId++;
            }

            return viewport;
         } else {
            Logger.error("Viewport width or height was zero, this is bad bad bad");
            return null;
         }
      }
   }

   public void renderOpaque(Viewport<?> viewport, int sourceDepthTexture, int sourceColourTexture) {
      if (viewport != null) {
         if (viewport.width <= 0 || viewport.height <= 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad, exiting frame");
         } else if (sourceDepthTexture == 0) {
            throw new IllegalStateException("Source depth texture cannot be 0");
         } else {
            TimingStatistics.resetSamplers();
            TimingStatistics.all.start();
            GPUTiming.INSTANCE.marker();
            TimingStatistics.main.start();
            int[] oldBufferBindings = new int[10];

            for (int i = 0; i < oldBufferBindings.length; i++) {
               oldBufferBindings[i] = GL30C.glGetIntegeri(37075, i);
            }

            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(this.properties.closerEqualDepthCompare());
            GlStateManager._depthMask(true);
            GlStateManager._disablePolygonOffset();
            int oldFB = GL11.glGetInteger(36006);
            int[] dims = new int[4];
            GL11.glGetIntegerv(2978, dims);
            GL30C.glViewport(0, 0, viewport.width, viewport.height);
            int scrWidth = ARBDirectStateAccess.glGetTextureLevelParameteri(sourceDepthTexture, 0, 4096);
            int scrHeight = ARBDirectStateAccess.glGetTextureLevelParameteri(sourceDepthTexture, 0, 4097);
            this.pipeline.preSetup(viewport);
            TimingStatistics.E.start();
            if (!VoxyClient.disableSodiumChunkRender() && !IrisUtil.irisShadowActive()) {
               if (VoxyClient.isFrexActive() != (this.columnStreamedBoundStore != null)) {
                  if (this.columnStreamedBoundStore == null) {
                     this.columnStreamedBoundStore = new ColumnStreamedBoundStore();
                  } else {
                     this.columnStreamedBoundStore.free();
                     this.columnStreamedBoundStore = null;
                  }
               }

               this.boundOutlineRenderer
                  .render(viewport, (IBoundStore)(this.columnStreamedBoundStore == null ? this.visbleSectionStream : this.columnStreamedBoundStore));
            } else {
               viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
            }

            TimingStatistics.E.stop();
            GPUTiming.INSTANCE.marker();
            this.modelService.drainBlendPalette();
            this.pipeline.runPipeline(viewport, sourceDepthTexture, sourceColourTexture, scrWidth, scrHeight);
            GPUTiming.INSTANCE.marker();
            TimingStatistics.main.stop();
            TimingStatistics.postDynamic.start();
            PrintfDebugUtil.tick();
            UploadStream.INSTANCE.tick();

            this.renderDistanceTracker.setProcessRate(this.getTopLevelNodeProcessRate());
            while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ) && VoxyClient.isFrexActive()) {
            }

            TimingStatistics.H.start();

            do {
               this.modelService.tick(this.getModelBakeBudgetNanos());
            } while (VoxyClient.isFrexActive() && !this.modelService.areQueuesEmpty());

            TimingStatistics.H.stop();
            GPUTiming.INSTANCE.marker();
            TimingStatistics.postDynamic.stop();
            GPUTiming.INSTANCE.tick();
            GL30C.glBindFramebuffer(36160, oldFB);
            GL30C.glViewport(dims[0], dims[1], dims[2], dims[3]);
            GlStateManager._glUseProgram(0);
            GL30C.glUseProgram(0);
            GlStateManager._enableDepthTest();
            GL30C.glEnable(2929);
            GL30C.glDisable(2960);
            GlStateManager._glBindVertexArray(0);
            GL30C.glBindVertexArray(0);
            GlStateManager._activeTexture(33985);

            for (int i = 0; i < 12; i++) {
               GlStateManager._activeTexture(33984 + i);
               GlStateManager._bindTexture(0);
               GL33.glBindSampler(i, 0);
            }

            IrisUtil.clearIrisSamplers();

            for (int i = 0; i < oldBufferBindings.length; i++) {
               GL30C.glBindBufferBase(37074, i, oldBufferBindings[i]);
            }

            GL30C.glBlendEquation(32774);
            GlStateManager._blendFuncSeparate(0, 0, 0, 0);
            GL30C.glBlendFunc(0, 0);
            GlStateManager._disableBlend();
            GL30C.glDisable(3042);
            GlStateManager._depthFunc(513);
            GL30C.glDepthFunc(513);
            TimingStatistics.all.stop();
         }
      }
   }

   private void autoBalanceSubDivSize() {
      boolean canDecreaseSize = this.renderGen.getTaskCount() < 300;
      int MIN_FPS = 55;
      int MAX_FPS = 65;
      float INCREASE_PER_SECOND = 60.0F;
      float DECREASE_PER_SECOND = 30.0F;
      if (Minecraft.getInstance().getFps() < MIN_FPS) {
         VoxyConfig.CONFIG.subDivisionSize = Math.min(
            VoxyConfig.CONFIG.subDivisionSize + INCREASE_PER_SECOND / Math.max(1.0F, (float)Minecraft.getInstance().getFps()), VoxyConfig.MAX_SUBDIVISION_SIZE
         );
      }

      if (MAX_FPS < Minecraft.getInstance().getFps() && canDecreaseSize) {
         VoxyConfig.CONFIG.subDivisionSize = Math.max(
            VoxyConfig.CONFIG.subDivisionSize - DECREASE_PER_SECOND / Math.max(1.0F, (float)Minecraft.getInstance().getFps()), VoxyConfig.MIN_SUBDIVISION_SIZE
         );
      }
   }

   public static float getRenderDistance() {
      return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
   }

   private static Matrix4f computeProjectionMat(RenderProperties properties, Matrix4fc base) {
      Matrix4f rawMCProj = Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
      Matrix4f extraProjection = rawMCProj.invert(new Matrix4f()).mul(base);
      float near = getRenderDistance() <= 32.0F ? 8.0F : 16.0F;
      near = VoxyClient.disableSodiumChunkRender() ? 0.1F : near;
      float far = 48000.0F;
      if (properties.isReverseZ()) {
         float tmp = near;
         near = far;
         far = tmp;
      }

      return extraProjection.mulLocal(
         new Matrix4f(rawMCProj)
            .m22((properties.isZero2One() ? far : far + near) / (near - far))
            .m32((properties.isZero2One() ? far : far + far) * near / (near - far))
      );
   }

   private boolean frexStillHasWork() {
      if (!VoxyClient.isFrexActive()) {
         return false;
      } else {
         UploadStream.INSTANCE.tick();
         this.modelService.tick(100000000L);
         GL11.glFinish();
         return this.nodeManager.hasWork() || this.renderGen.getTaskCount() != 0 || !this.modelService.areQueuesEmpty();
      }
   }

   public void setRenderDistance(float renderDistance) {
      this.renderDistanceTracker.setRenderDistance((int)Math.ceil(renderDistance + 1.0F));
   }

   private long getModelBakeBudgetNanos() {
      int pressure = VoxyConfig.CONFIG.getRenderPressureLevel();
      int fps = Minecraft.getInstance().getFps();
      if (fps <= 0) fps = 60;
      int renderTasks = this.renderGen.getTaskCount();
      if (fps < 40 || renderTasks > 1000) return MODEL_BAKE_BUDGET_LOW_FPS[pressure];
      if (fps < 55 || renderTasks > 400) return MODEL_BAKE_BUDGET_BUSY[pressure];
      return MODEL_BAKE_BUDGET_IDLE[pressure];
   }

   private int getTopLevelNodeProcessRate() {
      return TOP_LEVEL_NODE_PROCESS_RATE[VoxyConfig.CONFIG.getRenderPressureLevel()];
   }

   public Viewport<?> getViewport() {
      return IrisUtil.irisShadowActive() ? null : this.viewportSelector.getViewport();
   }

   public void addDebugInfo(List<String> debug) {
      debug.add(
         "Buf/Tex [#/Mb]: ["
            + GlBuffer.getCount()
            + "/"
            + GlBuffer.getTotalSize() / 1000000L
            + "],["
            + GlTexture.getCount()
            + "/"
            + GlTexture.getEstimatedTotalSize() / 1000000L
            + "]"
      );
      this.modelService.addDebugData(debug);
      this.renderGen.addDebugData(debug);
      this.nodeManager.addDebug(debug);
      this.pipeline.addDebug(debug);
      TimingStatistics.update();
      debug.add(
         "Voxy frame runtime (millis): "
            + TimingStatistics.dynamic.pVal()
            + ", "
            + TimingStatistics.main.pVal()
            + ", "
            + TimingStatistics.postDynamic.pVal()
            + ", "
            + TimingStatistics.all.pVal()
      );
      debug.add(
         "Extra time: " + TimingStatistics.A.pVal() + ", " + TimingStatistics.B.pVal() + ", " + TimingStatistics.C.pVal() + ", " + TimingStatistics.D.pVal()
      );
      debug.add(
         "Extra 2 time: "
            + TimingStatistics.E.pVal()
            + ", "
            + TimingStatistics.F.pVal()
            + ", "
            + TimingStatistics.G.pVal()
            + ", "
            + TimingStatistics.H.pVal()
            + ", "
            + TimingStatistics.I.pVal()
      );
      debug.add(GPUTiming.INSTANCE.getDebug());
      PrintfDebugUtil.addToOut(debug);
   }

   public void shutdown() {
        Logger.info("Shutting down rendering");
        var cleanup = new ExceptionCleanup(null);
        var geometry = (BasicSectionGeometryData)this.geometryData;
        this.releaseResources(cleanup, geometry.isExternalGeometryBuffer ? geometry.getGeometryBuffer() : null);
        try {
            cleanup.rethrow();
        } catch (RuntimeException | Error failure) {
            Logger.error("Error shutting down renderer components", failure);
        }
        Logger.info("Render shutdown completed");
    }

    private void releaseResources(ExceptionCleanup cleanup, GlBuffer borrowedGeometry) {
        boolean interrupted = Thread.interrupted();
        try {
            cleanup.run(() -> this.worldIn.setDirtyCallback(null));
            cleanup.run(() -> this.worldIn.getMapper().setBiomeCallback(null));
            cleanup.run(() -> this.worldIn.getMapper().setStateCallback(null));
            cleanup.run(() -> DownloadStream.INSTANCE.flushWaitClear());
            if (this.nodeManager != null) {
                cleanup.run(() -> this.nodeManager.stop(this.renderGen::shutdown));
            } else if (this.renderGen != null) {
                cleanup.run(this.renderGen::shutdown);
            }
            interrupted |= Thread.interrupted();
            if (this.modelService != null) cleanup.run(this.modelService::shutdown);
            interrupted |= Thread.interrupted();
            cleanup.run(() -> DownloadStream.INSTANCE.flushWaitClear());
            if (this.boundOutlineRenderer != null) cleanup.run(this.boundOutlineRenderer::free);
            if (this.visbleSectionStream != null) cleanup.run(this.visbleSectionStream::free);
            if (this.columnStreamedBoundStore != null) cleanup.run(this.columnStreamedBoundStore::free);
            if (this.viewportSelector != null) cleanup.run(this.viewportSelector::free);
            if (this.pipeline != null) cleanup.run(this.pipeline::free);
            if (this.traversal != null) cleanup.run(this.traversal::free);
            if (this.nodeCleaner != null) cleanup.run(this.nodeCleaner::free);
            if (this.geometryData != null) cleanup.run(this.geometryData::free);
            if (borrowedGeometry != null) cleanup.run(() -> RenderResourceReuse.giveBackGeometryBuffer(borrowedGeometry));
            cleanup.run(this.worldIn::releaseRef);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

   public WorldEngine getEngine() {
      return this.worldIn;
   }
}
