package me.cortex.voxy.client.compat.copycat;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.create.DistantFaceCulling;
import me.cortex.voxy.client.compat.create.DistantLightSampler;
import me.cortex.voxy.client.compat.create.DistantMesh;
import me.cortex.voxy.client.compat.create.DistantMeshBuilder;
import me.cortex.voxy.client.compat.create.DistantShaders;
import me.cortex.voxy.client.compat.create.DistantVisibility;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.compat.CreateCopycatCompat;
import me.cortex.voxy.commonImpl.compat.DisguiseStore;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

/** Copycat 专属 LOD 的异步烘焙和远景绘制；普通 LOD 由区段渲染器继续负责。 */
public final class CopycatDistantRenderer implements LodPipelineHooks.Renderer, LodPipelineHooks.TranslucentRenderer {
    private static final int MAX_BAKES_PER_TICK = 1;
    private static volatile CopycatDistantRenderer active;

    private final ConcurrentLinkedQueue<Update> updates = new ConcurrentLinkedQueue<>();
    private final Map<Long, Entry> sections = new HashMap<>();
    private final ArrayDeque<Long> bakeQueue = new ArrayDeque<>();
    private final HashSet<Long> queued = new HashSet<>();
    private SectionStorage storage;
    private ClientLevel level;
    private int lastScanX = Integer.MIN_VALUE;
    private int lastScanZ = Integer.MIN_VALUE;
    private int lastMaxChunks = Integer.MIN_VALUE;
    private int depthSampler;

    public CopycatDistantRenderer() {
        active = this;
        CreateCopycatCompat.sectionListener = CopycatDistantRenderer::changed;
    }

    private static void changed(SectionStorage storage, int sx, int sy, int sz) {
        CopycatDistantRenderer renderer = active;
        if (renderer != null) renderer.updates.add(new Update(storage, DisguiseStore.keyOf(sx, sy, sz)));
    }

    // ---- 生命周期与异步烘焙 -------------------------------------------

    @SubscribeEvent
    public void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var engine = WorldIdentifier.ofEngineNullable(mc.level);
        if (engine == null) return;
        if (this.storage != engine.storage) {
            clearMeshes();
            this.updates.clear();
            this.storage = engine.storage;
            this.level = mc.level;
            this.storage.forEachAux(CreateCopycatCompat.DISGUISE_TABLE,
                    (key, value) -> install(key, value, false));
        }

        drainUpdates(128);
        if (!VoxyConfig.CONFIG.isRenderingEnabled() || !VoxyConfig.CONFIG.distantCopycats) {
            this.lastScanX = this.lastScanZ = Integer.MIN_VALUE;
            return;
        }
        int maxChunks = VoxyConfig.CONFIG.distantCopycatsMaxChunks;
        if (this.lastMaxChunks != maxChunks) {
            this.lastMaxChunks = maxChunks;
            this.lastScanX = this.lastScanZ = Integer.MIN_VALUE;
        }
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        double maxDistance = VoxyConfig.CONFIG.createRenderDistance(maxChunks);
        double maxDistanceSq = maxDistance * maxDistance;
        int cx = ((int) Math.floor(camera.x)) >> 4;
        int cz = ((int) Math.floor(camera.z)) >> 4;
        if (this.lastScanX == Integer.MIN_VALUE || Math.abs(cx - this.lastScanX) >= 4
                || Math.abs(cz - this.lastScanZ) >= 4) {
            this.lastScanX = cx;
            this.lastScanZ = cz;
            double farSq = (maxDistance + 256.0) * (maxDistance + 256.0);
            for (var item : this.sections.entrySet()) {
                Entry entry = item.getValue();
                double distanceSq = distanceSq(item.getKey(), camera.x, camera.y, camera.z);
                if (!entry.baked && distanceSq <= maxDistanceSq && this.queued.add(item.getKey())) {
                    this.bakeQueue.add(item.getKey());
                } else if (entry.hasMesh() && distanceSq > farSq) {
                    entry.free();
                }
            }
        }

        for (int baked = 0, checked = 0;
             baked < MAX_BAKES_PER_TICK && checked < 128 && !this.bakeQueue.isEmpty(); checked++) {
            long key = this.bakeQueue.removeFirst();
            this.queued.remove(key);
            Entry entry = this.sections.get(key);
            if (entry == null || entry.baked
                    || distanceSq(key, camera.x, camera.y, camera.z) > maxDistanceSq) continue;
            Meshes meshes = bake(key, entry.blocks, engine.getMapper(), mc.level);
            entry.baked = true;
            if (meshes != null) {
                entry.opaqueMesh = meshes.opaque;
                entry.translucentMesh = meshes.translucent;
            }
            baked++;
        }
    }

    @SubscribeEvent
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (this.depthSampler != 0) org.lwjgl.opengl.GL33C.glDeleteSamplers(this.depthSampler);
        this.depthSampler = 0;
        clearMeshes();
        this.updates.clear();
        this.storage = null;
        this.level = null;
    }

    // ---- 绘制与地形深度遮挡 -------------------------------------------

    @Override
    public void render(AbstractRenderPipeline pipeline,
                       Viewport<?> viewport, int depthFunc) {
        renderMeshes(pipeline, viewport, depthFunc, false);
    }

    @Override
    public void renderTranslucent(AbstractRenderPipeline pipeline,
                                  Viewport<?> viewport, int depthFunc) {
        renderMeshes(pipeline, viewport, depthFunc, true);
    }

    private void renderMeshes(AbstractRenderPipeline pipeline,
                              Viewport<?> viewport, int depthFunc, boolean translucent) {
        if (this.sections.isEmpty() || !VoxyConfig.CONFIG.isRenderingEnabled()
                || !VoxyConfig.CONFIG.distantCopycats) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != this.level) return;
        if (translucent) pipeline.setupAndBindTranslucent(viewport);
        else pipeline.setupAndBindOpaque(viewport);

        double handoffSq = me.cortex.voxy.client.compat.SectionHandoff.distanceSquared();
        double maxDistance = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantCopycatsMaxChunks);
        double maxDistanceSq = maxDistance * maxDistance;
        boolean bound = false;
        int previousSampler = org.lwjgl.opengl.GL30C.glGetIntegeri(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING, 2);
        var transform = new Matrix4f();
        try {
            for (var item : this.sections.entrySet()) {
                Entry entry = item.getValue();
                DistantMesh mesh = translucent ? entry.translucentMesh : entry.opaqueMesh;
                if (mesh == null) continue;
                long key = item.getKey();
                double ox = BlockPos.getX(key) * 16.0;
                double oy = BlockPos.getY(key) * 16.0;
                double oz = BlockPos.getZ(key) * 16.0;
                double dx = ox + 8.0 - viewport.cameraX;
                double dy = oy + 8.0 - viewport.cameraY;
                double dz = oz + 8.0 - viewport.cameraZ;
                double farX = Math.abs(dx) + 12.0, farZ = Math.abs(dz) + 12.0;
                double nearSq = farX * farX + farZ * farZ;
                if (dx * dx + dy * dy + dz * dz > maxDistanceSq) continue;
                if (nearSq < handoffSq && me.cortex.voxy.client.compat.SectionHandoff.vanillaOwns(
                        BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key))) continue;
                if (!DistantVisibility.isBoxVisible(viewport, ox - 4, oy - 4, oz - 4,
                        ox + 20, oy + 20, oz + 20)) continue;
                if (!bound) {
                    DistantShaders.forCopycatPipeline(pipeline, translucent).bind();
                    DistantShaders.bindTextures();
                    bindTerrainDepth(pipeline, viewport);
                    glEnable(GL_DEPTH_TEST);
                    glDepthFunc(depthFunc);
                    glDepthMask(!translucent);
                    glDisable(GL_CULL_FACE);
                    if (translucent) {
                        glEnable(GL_BLEND);
                        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA,
                                GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
                    } else {
                        glDisable(GL_BLEND);
                    }
                    glEnable(GL_STENCIL_TEST);
                    glStencilFunc(GL_ALWAYS, 3, 0xFF);
                    glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
                    bound = true;
                }
                transform.set(viewport.MVP).translate((float) (ox - viewport.cameraX),
                        (float) (oy - viewport.cameraY), (float) (oz - viewport.cameraZ));
                DistantShaders.uploadTransform(transform);
                if (translucent) mesh.drawTranslucent(viewport, transform);
                else mesh.drawAt(viewport, ox, oy, oz);
            }
            if (bound) {
                glBindVertexArray(0);
                glUseProgram(0);
            }
        } finally {
            org.lwjgl.opengl.GL33C.glBindSampler(2, previousSampler);
            if (bound) {
                glStencilFunc(GL_EQUAL, 1, 0x1);
                glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            }
        }
    }

    /** 将原版地形深度绑定到 Copycat shader，修复交接阶段的远景透视。 */
    private void bindTerrainDepth(AbstractRenderPipeline pipeline, Viewport<?> viewport) {
        if (this.depthSampler == 0) {
            this.depthSampler = org.lwjgl.opengl.GL33C.glGenSamplers();
            org.lwjgl.opengl.GL33C.glSamplerParameteri(this.depthSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            org.lwjgl.opengl.GL33C.glSamplerParameteri(this.depthSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            org.lwjgl.opengl.GL33C.glSamplerParameteri(this.depthSampler, GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE);
            org.lwjgl.opengl.GL33C.glSamplerParameteri(this.depthSampler, GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE);
        }
        org.lwjgl.opengl.GL45C.glBindTextureUnit(2, pipeline.debugSourceDepthTex());
        org.lwjgl.opengl.GL33C.glBindSampler(2, this.depthSampler);
        var inverseSource = new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView).invert();
        var transform = new Matrix4f(viewport.MVP).mul(inverseSource);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.opengl.GL20C.glUniformMatrix4fv(8, false, transform.get(stack.mallocFloat(16)));
        }
        boolean halfNdc = RenderProperties.windowIsHalfNdc();
        org.lwjgl.opengl.GL20C.glUniform4f(12, halfNdc ? 0.5f : 1.0f, halfNdc ? 0.5f : 0.0f,
                halfNdc ? 2.0f : 1.0f, halfNdc ? -1.0f : 0.0f);
        org.lwjgl.opengl.GL20C.glUniform4f(13, viewport.width, viewport.height,
                (float) viewport.width / pipeline.debugSrcWidth(),
                (float) viewport.height / pipeline.debugSrcHeight());
        org.lwjgl.opengl.GL20C.glUniform1i(14, pipeline.properties.isReverseZ() ? 1 : 0);
    }

    private void drainUpdates(int limit) {
        for (int applied = 0; applied < limit;) {
            Update update = this.updates.poll();
            if (update == null) return;
            if (update.storage != this.storage) continue;
            applied++;
            install(update.key, this.storage.getAux(CreateCopycatCompat.DISGUISE_TABLE, update.key), true);
        }
    }

    private void install(long key, byte[] value, boolean urgent) {
        int[] pairs = decode(value);
        Entry old = this.sections.remove(key);
        if (old != null) old.free();
        this.queued.remove(key);
        this.bakeQueue.remove(key);
        if (pairs.length == 0) return;
        this.sections.put(key, new Entry(pairs));

        Minecraft mc = Minecraft.getInstance();
        if (VoxyConfig.CONFIG.distantCopycats && mc.level == this.level) {
            var camera = mc.gameRenderer.getMainCamera().getPosition();
            double maxDistance = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantCopycatsMaxChunks);
            if (distanceSq(key, camera.x, camera.y, camera.z) <= maxDistance * maxDistance
                    && this.queued.add(key)) {
                if (urgent) this.bakeQueue.addFirst(key);
                else this.bakeQueue.addLast(key);
            }
        }
    }

    private static int[] decode(byte[] value) {
        var values = new it.unimi.dsi.fastutil.ints.IntArrayList();
        DisguiseStore.decode(value, (index, id) -> {
            values.add(index);
            values.add(id);
        });
        return values.toIntArray();
    }

    // ---- 模型烘焙 ------------------------------------------------------

    private static Meshes bake(long key, int[] blocks, Mapper mapper, ClientLevel level) {
        int sx = BlockPos.getX(key), sy = BlockPos.getY(key), sz = BlockPos.getZ(key);
        var opaque = new DistantMeshBuilder();
        var translucent = DistantMeshBuilder.translucent();
        DistantMeshBuilder.CpuMesh opaqueCpu = null;
        DistantMeshBuilder.CpuMesh translucentCpu = null;
        DistantMesh opaqueMesh = null;
        DistantMesh translucentMesh = null;
        try {
            var blockRenderer = Minecraft.getInstance().getBlockRenderer();
            var colors = Minecraft.getInstance().getBlockColors();
            boolean[] fullBlocks = new boolean[4096];
            for (int i = 0; i < blocks.length; i += 2) {
                int local = blocks[i];
                fullBlocks[local] = DistantFaceCulling.isFullBlock(
                        mapper.getBlockStateFromBlockId(blocks[i + 1]));
            }
            for (int i = 0; i < blocks.length; i += 2) {
                int local = blocks[i], blockId = blocks[i + 1];
                BlockState state = mapper.getBlockStateFromBlockId(blockId);
                var plan = CreateCopycatCompat.getBakePlan(mapper, blockId, state);
                if (plan.isEmpty() || !plan.detailedMesh()) continue;
                int x = local & 15, z = (local >>> 4) & 15, y = (local >>> 8) & 15;
                int wx = sx * 16 + x, wy = sy * 16 + y, wz = sz * 16 + z;
                BlockPos worldPos = new BlockPos(wx, wy, wz);
                var model = blockRenderer.getBlockModel(state);
                var modelData = plan.modelData();
                try {
                    modelData = model.getModelData(level, worldPos, state, modelData);
                } catch (Throwable ignored) {
                }
                int light = DistantLightSampler.sample(level, wx, wy, wz);
                java.util.function.ToIntFunction<net.minecraft.client.renderer.block.model.BakedQuad> tint = quad -> {
                    BlockState material = CreateCopycatCompat.materialForQuad(mapper, blockId, quad);
                    if (material == null) return 0xFFFFFF;
                    int sampled = colors.getColor(material, level, worldPos, quad.getTintIndex());
                    return sampled == -1 ? 0xFFFFFF : sampled & 0xFFFFFF;
                };
                for (RenderType layer : new RenderType[]{RenderType.solid(), RenderType.cutout(), RenderType.cutoutMipped()}) {
                    opaque.blockModelLayer(state, model, x, y, z,
                            DistantLightSampler.sky(light), DistantLightSampler.block(light),
                            layer, tint, modelData,
                            direction -> DistantFaceCulling
                                    .hidesSectionFace(fullBlocks, local, direction));
                }
                translucent.blockModelLayer(state, model, x, y, z,
                        DistantLightSampler.sky(light), DistantLightSampler.block(light),
                        RenderType.translucent(), tint, modelData,
                        direction -> DistantFaceCulling
                                .hidesSectionFace(fullBlocks, local, direction));
            }
            opaqueCpu = opaque.assemble();
            translucentCpu = translucent.assemble();
            opaqueMesh = DistantMeshBuilder.upload(opaqueCpu);
            opaqueCpu = null;
            translucentMesh = DistantMeshBuilder.upload(translucentCpu);
            translucentCpu = null;
            return new Meshes(opaqueMesh, translucentMesh);
        } catch (Throwable t) {
            if (opaqueCpu != null) opaqueCpu.free();
            if (translucentCpu != null) translucentCpu.free();
            if (opaqueMesh != null) opaqueMesh.free();
            if (translucentMesh != null) translucentMesh.free();
            opaque.discard();
            translucent.discard();
            Logger.error("Baking Create Copycats+ LOD mesh", t);
            return null;
        }
    }

    private static double distanceSq(long key, double x, double y, double z) {
        double dx = BlockPos.getX(key) * 16.0 + 8.0 - x;
        double dy = BlockPos.getY(key) * 16.0 + 8.0 - y;
        double dz = BlockPos.getZ(key) * 16.0 + 8.0 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void clearMeshes() {
        for (Entry entry : this.sections.values()) entry.free();
        this.sections.clear();
        this.bakeQueue.clear();
        this.queued.clear();
        this.lastScanX = this.lastScanZ = Integer.MIN_VALUE;
        this.lastMaxChunks = Integer.MIN_VALUE;
    }

    private record Update(SectionStorage storage, long key) {}
    private record Meshes(DistantMesh opaque, DistantMesh translucent) {}

    private static final class Entry {
        final int[] blocks;
        DistantMesh opaqueMesh;
        DistantMesh translucentMesh;
        boolean baked;

        Entry(int[] blocks) {
            this.blocks = blocks;
        }

        boolean hasMesh() {
            return this.opaqueMesh != null || this.translucentMesh != null;
        }

        void free() {
            if (this.opaqueMesh != null) this.opaqueMesh.free();
            if (this.translucentMesh != null) this.translucentMesh.free();
            this.opaqueMesh = null;
            this.translucentMesh = null;
            this.baked = false;
        }
    }
}
