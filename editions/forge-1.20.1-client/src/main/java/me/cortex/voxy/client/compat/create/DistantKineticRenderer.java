package me.cortex.voxy.client.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.distant.DistantBlockShader;
import me.cortex.voxy.client.compat.distant.DistantMesh;
import me.cortex.voxy.client.compat.distant.DistantVertexCapture;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

public final class DistantKineticRenderer implements LodPipelineHooks.Renderer {
    private static final int CAPTURES_PER_TICK = 2;
    public static final DistantKineticRenderer INSTANCE = new DistantKineticRenderer();
    private static final ThreadLocal<Boolean> CAPTURING = ThreadLocal.withInitial(() -> false);
    private final Map<Long, Snapshot> snapshots = new HashMap<>();
    private final Map<Long, dev.engine_room.flywheel.api.visual.BlockEntityVisual<?>> hiddenVisuals = new HashMap<>();
    private final java.util.Set<Long> invalidated = new HashSet<>();
    private final Map<KineticBlockEntity, FailedCapture> failedCaptures =
            new com.google.common.collect.MapMaker().weakKeys().makeMap();
    private ResourceKey<Level> dimension;
    private ClientLevel snapshotLevel;
    private long snapshotGeneration;
    private CreateSnapshotStore.Load<CreateSnapshotStore.Kinetic> restoreLoad;
    private CreateSnapshotStore.Kinetic pendingRestore;
    private final java.util.Set<Long> restoreObserved = new HashSet<>();
    private int scanCursor;
    private long captureTick;
    private int captureBudget = CAPTURES_PER_TICK;
    private LevelChunk pendingChunk;
    private final ArrayDeque<BlockPos> pendingCaptures = new ArrayDeque<>();
    private boolean updating;
    private boolean capturingChunks;

    private record Snapshot(int x, int y, int z, net.minecraft.world.level.block.state.BlockState state,
                            int light, DistantMesh mesh) {}

    private record FailedCapture(net.minecraft.world.level.block.state.BlockState state,
                                 int light, long retryAfterTick) {}

    private DistantKineticRenderer() {}

    public static boolean isCapturing() {
        return CAPTURING.get();
    }

    public static void onDataUpdated(BlockEntity be) {
        var mc = Minecraft.getInstance();
        if (!(be instanceof KineticBlockEntity kinetic) || be.getLevel() != mc.level || isCapturing()) return;
        if (!mc.isSameThread()) {
            mc.execute(() -> onDataUpdated(be));
            return;
        }
        long key = be.getBlockPos().asLong();
        INSTANCE.failedCaptures.remove(kinetic);
        if (INSTANCE.snapshots.containsKey(key)) INSTANCE.invalidated.add(key);
    }

    public static boolean shouldCullLive(BlockPos pos, net.minecraft.world.phys.Vec3 camera) {
        var mc = Minecraft.getInstance();
        var cfg = VoxyConfig.CONFIG;
        if (mc.level == null || mc.level != INSTANCE.snapshotLevel || !mc.level.dimension().equals(INSTANCE.dimension)
                || !cfg.isRenderingEnabled() || !cfg.distantKinetics) return false;
        Snapshot snapshot = INSTANCE.snapshots.get(pos.asLong());
        if (snapshot == null || snapshot.mesh == null) return false;
        double min = Math.max(16.0, mc.options.getEffectiveRenderDistance() * 16.0);
        double max = cfg.distantKineticMaxChunks == 0
                ? cfg.getLodRenderDistanceBlocks() : cfg.distantKineticMaxChunks * 16.0;
        double distanceSq = pos.distToCenterSqr(camera.x, camera.y, camera.z);
        return distanceSq >= min * min && distanceSq <= max * max;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || this.updating || this.capturingChunks) return;
        this.updating = true;
        try {
            this.updateSnapshots();
        } finally {
            this.updating = false;
        }
    }

    private void updateSnapshots() {
        this.captureTick++;
        this.captureBudget = CAPTURES_PER_TICK;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !VoxyConfig.CONFIG.isRenderingEnabled() || !VoxyConfig.CONFIG.distantKinetics) {
            this.clear();
            return;
        }
        var level = mc.level;
        if (level != this.snapshotLevel) {
            this.clear();
            this.snapshotLevel = level;
            this.dimension = level.dimension();
            this.restoreLoad = CreateSnapshotStore.loadKinetics(level);
        }
        long generation = this.snapshotGeneration;
        int radius = mc.options.getEffectiveRenderDistance() + 1;
        int side = radius * 2 + 1;
        int centerX = BlockPos.containing(mc.gameRenderer.getMainCamera().getPosition()).getX() >> 4;
        int centerZ = BlockPos.containing(mc.gameRenderer.getMainCamera().getPosition()).getZ() >> 4;
        var scannedChunks = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<LevelChunk>(8);
        int scanned = 0;
        if (this.pendingChunk != null) {
            LevelChunk chunk = this.pendingChunk;
            var pos = chunk.getPos();
            if (level.getChunkSource().getChunk(pos.x, pos.z, false) == chunk) {
                this.captureChunk(level, chunk, false);
                scannedChunks.put(pos.toLong(), chunk);
                scanned++;
            } else {
                this.pendingChunk = null;
                this.pendingCaptures.clear();
            }
            if (!this.isCurrentCapture(level, generation)) return;
        }
        for (int i = scanned; i < 8 && this.pendingChunk == null; i++) {
            int index = Math.floorMod(this.scanCursor++, side * side);
            int cx = centerX + index % side - radius;
            int cz = centerZ + index / side - radius;
            LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, false);
            if (chunk != null) {
                this.captureChunk(level, chunk, false);
                scannedChunks.put(chunk.getPos().toLong(), chunk);
            }
            if (!this.isCurrentCapture(level, generation)) return;
        }
        this.pruneScannedChunks(level, scannedChunks);
        this.restore(level);
        if (!this.isCurrentCapture(level, generation)) return;
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        double max = (VoxyConfig.CONFIG.distantKineticMaxChunks == 0
                ? VoxyConfig.CONFIG.getLodRenderDistanceBlocks()
                : VoxyConfig.CONFIG.distantKineticMaxChunks * 16.0) + 32.0;
        double maxSq = max * max;
        this.snapshots.entrySet().removeIf(entry -> {
            Snapshot snapshot = entry.getValue();
            double dx = snapshot.x + 0.5 - camera.x;
            double dy = snapshot.y + 0.5 - camera.y;
            double dz = snapshot.z + 0.5 - camera.z;
            if (dx * dx + dy * dy + dz * dz <= maxSq) return false;
            snapshot.mesh.free();
            this.invalidated.remove(entry.getKey());
            return true;
        });
        this.syncFlywheelVisibility(mc);
    }

    @SubscribeEvent
    public void chunkUnload(ChunkEvent.Unload event) {
        var mc = Minecraft.getInstance();
        if (mc.level != null && event.getLevel() == mc.level && event.getChunk() instanceof LevelChunk chunk) {
            this.captureChunk(mc.level, chunk, true);
        }
    }

    @SubscribeEvent
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        this.clear();
        this.dimension = null;
    }

    private void captureChunk(ClientLevel level, LevelChunk chunk, boolean unloading) {
        if (this.capturingChunks || Minecraft.getInstance().level != level) return;
        this.capturingChunks = true;
        try {
            this.captureChunkCurrent(level, chunk, unloading);
        } finally {
            this.capturingChunks = false;
        }
    }

    private boolean isCurrentCapture(ClientLevel level, long generation) {
        return this.snapshotGeneration == generation && Minecraft.getInstance().level == level;
    }

    private void captureChunkCurrent(ClientLevel level, LevelChunk chunk, boolean unloading) {
        long generation = this.snapshotGeneration;
        boolean deferred = !unloading && this.pendingChunk == chunk;
        var iterator = deferred ? null : chunk.getBlockEntities().values().iterator();
        while (deferred ? !this.pendingCaptures.isEmpty() : iterator.hasNext()) {
            BlockEntity blockEntity = deferred
                    ? chunk.getBlockEntities().get(this.pendingCaptures.removeFirst()) : iterator.next();
            if (!(blockEntity instanceof KineticBlockEntity kinetic)) continue;
            if (kinetic.getLevel() != level) continue;
            long key = kinetic.getBlockPos().asLong();
            var state = kinetic.getBlockState();
            int light = LevelRenderer.getLightColor(level, kinetic.getBlockPos());
            if (!this.isCurrentCapture(level, generation)) return;
            Snapshot old = this.snapshots.get(key);
            if (old != null && old.state == state && old.light == light && !this.invalidated.contains(key)) continue;
            FailedCapture failed = this.failedCaptures.get(kinetic);
            if (!unloading && failed != null && failed.state == state && failed.light == light
                    && this.captureTick < failed.retryAfterTick) continue;
            if (!unloading) {
                if (this.captureBudget == 0) {
                    if (deferred) {
                        this.pendingCaptures.addFirst(kinetic.getBlockPos().immutable());
                    } else {
                        this.pendingChunk = chunk;
                        this.pendingCaptures.addLast(kinetic.getBlockPos().immutable());
                        // 只保存本区块剩余位置，避免反复扫描前缀或持有已卸载的实体。
                        while (iterator.hasNext()) {
                            var remaining = iterator.next();
                            if (remaining instanceof KineticBlockEntity && remaining.getLevel() == level) {
                                this.pendingCaptures.addLast(remaining.getBlockPos().immutable());
                            }
                        }
                    }
                    return;
                }
                this.captureBudget--;
            }
            DistantMesh.Built built = capture(kinetic, light);
            // 回调可能清理、重载或替换同位置来源；只发布原会话的当前 owner。
            if (!this.isCurrentCapture(level, generation) || this.snapshots.get(key) != old
                    || kinetic.getLevel() != level || kinetic.getBlockState() != state
                    || chunk.getBlockEntities().get(kinetic.getBlockPos()) != kinetic) {
                if (built.mesh() != null) built.mesh().free();
                return;
            }
            if (built.mesh() == null) {
                // 同一失败输入退避；卸载时实体仍可达，保留最后一次捕获机会。
                if (unloading) this.failedCaptures.remove(kinetic);
                else this.failedCaptures.put(kinetic, new FailedCapture(state, light, this.captureTick + 20));
                continue;
            }
            this.failedCaptures.remove(kinetic);
            if (old != null) old.mesh.free();
            var pos = kinetic.getBlockPos();
            this.snapshots.put(key, new Snapshot(pos.getX(), pos.getY(), pos.getZ(), state, light, built.mesh()));
            if (this.restoreLoad != null) this.restoreObserved.add(key);
            this.invalidated.remove(key);
            CreateSnapshotStore.saveKinetic(level, new CreateSnapshotStore.Kinetic(
                    key, pos.getX(), pos.getY(), pos.getZ(), state.hashCode(), built.bytes()));
        }
        if (deferred) this.pendingChunk = null;
    }

    private void pruneScannedChunks(ClientLevel level,
                                   it.unimi.dsi.fastutil.longs.Long2ObjectMap<LevelChunk> scannedChunks) {
        if (scannedChunks.isEmpty()) return;
        var pos = new BlockPos.MutableBlockPos();
        // 本 tick 的已扫描区块共用一次清理，避免每个区块重扫全部历史。
        this.snapshots.entrySet().removeIf(entry -> {
            Snapshot snapshot = entry.getValue();
            long chunkKey = net.minecraft.world.level.ChunkPos.asLong(snapshot.x >> 4, snapshot.z >> 4);
            LevelChunk chunk = scannedChunks.get(chunkKey);
            if (chunk == null) return false;
            var be = chunk.getBlockEntities().get(pos.set(snapshot.x, snapshot.y, snapshot.z));
            if (be instanceof KineticBlockEntity && be.getLevel() == level) return false;
            snapshot.mesh.free();
            this.invalidated.remove(entry.getKey());
            if (this.restoreLoad != null) this.restoreObserved.add(entry.getKey());
            CreateSnapshotStore.removeKinetic(level, entry.getKey());
            return true;
        });
    }

    @SuppressWarnings("unchecked")
    private static DistantMesh.Built capture(KineticBlockEntity kinetic, int light) {
        var dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        var renderer = dispatcher.getRenderer(kinetic);
        if (renderer == null) return new DistantMesh.Built(null, null);
        var builder = new DistantMesh.Builder();
        int blockLight = light & 0xFFFF;
        int skyLight = light >>> 16 & 0xFFFF;
        var capture = new DistantVertexCapture(builder, true, blockLight, skyLight);
        CAPTURING.set(true);
        try {
            ((net.minecraft.client.renderer.blockentity.BlockEntityRenderer<KineticBlockEntity>) renderer)
                    .render(kinetic, 1.0f, new PoseStack(), capture, light, OverlayTexture.NO_OVERLAY);
            return builder.buildPersistent();
        } catch (Throwable ignored) {
            builder.discard();
            return new DistantMesh.Built(null, null);
        } finally {
            CAPTURING.set(false);
        }
    }

    @Override
    public void onResourceReload() {
        this.snapshotGeneration++;
        // 图集或模型变化后，由正常扫描重捕获；失败仍保留旧快照和失效标记。
        this.invalidated.addAll(this.snapshots.keySet());
        this.failedCaptures.clear();
    }

    private void restore(ClientLevel level) {
        var load = this.restoreLoad;
        if (load == null) return;
        long generation = this.snapshotGeneration;
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double max = (VoxyConfig.CONFIG.distantKineticMaxChunks == 0
                ? VoxyConfig.CONFIG.getLodRenderDistanceBlocks() : VoxyConfig.CONFIG.distantKineticMaxChunks * 16.0) + 32.0;
        int uploads = 0;
        for (int i = 0; i < 16 && uploads < 2 && this.captureBudget > 0; i++) {
            if (!this.isCurrentRestore(level, generation, load)) return;
            var stored = this.pendingRestore == null ? load.poll() : this.pendingRestore;
            if (stored == null) break;
            this.pendingRestore = null;
            if (this.restoreObserved.contains(stored.key()) || this.snapshots.containsKey(stored.key())) continue;
            double dx = stored.x() + 0.5 - camera.x;
            double dy = stored.y() + 0.5 - camera.y;
            double dz = stored.z() + 0.5 - camera.z;
            if (dx * dx + dy * dy + dz * dz > max * max) continue;
            uploads++;
            this.captureBudget--;
            this.pendingRestore = stored;
            DistantMesh mesh = null;
            try {
                mesh = DistantMesh.fromBytes(stored.mesh());
                if (!this.isCurrentRestore(level, generation, load)) return;
                if (mesh != null && !this.restoreObserved.contains(stored.key())) {
                    var snapshot = new Snapshot(stored.x(), stored.y(), stored.z(), null, -1, mesh);
                    if (this.snapshots.putIfAbsent(stored.key(), snapshot) == null) mesh = null;
                }
            } catch (Throwable failure) {
                me.cortex.voxy.common.Logger.error("Restoring Create kinetic LOD snapshot", failure);
            } finally {
                if (mesh != null) mesh.free();
                if (this.isCurrentRestore(level, generation, load)) this.pendingRestore = null;
            }
        }
        if (this.pendingRestore == null && load.isFinished() && this.isCurrentRestore(level, generation, load)) {
            load.close();
            this.restoreLoad = null;
            this.restoreObserved.clear();
        }
    }

    private boolean isCurrentRestore(ClientLevel level, long generation, CreateSnapshotStore.Load<?> load) {
        return this.snapshotLevel == level && Minecraft.getInstance().level == level
                && this.snapshotGeneration == generation && this.restoreLoad == load
                && VoxyConfig.CONFIG.isRenderingEnabled() && VoxyConfig.CONFIG.distantKinetics;
    }

    @Override
    public void render(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != this.snapshotLevel || !mc.level.dimension().equals(this.dimension)
                || !VoxyConfig.CONFIG.distantKinetics || this.snapshots.isEmpty()) return;
        double min = Math.max(16.0, mc.options.getEffectiveRenderDistance() * 16.0);
        double minSq = min * min;
        double max = VoxyConfig.CONFIG.distantKineticMaxChunks == 0
                ? VoxyConfig.CONFIG.getLodRenderDistanceBlocks()
                : VoxyConfig.CONFIG.distantKineticMaxChunks * 16.0;
        double maxSq = max * max;
        boolean bound = false;
        Matrix4f transform = new Matrix4f();
        pipeline.setupAndBindOpaque(viewport);
        for (Snapshot snapshot : this.snapshots.values()) {
            double dx = snapshot.x - viewport.cameraX;
            double dy = snapshot.y - viewport.cameraY;
            double dz = snapshot.z - viewport.cameraZ;
            double distanceSq = (dx + 0.5) * (dx + 0.5) + (dy + 0.5) * (dy + 0.5)
                    + (dz + 0.5) * (dz + 0.5);
            if (distanceSq < minSq || distanceSq > maxSq) continue;
            if (!bound) {
                DistantBlockShader.get(pipeline).bind();
                DistantBlockShader.bindTextures();
                glEnable(GL_DEPTH_TEST);
                glDepthFunc(depthFunc);
                glDepthMask(true);
                glDisable(GL_CULL_FACE);
                glDisable(GL_BLEND);
                glEnable(GL_STENCIL_TEST);
                glStencilFunc(GL_ALWAYS, 3, 0xFF);
                glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
                bound = true;
            }
            transform.set(viewport.MVP).translate((float) dx, (float) dy, (float) dz);
            DistantBlockShader.uploadTransform(transform);
            snapshot.mesh.draw();
        }
        if (bound) {
            glBindVertexArray(0);
            glUseProgram(0);
            glStencilFunc(GL_EQUAL, 1, 0xFF);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        }
    }

    private void clear() {
        this.snapshotGeneration++;
        if (this.restoreLoad != null) this.restoreLoad.close();
        this.restoreLoad = null;
        this.pendingRestore = null;
        this.restoreObserved.clear();
        this.snapshotLevel = null;
        this.dimension = null;
        this.restoreFlywheelVisibility();
        for (Snapshot snapshot : this.snapshots.values()) snapshot.mesh.free();
        this.snapshots.clear();
        this.invalidated.clear();
        this.failedCaptures.clear();
        this.pendingChunk = null;
        this.pendingCaptures.clear();
        this.scanCursor = 0;
        this.captureTick = 0;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void syncFlywheelVisibility(Minecraft mc) {
        var manager = dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl.get(mc.level);
        if (manager == null) {
            this.restoreFlywheelVisibility();
            return;
        }
        var visualManager = (dev.engine_room.flywheel.impl.visualization.VisualManagerImpl) manager.blockEntities();
        var storage = (dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage) visualManager.getStorage();
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        var hidden = this.hiddenVisuals.entrySet().iterator();
        while (hidden.hasNext()) {
            var entry = hidden.next();
            if (!this.snapshots.containsKey(entry.getKey()) || storage.visualAtPos(entry.getKey()) != entry.getValue()
                    || !shouldCullLive(BlockPos.of(entry.getKey()), camera)) {
                setVisible(entry.getValue(), true);
                hidden.remove();
            }
        }
        for (var entry : this.snapshots.entrySet()) {
            var visual = storage.visualAtPos(entry.getKey());
            if (visual == null) continue;
            if (shouldCullLive(BlockPos.of(entry.getKey()), camera)) {
                setVisible(visual, false);
                this.hiddenVisuals.put(entry.getKey(), visual);
            }
        }
    }

    private void restoreFlywheelVisibility() {
        // 保留实际被隐藏的 visual，移除快照或切换世界后仍能归还可见性。
        for (var visual : this.hiddenVisuals.values()) setVisible(visual, true);
        this.hiddenVisuals.clear();
    }

    private static void setVisible(dev.engine_room.flywheel.api.visual.BlockEntityVisual<?> visual, boolean visible) {
        visual.collectCrumblingInstances(instance -> {
            if (instance != null) {
                instance.setVisible(visible);
                if (visible) instance.setChanged();
            }
        });
    }
}
