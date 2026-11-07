package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.common.util.ExceptionCleanup;

import java.util.List;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.LodBoundaryFade;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.LightMapHelper;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

/** 26.1.2 NeoForge 的 MDIC 区段渲染器；命令缓冲偏移与 compute shader 固定匹配。 */
public class MDICSectionRenderer extends AbstractSectionRenderer<MDICViewport, BasicSectionGeometryData> {
   public static final AbstractSectionRenderer.Factory<MDICViewport, BasicSectionGeometryData> FACTORY = AbstractSectionRenderer.Factory.create(
      MDICSectionRenderer.class
   );
   // 三类间接命令使用连续分区，不能在运行时重新排列。
   public static final int OPAQUE_DRAW_COUNT = 400000;
   public static final int TRANSLUCENT_DRAW_COUNT = 100000;
   public static final int TEMPORAL_DRAW_COUNT = 100000;
   private static final int TRANSLUCENT_OFFSET = 400000;
   private static final int TEMPORAL_OFFSET = 500000;
   private static final int STATISTICS_BUFFER_BINDING = 8;
   private final Shader terrainShader;
   private final Shader translucentTerrainShader;
   private final Shader commandGenShader;
   private final Shader prepShader;
   private final Shader cullShader;
   private final Shader prefixSumShader;
   private final Shader translucentGenShader;
   private final GlBuffer uniform;
   private final GlBuffer distanceCountBuffer;
   private final GlBuffer statisticsBuffer;
   private final AbstractRenderPipeline pipeline;
   private final float fluidDatumY;

   public MDICSectionRenderer(AbstractRenderPipeline pipeline, ModelStore modelStore, BasicSectionGeometryData geometryData) {
      super(pipeline.properties, modelStore, geometryData);
        try {
            this.commandGenShader = Shader.make()
                    .define("TRANSLUCENT_WRITE_BASE", 1024)
                    .define("TEMPORAL_OFFSET", 500000)
                    .define("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 7)
                    .defineIf("HAS_STATISTICS", RenderStatistics.enabled)
                    .defineIf("STATISTICS_BUFFER_BINDING", RenderStatistics.enabled, 8)
                    .add(ShaderType.COMPUTE, "voxy:lod/gl46/cmdgen.comp")
                    .compile();
            this.prepShader = Shader.make().add(ShaderType.COMPUTE, "voxy:lod/gl46/prep.comp").compile();
            this.prefixSumShader = Shader.make()
                    .add(ShaderType.COMPUTE, Capabilities.INSTANCE.subgroup ? "voxy:util/prefixsum/inital3.comp" : "voxy:util/prefixsum/simple.comp")
                    .define("IO_BUFFER", 0)
                    .compile();
            this.translucentGenShader = Shader.make()
                    .add(ShaderType.COMPUTE, "voxy:lod/gl46/buildtranslucents.comp")
                    .define("TRANSLUCENT_WRITE_BASE", 1024)
                    .define("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 5)
                    .define("TRANSLUCENT_OFFSET", 400000)
                    .compile();
            this.uniform = new GlBuffer(1024L).zero();
            this.distanceCountBuffer = new GlBuffer(404096L).zero();
            this.statisticsBuffer = new GlBuffer(1024L).zero();
            this.pipeline = pipeline;
            var level = Minecraft.getInstance().level;
            this.fluidDatumY = level == null ? -1.0e9F : level.getSeaLevel() - 7.0F / 64.0F;
            String vertex = ShaderLoader.parse("voxy:lod/gl46/quads3.vert");
            String taa = pipeline.taaFunction("taaShift");
            if (taa != null) {
               vertex = vertex + "\n" + taa;
            }

            Shader.Builder<Shader> builder = Shader.make()
               .apply(this.properties::apply)
               .defineIf("TAA_PATCH", taa != null)
               .defineIf("DEBUG_RENDER", false)
               .addSource(ShaderType.VERTEX, vertex);
            addDirectionalFaceTint(builder, Minecraft.getInstance().level);
            String frag = ShaderLoader.parse("voxy:lod/gl46/quads.frag");
            String opaqueFrag = pipeline.patchOpaqueShader(this, frag);
            opaqueFrag = opaqueFrag == null ? frag : opaqueFrag;
            this.terrainShader = tryCompilePatchedOrNormal(builder, opaqueFrag, frag);
            String translucentFrag = pipeline.patchTranslucentShader(this, frag);
            translucentFrag = translucentFrag == null ? frag : translucentFrag;
            this.translucentTerrainShader = tryCompilePatchedOrNormal(builder.define("TRANSLUCENT"), translucentFrag, frag);
            if (this.pipeline.hasTAA()) {
               this.cullShader = Shader.make()
                  .apply(this.properties::apply)
                  .addSource(ShaderType.VERTEX, ShaderLoader.parse("voxy:lod/gl46/cull/raster.vert") + "\n\n\n\n" + pipeline.taaFunction("getTAA"))
                  .define("TAA")
                  .add(ShaderType.FRAGMENT, "voxy:lod/gl46/cull/raster.frag")
                  .compile();
            } else {
               this.cullShader = Shader.make()
                  .apply(this.properties::apply)
                  .add(ShaderType.VERTEX, "voxy:lod/gl46/cull/raster.vert")
                  .add(ShaderType.FRAGMENT, "voxy:lod/gl46/cull/raster.frag")
                  .compile();
            }
        } catch (RuntimeException | Error failure) {
            this.releaseResources(new ExceptionCleanup(failure));
            throw failure;
        }
    }

   private void uploadUniformBuffer(MDICViewport viewport) {
      long ptr = UploadStream.INSTANCE.upload(this.uniform, 0L, 1024L);
      Matrix4f mat = new Matrix4f(viewport.MVP);
      mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
      mat.getToAddress(ptr);
      ptr += 64L;
      viewport.section.getToAddress(ptr);
      ptr += 12L;
      if (viewport.frameId < 0) {
         Logger.error("Frame ID negative, this will cause things to break, wrapping around");
         viewport.frameId &= Integer.MAX_VALUE;
      }

      MemoryUtil.memPutInt(ptr, viewport.frameId & 2147483647);
      ptr += 4L;
        viewport.innerTranslation.getToAddress(ptr);
        ptr += 12L;
        MemoryUtil.memPutFloat(ptr, this.fluidDatumY); ptr += 4L;
        var fade = LodBoundaryFade.getDistances();
        MemoryUtil.memPutFloat(ptr, fade.enabled() ? 1.0F : 0.0F); ptr += 4L;
        MemoryUtil.memPutFloat(ptr, fade.fadeStart()); ptr += 4L;
        MemoryUtil.memPutFloat(ptr, fade.fadeEnd()); ptr += 4L;
        MemoryUtil.memPutFloat(ptr, 0.0F); ptr += 4L;
        int curveRatio = me.cortex.voxy.client.config.VoxyConfig.CONFIG.earthCurveRatio;
        MemoryUtil.memPutFloat(ptr, curveRatio >= 50 ? 6371000.0F / curveRatio : 0.0F); ptr += 4L;
        MemoryUtil.memPutFloat(ptr, Math.max(net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0F - 16.0F, 16.0F)); ptr += 4L;
        MemoryUtil.memPutFloat(ptr, 0.0F); ptr += 4L;
        MemoryUtil.memPutFloat(ptr, 0.0F);
      UploadStream.INSTANCE.commit();
   }

   private void bindRenderingBuffers(MDICViewport viewport) {
      GL30.glBindBufferBase(35345, 0, this.uniform.id);
      GL30.glBindBufferBase(37074, 1, this.geometryManager.getGeometryBuffer().id);
      GL30.glBindBufferBase(37074, 2, this.geometryManager.getMetadataBuffer().id);
      this.modelStore.bind(3, 4, 0);
      GL30.glBindBufferBase(37074, 5, viewport.positionScratchBuffer.id);
      LightMapHelper.bind(1);
      GL45.glBindTextureUnit(2, viewport.depthBoundingBuffer.getDepthTex().id);
      GL15.glBindBuffer(34963, SharedIndexBuffer.INSTANCE.id());
      GL15.glBindBuffer(36671, viewport.drawCallBuffer.id);
      GL15.glBindBuffer(33006, viewport.drawCountCallBuffer.id);
   }

   private void renderTerrain(MDICViewport viewport, long indirectOffset, long drawCountOffset, int maxDrawCount) {
      GL43.glDisable(2884);
      GL43.glDisable(3042);
      GL43.glEnable(2929);
      GL43.glDepthFunc(this.properties.closerEqualDepthCompare());
      this.terrainShader.bind();
      GL30.glBindVertexArray(GlVertexArray.STATIC_VAO);
      this.pipeline.setupAndBindOpaque(viewport);
      this.bindRenderingBuffers(viewport);
      GL42.glMemoryBarrier(8256);
      GL43.glProvokingVertex(36429);
      if (VoxyClient.getOcclusionDebugState() == 3) {
         GL43.glPolygonMode(1032, 6913);
      }

      ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(4, 5123, indirectOffset, drawCountOffset, maxDrawCount, 0);
      if (VoxyClient.getOcclusionDebugState() == 3) {
         GL43.glPolygonMode(1032, 6914);
      }

      GL43.glEnable(2884);
      GL30.glBindVertexArray(0);
      GL33.glBindSampler(0, 0);
      GL45.glBindTextureUnit(0, 0);
      GL33.glBindSampler(1, 0);
      GL45.glBindTextureUnit(1, 0);
   }

   // ---- 绘制与 GPU 缓冲绑定 ------------------------------------------

   public void renderOpaque(MDICViewport viewport) {
      if (this.geometryManager.getSectionCount() != 0) {
         this.uploadUniformBuffer(viewport);
         this.renderTerrain(viewport, 0L, 12L, Math.min((int)(this.geometryManager.getSectionCount() * 4.4 + 128.0), 400000));
      }
   }

   public void renderTranslucent(MDICViewport viewport) {
      if (this.geometryManager.getSectionCount() != 0) {
         GL43.glEnable(3042);
         GL43.glBlendFuncSeparate(770, 771, 1, 771);
         GL43.glDisable(2884);
         GL43.glEnable(2929);
         GL43.glDepthFunc(this.properties.closerEqualDepthCompare());
         this.translucentTerrainShader.bind();
         GL30.glBindVertexArray(GlVertexArray.STATIC_VAO);
         this.pipeline.setupAndBindTranslucent(viewport);
         this.bindRenderingBuffers(viewport);
         GL42.glMemoryBarrier(8256);
         GL43.glProvokingVertex(36429);
         ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(4, 5123, 8000000L, 16L, Math.min(this.geometryManager.getSectionCount(), 100000), 0);
         GL43.glEnable(2884);
         GL30.glBindVertexArray(0);
         GL33.glBindSampler(0, 0);
         GL45.glBindTextureUnit(0, 0);
         GL33.glBindSampler(1, 0);
         GL45.glBindTextureUnit(1, 0);
         GL43.glDisable(3042);
      }
   }

   // ---- Compute 建令与时序同步 ---------------------------------------

   public void buildDrawCalls(MDICViewport viewport) {
      if (this.geometryManager.getSectionCount() != 0) {
         this.uploadUniformBuffer(viewport);
         this.prepShader.bind();
         GL30.glBindBufferBase(35345, 0, this.uniform.id);
         GL30.glBindBufferBase(37074, 1, viewport.drawCountCallBuffer.id);
         GL30.glBindBufferBase(37074, 2, viewport.getRenderList().id);
         GL42.glMemoryBarrier(8192);
         GL43.glDispatchCompute(1, 1, 1);
         GL42.glMemoryBarrier(8192);
         GPUTiming.INSTANCE.marker("OT");
         this.cullShader.bind();
         if (this.pipeline.hasTAA()) {
            this.pipeline.bindUniforms();
         }

         if (Capabilities.INSTANCE.repFragTest) {
            GL43.glEnable(37759);
         }

         GL30.glBindVertexArray(GlVertexArray.STATIC_VAO);
         GL30.glBindBufferBase(35345, 0, this.uniform.id);
         GL30.glBindBufferBase(37074, 1, this.geometryManager.getMetadataBuffer().id);
         GL30.glBindBufferBase(37074, 2, viewport.visibilityBuffer.id);
         GL30.glBindBufferBase(37074, 3, viewport.indirectLookupBuffer.id);
         GL15.glBindBuffer(36671, viewport.drawCountCallBuffer.id);
         GL15.glBindBuffer(34963, SharedIndexBuffer.INSTANCE.id());
         GL43.glEnable(2929);
         GL43.glDepthFunc(this.properties.closerEqualDepthCompare());
         GL43.glColorMask(false, false, false, false);
         GL43.glDepthMask(false);
         GL42.glMemoryBarrier(8256);
         GL43.glDrawElementsIndirect(4, 5121, 24L);
         GL43.glDepthMask(true);
         GL43.glColorMask(true, true, true, true);
         GL43.glDisable(2929);
         if (Capabilities.INSTANCE.repFragTest) {
            GL43.glDisable(37759);
         }

         GPUTiming.INSTANCE.marker("CG");
         this.distanceCountBuffer.zeroRange(0L, 4096L);
         this.commandGenShader.bind();
         GL30.glBindBufferBase(35345, 0, this.uniform.id);
         GL30.glBindBufferBase(37074, 1, viewport.drawCallBuffer.id);
         GL30.glBindBufferBase(37074, 2, viewport.drawCountCallBuffer.id);
         GL30.glBindBufferBase(37074, 3, this.geometryManager.getMetadataBuffer().id);
         GL30.glBindBufferBase(37074, 4, viewport.visibilityBuffer.id);
         GL30.glBindBufferBase(37074, 5, viewport.indirectLookupBuffer.id);
         GL30.glBindBufferBase(37074, 6, viewport.positionScratchBuffer.id);
         GL30.glBindBufferBase(37074, 7, this.distanceCountBuffer.id);
         if (RenderStatistics.enabled) {
            this.statisticsBuffer.zero();
            GL30.glBindBufferBase(37074, 8, this.statisticsBuffer.id);
         }

         GL15.glBindBuffer(37102, viewport.drawCountCallBuffer.id);
         GL42.glMemoryBarrier(8192);
         GL43.glDispatchComputeIndirect(0L);
         GL42.glMemoryBarrier(8256);
         if (RenderStatistics.enabled) {
            DownloadStream.INSTANCE.download(this.statisticsBuffer, down -> {
               int LAYERS = 5;

               for (int i = 0; i < 5; i++) {
                  RenderStatistics.visibleSections[i] = MemoryUtil.memGetInt(down.address + i * 4L);
               }

               for (int i = 0; i < 5; i++) {
                  RenderStatistics.quadCount[i] = MemoryUtil.memGetInt(down.address + 20L + i * 4L);
               }
            });
         }

         GPUTiming.INSTANCE.marker("TS");
         this.prefixSumShader.bind();
         GL30.glBindBufferBase(37074, 0, this.distanceCountBuffer.id);
         GL42.glMemoryBarrier(8192);
         GL43.glDispatchCompute(1, 1, 1);
         GL42.glMemoryBarrier(8192);
         this.translucentGenShader.bind();
         GL30.glBindBufferBase(35345, 0, this.uniform.id);
         GL30.glBindBufferBase(37074, 1, viewport.drawCallBuffer.id);
         GL30.glBindBufferBase(37074, 2, viewport.drawCountCallBuffer.id);
         GL30.glBindBufferBase(37074, 3, this.geometryManager.getMetadataBuffer().id);
         GL30.glBindBufferBase(37074, 4, viewport.indirectLookupBuffer.id);
         GL30.glBindBufferBase(37074, 5, this.distanceCountBuffer.id);
         GL15.glBindBuffer(37102, viewport.drawCountCallBuffer.id);
         GL42.glMemoryBarrier(8260);
         GL43.glDispatchComputeIndirect(0L);
         GL42.glMemoryBarrier(8256);
      }
   }

   public void renderTemporal(MDICViewport viewport) {
      if (this.geometryManager.getSectionCount() != 0) {
         this.renderTerrain(viewport, 10000000L, 20L, Math.min(this.geometryManager.getSectionCount(), 100000));
      }
   }

   @Override
   public void addDebug(List<String> lines) {
      super.addDebug(lines);
   }

   public MDICViewport createViewport() {
      return new MDICViewport(this.properties, this.geometryManager.getMaxSectionCount());
   }

   @Override
   // ---- 生命周期 ------------------------------------------------------

   public void free() {
        var cleanup = new ExceptionCleanup(null);
        this.releaseResources(cleanup);
        cleanup.rethrow();
    }

    private void releaseResources(ExceptionCleanup cleanup) {
        if (this.cullShader != null) cleanup.run(this.cullShader::free);
        if (this.translucentTerrainShader != null) cleanup.run(this.translucentTerrainShader::free);
        if (this.terrainShader != null) cleanup.run(this.terrainShader::free);
        if (this.statisticsBuffer != null) cleanup.run(this.statisticsBuffer::free);
        if (this.distanceCountBuffer != null) cleanup.run(this.distanceCountBuffer::free);
        if (this.uniform != null) cleanup.run(this.uniform::free);
        if (this.translucentGenShader != null) cleanup.run(this.translucentGenShader::free);
        if (this.prefixSumShader != null) cleanup.run(this.prefixSumShader::free);
        if (this.prepShader != null) cleanup.run(this.prepShader::free);
        if (this.commandGenShader != null) cleanup.run(this.commandGenShader::free);
    }
}
