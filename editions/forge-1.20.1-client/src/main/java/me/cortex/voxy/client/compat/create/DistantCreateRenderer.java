package me.cortex.voxy.client.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.distant.DistantBlockShader;
import me.cortex.voxy.client.compat.distant.DistantMesh;
import me.cortex.voxy.client.compat.distant.DistantVertexCapture;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.commonImpl.compat.create.CreateShapeRevision;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

public final class DistantCreateRenderer implements LodPipelineHooks.Renderer {
    public static final DistantCreateRenderer INSTANCE = new DistantCreateRenderer();
    private static final PoseStack POSE = new PoseStack();
    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private ResourceKey<Level> dimension;
    private long maintenanceTick;
    private int resourceGeneration;
    private int remainingBuilds;
    private long nextMeshSignature;
    // 同维度也可能换 ClientLevel，快照只属于原来的世界对象。
    private net.minecraft.client.multiplayer.ClientLevel snapshotLevel;
    private long snapshotGeneration;
    private boolean updating;
    private boolean restorePending;
    private CreateSnapshotStore.Load<CreateSnapshotStore.Contraption> restoreLoad;
    private final java.util.Set<UUID> restoreObserved = new HashSet<>();
    private record PendingSnapshot(UUID id, Snapshot snapshot) {}
    private final java.util.ArrayDeque<PendingSnapshot> rebuildQueue = new java.util.ArrayDeque<>();

    private static final class Snapshot {
        DistantMesh mesh;
        byte[] meshBytes;
        CreateMeshSource meshSource;
        int resourceGeneration;
        int failedResourceGeneration = -1;
        boolean rebuildQueued;
        final Matrix4f local = new Matrix4f();
        double x;
        double y;
        double z;
        long signature;
        java.lang.ref.WeakReference<Object> observedSource;
        long observedRevision;
        long observedSignature;
        long failedSignature;
        long retryAfterTick;
        long lastSeen;
        boolean train;
        boolean live;
        int entityId = -1;
        int light = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
        long lightSampledAt;
        List<IntegratedContraptionTracker.BogeyInfo> bogeyInfo = List.of();
        List<IntegratedContraptionTracker.BogeyInfo> capturedBogeyInfo;
        DistantMesh[] bogeyMeshes;
        boolean[] bogeyResolved;
        int bogeyMeshGeneration;
        List<IntegratedContraptionTracker.BogeyPose> previousBogeys = List.of();
        List<IntegratedContraptionTracker.BogeyPose> currentBogeys = List.of();
        long bogeyReceivedNanos;
        long bogeyIntervalNanos = 250_000_000L;
        IntegratedContraptionTracker.Track remoteTrack;
    }

    private DistantCreateRenderer() {}

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || this.updating) return;
        this.updating = true;
        try {
            this.updateSnapshots();
        } finally {
            this.updating = false;
        }
    }

    private void updateSnapshots() {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (mc.level == null || !VoxyConfig.CONFIG.isRenderingEnabled()
                || (!VoxyConfig.CONFIG.distantContraptions && !VoxyConfig.CONFIG.distantTrains)) {
            this.clear();
            return;
        }
        if (level != this.snapshotLevel) {
            this.clear();
            this.snapshotLevel = level;
            this.dimension = level.dimension();
            this.restorePending = true;
        }

        long generation = this.snapshotGeneration;
        if (this.restorePending) {
            try {
                this.restore(level);
            } finally {
                if (this.isCurrentUpdate(level, generation)
                        && (this.restoreLoad == null || this.restoreLoad.isFinished())) {
                    if (this.restoreLoad != null) this.restoreLoad.close();
                    this.restoreLoad = null;
                    this.restoreObserved.clear();
                    this.restorePending = false;
                }
            }
            if (!this.isCurrentUpdate(level, generation)) return;
        }
        this.maintenanceTick++;
        this.remainingBuilds = 2;
        long now = System.currentTimeMillis();
        var seen = new HashSet<UUID>();
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof AbstractContraptionEntity contraptionEntity)) continue;
            var contraption = contraptionEntity.getContraption();
            if (contraption == null || contraption.getBlocks().isEmpty()) continue;
            UUID id = snapshotId(contraptionEntity);
            seen.add(id);
            if (this.restorePending) this.restoreObserved.add(id);
            Snapshot snapshot = this.snapshots.computeIfAbsent(id, ignored -> new Snapshot());
            snapshot.train = entity instanceof CarriageContraptionEntity;
            snapshot.live = true;
            snapshot.x = entity.getX();
            snapshot.y = entity.getY();
            snapshot.z = entity.getZ();
            snapshot.lastSeen = now;
            snapshot.entityId = entity.getId();
            if (now - snapshot.lightSampledAt >= 1000) {
                snapshot.light = sampleLight(mc.level, snapshot.x, snapshot.y, snapshot.z);
                snapshot.lightSampledAt = now;
            }

            long signature = this.meshSignature(snapshot, contraption, shapeRevision(contraption));
            boolean meshChanged = false;
            if (shouldRebuild(snapshot, signature) && this.takeMeshBudget()) {
                meshChanged = rebuildLive(snapshot, contraptionEntity, signature, id, level, generation);
                if (!this.isCurrentSnapshot(snapshot, id, level, generation)) return;
            }

            if (!this.isCurrentUpdate(level, generation)) return;
            POSE.pushPose();
            try {
                contraptionEntity.applyLocalTransforms(POSE, 1.0f);
                if (!this.isCurrentSnapshot(snapshot, id, level, generation)) return;
                snapshot.local.set(POSE.last().pose());
            } catch (Throwable ignored) {
                if (!this.isCurrentSnapshot(snapshot, id, level, generation)) return;
            } finally {
                POSE.popPose();
            }
            if (meshChanged) this.save(level, id, snapshot);
        }

        IntegratedContraptionTracker.update(mc, this.snapshots, seen);
        if (!this.isCurrentUpdate(level, generation)) return;
        this.rebuildQueuedSources(level);
        if (!this.isCurrentUpdate(level, generation)) return;

        double reach = mc.options.getEffectiveRenderDistance() * 16.0;
        double reachSq = reach * reach;
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        this.snapshots.entrySet().removeIf(entry -> {
            Snapshot snapshot = entry.getValue();
            if (seen.contains(entry.getKey())) return false;
            if (snapshot.live) this.save(mc.level, entry.getKey(), snapshot);
            snapshot.live = false;
            double dx = snapshot.x - camera.x;
            double dy = snapshot.y - camera.y;
            double dz = snapshot.z - camera.z;
            if (now - snapshot.lastSeen >= 2000 && dx * dx + dy * dy + dz * dz < reachSq) {
                if (snapshot.mesh != null) snapshot.mesh.free();
                return true;
            }
            double max = distanceFor(snapshot) + 32.0;
            if (dx * dx + dy * dy + dz * dz > max * max) {
                if (snapshot.mesh != null) snapshot.mesh.free();
                return true;
            }
            return false;
        });
    }

    @SubscribeEvent
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        var level = Minecraft.getInstance().level;
        if (level != null && level == this.snapshotLevel) {
            for (var entry : this.snapshots.entrySet()) this.save(level, entry.getKey(), entry.getValue());
        }
        CreateSnapshotStore.flush();
        this.clear();
        this.dimension = null;
    }

    @Override
    public void render(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != this.snapshotLevel
                || !mc.level.dimension().equals(this.dimension) || this.snapshots.isEmpty()
                || !VoxyConfig.CONFIG.isRenderingEnabled()) return;
        double vanilla = Math.max(16.0, mc.options.getEffectiveRenderDistance() * 16.0);
        double vanillaSq = vanilla * vanilla;
        boolean bound = false;
        Matrix4f transform = new Matrix4f();
        Matrix4f liveLocal = new Matrix4f();
        pipeline.setupAndBindOpaque(viewport);
        for (var entry : this.snapshots.entrySet()) {
            Snapshot snapshot = entry.getValue();
            if (snapshot.train && !VoxyConfig.CONFIG.distantTrains
                    || !snapshot.train && !VoxyConfig.CONFIG.distantContraptions) continue;
            double worldX = snapshot.x;
            double worldY = snapshot.y;
            double worldZ = snapshot.z;
            Matrix4f local = snapshot.local;
            var entity = snapshot.entityId < 0 ? null : mc.level.getEntity(snapshot.entityId);
            if (!(entity instanceof AbstractContraptionEntity candidate)
                    || !snapshotId(candidate).equals(entry.getKey())) entity = null;
            if (entity instanceof AbstractContraptionEntity contraptionEntity && !entity.isRemoved()) {
                float partial = mc.getFrameTime();
                worldX = net.minecraft.util.Mth.lerp(partial, entity.xOld, entity.getX());
                worldY = net.minecraft.util.Mth.lerp(partial, entity.yOld, entity.getY());
                worldZ = net.minecraft.util.Mth.lerp(partial, entity.zOld, entity.getZ());
                POSE.pushPose();
                try {
                    contraptionEntity.applyLocalTransforms(POSE, partial);
                    local = liveLocal.set(POSE.last().pose());
                } catch (Throwable ignored) {
                } finally {
                    POSE.popPose();
                }
            } else if (snapshot.remoteTrack != null && snapshot.remoteTrack.current != null) {
                var current = snapshot.remoteTrack.current;
                var previous = snapshot.remoteTrack.previous == null ? current : snapshot.remoteTrack.previous;
                float amount = Math.max(0.0f, Math.min(1.25f,
                        (float) (System.nanoTime() - current.receivedNanos()) / snapshot.remoteTrack.intervalNanos));
                worldX = net.minecraft.util.Mth.lerp(amount, previous.x(), current.x());
                worldY = net.minecraft.util.Mth.lerp(amount, previous.y(), current.y());
                worldZ = net.minecraft.util.Mth.lerp(amount, previous.z(), current.z());
                if (current.train()) {
                    float yaw = previous.yaw() + net.minecraft.util.Mth.wrapDegrees(
                            current.yaw() - previous.yaw()) * amount;
                    float pitch = net.minecraft.util.Mth.lerp(amount, previous.pitch(), current.pitch());
                    local = liveLocal.identity().translate(0.0f, 0.5f, 0.0f)
                            .rotateY((float) Math.toRadians(-yaw))
                            .rotateZ((float) Math.toRadians(pitch))
                            .rotateY((float) Math.toRadians(current.initialYaw()))
                            .translate(-0.5f, -0.5f, -0.5f);
                }
            }
            double dx = worldX - viewport.cameraX;
            double dy = worldY - viewport.cameraY;
            double dz = worldZ - viewport.cameraZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            double max = distanceFor(snapshot);
            boolean liveOwns = entity instanceof AbstractContraptionEntity
                    && !entity.isRemoved()
                    && (!(entity instanceof CarriageContraptionEntity carriage) || carriage.validForRender);
            if (liveOwns && distanceSq < vanillaSq || distanceSq > max * max) continue;
            if (snapshot.resourceGeneration != this.resourceGeneration) {
                this.requestRebuild(entry.getKey(), snapshot);
                continue;
            }
            if (snapshot.mesh == null) continue;
            if (!bound) {
                DistantBlockShader.get(pipeline, true).bind();
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
            transform.set(viewport.MVP).translate((float) dx, (float) dy, (float) dz).mul(local);
            DistantBlockShader.uploadTransform(transform);
            DistantBlockShader.uploadLight(snapshot.light);
            snapshot.mesh.draw();
            if (snapshot.train && !snapshot.currentBogeys.isEmpty() && !snapshot.bogeyInfo.isEmpty()) {
                renderBogeys(snapshot, viewport, transform);
            }
        }
        if (bound) {
            glBindVertexArray(0);
            glUseProgram(0);
            glStencilFunc(GL_EQUAL, 1, 0xFF);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        }
    }

    public static boolean shouldCullLive(AbstractContraptionEntity entity,
                                         net.minecraft.world.phys.Vec3 camera) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != INSTANCE.snapshotLevel || entity.level() != mc.level || entity.isRemoved()
                || !mc.level.dimension().equals(INSTANCE.dimension)
                || !VoxyConfig.CONFIG.isRenderingEnabled()) return false;
        Snapshot snapshot = INSTANCE.snapshots.get(snapshotId(entity));
        if (snapshot == null || snapshot.mesh == null || snapshot.resourceGeneration != INSTANCE.resourceGeneration) return false;
        if (snapshot.train ? !VoxyConfig.CONFIG.distantTrains : !VoxyConfig.CONFIG.distantContraptions) return false;
        double reach = Math.max(16.0, mc.options.getEffectiveRenderDistance() * 16.0);
        double max = distanceFor(snapshot);
        double distanceSq = entity.position().distanceToSqr(camera);
        return distanceSq >= reach * reach && distanceSq <= max * max;
    }

    private static double distanceFor(Snapshot snapshot) {
        int chunks = snapshot.train ? VoxyConfig.CONFIG.distantTrainMaxChunks
                : VoxyConfig.CONFIG.distantContraptionMaxChunks;
        return chunks == 0 ? VoxyConfig.CONFIG.getLodRenderDistanceBlocks() : chunks * 16.0;
    }

    private static UUID snapshotId(AbstractContraptionEntity entity) {
        if (entity instanceof CarriageContraptionEntity carriage && carriage.trainId != null) {
            return IntegratedContraptionTracker.carriageId(carriage.trainId, carriage.carriageIndex);
        }
        return entity.getUUID();
    }

    private static void renderBogeys(Snapshot snapshot, Viewport<?> viewport, Matrix4f transform) {
        long now = System.nanoTime();
        float amount = snapshot.bogeyReceivedNanos == 0 ? 1.0f : Math.max(0.0f, Math.min(1.25f,
                (float) (now - snapshot.bogeyReceivedNanos) / snapshot.bogeyIntervalNanos));
        int count = Math.min(snapshot.bogeyInfo.size(), snapshot.currentBogeys.size());
        Matrix4f model = new Matrix4f();
        for (int i = 0; i < count; i++) {
            DistantMesh mesh = bogeyMesh(snapshot, i);
            if (mesh == null) continue;
            var current = snapshot.currentBogeys.get(i);
            var previous = i < snapshot.previousBogeys.size() ? snapshot.previousBogeys.get(i) : current;
            double x = net.minecraft.util.Mth.lerp(amount, previous.x(), current.x());
            double y = net.minecraft.util.Mth.lerp(amount, previous.y(), current.y());
            double z = net.minecraft.util.Mth.lerp(amount, previous.z(), current.z());
            float yaw = previous.yaw() + net.minecraft.util.Mth.wrapDegrees(current.yaw() - previous.yaw()) * amount;
            float pitch = net.minecraft.util.Mth.lerp(amount, previous.pitch(), current.pitch());
            model.identity()
                    .translate((float) (x - viewport.cameraX), (float) (y - viewport.cameraY + 0.5),
                            (float) (z - viewport.cameraZ))
                    .rotateY((float) Math.toRadians(yaw))
                    .rotateX((float) Math.toRadians(pitch));
            if (current.upsideDown()) model.rotateZ((float) Math.PI);
            DistantBlockShader.uploadTransform(transform.set(viewport.MVP).mul(model));
            mesh.draw();
        }
    }

    private static DistantMesh bogeyMesh(Snapshot snapshot, int index) {
        int generation = DistantBogeyMeshes.generation();
        var bogeys = snapshot.bogeyInfo;
        if (snapshot.capturedBogeyInfo != bogeys || snapshot.bogeyMeshGeneration != generation) {
            snapshot.capturedBogeyInfo = bogeys;
            snapshot.bogeyMeshes = new DistantMesh[bogeys.size()];
            snapshot.bogeyResolved = new boolean[bogeys.size()];
            snapshot.bogeyMeshGeneration = generation;
        }
        if (!snapshot.bogeyResolved[index]) {
            var info = bogeys.get(index);
            var mesh = DistantBogeyMeshes.get(info.styleId(), info.sizeId(), info.data());
            if (generation != DistantBogeyMeshes.generation() || snapshot.bogeyInfo != bogeys) return null;
            // 重入的临时空值要重试，真正的空结果仍按代次复用。
            if (mesh == null && DistantBogeyMeshes.isCapturing()) return null;
            snapshot.bogeyMeshes[index] = mesh;
            snapshot.bogeyResolved[index] = true;
        }
        return snapshot.bogeyMeshes[index];
    }

    @Override
    public void onResourceReload() {
        this.snapshotGeneration++;
        DistantBogeyMeshes.clear();
        this.resourceGeneration++;
        this.rebuildQueue.clear();
        for (var snapshot : this.snapshots.values()) {
            snapshot.retryAfterTick = 0;
            snapshot.rebuildQueued = false;
            // 旧格式只有UV网格，没有重建源；保留它直到重新见到活体并升级。
            if (snapshot.meshSource == null && snapshot.mesh != null) snapshot.resourceGeneration = this.resourceGeneration;
        }
    }

    private boolean takeMeshBudget() {
        if (this.remainingBuilds <= 0) return false;
        this.remainingBuilds--;
        return true;
    }

    private static long shapeRevision(Object source) {
        return source instanceof CreateShapeRevision revision ? revision.voxy$shapeRevision() : 0;
    }

    private long meshSignature(Snapshot snapshot, Object source, long revision) {
        if (snapshot.observedSource == null || snapshot.observedSource.get() != source
                || snapshot.observedRevision != revision) {
            snapshot.observedSource = new java.lang.ref.WeakReference<>(source);
            snapshot.observedRevision = revision;
            // 版本只在本会话内比较，避开磁盘旧版本及 live/remote 的来源碰撞。
            do snapshot.observedSignature = ++this.nextMeshSignature;
            while (snapshot.observedSignature == snapshot.signature);
        }
        return snapshot.observedSignature;
    }

    private void requestRebuild(UUID id, Snapshot snapshot) {
        if ((snapshot.meshSource != null || snapshot.mesh == null && snapshot.meshBytes != null) && !snapshot.rebuildQueued
                && snapshot.resourceGeneration != this.resourceGeneration
                && snapshot.failedResourceGeneration != this.resourceGeneration
                && this.maintenanceTick >= snapshot.retryAfterTick) {
            snapshot.rebuildQueued = true;
            this.rebuildQueue.addLast(new PendingSnapshot(id, snapshot));
        }
    }

    private void rebuildQueuedSources(net.minecraft.client.multiplayer.ClientLevel level) {
        long generation = this.snapshotGeneration;
        while (this.isCurrentUpdate(level, generation) && !this.rebuildQueue.isEmpty() && this.takeMeshBudget()) {
            var pending = this.rebuildQueue.removeFirst();
            Snapshot snapshot = pending.snapshot();
            snapshot.rebuildQueued = false;
            if (this.snapshots.get(pending.id()) != snapshot
                    || snapshot.meshSource == null && (snapshot.mesh != null || snapshot.meshBytes == null)
                    || snapshot.resourceGeneration == this.resourceGeneration
                    || this.maintenanceTick < snapshot.retryAfterTick) continue;
            if (rebuildFromSource(snapshot, pending.id(), level, generation) && snapshot.meshSource != null) {
                this.save(level, pending.id(), snapshot);
            }
        }
    }

    private static boolean rebuildFromSource(Snapshot snapshot, UUID id,
                                             net.minecraft.client.multiplayer.ClientLevel level, long generation) {
        DistantMesh.Built replacement;
        try {
            replacement = snapshot.meshSource == null
                    ? new DistantMesh.Built(DistantMesh.fromBytes(snapshot.meshBytes), snapshot.meshBytes)
                    : snapshot.meshSource.bake(level);
        } catch (Throwable failure) {
            me.cortex.voxy.common.Logger.error("Rebuilding stored Create contraption", failure);
            replacement = new DistantMesh.Built(null, null);
        }
        if (!INSTANCE.isCurrentSnapshot(snapshot, id, level, generation)) {
            if (replacement.mesh() != null) replacement.mesh().free();
            return false;
        }
        boolean changed = replaceMesh(snapshot, replacement, snapshot.signature);
        snapshot.failedResourceGeneration = changed ? -1 : INSTANCE.resourceGeneration;
        return changed;
    }

    private static boolean rebuildLive(Snapshot snapshot, AbstractContraptionEntity entity, long signature,
                                       UUID id, net.minecraft.client.multiplayer.ClientLevel level, long generation) {
        if (snapshot.meshSource != null && snapshot.signature == signature
                && snapshot.resourceGeneration != INSTANCE.resourceGeneration) return rebuildFromSource(snapshot, id, level, generation);
        var contraption = entity.getContraption();
        long revision = shapeRevision(contraption);
        CreateMeshSource source;
        DistantMesh.Built replacement;
        try {
            source = CreateMeshSource.contraption(contraption);
            if (!INSTANCE.isCurrentSnapshot(snapshot, id, level, generation)
                    || !isCurrentLiveSource(entity, contraption, revision)) return false;
            replacement = bake(entity);
        } catch (Throwable failure) {
            if (!isCurrentLiveSource(entity, contraption, revision)) return false;
            me.cortex.voxy.common.Logger.error("Capturing Create contraption source", failure);
            return publishMesh(snapshot, new DistantMesh.Built(null, null), signature, id, level, generation);
        }
        // 捕获与烘焙间的内容变化不能把不同版本的来源和网格一起保存。
        if (!isCurrentLiveSource(entity, contraption, revision)) {
            if (replacement.mesh() != null) replacement.mesh().free();
            return false;
        }
        if (!publishMesh(snapshot, replacement, signature, id, level, generation)) return false;
        snapshot.meshSource = source;
        return true;
    }

    private static boolean isCurrentLiveSource(AbstractContraptionEntity entity, Object source, long revision) {
        return entity.getContraption() == source && shapeRevision(source) == revision;
    }

    private static boolean rebuildRemote(Snapshot snapshot, List<IntegratedContraptionTracker.RemoteBlock> blocks, long signature,
                                         UUID id, net.minecraft.client.multiplayer.ClientLevel level, long generation) {
        if (snapshot.meshSource != null && snapshot.signature == signature
                && snapshot.resourceGeneration != INSTANCE.resourceGeneration) return rebuildFromSource(snapshot, id, level, generation);
        CreateMeshSource source;
        DistantMesh.Built replacement;
        try {
            var sourceBlocks = new net.minecraft.nbt.ListTag();
            for (var block : blocks) {
                var tag = new CompoundTag();
                tag.putInt("X", block.x());
                tag.putInt("Y", block.y());
                tag.putInt("Z", block.z());
                tag.put("State", net.minecraft.nbt.NbtUtils.writeBlockState(block.state()));
                sourceBlocks.add(tag);
            }
            source = CreateMeshSource.blocks(sourceBlocks);
            if (!INSTANCE.isCurrentSnapshot(snapshot, id, level, generation)) return false;
            replacement = bakeRemote(blocks);
        } catch (Throwable failure) {
            me.cortex.voxy.common.Logger.error("Capturing remote Create carriage source", failure);
            return publishMesh(snapshot, new DistantMesh.Built(null, null), signature, id, level, generation);
        }
        if (!publishMesh(snapshot, replacement, signature, id, level, generation)) return false;
        snapshot.meshSource = source;
        return true;
    }

    private boolean isCurrentUpdate(net.minecraft.client.multiplayer.ClientLevel level, long generation) {
        return Minecraft.getInstance().level == level && this.snapshotLevel == level
                && this.snapshotGeneration == generation && VoxyConfig.CONFIG.isRenderingEnabled()
                && (VoxyConfig.CONFIG.distantContraptions || VoxyConfig.CONFIG.distantTrains);
    }

    private boolean isCurrentSnapshot(Snapshot snapshot, UUID id,
                                      net.minecraft.client.multiplayer.ClientLevel level, long generation) {
        return this.isCurrentUpdate(level, generation) && this.snapshots.get(id) == snapshot;
    }

    private static boolean publishMesh(Snapshot snapshot, DistantMesh.Built replacement, long signature,
                                       UUID id, net.minecraft.client.multiplayer.ClientLevel level, long generation) {
        // 模型回调可能重载资源或清空快照，返回的网格先核对归属再发布。
        if (!INSTANCE.isCurrentSnapshot(snapshot, id, level, generation)) {
            if (replacement.mesh() != null) replacement.mesh().free();
            return false;
        }
        return replaceMesh(snapshot, replacement, signature);
    }

    private static boolean shouldRebuild(Snapshot snapshot, long signature) {
        if (snapshot.meshSource != null && snapshot.signature == signature
                && snapshot.resourceGeneration != INSTANCE.resourceGeneration
                && snapshot.failedResourceGeneration == INSTANCE.resourceGeneration) return false;
        return (snapshot.mesh == null || snapshot.meshSource == null || snapshot.signature != signature
                || snapshot.resourceGeneration != INSTANCE.resourceGeneration)
                && (snapshot.failedSignature != signature || INSTANCE.maintenanceTick >= snapshot.retryAfterTick);
    }

    private static boolean replaceMesh(Snapshot snapshot, DistantMesh.Built replacement, long signature) {
        if (replacement.mesh() == null) {
            snapshot.failedSignature = signature;
            snapshot.retryAfterTick = INSTANCE.maintenanceTick + 20;
            return false;
        }
        DistantMesh previous = snapshot.mesh;
        snapshot.mesh = replacement.mesh();
        snapshot.meshBytes = replacement.bytes();
        snapshot.signature = signature;
        snapshot.resourceGeneration = INSTANCE.resourceGeneration;
        snapshot.retryAfterTick = 0;
        if (previous != null) previous.free();
        return true;
    }

    static DistantMesh.Built bake(AbstractContraptionEntity entity) {
        var contraption = entity.getContraption();
        var builder = new DistantMesh.Builder();
        try {
            var capture = new DistantVertexCapture(builder, true);
            var clientContraption = contraption.getOrCreateClientContraptionLazy();
            var renderWorld = clientContraption.getRenderLevel();
            var pose = new PoseStack();
            for (var layer : net.minecraft.client.renderer.RenderType.chunkBufferLayers()) {
                var buffer = com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer
                        .getBuffer(contraption, renderWorld, layer);
                if (!buffer.isEmpty()) buffer.renderInto(pose, capture);
            }
            return builder.buildPersistent();
        } catch (Throwable failure) {
            me.cortex.voxy.common.Logger.error("Capturing distant Create contraption", failure);
            return new DistantMesh.Built(null, null);
        } finally {
            builder.discard();
        }
    }

    private static DistantMesh.Built bakeRemote(java.util.List<IntegratedContraptionTracker.RemoteBlock> blocks) {
        var builder = new DistantMesh.Builder();
        try {
            var shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();
            for (var block : blocks) {
                if (block.state().isAir() || block.state().getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) continue;
                builder.blockModelLit(block.state(), shaper.getBlockModel(block.state()), BlockPos.ZERO,
                        block.x(), block.y(), block.z(), 0, 240);
            }
            return builder.buildPersistent();
        } catch (Throwable failure) {
            me.cortex.voxy.common.Logger.error("Baking distant Create carriage", failure);
            return new DistantMesh.Built(null, null);
        } finally {
            builder.discard();
        }
    }

    private void save(net.minecraft.client.multiplayer.ClientLevel level, UUID id, Snapshot snapshot) {
        if (snapshot.meshBytes == null || snapshot.mesh == null) return;
        CreateSnapshotStore.saveContraption(level, id, snapshot.x, snapshot.y, snapshot.z,
                snapshot.signature, snapshot.train, snapshot.local, snapshot.light, snapshot.meshBytes, snapshot.meshSource);
    }

    private void restore(net.minecraft.client.multiplayer.ClientLevel level) {
        long generation = this.snapshotGeneration;
        if (this.restoreLoad == null) this.restoreLoad = CreateSnapshotStore.loadContraptions(level);
        for (int i = 0; i < 16; i++) {
            if (!this.isCurrentUpdate(level, generation)) return;
            var stored = this.restoreLoad.poll();
            if (stored == null) break;
            if (this.restoreObserved.contains(stored.id()) || this.snapshots.containsKey(stored.id())) continue;
            // 旧格式也先保留字节，可见时再占用既有队列的上传预算。
            if (stored.source() == null && (stored.mesh() == null || stored.mesh().length == 0
                    || stored.mesh().length % (DistantMesh.STRIDE * 4) != 0)) continue;
            var snapshot = new Snapshot();
            snapshot.meshBytes = stored.mesh();
            snapshot.meshSource = stored.source();
            snapshot.resourceGeneration = this.resourceGeneration - 1;
            snapshot.x = stored.x();
            snapshot.y = stored.y();
            snapshot.z = stored.z();
            snapshot.signature = stored.signature();
            snapshot.train = stored.train();
            snapshot.local.set(stored.local());
            snapshot.light = stored.light();
            snapshot.lastSeen = System.currentTimeMillis();
            if (this.snapshots.putIfAbsent(stored.id(), snapshot) != null) continue;
        }
    }

    private static int sampleLight(net.minecraft.client.multiplayer.ClientLevel level, double x, double y, double z) {
        var pos = BlockPos.containing(x, y, z);
        if (!level.isLoaded(pos)) return net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
        int above = net.minecraft.client.renderer.LevelRenderer.getLightColor(level, pos.above());
        if (above != 0) return above;
        return net.minecraft.client.renderer.LevelRenderer.getLightColor(level, pos);
    }

    private static final class IntegratedContraptionTracker {
        private record Pose(ResourceKey<Level> dimension, double x, double y, double z,
                            byte axis, float angle, float yaw, float pitch, float initialYaw, float[] matrix,
                            boolean train, int light, List<BogeyPose> bogeys, long receivedNanos) {}
        private record RemoteBlock(int x, int y, int z, net.minecraft.world.level.block.state.BlockState state) {}
        private record RemoteShape(long signature, List<RemoteBlock> blocks) {}
        private record BogeyInfo(ResourceLocation styleId, ResourceLocation sizeId, CompoundTag data) {}
        private record BogeyPose(double x, double y, double z, float yaw, float pitch, boolean upsideDown) {}

        private static final class Track {
            volatile Pose previous;
            volatile Pose current;
            volatile long intervalNanos = 250_000_000L;
            volatile RemoteShape shape;
            volatile List<BogeyInfo> bogeyInfo = List.of();
        }

        private static final class ShapeSource {
            final java.lang.ref.WeakReference<com.simibubi.create.content.trains.entity.Carriage> carriage;
            final java.lang.ref.WeakReference<com.simibubi.create.content.contraptions.Contraption> contraption;
            final long carriageRevision;
            final long contraptionRevision;
            RemoteShape shape;
            long retryAfterTick;

            ShapeSource(com.simibubi.create.content.trains.entity.Carriage carriage,
                        com.simibubi.create.content.contraptions.Contraption live) {
                this.carriage = new java.lang.ref.WeakReference<>(carriage);
                this.contraption = new java.lang.ref.WeakReference<>(live);
                this.carriageRevision = shapeRevision(carriage);
                this.contraptionRevision = shapeRevision(live);
            }

            boolean matches(com.simibubi.create.content.trains.entity.Carriage carriage,
                            com.simibubi.create.content.contraptions.Contraption live) {
                return this.carriage.get() == carriage && this.carriageRevision == shapeRevision(carriage)
                        && (live == null || this.contraption.get() == live
                        && this.contraptionRevision == shapeRevision(live));
            }
        }

        private static final class SamplingSession {
            final net.minecraft.server.MinecraftServer server;
            final java.util.concurrent.ConcurrentHashMap<UUID, Track> tracks = new java.util.concurrent.ConcurrentHashMap<>();
            final java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean();
            final Map<UUID, ShapeSource> shapeSources = new HashMap<>();
            volatile long ticks;
            int remainingShapeBuilds;
            long nextShapeSignature;

            SamplingSession(net.minecraft.server.MinecraftServer server) {
                this.server = server;
            }
        }

        private static final SamplingSession INACTIVE_SESSION = new SamplingSession(null);
        private static volatile SamplingSession session = INACTIVE_SESSION;

        static void update(Minecraft mc, Map<UUID, Snapshot> snapshots, java.util.Set<UUID> seen) {
            var level = mc.level;
            long generation = INSTANCE.snapshotGeneration;
            var server = mc.getSingleplayerServer();
            SamplingSession sampling = session;
            if (sampling.server != server) {
                session = sampling = new SamplingSession(server);
            }
            if (server != null && ++sampling.ticks % 5 == 0 && !sampling.pending.get()) {
                UUID[] ids = snapshots.keySet().toArray(UUID[]::new);
                scheduleSample(sampling, ids);
            }

            long now = System.nanoTime();
            long nowMs = System.currentTimeMillis();
            sampling.tracks.entrySet().removeIf(entry -> {
                if (!INSTANCE.isCurrentUpdate(level, generation)) return false;
                Track track = entry.getValue();
                Pose current = track.current;
                if (current == null || now - current.receivedNanos() > 1_500_000_000L) return true;
                if (!mc.level.dimension().equals(current.dimension())) return false;
                if (INSTANCE.restorePending) INSTANCE.restoreObserved.add(entry.getKey());
                Snapshot snapshot = snapshots.get(entry.getKey());
                if (snapshot == null) {
                    snapshot = new Snapshot();
                    snapshots.put(entry.getKey(), snapshot);
                }
                if (current.train()) {
                    snapshot.remoteTrack = track;
                    snapshot.bogeyInfo = track.bogeyInfo;
                    snapshot.previousBogeys = track.previous == null ? current.bogeys() : track.previous.bogeys();
                    snapshot.currentBogeys = current.bogeys();
                    snapshot.bogeyReceivedNanos = current.receivedNanos();
                    snapshot.bogeyIntervalNanos = track.intervalNanos;
                }
                if (seen.contains(entry.getKey())) return false;
                boolean meshChanged = false;
                RemoteShape shape = track.shape;
                long signature = shape == null ? 0 : INSTANCE.meshSignature(snapshot, shape, shape.signature());
                if (current.train() && shape != null
                        && shouldRebuild(snapshot, signature) && INSTANCE.takeMeshBudget()) {
                    meshChanged = rebuildRemote(snapshot, shape.blocks(), signature, entry.getKey(), level, generation);
                    if (meshChanged) {
                        snapshot.train = true;
                    }
                }
                if (!INSTANCE.isCurrentSnapshot(snapshot, entry.getKey(), level, generation)) return false;
                Pose previous = track.previous == null ? current : track.previous;
                float amount = Math.max(0.0f, Math.min(1.0f,
                        (float) (now - current.receivedNanos()) / track.intervalNanos));
                snapshot.x = net.minecraft.util.Mth.lerp(amount, previous.x(), current.x());
                snapshot.y = net.minecraft.util.Mth.lerp(amount, previous.y(), current.y());
                snapshot.z = net.minecraft.util.Mth.lerp(amount, previous.z(), current.z());
                if (current.train()) {
                    float yaw = previous.yaw() + net.minecraft.util.Mth.wrapDegrees(
                            current.yaw() - previous.yaw()) * amount;
                    float pitch = net.minecraft.util.Mth.lerp(amount, previous.pitch(), current.pitch());
                    snapshot.local.identity().translate(0.0f, 0.5f, 0.0f)
                            .rotateY((float) Math.toRadians(-yaw))
                            .rotateZ((float) Math.toRadians(pitch))
                            .rotateY((float) Math.toRadians(current.initialYaw()))
                            .translate(-0.5f, -0.5f, -0.5f);
                } else if (current.matrix() != null) {
                    snapshot.local.set(current.matrix());
                } else {
                    float angle = previous.angle() + net.minecraft.util.Mth.wrapDegrees(
                            current.angle() - previous.angle()) * amount;
                    snapshot.local.identity().translate(0.5f, 0.5f, 0.5f);
                    switch (current.axis()) {
                        case 0 -> snapshot.local.rotateX((float) Math.toRadians(angle));
                        case 1 -> snapshot.local.rotateY((float) Math.toRadians(angle));
                        default -> snapshot.local.rotateZ((float) Math.toRadians(angle));
                    }
                    snapshot.local.translate(-0.5f, -0.5f, -0.5f);
                }
                snapshot.train = current.train();
                snapshot.light = current.light();
                snapshot.lastSeen = nowMs;
                if (meshChanged) {
                    CreateSnapshotStore.saveContraption(level, entry.getKey(), snapshot.x, snapshot.y, snapshot.z,
                            snapshot.signature, true, snapshot.local, snapshot.light, snapshot.meshBytes, snapshot.meshSource);
                }
                return false;
            });
        }

        private static void scheduleSample(SamplingSession sampling, UUID[] ids) {
            if (!sampling.pending.compareAndSet(false, true)) return;
            try {
                sampling.server.execute(() -> {
                    try {
                        if (session != sampling) return;
                        var server = sampling.server;
                        long now = System.nanoTime();
                        for (UUID id : ids) {
                            if (session != sampling) return;
                            for (var level : server.getAllLevels()) {
                                var entity = level.getEntity(id);
                                if (!(entity instanceof com.simibubi.create.content.contraptions.ControlledContraptionEntity controlled)) continue;
                                var axis = controlled.getRotationAxis();
                                if (axis == null) break;
                                Pose pose = new Pose(level.dimension(), controlled.getX(), controlled.getY(), controlled.getZ(),
                                        (byte) axis.ordinal(), controlled.getAngle(1.0f), 0, 0, 0, null, false,
                                        serverLight(level, controlled.blockPosition()), List.of(), now);
                                publish(sampling, id, pose, null, null);
                                break;
                            }
                        }
                        sampling.remainingShapeBuilds = 2;
                        var sampledCarriages = new java.util.HashSet<UUID>();
                        for (var level : server.getAllLevels()) {
                            if (session != sampling) return;
                            var railways = com.simibubi.create.Create.RAILWAYS.sided(level);
                            for (var train : railways.trains.values()) {
                                for (int carriageIndex = 0; carriageIndex < train.carriages.size(); carriageIndex++) {
                                    if (session != sampling) return;
                                    var carriageData = train.carriages.get(carriageIndex);
                                    var dimensional = carriageData.getDimensionalIfPresent(level.dimension());
                                    if (dimensional == null || dimensional.positionAnchor == null) continue;
                                    UUID id = carriageId(train.id, carriageIndex);
                                    if (!sampledCarriages.add(id)) continue;
                                    RemoteShape shape = shapeFor(sampling, level, carriageData, id);
                                    var leading = dimensional.rotationAnchors.getFirst();
                                    var trailing = dimensional.rotationAnchors.getSecond();
                                    float yaw = 0;
                                    float pitch = 0;
                                    if (leading != null && trailing != null) {
                                        var diff = leading.subtract(trailing);
                                        yaw = (float) (Math.atan2(diff.z, diff.x) * (180.0 / Math.PI)) + 180.0f;
                                        pitch = (float) (-Math.atan2(diff.y,
                                                Math.sqrt(diff.x * diff.x + diff.z * diff.z)) * (180.0 / Math.PI));
                                    }
                                    List<BogeyInfo> info = bogeyInfo(carriageData);
                                    List<BogeyPose> bogeys = bogeyPoses(train, carriageData, level.dimension());
                                    var anchor = dimensional.positionAnchor;
                                    Pose pose = new Pose(level.dimension(), anchor.x, anchor.y, anchor.z,
                                            (byte) -1, 0.0f, yaw, pitch, initialYaw(carriageData), null, true,
                                            serverLight(level, BlockPos.containing(anchor)), bogeys, now);
                                    publish(sampling, id, pose, shape, info);
                                }
                            }
                        }
                        sampling.shapeSources.keySet().retainAll(sampledCarriages);
                    } finally {
                        sampling.pending.set(false);
                    }
                });
            } catch (RuntimeException e) {
                sampling.pending.set(false);
                me.cortex.voxy.common.Logger.warn("Could not schedule distant Create sampling", e);
            }
        }

        private static RemoteShape shapeFor(SamplingSession sampling, net.minecraft.server.level.ServerLevel level,
                                             com.simibubi.create.content.trains.entity.Carriage carriage, UUID id) {
            var entity = carriage.anyAvailableEntity();
            var live = entity == null ? null : entity.getContraption();
            ShapeSource source = sampling.shapeSources.get(id);
            if (source == null || !source.matches(carriage, live)) {
                source = new ShapeSource(carriage, live);
                sampling.shapeSources.put(id, source);
            }
            if (source.shape != null) return source.shape;
            if (sampling.remainingShapeBuilds <= 0 || sampling.ticks < source.retryAfterTick) return null;
            sampling.remainingShapeBuilds--;
            try {
                var contraption = live == null ? resolveContraption(level, carriage) : live;
                if (contraption == null || contraption.getBlocks().isEmpty()) return null;
                var copied = new java.util.ArrayList<RemoteBlock>(contraption.getBlocks().size());
                for (var block : contraption.getBlocks().entrySet()) {
                    var pos = block.getKey();
                    copied.add(new RemoteBlock(pos.getX(), pos.getY(), pos.getZ(), block.getValue().state()));
                }
                var currentEntity = carriage.anyAvailableEntity();
                var current = currentEntity == null ? null : currentEntity.getContraption();
                if (!source.matches(carriage, current)) return null;
                source.shape = new RemoteShape(++sampling.nextShapeSignature, List.copyOf(copied));
                return source.shape;
            } catch (Throwable failure) {
                me.cortex.voxy.common.Logger.error("Capturing remote Create carriage source", failure);
                return null;
            } finally {
                // 未就绪的序列化源也退避，避免先遍历的失败项耗尽每轮预算。
                if (source.shape == null) source.retryAfterTick = sampling.ticks + 20;
            }
        }

        private static void publish(SamplingSession sampling, UUID id, Pose pose, RemoteShape shape,
                                    List<BogeyInfo> bogeyInfo) {
            if (session != sampling) return;
            sampling.tracks.compute(id, (ignored, track) -> {
                if (track == null) track = new Track();
                if (track.current != null) {
                    track.previous = track.current;
                    track.intervalNanos = Math.max(50_000_000L,
                            Math.min(1_000_000_000L, pose.receivedNanos() - track.current.receivedNanos()));
                } else {
                    track.previous = pose;
                }
                track.current = pose;
                // 内容与版本通过同一个不可变对象发布，客户端不会读到半次更新。
                if (shape != null) track.shape = shape;
                if (bogeyInfo != null) track.bogeyInfo = bogeyInfo;
                return track;
            });
        }

        static UUID carriageId(UUID trainId, int carriageIndex) {
            return UUID.nameUUIDFromBytes((trainId + ":" + carriageIndex)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        private static List<BogeyInfo> bogeyInfo(com.simibubi.create.content.trains.entity.Carriage carriage) {
            var result = new java.util.ArrayList<BogeyInfo>(2);
            addBogeyInfo(carriage.bogeys.getFirst(), result);
            if (carriage.isOnTwoBogeys()) addBogeyInfo(carriage.bogeys.getSecond(), result);
            return List.copyOf(result);
        }

        private static void addBogeyInfo(com.simibubi.create.content.trains.entity.CarriageBogey bogey,
                                         java.util.List<BogeyInfo> result) {
            var style = bogey.getStyle();
            var size = bogey.getSize();
            result.add(new BogeyInfo(style.id, size.id(),
                    bogey.bogeyData == null ? new CompoundTag() : bogey.bogeyData.copy()));
        }

        private static List<BogeyPose> bogeyPoses(com.simibubi.create.content.trains.entity.Train train,
                                                   com.simibubi.create.content.trains.entity.Carriage carriage,
                                                   ResourceKey<Level> dimension) {
            if (train.derailed || train.graph == null) return List.of();
            var result = new java.util.ArrayList<BogeyPose>(2);
            if (!addBogeyPose(train, carriage.bogeys.getFirst(), dimension, result)) return List.of();
            if (carriage.isOnTwoBogeys()
                    && !addBogeyPose(train, carriage.bogeys.getSecond(), dimension, result)) return List.of();
            return List.copyOf(result);
        }

        private static boolean addBogeyPose(com.simibubi.create.content.trains.entity.Train train,
                                             com.simibubi.create.content.trains.entity.CarriageBogey bogey,
                                             ResourceKey<Level> dimension, java.util.List<BogeyPose> result) {
            var leading = bogey.leading();
            var trailing = bogey.trailing();
            if (leading.edge == null || trailing.edge == null || !dimension.equals(bogey.getDimension())) return false;
            var first = leading.getPosition(train.graph);
            var second = trailing.getPosition(train.graph);
            var anchor = first.add(second).scale(0.5);
            double dx = first.x - second.x;
            double dy = first.y - second.y;
            double dz = first.z - second.z;
            float yRot = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) + 90.0f;
            float xRot = (float) (Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI));
            result.add(new BogeyPose(anchor.x, anchor.y, anchor.z, -yRot, xRot, bogey.isUpsideDown()));
            return true;
        }

        private static java.lang.reflect.Field serializedEntity;

        private static com.simibubi.create.content.contraptions.Contraption resolveContraption(
                net.minecraft.server.level.ServerLevel level,
                com.simibubi.create.content.trains.entity.Carriage carriage) {
            try {
                var entity = carriage.anyAvailableEntity();
                if (entity != null && entity.getContraption() != null) return entity.getContraption();
                CompoundTag serialized = serializedEntity(carriage);
                if (serialized == null || !serialized.contains("Contraption")) return null;
                var saved = serialized.getCompound("Contraption");
                var contraption = com.simibubi.create.api.contraption.ContraptionType.fromType(saved.getString("Type"));
                contraption.readNBT(level, saved.copy(), false);
                return contraption;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static float initialYaw(com.simibubi.create.content.trains.entity.Carriage carriage) {
            try {
                var entity = carriage.anyAvailableEntity();
                if (entity != null) return entity.getInitialYaw();
                CompoundTag serialized = serializedEntity(carriage);
                if (serialized != null && serialized.contains("InitialOrientation")) {
                    return net.minecraft.core.Direction.valueOf(serialized.getString("InitialOrientation")
                            .toUpperCase(java.util.Locale.ROOT)).toYRot();
                }
            } catch (Throwable ignored) {}
            return 0;
        }

        private static CompoundTag serializedEntity(com.simibubi.create.content.trains.entity.Carriage carriage)
                throws ReflectiveOperationException {
            if (serializedEntity == null) {
                serializedEntity = com.simibubi.create.content.trains.entity.Carriage.class
                        .getDeclaredField("serialisedEntity");
                serializedEntity.setAccessible(true);
            }
            return (CompoundTag) serializedEntity.get(carriage);
        }

        private static int serverLight(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
            BlockPos sample = pos.above();
            int block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, sample);
            int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, sample);
            return block << 4 | sky << 20;
        }

        static void clear() {
            // 旧任务持有自己的 map 和 pending，完成后不会污染新会话。
            session = INACTIVE_SESSION;
        }
    }

    private void clear() {
        this.snapshotGeneration++;
        if (this.restoreLoad != null) this.restoreLoad.close();
        this.restoreLoad = null;
        this.restoreObserved.clear();
        this.rebuildQueue.clear();
        IntegratedContraptionTracker.clear();
        DistantBogeyMeshes.clear();
        for (Snapshot snapshot : this.snapshots.values()) {
            if (snapshot.mesh != null) snapshot.mesh.free();
        }
        this.snapshots.clear();
        this.dimension = null;
        this.snapshotLevel = null;
        this.restorePending = false;
    }
}
