package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.common.util.ExceptionCleanup;


import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.LightMapHelper;
import me.cortex.voxy.client.core.rendering.LodBoundaryFade;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB;
import static org.lwjgl.opengl.ARBIndirectParameters.glMultiDrawElementsIndirectCountARB;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV;

/** 使用 Multi-Draw Indirect Count 绘制 LOD 区块。 */
public class MDICSectionRenderer extends AbstractSectionRenderer<MDICViewport, BasicSectionGeometryData> {
    public static final Factory<MDICViewport, BasicSectionGeometryData> FACTORY = AbstractSectionRenderer.Factory.create(MDICSectionRenderer.class);

    // 三段间接绘制命令在同一缓冲中分区，偏移量必须与 compute shader 保持一致。
    public static final int OPAQUE_DRAW_COUNT = 400_000;
    public static final int TRANSLUCENT_DRAW_COUNT = 100_000;
    public static final int TEMPORAL_DRAW_COUNT = 100_000;
    private static final int TRANSLUCENT_OFFSET = OPAQUE_DRAW_COUNT;
    private static final int TEMPORAL_OFFSET = TRANSLUCENT_OFFSET + TRANSLUCENT_DRAW_COUNT;
    private static final int STATISTICS_BUFFER_BINDING = 8;
    private final Shader terrainShader;
    private final Shader translucentTerrainShader;

    private final boolean opaqueNearFirst = VoxyConfig.CONFIG.experimentalOpaqueNearFirst;
    private final boolean chunkMaskHalfRes = VoxyConfig.CONFIG.experimentalChunkMaskHalfRes;
    private final Shader commandGenShader;

    private final Shader prepShader;

    private final Shader cullShader;

    private final Shader prefixSumShader;

    private final Shader translucentGenShader;

    private final GlBuffer uniform;

    private final GlBuffer distanceCountBuffer;

    // 调试统计缓冲只在统计开关打开时读取，仍预留固定大小以保持 shader ABI。
    private final GlBuffer statisticsBuffer;

    private final AbstractRenderPipeline pipeline;
    private final float fluidDatumY;
    private final Matrix4f uniformMatrix = new Matrix4f();

    public MDICSectionRenderer(AbstractRenderPipeline pipeline, ModelStore modelStore, BasicSectionGeometryData geometryData) {
        super(pipeline.properties, modelStore, geometryData);
        try {
            this.commandGenShader = Shader.make()
                    .define("TRANSLUCENT_WRITE_BASE", 1024)
                    .defineIf("OPAQUE_NEAR_FIRST", this.opaqueNearFirst)
                    .define("TEMPORAL_OFFSET", TEMPORAL_OFFSET)

                    .define("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 7)

                    .defineIf("HAS_STATISTICS", RenderStatistics.enabled)
                    .defineIf("STATISTICS_BUFFER_BINDING", RenderStatistics.enabled, STATISTICS_BUFFER_BINDING)

                    .add(ShaderType.COMPUTE, "voxy:lod/gl46/cmdgen.comp")
                    .compile();
            this.prepShader = Shader.make()
                    .add(ShaderType.COMPUTE, "voxy:lod/gl46/prep.comp")
                    .compile();
            this.prefixSumShader = Shader.make()
                    .add(ShaderType.COMPUTE, Capabilities.INSTANCE.subgroup?"voxy:util/prefixsum/inital3.comp":"voxy:util/prefixsum/simple.comp")
                    .define("IO_BUFFER", 0)
                    .compile();
            this.translucentGenShader = Shader.make()
                    .add(ShaderType.COMPUTE, "voxy:lod/gl46/buildtranslucents.comp")
                    .define("TRANSLUCENT_WRITE_BASE", 1024)
                    .defineIf("OPAQUE_NEAR_FIRST", this.opaqueNearFirst)
                    .define("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 5)
                    .define("TRANSLUCENT_OFFSET", TRANSLUCENT_OFFSET)

                    .compile();
            this.uniform = new GlBuffer(1024).zero();
            this.distanceCountBuffer = new GlBuffer(1024*4+TRANSLUCENT_DRAW_COUNT*4).zero();
            this.statisticsBuffer = new GlBuffer(1024).zero();
            this.pipeline = pipeline;
            var level = Minecraft.getInstance().level;
            this.fluidDatumY = level == null ? -1.0e9f : level.getSeaLevel() - (7.0f / 64.0f);

            // 管线可以在 shader 构建阶段注入 TAA、雾效和光影兼容代码。
            String vertex = ShaderLoader.parse("voxy:lod/gl46/quads3.vert");
            String taa = pipeline.taaFunction("taaShift");
            if (taa != null) {
                vertex += "\n" + taa;
            }
            var builder = Shader.make()
                    .apply(this.properties::apply)
                    .defineIf("TAA_PATCH", taa != null)
                    .defineIf("DEBUG_RENDER", false)
                    .defineIf("CHUNK_MASK_HALF_RES", this.chunkMaskHalfRes)
                    .addSource(ShaderType.VERTEX, vertex);

            // 方向光照在顶点阶段处理，保持模型颜色和 LOD 光照采样一致。
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
                        .addSource(ShaderType.VERTEX,
                                ShaderLoader.parse("voxy:lod/gl46/cull/raster.vert")
                                        + "\n\n\n\n" + pipeline.taaFunction("getTAA"))
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
        long ptr = UploadStream.INSTANCE.upload(this.uniform, 0, 1024);

        var mat = this.uniformMatrix.set(viewport.MVP);
        mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        mat.getToAddress(ptr);
        ptr += 4 * 4 * 4;

        viewport.section.getToAddress(ptr);
        ptr += 4 * 3;

        if (viewport.frameId < 0) {
            Logger.error("Frame ID negative, this will cause things to break, wrapping around");
            viewport.frameId &= 0x7fffffff;
        }
        MemoryUtil.memPutInt(ptr, viewport.frameId & 0x7fffffff);
        ptr += 4;
        viewport.innerTranslation.getToAddress(ptr);
        ptr += 4 * 3;
        MemoryUtil.memPutFloat(ptr, this.fluidDatumY);
        ptr += 4;

        // 以下字段由 quads shader 读取，顺序和 1024 字节 uniform ABI 保持不变。
        var boundary = LodBoundaryFade.getDistances();
        MemoryUtil.memPutFloat(ptr, boundary.enabled() ? 1.0f : 0.0f);
        ptr += 4;
        MemoryUtil.memPutFloat(ptr, boundary.fadeStart());
        ptr += 4;
        MemoryUtil.memPutFloat(ptr, boundary.fadeEnd());
        ptr += 4;

        var config = VoxyConfig.CONFIG;
        double framedDistance = config.distantFramedBlocks
                ? config.createRenderDistance(config.distantFramedBlocksMaxChunks) : 0.0;
        MemoryUtil.memPutFloat(ptr, (float) Math.min(framedDistance * framedDistance, Float.MAX_VALUE));
        ptr += 4;

        int curveRatio = VoxyConfig.CONFIG.earthCurveRatio;
        MemoryUtil.memPutFloat(ptr, curveRatio >= 50 ? 6371000.0f / curveRatio : 0.0f);
        ptr += 4;
        MemoryUtil.memPutFloat(ptr, Math.max(
                Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f - 16.0f,
                16.0f));
        ptr += 4;

        boolean distantTracksReady = config.distantTracks
                && LodPipelineHooks.distantTrackMeshesReady;
        MemoryUtil.memPutFloat(ptr, distantTracksReady ? 1.0f : 0.0f);
        ptr += 4;
        MemoryUtil.memPutFloat(ptr, 0.0f);
        ptr += 4;
        MemoryUtil.memPutInt(ptr, viewport.prevBuildFrameId & 0x7fffffff);
        ptr += 4;
        MemoryUtil.memPutInt(ptr, VoxyConfig.CONFIG.experimentalCmdListHold
                ? viewport.prevBuildFrameId & 0x7fffffff : 0);

        UploadStream.INSTANCE.commit();
    }


    private void bindRenderingBuffers(MDICViewport viewport) {
        // 绑定布局必须和 lod/gl46/bindings.glsl、各 compute shader 的 binding 对齐。
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.geometryManager.getGeometryBuffer().id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, this.geometryManager.getMetadataBuffer().id);
        this.modelStore.bind(3, 4, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, viewport.positionScratchBuffer.id);
        LightMapHelper.bind(1);
        glBindTextureUnit(2, viewport.depthBoundingBuffer.getDepthTex().id);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE.id());
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, viewport.drawCallBuffer.id);
        glBindBuffer(GL_PARAMETER_BUFFER_ARB, viewport.drawCountCallBuffer.id);
    }

    private void renderTerrain(MDICViewport viewport, long indirectOffset, long drawCountOffset, int maxDrawCount) {
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(this.properties.closerEqualDepthCompare());
        this.terrainShader.bind();
        glBindVertexArray(GlVertexArray.STATIC_VAO);
        this.pipeline.setupAndBindOpaque(viewport);
        this.bindRenderingBuffers(viewport);

        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        glProvokingVertex(GL_FIRST_VERTEX_CONVENTION);

        if (VoxyClient.getOcclusionDebugState() == 3) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        }
        glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, indirectOffset, drawCountOffset, maxDrawCount, 0);
        if (VoxyClient.getOcclusionDebugState() == 3) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }

        glEnable(GL_CULL_FACE);
        glBindVertexArray(0);
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
        glBindSampler(1, 0);
        glBindTextureUnit(1, 0);

    }

    @Override
    public void renderOpaque(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) {
            return;
        }

        this.uploadUniformBuffer(viewport);

        int drawCount = Math.min((int) (this.geometryManager.getSectionCount() * 4.4 + 128), OPAQUE_DRAW_COUNT);
        this.renderTerrain(viewport, 0, 4 * 3, drawCount);
    }

    @Override
    public void renderTranslucent(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) {
            return;
        }

        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(this.properties.closerEqualDepthCompare());
        this.translucentTerrainShader.bind();
        glBindVertexArray(GlVertexArray.STATIC_VAO);
        this.pipeline.setupAndBindTranslucent(viewport);
        this.bindRenderingBuffers(viewport);

        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        glProvokingVertex(GL_FIRST_VERTEX_CONVENTION);
        glMultiDrawElementsIndirectCountARB(
                GL_TRIANGLES,
                GL_UNSIGNED_SHORT,
                TRANSLUCENT_OFFSET * 5 * 4,
                4 * 4,
                Math.min(this.geometryManager.getSectionCount(), TRANSLUCENT_DRAW_COUNT),
                0);

        glEnable(GL_CULL_FACE);
        glBindVertexArray(0);
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
        glBindSampler(1, 0);
        glBindTextureUnit(1, 0);

        glDisable(GL_BLEND);
    }

    @Override
    public void buildDrawCalls(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) {
            return;
        }
        this.uploadUniformBuffer(viewport);

        // renderList 只标记可见区块；先清理计数，再执行遮挡测试和命令生成。


        // 准备间接绘制计数和当前帧的渲染列表。
        {
            this.prepShader.bind();
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, viewport.drawCountCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.getRenderList().id);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute(1, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }

        GPUTiming.INSTANCE.marker("OT");
        // 使用上一阶段深度测试可见性，并把结果写入 visibilityBuffer。
        {
            this.cullShader.bind();
            if (this.pipeline.hasTAA()) this.pipeline.bindUniforms();//Used for shader TAA
            if (Capabilities.INSTANCE.repFragTest) {
                glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            }
            glBindVertexArray(GlVertexArray.STATIC_VAO);
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.geometryManager.getMetadataBuffer().id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.visibilityBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, viewport.indirectLookupBuffer.id);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE.id());
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(this.properties.closerEqualDepthCompare());
            glColorMask(false, false, false, false);
            glDepthMask(false);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
            glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_BYTE, 6*4);
            glDepthMask(true);
            glColorMask(true, true, true, true);
            glDisable(GL_DEPTH_TEST);
            if (Capabilities.INSTANCE.repFragTest) {
                glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            }
        }

        GPUTiming.INSTANCE.marker("CG");

        // 根据可见性生成不透明、时间性和半透明绘制命令。
        {
            this.distanceCountBuffer.zeroRange(0, 1024 * 4);
            this.commandGenShader.bind();
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, viewport.drawCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.drawCountCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, this.geometryManager.getMetadataBuffer().id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, viewport.visibilityBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, viewport.indirectLookupBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, viewport.positionScratchBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, this.distanceCountBuffer.id);

            if (RenderStatistics.enabled) {
                this.statisticsBuffer.zero();
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, STATISTICS_BUFFER_BINDING, this.statisticsBuffer.id);
            }

            glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchComputeIndirect(0);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);

            if (RenderStatistics.enabled) {
                DownloadStream.INSTANCE.download(this.statisticsBuffer, down->{
                    final int LAYERS = WorldEngine.MAX_LOD_LAYER+1;
                    for (int i = 0; i < LAYERS; i++) {
                        RenderStatistics.visibleSections[i] = MemoryUtil.memGetInt(down.address+i*4L);
                    }

                    for (int i = 0; i < LAYERS; i++) {
                        RenderStatistics.quadCount[i] = MemoryUtil.memGetInt(down.address+LAYERS*4L+i*4L);
                    }
                });
            }
        }

        GPUTiming.INSTANCE.marker("TS");
        // 对半透明区块按距离排序，再生成第二段间接绘制命令。
        {
            this.prefixSumShader.bind();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, this.distanceCountBuffer.id);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);//Am unsure if is needed
            glDispatchCompute(1, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

            this.translucentGenShader.bind();
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, viewport.drawCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.drawCountCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, this.geometryManager.getMetadataBuffer().id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, viewport.indirectLookupBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, this.distanceCountBuffer.id);

            glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT | GL_UNIFORM_BARRIER_BIT);
            glDispatchComputeIndirect(0);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
        }

    }

    @Override
    public void renderTemporal(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) {
            return;
        }
        // 时间性命令在同一地形 shader 中复用，避免额外的状态切换。
        this.renderTerrain(
                viewport,
                TEMPORAL_OFFSET * 5 * 4,
                4 * 5,
                Math.min(this.geometryManager.getSectionCount(), TEMPORAL_DRAW_COUNT));
    }

    @Override
    public void addDebug(List<String> lines) {
        super.addDebug(lines);
        lines.add("opaqueNearFirst: " + this.opaqueNearFirst);
    }

    @Override
    public MDICViewport createViewport() {
        return new MDICViewport(this.properties, this.geometryManager.getMaxSectionCount(), this.chunkMaskHalfRes);
    }

    @Override
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
