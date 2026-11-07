package me.cortex.voxy.client.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ContraptionPose;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ContraptionPosesPayload;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.RenderShape;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

//Caches Create contraption meshes and applies either a live entity transform or a low-rate remote pose.
public final class DistantContraptionManager {
    private DistantContraptionManager() {}

    //Coordinate range of the per-carriage byte packing reused from the train path
    private static final int MAX_LOCAL = 127;

    public static final class Snapshot {
        CarriageMeshBaker.BakedCarriage mesh;
        Source source;
        long lastMeshBytes;
        //M_local from AbstractContraptionEntity.applyLocalTransforms; the world position is kept
        //separately as doubles so the draw can be camera-relative without float world-coord error.
        final Matrix4f local = new Matrix4f();
        double x, y, z;
        //How far the structure reaches from its anchor, from the source blocks. A radius rather than a
        //box because bearing poses rotate freely; every distance test against x/y/z alone understates a
        //long structure by up to this much.
        double boundRadius;
        double trackingBlocks = 80.0;
        ResourceLocation dim;
        int lightPacked = -1;
        long lastSeenMs;
        //Set once a bake ran on a non-empty contraption but produced no drawable mesh (all non-MODEL
        //blocks); stops the per-tick 64KB re-bake retry for structures that can never draw.
        boolean bakeGaveNothing;
        boolean liveBakePending;
        boolean movedWhileSeen;
        long remoteUpdatedAtNanos;
        //Network id of the entity behind the last refresh, for the renderer's frame-time lookup -
        //Level.getEntity(int) is the public O(1) path; the UUID re-check guards against id reuse
        int entityId = -1;
        volatile boolean live;
        int railAxis = -1;
        double railU, railV;

        public Matrix4f local() { return this.local; }
        public boolean live() { return this.live; }
        public double x() { return this.x; }
        public double y() { return this.y; }
        public double z() { return this.z; }
        public ResourceLocation dim() { return this.dim; }
        public int lightPacked() { return this.lightPacked; }
        public CarriageMeshBaker.BakedCarriage mesh() { return this.mesh; }
        public Source source() { return this.source; }
        public boolean movedWhileSeen() { return this.movedWhileSeen; }
        public double trackingBlocks() { return this.trackingBlocks; }
    }

    private static final Map<UUID, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<UUID, RemotePose> REMOTE_POSES = new ConcurrentHashMap<>();
    private static final long REMOTE_TIMEOUT_NANOS = 1_500_000_000L;

    private static final class RemotePose {
        ContraptionPose previous;
        ContraptionPose current;
        ResourceLocation dimension;
        long receivedAtNanos;
        long intervalNanos = 250_000_000L;
    }
    //Read from storage on world entry and baked a few per tick, nearest first, so re-entering a world
    //with a lot of stored structures does not stall on one frame's worth of mesh uploads.
    private static final int BAKES_PER_TICK = 2;
    private static final PoseStack SCRATCH_POSE = new PoseStack();

    //Diagnostics for /voxy debug trains
    public static volatile int snapshotCount;

    private static boolean updating;
    private static long snapshotGeneration;

    public static void update(ClientLevel level, double camX, double camY, double camZ, double maxDist) {
        if (updating) return;
        updating = true;
        try {
            updateSnapshots(level, camX, camY, camZ, maxDist);
        } finally {
            updating = false;
        }
    }

    private static boolean isCurrentUpdate(ClientLevel level, long generation) {
        return generation == snapshotGeneration && Minecraft.getInstance().level == level
                && VoxyConfig.CONFIG.isRenderingEnabled() && VoxyConfig.CONFIG.distantContraptions;
    }

    private static void updateSnapshots(ClientLevel level, double camX, double camY, double camZ, double maxDist) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled() || !VoxyConfig.CONFIG.distantContraptions) {
            if (!SNAPSHOTS.isEmpty() || loadedLevel != null) {
                clearAll();
            }
            return;
        }
        loadStored(level);
        long generation = snapshotGeneration;
        long now = System.currentTimeMillis();
        double maxDistSq = maxDist * maxDist;
        var dimId = level.dimension().location();
        applyRemotePoses(dimId, System.nanoTime(), now);

        // 活跃网格与历史恢复共享预算；首次形状和姿态先保留，避免卸载丢失。
        int bakes = 0;
        var seenThisTick = new java.util.HashSet<UUID>();
        var liveBodies = new ArrayList<double[]>();
        var liveRails = new ArrayList<double[]>();
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof AbstractContraptionEntity ce)) {
                continue;
            }
            seenThisTick.add(ce.getUUID());
            observeRestore(ce.getUUID());
            if (ce instanceof CarriageContraptionEntity) {
                continue;
            }
            if (Math.abs(ce.getX()) > 1.0e6 || Math.abs(ce.getZ()) > 1.0e6
                    || me.cortex.voxy.client.compat.ShipBorne.isShipBorne(ce.getX(), ce.getZ())) {
                continue;
            }
            double dx = ce.getX() - camX, dy = ce.getY() - camY, dz = ce.getZ() - camZ;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) {
                //Past the LOD radius entirely: never drawn, no reason to refresh
                continue;
            }
            Contraption contraption = ce.getContraption();
            if (contraption == null) {
                continue;
            }
            var snap = SNAPSHOTS.computeIfAbsent(ce.getUUID(), k -> new Snapshot());
            snap.trackingBlocks = ce.getType().clientTrackingRange() * 16.0;
            snap.live = true;
            if (snap.mesh == null && !snap.bakeGaveNothing) {
                if (!snap.liveBakePending && !contraption.getBlocks().isEmpty()) {
                    var source = collectBlocks(contraption);
                    if (!isCurrentUpdate(level, generation) || SNAPSHOTS.get(ce.getUUID()) != snap) return;
                    snap.source = source;
                    snap.boundRadius = boundRadiusOf(source);
                    snap.liveBakePending = true;
                }
                if (bakes < BAKES_PER_TICK && snap.liveBakePending) {
                    bakes++;
                    var mesh = bakeBlocks(snap.source);
                    // 模型回调可重载资源或删除来源，旧结果不能晚于清理发布。
                    if (!isCurrentUpdate(level, generation) || SNAPSHOTS.get(ce.getUUID()) != snap) {
                        if (mesh != null) mesh.close();
                        return;
                    }
                    snap.mesh = mesh;
                    if (mesh != null) residentGpuBytes += mesh.mesh.gpuByteSize();
                    snap.bakeGaveNothing = mesh == null;
                    snap.liveBakePending = false;
                }
            } else if (snap.bakeGaveNothing) {
                me.cortex.voxy.commonImpl.PerfStats.contraptionRebakeSkipped.increment();
            }
            if (snap.source != null) updatePose(ce, snap);
            if (snap.mesh == null) {
                trackMotion(ce, snap, now);
                snap.x = ce.getX();
                snap.y = ce.getY();
                snap.z = ce.getZ();
                snap.dim = dimId;
                snap.lastSeenMs = now;
                snap.entityId = ce.getId();
                recordRail(ce, snap, liveRails);
                liveBodies.add(new double[]{ce.getX(), ce.getY(), ce.getZ(), snap.boundRadius});
                continue;
            }
            trackMotion(ce, snap, now);
            snap.x = ce.getX();
            snap.y = ce.getY();
            snap.z = ce.getZ();
            snap.dim = dimId;
            snap.lightPacked = DistantLightSampler.samplePeek(level,
                    (int) Math.floor(ce.getX()), (int) Math.floor(ce.getY()), (int) Math.floor(ce.getZ()));
            snap.lastSeenMs = now;
            snap.entityId = ce.getId();
            recordRail(ce, snap, liveRails);
            liveBodies.add(new double[]{ce.getX(), ce.getY(), ce.getZ(), snap.boundRadius});
        }

        var storage = storageFor(level);
        for (var entry : SNAPSHOTS.entrySet()) {
            if (!seenThisTick.contains(entry.getKey())) {
                var snap = entry.getValue();
                //The tick it stops being live is the tick its pose stops changing, so that is when the
                //already and the structure mints a fresh record when it is next seen.
                if (snap.live && !snap.movedWhileSeen && storage != null) {
                    ContraptionStore.save(storage, entry.getKey(), snap);
                }
                snap.live = false;
            }
        }

        if (!liveBodies.isEmpty() || !liveRails.isEmpty()) {
            for (var entry : SNAPSHOTS.entrySet()) {
                var s = entry.getValue();
                if (seenThisTick.contains(entry.getKey()) || !dimId.equals(s.dim)
                        || now - s.lastSeenMs < 2000) {
                    continue;
                }
                boolean superseded = false;
                for (double[] body : liveBodies) {
                    double ox = s.x - body[0], oy = s.y - body[1], oz = s.z - body[2];
                    double touch = s.boundRadius + body[3];
                    if (ox * ox + oy * oy + oz * oz < touch * touch) {
                        superseded = true;
                        break;
                    }
                }
                if (!superseded && s.movedWhileSeen && s.railAxis >= 0) {
                    for (double[] rail : liveRails) {
                        if ((int) rail[0] == s.railAxis && Math.abs(rail[1] - s.railU) < 0.5
                                && Math.abs(rail[2] - s.railV) < 0.5) {
                            superseded = true;
                            break;
                        }
                    }
                }
                if (superseded) {
                    removeDead(entry.getKey());
                }
            }
        }

        // 本轮常规删除已完成；后续烘焙从当前代次开始，避免跳过正常预算结算。
        generation = snapshotGeneration;
        bakeDormant(camX, camY, camZ, maxDist, BAKES_PER_TICK - bakes);
        if (!isCurrentUpdate(level, generation)) return;

        double reach = net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        SNAPSHOTS.entrySet().removeIf(entry -> {
            var s = entry.getValue();
            if (seenThisTick.contains(entry.getKey())) {
                return false;
            }
            //Neither mesh nor source: it cannot draw, rebake, or persist, and the only thing that
            //refills it is a live re-sighting, which mints its own fields anyway. Nothing on disk
            //either - the save rejects a null source.
            if (s.mesh == null && s.source == null && now - s.lastSeenMs >= 2000) {
                observeRestore(entry.getKey());
                return true;
            }
            double sx = s.x - camX, sy = s.y - camY, sz = s.z - camZ;
            double anchorDistSq = sx * sx + sy * sy + sz * sz;
            double presenceRadius = Math.min(s.trackingBlocks, Math.max(16.0, reach - 8.0));
            if (anchorDistSq > presenceRadius * presenceRadius) {
                //Anchor beyond certain tracking. If the camera is at least near the body, ask the level.
                double bodyReach = presenceRadius + s.boundRadius;
                if (anchorDistSq <= bodyReach * bodyReach
                        && now - s.lastSeenMs >= 2000 && disassembledInPlace(level, s)) {
                    closeMesh(s);
                    observeRestore(entry.getKey());
                    if (storage != null) {
                        ContraptionStore.remove(storage, entry.getKey());
                    }
                    return true;
                }
                return false;
            }
            //Grace only for entity-sync lag: any longer and a player who disassembles a structure and
            //walks off crosses the presence line before the check fires, leaving a permanent ghost.
            if (now - s.lastSeenMs < 2000) {
                return false;
            }
            if (!level.isLoaded(net.minecraft.core.BlockPos.containing(s.x, s.y, s.z))) {
                return false;
            }
            closeMesh(s);
            observeRestore(entry.getKey());
            //Presence removal means the structure no longer exists - the record goes with it, or the
            //same ghost restores at the next world entry and has to be walked to all over again.
            if (storage != null) {
                ContraptionStore.remove(storage, entry.getKey());
            }
            return true;
        });

        SNAPSHOTS.entrySet().removeIf(entry -> {
            var s = entry.getValue();
            if (seenThisTick.contains(entry.getKey())) {
                return false;
            }
            //The anchor is a point; the body reaches boundRadius past it. Without the margin a long
            //structure whose anchor sits just past the line has its mesh dropped while its near end is
            //still on screen.
            double evictDist = maxDist + 32.0 + s.boundRadius;
            double evictDistSq = evictDist * evictDist;
            //Another dimension's snapshot is not coming back into view here, and the renderer filters on
            //dimension anyway, so that one goes entirely - the record is on disk if it is worth keeping.
            if (!dimId.equals(s.dim)) {
                dropMesh(s);
                return true;
            }
            double sx = s.x - camX, sy = s.y - camY, sz = s.z - camZ;
            if ((sx * sx + sy * sy + sz * sz) > evictDistSq) {
                dropMesh(s);
            }
            return false;
        });

        enforceGpuBudget(camX, camY, camZ);
        snapshotCount = SNAPSHOTS.size();
    }

    public static void handleRemotePoses(ContraptionPosesPayload payload) {
        if (!me.cortex.voxy.client.ServerCapabilities.supports(ContraptionPosesPayload.TYPE)) return;
        long now = System.nanoTime();
        for (ContraptionPose pose : payload.poses()) {
            REMOTE_POSES.compute(pose.id(), (id, track) -> {
                if (track == null || !payload.dimension().equals(track.dimension)) {
                    track = new RemotePose();
                    track.previous = pose;
                } else {
                    track.previous = track.current == null ? pose : track.current;
                    track.intervalNanos = Math.clamp(now - track.receivedAtNanos,
                            50_000_000L, 1_000_000_000L);
                }
                track.current = pose;
                track.dimension = payload.dimension();
                track.receivedAtNanos = now;
                return track;
            });
        }
    }

    private static void applyRemotePoses(ResourceLocation dimension, long nowNanos, long nowMs) {
        REMOTE_POSES.entrySet().removeIf(entry -> {
            RemotePose track = entry.getValue();
            long age = nowNanos - track.receivedAtNanos;
            if (age > REMOTE_TIMEOUT_NANOS) {
                return true;
            }
            Snapshot snap = SNAPSHOTS.get(entry.getKey());
            if (snap == null || !dimension.equals(track.dimension) || track.current == null) {
                return false;
            }
            ContraptionPose a = track.previous == null ? track.current : track.previous;
            ContraptionPose b = track.current;
            float t = Math.clamp((float) age / track.intervalNanos, 0.0f, 1.0f);
            snap.x = a.x() + (b.x() - a.x()) * t;
            snap.y = a.y() + (b.y() - a.y()) * t;
            snap.z = a.z() + (b.z() - a.z()) * t;
            float angle = a.angle() + net.minecraft.util.Mth.wrapDegrees(b.angle() - a.angle()) * t;
            snap.local.identity().translate(0.5f, 0.5f, 0.5f);
            switch (b.axis()) {
                case 0 -> snap.local.rotateX((float) Math.toRadians(angle));
                case 1 -> snap.local.rotateY((float) Math.toRadians(angle));
                default -> snap.local.rotateZ((float) Math.toRadians(angle));
            }
            snap.local.translate(-0.5f, -0.5f, -0.5f);
            snap.dim = dimension;
            snap.lastSeenMs = nowMs;
            snap.remoteUpdatedAtNanos = nowNanos;
            snap.movedWhileSeen = true;
            return false;
        });
    }

    public static boolean hasFreshRemotePose(Snapshot snap, long nowNanos) {
        return snap.remoteUpdatedAtNanos != 0
                && nowNanos - snap.remoteUpdatedAtNanos <= REMOTE_TIMEOUT_NANOS;
    }

    private static void updatePose(AbstractContraptionEntity ce, Snapshot snap) {
        SCRATCH_POSE.pushPose();
        try {
            ce.applyLocalTransforms(SCRATCH_POSE, 1.0f);
            var pose = SCRATCH_POSE.last().pose();
            if (Math.abs(pose.m30()) < 100_000f && Math.abs(pose.m31()) < 100_000f
                    && Math.abs(pose.m32()) < 100_000f) {
                snap.local.set(pose);
            }
        } catch (Throwable ignored) {
        } finally {
            SCRATCH_POSE.popPose();
        }
    }

    private static void trackMotion(AbstractContraptionEntity ce, Snapshot snap, long now) {
        double mx, my, mz;
        if (now - snap.lastSeenMs < 150) {
            mx = ce.getX() - snap.x;
            my = ce.getY() - snap.y;
            mz = ce.getZ() - snap.z;
        } else {
            mx = ce.getX() - ce.xOld;
            my = ce.getY() - ce.yOld;
            mz = ce.getZ() - ce.zOld;
        }
        snap.movedWhileSeen = mx * mx + my * my + mz * mz > 1.0e-9;
    }

    private static void recordRail(AbstractContraptionEntity ce, Snapshot snap, List<double[]> liveRails) {
        if (!(ce instanceof com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity)) {
            return;
        }
        var move = ((me.cortex.voxy.client.mixin.create.AccessorGantryContraptionEntity) ce).voxy$getMovementAxis();
        if (move == null) {
            return;
        }
        var axis = move.getAxis();
        double u = axis == net.minecraft.core.Direction.Axis.X ? ce.getY() : ce.getX();
        double v = axis == net.minecraft.core.Direction.Axis.Z ? ce.getY() : ce.getZ();
        snap.railAxis = axis.ordinal();
        snap.railU = u;
        snap.railV = v;
        liveRails.add(new double[]{axis.ordinal(), u, v});
    }

    //ModelData serves the live bake; renderNbt preserves copycat materials across reloads.
    public record Source(List<ShapeBlock> blocks,
                         Map<BlockPos, net.neoforged.neoforge.client.model.data.ModelData> modelData,
                         Map<BlockPos, net.minecraft.nbt.CompoundTag> renderNbt) {
        public int blockCount() {
            return this.blocks.size();
        }
    }

    //Furthest block corner from the anchor, so distance tests can extend a point to the whole
    //structure. A radius, not a box: bearing poses rotate freely and a sphere holds under any of them.
    private static double boundRadiusOf(Source source) {
        int furthestSq = 0;
        for (var b : source.blocks()) {
            int ax = Math.abs(b.x()) + 1, ay = Math.abs(b.y()) + 1, az = Math.abs(b.z()) + 1;
            int d = ax * ax + ay * ay + az * az;
            if (d > furthestSq) {
                furthestSq = d;
            }
        }
        return Math.sqrt(furthestSq);
    }

    private static boolean disassembledInPlace(ClientLevel level, Snapshot s) {
        var source = s.source;
        if (source == null || source.blocks().isEmpty()) {
            return false;
        }
        var blocks = source.blocks();
        int samples = Math.min(12, blocks.size());
        int step = Math.max(1, blocks.size() / samples);
        int matched = 0, conclusive = 0;
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        var v = new org.joml.Vector3f();
        for (int i = 0; i < blocks.size() && conclusive < samples; i += step) {
            var b = blocks.get(i);
            //Same transform the draw uses: world = anchor + M_local * (block centre)
            v.set(b.x() + 0.5f, b.y() + 0.5f, b.z() + 0.5f);
            s.local.transformPosition(v);
            pos.set(net.minecraft.util.Mth.floor(s.x + v.x),
                    net.minecraft.util.Mth.floor(s.y + v.y),
                    net.minecraft.util.Mth.floor(s.z + v.z));
            if (!level.isLoaded(pos)) {
                continue;
            }
            var worldState = level.getBlockState(pos);
            if (worldState.isAir()) {
                continue;
            }
            conclusive++;
            if (worldState == b.state()) {
                matched++;
            }
        }
        return conclusive >= 6 && matched * 4 >= conclusive * 3;
    }

    private static Source collectBlocks(Contraption contraption) {
        List<ShapeBlock> blocks = new ArrayList<>();
        Map<BlockPos, net.neoforged.neoforge.client.model.data.ModelData> blockEntityData = null;
        Map<BlockPos, net.minecraft.nbt.CompoundTag> renderNbt = null;
        for (var entry : contraption.getBlocks().entrySet()) {
            BlockPos pos = entry.getKey();
            var state = entry.getValue().state();
            if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                continue;
            }
            if (Math.abs(pos.getX()) > MAX_LOCAL || Math.abs(pos.getY()) > MAX_LOCAL || Math.abs(pos.getZ()) > MAX_LOCAL) {
                continue;
            }
            blocks.add(new ShapeBlock((byte) pos.getX(), (byte) pos.getY(), (byte) pos.getZ(), state));
            //Copycat looks live in the captured block entity nbt, not the state
            var copycatNbt = me.cortex.voxy.commonImpl.compat.CopycatCommon
                    .renderNbt(state, entry.getValue().nbt());
            var copycatData = me.cortex.voxy.commonImpl.compat.CreateCopycatCompat
                    .materialFromContraptionNbt(state, copycatNbt);
            if (copycatData != null) {
                if (blockEntityData == null) {
                    blockEntityData = new HashMap<>();
                }
                blockEntityData.put(pos, copycatData);
            }
            if (copycatNbt != null) {
                if (renderNbt == null) renderNbt = new HashMap<>();
                renderNbt.put(pos, copycatNbt);
            }
        }
        return new Source(blocks, blockEntityData, renderNbt);
    }

    private static CarriageMeshBaker.BakedCarriage bakeBlocks(Source source) {
        return CarriageMeshBaker.bake(source.blocks(), source.modelData());
    }


    private static me.cortex.voxy.common.config.section.SectionStorage storageFor(ClientLevel level) {
        var engine = me.cortex.voxy.commonImpl.WorldIdentifier.ofEngineNullable(level);
        return engine == null ? null : engine.storage;
    }

    private static ClientLevel loadedLevel;
    private static me.cortex.voxy.common.world.WorldEngine loadedEngine;
    private static me.cortex.voxy.client.compat.AuxiliaryTableLoader<ContraptionStore.Stored> restoreLoad;
    private static final java.util.Set<UUID> restoreObserved = new java.util.HashSet<>();
    private static int restoredCount;

    private static void observeRestore(UUID id) {
        if (restoreLoad != null) restoreObserved.add(id);
    }

    public static void loadStored(ClientLevel level) {
        if (Minecraft.getInstance().level != level) return;
        var engine = me.cortex.voxy.commonImpl.WorldIdentifier.ofEngineNullable(level);
        if (loadedLevel != level || loadedEngine != engine) {
            if (loadedLevel != null && (loadedLevel != level || loadedEngine != null)) clearAll();
            loadedLevel = level;
            loadedEngine = engine;
            if (engine != null && engine.isLive() && engine.storage.supportsAuxTable(ContraptionStore.TABLE)) {
                var dimension = level.dimension().location();
                restoreLoad = new me.cortex.voxy.client.compat.AuxiliaryTableLoader<>(engine, ContraptionStore.TABLE,
                        (key, bytes) -> ContraptionStore.decode(bytes, dimension), ContraptionStore.Stored::estimatedBytes);
            }
        }
        var load = restoreLoad;
        if (load == null) return;
        var here = level.dimension().location();
        // 后台限量缓存，前台只安装 CPU 元数据；GPU 仍走既有两次烘焙额度。
        load.drain(16, (key, entry) -> {
            if (restoreLoad != load || loadedLevel != level || Minecraft.getInstance().level != level
                    || !here.equals(entry.dim()) || restoreObserved.contains(entry.id())
                    || SNAPSHOTS.containsKey(entry.id())) return;
            var snap = new Snapshot();
            snap.source = entry.source();
            snap.boundRadius = entry.boundRadius();
            snap.local.set(entry.pose());
            snap.x = entry.x();
            snap.y = entry.y();
            snap.z = entry.z();
            snap.dim = entry.dim();
            snap.trackingBlocks = entry.trackingBlocks();
            snap.lastSeenMs = System.currentTimeMillis();
            snap.live = false;
            SNAPSHOTS.put(entry.id(), snap);
            restoredCount++;
        });
        snapshotCount = SNAPSHOTS.size();
        if (restoreLoad == load && load.isFinished()) {
            load.close();
            restoreLoad = null;
            restoreObserved.clear();
            if (restoredCount != 0) {
                me.cortex.voxy.common.Logger.info("Restored " + restoredCount + " distant contraption(s) for " + here);
            }
            restoredCount = 0;
        }
    }



    private static long closeMesh(Snapshot snap) {
        if (snap.mesh == null) return 0;
        long bytes = snap.mesh.mesh.gpuByteSize();
        snap.mesh.close();
        snap.mesh = null;
        // 发布与释放即时结算，历史接纳使用本 tick 的实际驻留量。
        residentGpuBytes -= bytes;
        return bytes;
    }

    private static void dropMesh(Snapshot snap) {
        if (snap.mesh != null) {
            snap.lastMeshBytes = closeMesh(snap);
            me.cortex.voxy.commonImpl.PerfStats.contraptionSnapshotEvicted.increment();
        }
    }

    //A snapshot that still knows what it is made of but has no mesh right now. It draws nothing and
    //costs no vertex memory until something brings it back.
    private static boolean isDormant(Snapshot snap) {
        return snap.mesh == null && snap.source != null && !snap.bakeGaveNothing;
    }

    private record GpuEviction(Snapshot snapshot, double distanceSquared) {}

    private static void enforceGpuBudget(double camX, double camY, double camZ) {
        long budget = (long) VoxyConfig.CONFIG.distantContraptionGpuBudgetMiB * 1024L * 1024L;
        if (budget <= 0) {
            return;
        }
        if (residentGpuBytes <= budget) return;
        var candidates = new java.util.ArrayList<GpuEviction>();
        for (var snap : SNAPSHOTS.values()) {
            if (snap.mesh == null || snap.live) continue;
            double dx = snap.x - camX, dy = snap.y - camY, dz = snap.z - camZ;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared >= 0.0) candidates.add(new GpuEviction(snap, distanceSquared));
        }
        // 超预算才排序一次，逐个回收到九成水位，避免每释放一个网格都重扫全表。
        candidates.sort(java.util.Comparator.comparingDouble(GpuEviction::distanceSquared).reversed());
        long target = (budget * 9L) / 10L;
        for (var candidate : candidates) {
            if (residentGpuBytes <= target) break;
            Snapshot snap = candidate.snapshot;
            if (snap.mesh != null) {
                dropMesh(snap);
            }
        }
    }

    //Rebuilds a few dormant snapshots per tick, nearest first, while there is budget for them. Same
    //pacing as the restore path for the same reason: baking uploads a buffer.
    private static void bakeDormant(double camX, double camY, double camZ, double maxDist, int allowance) {
        if (allowance <= 0) return;
        var level = Minecraft.getInstance().level;
        long generation = snapshotGeneration;
        long budget = (long) VoxyConfig.CONFIG.distantContraptionGpuBudgetMiB * 1024L * 1024L;
        double maxDistSq = maxDist * maxDist;
        //Candidates rejected for size this tick. Without this the loop keeps picking the same nearest
        //one, finds it does not fit, and burns its whole allowance doing nothing.
        var tooBig = new java.util.HashSet<Snapshot>();
        for (int done = 0; done < allowance; done++) {
            Snapshot nearest = null;
            double nearestDistSq = Double.MAX_VALUE;
            for (var snap : SNAPSHOTS.values()) {
                if (!isDormant(snap) || tooBig.contains(snap)) {
                    continue;
                }
                double dx = snap.x - camX, dy = snap.y - camY, dz = snap.z - camZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                //Admission by the body, not the anchor: a long structure needs its mesh back as soon as
                //any of it can be on screen
                double admitDist = maxDist + snap.boundRadius;
                if (distSq > admitDist * admitDist || distSq >= nearestDistSq) {
                    continue;
                }
                nearestDistSq = distSq;
                nearest = snap;
            }
            if (nearest == null) {
                return;
            }
            if (budget > 0 && nearest.lastMeshBytes > 0
                    && residentGpuBytes + nearest.lastMeshBytes > budget) {
                tooBig.add(nearest);
                continue;
            }
            var mesh = bakeBlocks(nearest.source);
            if (!isCurrentUpdate(level, generation)) {
                if (mesh != null) mesh.close();
                return;
            }
            nearest.liveBakePending = false;
            if (mesh == null) {
                nearest.bakeGaveNothing = true;
                continue;
            }
            nearest.mesh = mesh;
            nearest.lastMeshBytes = mesh.mesh.gpuByteSize();
            if (nearest.lightPacked < 0) {
                var mc = Minecraft.getInstance();
                if (mc.level != null) {
                    nearest.lightPacked = DistantLightSampler.samplePeek(mc.level,
                            (int) Math.floor(nearest.x), (int) Math.floor(nearest.y), (int) Math.floor(nearest.z));
                }
            }
            residentGpuBytes += nearest.lastMeshBytes;
        }
    }

    public static long residentGpuBytes() {
        return residentGpuBytes;
    }

    private static volatile long residentGpuBytes;

    public static net.minecraft.world.entity.Entity trackedEntity(UUID id, Snapshot snap) {
        if (snap.entityId < 0) {
            return null;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        var entity = level.getEntity(snap.entityId);
        return entity != null && id.equals(entity.getUUID()) && !entity.isRemoved() ? entity : null;
    }

    public static boolean hiddenThisFrame(net.minecraft.world.entity.Entity entity) {
        if (!NowheelCulled.isCulled(entity)) {
            return false;
        }
        if (!dev.engine_room.flywheel.api.visualization.VisualizationManager.supportsVisualization(entity.level())) {
            return true;
        }
        return !FlywheelVisuals.hasVisual(entity);
    }

    public static Map<UUID, Snapshot> snapshots() {
        return SNAPSHOTS;
    }

    public static void retireToLeaveBehind(AbstractContraptionEntity ce) {
        var snap = SNAPSHOTS.get(ce.getUUID());
        if (snap == null) {
            return;
        }
        SCRATCH_POSE.pushPose();
        try {
            ce.applyLocalTransforms(SCRATCH_POSE, 1.0f);
            var pose = SCRATCH_POSE.last().pose();
            if (Math.abs(pose.m30()) < 100_000f && Math.abs(pose.m31()) < 100_000f
                    && Math.abs(pose.m32()) < 100_000f) {
                snap.local.set(pose);
            }
        } catch (Throwable ignored) {
        } finally {
            SCRATCH_POSE.popPose();
        }
        snap.x = ce.getX();
        snap.y = ce.getY();
        snap.z = ce.getZ();
        snap.movedWhileSeen = false;
        snap.live = false;
        var level = Minecraft.getInstance().level;
        if (level != null) {
            var storage = storageFor(level);
            if (storage != null) {
                ContraptionStore.save(storage, ce.getUUID(), snap);
            }
        }
    }

    //A contraption that died (disassembled back into blocks, broken, killed) no longer exists - its
    //snapshot must go immediately. Only unloading (the player walking away) freezes a leave-behind.
    public static void removeDead(UUID id) {
        observeRestore(id);
        Snapshot snap = SNAPSHOTS.remove(id);
        // 保留活动更新门禁；正在回调中的任务由外层检查代次并回收。
        if (snap != null) snapshotGeneration++;
        if (snap != null) closeMesh(snap);
        var level = Minecraft.getInstance().level;
        if (level != null) {
            var storage = storageFor(level);
            if (storage != null) {
                ContraptionStore.remove(storage, id);
            }
        }
        snapshotCount = SNAPSHOTS.size();
    }

    public static void onResourceReload() {
        snapshotGeneration++;
        for (var snap : SNAPSHOTS.values()) {
            closeMesh(snap);
            snap.lastMeshBytes = 0;
            snap.bakeGaveNothing = false;
            snap.liveBakePending = false;
        }
        residentGpuBytes = 0;
    }

    public static void clearAll() {
        snapshotGeneration++;
        if (restoreLoad != null) restoreLoad.close();
        restoreLoad = null;
        restoreObserved.clear();
        restoredCount = 0;
        loadedLevel = null;
        loadedEngine = null;
        for (var snap : SNAPSHOTS.values()) {
            closeMesh(snap);
        }
        SNAPSHOTS.clear();
        REMOTE_POSES.clear();
        snapshotCount = 0;
        residentGpuBytes = 0;
    }
}
