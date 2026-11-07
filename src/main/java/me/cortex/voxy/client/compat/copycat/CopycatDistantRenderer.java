package me.cortex.voxy.client.compat.copycat;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.AuxiliaryTableLoader;
import me.cortex.voxy.client.compat.NearbyBuckets;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.TreeSet;
import java.util.Comparator;
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
    private static final double PREFETCH_BLOCKS = 114.0;
    private static volatile CopycatDistantRenderer active;

    private final ConcurrentLinkedQueue<Update> updates = new ConcurrentLinkedQueue<>();
    private final Map<Long, Entry> sections = new HashMap<>();
    private final Map<Long, HashSet<Entry>> spatialBuckets = new HashMap<>();
    private final ArrayList<Entry> candidates = new ArrayList<>();
    private final HashSet<Entry> residentMeshes = new HashSet<>();
    private final LinkedHashSet<Entry> bakeQueue = new LinkedHashSet<>();
    private final TreeSet<Entry> retries = new TreeSet<>(Comparator.<Entry>comparingLong(e -> e.retryAfterNanos)
            .thenComparingLong(e -> e.key));
    private final HashSet<Entry> queued = new HashSet<>();
    private SectionStorage storage;
    private AuxiliaryTableLoader<int[]> baseline;
    private final java.util.ArrayDeque<ReadRetry> readRetries = new java.util.ArrayDeque<>();
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet retryKeys = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet pendingUpdates = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private ClientLevel level;
    private int lastScanX = Integer.MIN_VALUE;
    private int lastScanZ = Integer.MIN_VALUE;
    private int lastScanY = Integer.MIN_VALUE;
    private double lastScanDistance = -1;
    private boolean candidatesDirty = true;
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
        if (mc.level == null) {
            unbindStorage();
            return;
        }
        var engine = WorldIdentifier.ofEngineNullable(mc.level);
        if (engine == null || !engine.isLive()) {
            unbindStorage();
            return;
        }
        if (this.storage != engine.storage || this.level != mc.level) {
            unbindStorage();
            this.storage = engine.storage;
            this.level = mc.level;
            this.baseline = new AuxiliaryTableLoader<>(engine, CreateCopycatCompat.DISGUISE_TABLE,
                    (key, value) -> decode(value), pairs -> 24L + pairs.length * 4L);
        }

        drainBaseline(128 - drainUpdates(this.baseline == null ? 128 : 96));
        if (!VoxyConfig.CONFIG.isRenderingEnabled() || !VoxyConfig.CONFIG.distantCopycats) {
            this.candidatesDirty = true;
            return;
        }
        int maxChunks = VoxyConfig.CONFIG.distantCopycatsMaxChunks;
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        double maxDistance = VoxyConfig.CONFIG.createRenderDistance(maxChunks);
        double maxDistanceSq = (maxDistance + PREFETCH_BLOCKS) * (maxDistance + PREFETCH_BLOCKS);
        refreshCandidates(camera.x, camera.y, camera.z, maxDistance);
        scheduleBakes(engine.getMapper(), mc.level, camera.x, camera.y, camera.z, maxDistanceSq);
    }

    private void refreshCandidates(double cameraX, double cameraY, double cameraZ, double maxDistance) {
        double maxDistanceSq = (maxDistance + PREFETCH_BLOCKS) * (maxDistance + PREFETCH_BLOCKS);
        int cx = ((int) Math.floor(cameraX)) >> 4;
        int cy = ((int) Math.floor(cameraY)) >> 4;
        int cz = ((int) Math.floor(cameraZ)) >> 4;
        if (!this.candidatesDirty && this.lastScanDistance == maxDistance
                && Math.abs(cx - this.lastScanX) < 4 && Math.abs(cy - this.lastScanY) < 4
                && Math.abs(cz - this.lastScanZ) < 4) return;
        this.candidates.clear();
        int bx = cx >> 3, bz = cz >> 3;
        int radius = (int) Math.ceil((maxDistance + PREFETCH_BLOCKS) / 128.0) + 1;
        NearbyBuckets.forEach(this.spatialBuckets, bx, bz, radius, bucket -> {
            for (Entry entry : bucket) {
                if (distanceSq(entry.key, cameraX, cameraY, cameraZ) > maxDistanceSq) continue;
                this.candidates.add(entry);
                if (!entry.baked && this.queued.add(entry)) this.bakeQueue.add(entry);
            }
        });
        double farSq = (maxDistance + 256.0) * (maxDistance + 256.0);
        for (var iterator = this.residentMeshes.iterator(); iterator.hasNext();) {
            Entry entry = iterator.next();
            if (distanceSq(entry.key, cameraX, cameraY, cameraZ) > farSq) {
                entry.free();
                iterator.remove();
            }
        }
        this.lastScanX = cx;
        this.lastScanY = cy;
        this.lastScanZ = cz;
        this.lastScanDistance = maxDistance;
        this.candidatesDirty = false;
    }

    private void scheduleBakes(Mapper mapper, ClientLevel level, double cameraX, double cameraY,
                               double cameraZ, double maxDistanceSq) {
        long now = System.nanoTime();
        int checks = 128;
        while (checks > 0 && !this.retries.isEmpty() && now - this.retries.first().retryAfterNanos >= 0) {
            this.bakeQueue.add(this.retries.pollFirst());
            checks--;
        }
        for (int baked = 0; baked < MAX_BAKES_PER_TICK && checks-- > 0 && !this.bakeQueue.isEmpty();) {
            Entry entry = this.bakeQueue.removeFirst();
            this.queued.remove(entry);
            if (this.sections.get(entry.key) != entry || entry.baked
                    || distanceSq(entry.key, cameraX, cameraY, cameraZ) > maxDistanceSq) continue;
            Meshes meshes = bake(entry.key, entry.blocks, mapper, level);
            if (meshes != null) {
                entry.baked = true;
                entry.opaqueMesh = meshes.opaque;
                entry.translucentMesh = meshes.translucent;
                if (entry.hasMesh()) this.residentMeshes.add(entry);
            } else if (this.queued.add(entry)) {
                entry.retryAfterNanos = now + 1_000_000_000L;
                this.retries.add(entry);
            }
            baked++;
        }
    }

    @Override
    public void onResourceReload() {
        // 保留材质快照，下一 tick 按既有额度重建图集相关网格。
        this.bakeQueue.clear();
        this.queued.clear();
        this.retries.clear();
        for (Entry entry : this.sections.values()) entry.free();
        this.residentMeshes.clear();
        this.candidatesDirty = true;
    }

    @SubscribeEvent
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (this.depthSampler != 0) org.lwjgl.opengl.GL33C.glDeleteSamplers(this.depthSampler);
        this.depthSampler = 0;
        unbindStorage();
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
            for (Entry entry : this.candidates) {
                DistantMesh mesh = translucent ? entry.translucentMesh : entry.opaqueMesh;
                if (mesh == null) continue;
                long key = entry.key;
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

    private int drainUpdates(int limit) {
        this.pendingUpdates.clear();
        long now = System.nanoTime();
        int applied = 0;
        // 到期重读与新通知共用额度；失败的同 key 通知合并，避免每 tick 重试 IO。
        while (applied < Math.min(32, limit) && !this.readRetries.isEmpty()
                && now - this.readRetries.peekFirst().retryAtNanos >= 0) {
            this.pendingUpdates.add(this.readRetries.removeFirst().key);
            applied++;
        }
        for (; applied < limit;) {
            Update update = this.updates.poll();
            if (update == null) break;
            applied++;
            if (update.storage == this.storage && !this.retryKeys.contains(update.key)) this.pendingUpdates.add(update.key);
        }
        for (var iterator = this.pendingUpdates.iterator(); iterator.hasNext();) {
            long key = iterator.nextLong();
            if (this.baseline != null) this.baseline.markUpdated(key);
            byte[] value;
            try {
                value = this.storage.getAux(CreateCopycatCompat.DISGUISE_TABLE, key);
            } catch (RuntimeException failure) {
                if (this.retryKeys.add(key)) Logger.error("Reading Copycat LOD update", failure);
                this.readRetries.addLast(new ReadRetry(key, System.nanoTime() + 1_000_000_000L));
                continue;
            }
            this.retryKeys.remove(key);
            install(key, value, true);
        }
        return applied;
    }

    private void drainBaseline(int limit) {
        if (this.baseline == null) return;
        this.baseline.drain(limit, (key, pairs) -> installDecoded(key, pairs, false));
        if (this.baseline.isFinished()) {
            this.baseline.close();
            this.baseline = null;
        }
    }

    private void unbindStorage() {
        if (this.baseline != null) this.baseline.close();
        this.baseline = null;
        clearMeshes();
        this.updates.clear();
        this.pendingUpdates.clear();
        this.readRetries.clear();
        this.retryKeys.clear();
        this.storage = null;
        this.level = null;
    }

    private void install(long key, byte[] value, boolean urgent) {
        installDecoded(key, decode(value), urgent);
    }

    private void installDecoded(long key, int[] pairs, boolean urgent) {
        Entry old = this.sections.get(key);
        if (old != null && java.util.Arrays.equals(old.blocks, pairs)) return;
        this.sections.remove(key);
        if (old != null) {
            old.free();
            this.residentMeshes.remove(old);
            this.queued.remove(old);
            this.bakeQueue.remove(old);
            this.retries.remove(old);
            var bucket = this.spatialBuckets.get(bucketKey(BlockPos.getX(key) >> 3, BlockPos.getZ(key) >> 3));
            if (bucket != null) {
                bucket.remove(old);
                if (bucket.isEmpty()) this.spatialBuckets.remove(bucketKey(BlockPos.getX(key) >> 3, BlockPos.getZ(key) >> 3));
            }
        }
        this.candidatesDirty = true;
        if (pairs.length == 0) return;
        Entry entry = new Entry(key, pairs);
        this.sections.put(key, entry);
        this.spatialBuckets.computeIfAbsent(bucketKey(BlockPos.getX(key) >> 3, BlockPos.getZ(key) >> 3),
                ignored -> new HashSet<>()).add(entry);

        Minecraft mc = Minecraft.getInstance();
        if (VoxyConfig.CONFIG.distantCopycats && mc.level == this.level) {
            var camera = mc.gameRenderer.getMainCamera().getPosition();
            double maxDistance = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantCopycatsMaxChunks);
            double prepareDistance = maxDistance + PREFETCH_BLOCKS;
            if (distanceSq(key, camera.x, camera.y, camera.z) <= prepareDistance * prepareDistance
                    && this.queued.add(entry)) {
                if (urgent) this.bakeQueue.addFirst(entry);
                else this.bakeQueue.addLast(entry);
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
        DistantMeshBuilder opaque = null, translucent = null;
        DistantMeshBuilder.CpuMesh opaqueCpu = null;
        DistantMeshBuilder.CpuMesh translucentCpu = null;
        DistantMesh opaqueMesh = null;
        DistantMesh translucentMesh = null;
        try {
            opaque = new DistantMeshBuilder();
            translucent = DistantMeshBuilder.translucent();
            var blockRenderer = Minecraft.getInstance().getBlockRenderer();
            var colors = Minecraft.getInstance().getBlockColors();
            boolean[] fullBlocks = new boolean[4096];
            for (int i = 0; i < blocks.length; i += 2) {
                int local = blocks[i];
                fullBlocks[local] = DistantFaceCulling.isFullBlock(
                        mapper.getBlockStateFromBlockId(blocks[i + 1]));
            }
            try (var lightSampler = DistantLightSampler.batch(level)) {
                for (int i = 0; i < blocks.length; i += 2) {
                    int local = blocks[i], blockId = blocks[i + 1];
                    BlockState state = mapper.getBlockStateFromBlockId(blockId);
                    var plan = CreateCopycatCompat.getBakePlan(mapper, blockId, state);
                    if (plan.isEmpty()) throw new IllegalStateException("Copycat material is not ready: " + blockId);
                    if (!plan.detailedMesh()) continue;
                    int x = local & 15, z = (local >>> 4) & 15, y = (local >>> 8) & 15;
                    int wx = sx * 16 + x, wy = sy * 16 + y, wz = sz * 16 + z;
                    BlockPos worldPos = new BlockPos(wx, wy, wz);
                    var model = blockRenderer.getBlockModel(state);
                    var modelData = plan.modelData();
                    try {
                        modelData = model.getModelData(level, worldPos, state, modelData);
                    } catch (Throwable ignored) {
                    }
                    int light = lightSampler.sample(wx, wy, wz);
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
            if (opaque != null) opaque.discard();
            if (translucent != null) translucent.discard();
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
        this.retries.clear();
        this.spatialBuckets.clear();
        this.candidates.clear();
        this.residentMeshes.clear();
        this.lastScanX = this.lastScanY = this.lastScanZ = Integer.MIN_VALUE;
        this.lastScanDistance = -1;
        this.candidatesDirty = true;
    }

    private static long bucketKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private record ReadRetry(long key, long retryAtNanos) {}
    private record Update(SectionStorage storage, long key) {}
    private record Meshes(DistantMesh opaque, DistantMesh translucent) {}

    private static final class Entry {
        final long key;
        final int[] blocks;
        DistantMesh opaqueMesh;
        DistantMesh translucentMesh;
        boolean baked;
        long retryAfterNanos;

        Entry(long key, int[] blocks) {
            this.key = key;
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
