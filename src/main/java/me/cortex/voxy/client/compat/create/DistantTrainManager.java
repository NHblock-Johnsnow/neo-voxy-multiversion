package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.CarriagePose;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.CarriageShapePayload;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBogey;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.TrainPosesPayload;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainPackets;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

//Client-side state for distant trains: baked carriage meshes keyed by shape id plus the latest two
//pose samples per carriage for interpolation. GPU/queue mutations stay on the main/render thread;
//Flywheel workers may query readiness without requesting rebuilds.
public final class DistantTrainManager {
    private DistantTrainManager() {}

    //Wheel rotation is accumulated client side from interpolated bogey movement, so it stays smooth
    //regardless of the pose sample rate.
    public static final class BogeyAnim {
        public float wheelAngle;
        public double lastX, lastY, lastZ;
        public boolean hasLast;
    }

    public static final class CarriageTrack {
        public long shapeId;
        public CarriagePose prev;
        public CarriagePose cur;
        public long curTimeNanos;
        private long poseGeneration;
        public long sampleIntervalNanos = 250_000_000L;
        public BogeyAnim[] bogeyAnims;
        //Voxel-store light sample at the carriage position, refreshed periodically while it moves
        public int lightPacked = -1;
        public long lightSampledAtMs;
        public final me.cortex.voxy.client.compat.LiveHandoffState handoff =
                new me.cortex.voxy.client.compat.LiveHandoffState();
    }

    public static final class TrainState {
        public ResourceLocation dimension;
        private long poseGeneration;
        public final Map<Integer, CarriageTrack> carriages = new HashMap<>();
    }

    public static final class ShapeEntry {
        private final CarriageMeshBaker.BakedCarriage mesh;
        private final float initialYaw;
        private final List<ShapeBogey> bogeys;
        private DistantMesh[] bogeyMeshes;
        private boolean[] bogeyResolved;
        private int bogeyGeneration;

        public ShapeEntry(CarriageMeshBaker.BakedCarriage mesh, float initialYaw, List<ShapeBogey> bogeys) {
            this.mesh = mesh;
            this.initialYaw = initialYaw;
            this.bogeys = bogeys;
        }

        public CarriageMeshBaker.BakedCarriage mesh() { return this.mesh; }
        public float initialYaw() { return this.initialYaw; }
        public List<ShapeBogey> bogeys() { return this.bogeys; }

        DistantMesh bogeyMesh(int index) {
            var provider = DistantTrainRenderer.bogeyMeshProvider;
            if (provider == null) return null;
            int generation = DistantTrainRenderer.bogeyMeshGeneration;
            if (this.bogeyMeshes == null || this.bogeyGeneration != generation) {
                this.bogeyMeshes = new DistantMesh[this.bogeys.size()];
                this.bogeyResolved = new boolean[this.bogeys.size()];
                this.bogeyGeneration = generation;
            }
            // NBT 匹配只在首次解析或资源重载后发生，逐帧复用空/非空结果。
            if (!this.bogeyResolved[index]) {
                var mesh = provider.apply(this.bogeys.get(index));
                if (generation != DistantTrainRenderer.bogeyMeshGeneration) return null;
                var active = DistantTrainRenderer.bogeyMeshCaptureActive;
                // 重入的临时空值要重试，真正的空结果仍按代次复用。
                if (mesh == null && active != null && active.getAsBoolean()) return null;
                this.bogeyMeshes[index] = mesh;
                this.bogeyResolved[index] = true;
            }
            return this.bogeyMeshes[index];
        }

        void close() {
            this.mesh.close();
        }
    }

    private static final Map<UUID, TrainState> TRAINS = new ConcurrentHashMap<>();
    private static final Map<Long, ShapeEntry> SHAPES = new ConcurrentHashMap<>();
    private static final Map<Long, CarriageShapePayload> SHAPE_SOURCES = new HashMap<>();
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<CarriageShapePayload>
            REBUILD_QUEUE = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet REBUILD_REQUESTED =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private static long resourceGeneration;
    private static final int REBUILDS_PER_TICK = 2;
    private static final DistantTrainPackets.Assembler PACKETS = new DistantTrainPackets.Assembler();

    //Diagnostics for /voxy debug trains
    public static volatile int shapesReceived;
    public static volatile int bakesFailed;

    public static void handlePacket(net.minecraft.network.Connection connection, Object payload) {
        var listener = net.minecraft.client.Minecraft.getInstance().getConnection();
        if (listener == null || listener.getConnection() != connection
                || !me.cortex.voxy.client.ServerCapabilities.trains()) return;
        try {
            if (payload instanceof DistantTrainPackets.FragmentPayload fragment) {
                payload = PACKETS.accept(fragment, System.nanoTime());
            }
            if (payload instanceof CarriageShapePayload shape) handleShape(shape);
            else if (payload instanceof TrainPosesPayload poses) handlePoses(poses);
        } catch (RuntimeException failure) {
            me.cortex.voxy.common.Logger.error("Decoding distant train packet", failure);
        }
    }

    public static void handleShape(CarriageShapePayload payload) {
        if (!me.cortex.voxy.client.ServerCapabilities.trains()) return;
        shapesReceived++;
        SHAPE_SOURCES.put(payload.shapeId(), payload);
        REBUILD_REQUESTED.add(payload.shapeId());
        // 同车厢只保留最新待处理源，首包与资源重建共用每 tick 预算。
        REBUILD_QUEUE.put(payload.shapeId(), payload);
    }

    private static void bakeShape(CarriageShapePayload payload) {
        long generation = resourceGeneration;
        try {
            //Rebuild copycat ModelData from the material slices the server attached to the shape
            java.util.Map<net.minecraft.core.BlockPos, net.neoforged.neoforge.client.model.data.ModelData> blockEntityData = null;
            for (var block : payload.blocks()) {
                if (block.renderNbt().isEmpty()) {
                    continue;
                }
                var data = me.cortex.voxy.commonImpl.compat.CreateCopycatCompat
                        .materialFromContraptionNbt(block.state(), block.renderNbt().get());
                if (data != null) {
                    if (blockEntityData == null) {
                        blockEntityData = new java.util.HashMap<>();
                    }
                    blockEntityData.put(new net.minecraft.core.BlockPos(block.x(), block.y(), block.z()), data);
                }
            }
            var baked = CarriageMeshBaker.bake(payload.blocks(), blockEntityData);
            if (baked != null) {
                // 模型回调可能重入更新、重载或登出，旧结果不得再发布。
                if (generation != resourceGeneration || SHAPE_SOURCES.get(payload.shapeId()) != payload) {
                    baked.close();
                    return;
                }
                var existing = SHAPES.put(payload.shapeId(), new ShapeEntry(baked, payload.initialYaw(), payload.bogeys()));
                if (existing != null) existing.close();
            } else {
                bakesFailed++;
            }
        } catch (Throwable e) {
            //enqueueWork futures swallow exceptions; surface them ourselves
            bakesFailed++;
            me.cortex.voxy.common.Logger.error("Distant train shape handling failed (shapeId="
                    + Long.toHexString(payload.shapeId()) + ")", e);
        }
    }

    public static void handlePoses(TrainPosesPayload payload) {
        if (!me.cortex.voxy.client.ServerCapabilities.trains()) return;
        if (payload.carriages().isEmpty()) {
            removeTrain(payload.trainId());
            return;
        }
        var state = TRAINS.computeIfAbsent(payload.trainId(), k -> new TrainState());
        if (!payload.dimension().equals(state.dimension)) {
            state.carriages.clear();
        }
        state.dimension = payload.dimension();
        long generation = ++state.poseGeneration;
        long now = System.nanoTime();
        for (var pose : payload.carriages()) {
            var track = state.carriages.computeIfAbsent(pose.carriageIndex(), k -> new CarriageTrack());
            if (track.cur != null) {
                track.sampleIntervalNanos = Math.max(50_000_000L, Math.min(2_000_000_000L, now - track.curTimeNanos));
            }
            track.prev = track.cur != null ? track.cur : pose;
            track.cur = pose;
            track.curTimeNanos = now;
            track.shapeId = pose.shapeId();
            track.poseGeneration = generation;
        }
        // 每包是当前范围内的完整车厢集，用代次剔除缺席项，无需临时集合。
        var tracks = state.carriages.values().iterator();
        while (tracks.hasNext()) {
            if (tracks.next().poseGeneration != generation) {
                tracks.remove();
            }
        }
    }

    public static void removeTrain(UUID trainId) {
        //Poses only: trains drift in and out of the window constantly and the server sends each
        //shape once per session, so baked meshes stay cached until logout or replacement.
        TRAINS.remove(trainId);
    }

    public static void clearAll() {
        resourceGeneration++;
        PACKETS.clear();
        TRAINS.clear();
        for (var baked : SHAPES.values()) {
            baked.close();
        }
        SHAPES.clear();
        SHAPE_SOURCES.clear();
        REBUILD_QUEUE.clear();
        REBUILD_REQUESTED.clear();
    }

    public static void onResourceReload() {
        resourceGeneration++;
        for (var shape : SHAPES.values()) shape.close();
        SHAPES.clear();
        REBUILD_QUEUE.clear();
        REBUILD_REQUESTED.clear();
    }

    public static void rebuildPending() {
        int remaining = REBUILDS_PER_TICK;
        while (remaining-- > 0 && !REBUILD_QUEUE.isEmpty()) {
            var source = REBUILD_QUEUE.removeFirst();
            if (SHAPE_SOURCES.get(source.shapeId()) != source) continue;
            bakeShape(source);
        }
    }

    public static void expirePackets() {
        PACKETS.expire(System.nanoTime());
    }

    private static void requestRebuild(long shapeId) {
        var source = SHAPE_SOURCES.get(shapeId);
        // 只重建再次被访问的车厢；同一资源代次的失败也只尝试一次。
        if (source != null && REBUILD_REQUESTED.add(shapeId)) {
            REBUILD_QUEUE.put(shapeId, source);
        }
    }

    public static Map<UUID, TrainState> trains() {
        return TRAINS;
    }

    public static ShapeEntry shape(long shapeId) {
        ShapeEntry shape = SHAPES.get(shapeId);
        if (shape == null) requestRebuild(shapeId);
        return shape;
    }

    public static boolean hasRenderable(UUID trainId, int carriageIndex, ResourceLocation dimension) {
        var state = TRAINS.get(trainId);
        if (state == null || !dimension.equals(state.dimension)) {
            return false;
        }
        var track = state.carriages.get(carriageIndex);
        // Flywheel 可在 worker 查询 ready；此处只读，重建由主线程绘制请求。
        return track != null && track.cur != null && SHAPES.containsKey(track.shapeId);
    }

    public static CarriageTrack track(UUID trainId, int carriageIndex, ResourceLocation dimension) {
        var state = TRAINS.get(trainId);
        if (state == null || !dimension.equals(state.dimension)) {
            return null;
        }
        return state.carriages.get(carriageIndex);
    }

    public static int meshCount() {
        return SHAPES.size();
    }

    public static java.util.Set<Long> meshKeys() {
        return SHAPES.keySet();
    }

    public static boolean isEmpty() {
        return TRAINS.isEmpty();
    }
}
