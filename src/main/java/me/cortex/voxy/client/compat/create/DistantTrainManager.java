package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.CarriagePose;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.CarriageShapePayload;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBogey;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.TrainPosesPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

//Client-side state for distant trains: baked carriage meshes keyed by shape id plus the latest two
//pose samples per carriage for interpolation. All access happens on the render/main thread.
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
        public final Map<Integer, CarriageTrack> carriages = new HashMap<>();
    }

    public record ShapeEntry(CarriageMeshBaker.BakedCarriage mesh, float initialYaw, List<ShapeBogey> bogeys) {
        void close() {
            this.mesh.close();
        }
    }

    private static final Map<UUID, TrainState> TRAINS = new ConcurrentHashMap<>();
    private static final Map<Long, ShapeEntry> SHAPES = new ConcurrentHashMap<>();

    //Diagnostics for /voxy debug trains
    public static volatile int shapesReceived;
    public static volatile int bakesFailed;

    public static void handleShape(CarriageShapePayload payload) {
        if (!me.cortex.voxy.client.ServerCapabilities.trains()) return;
        shapesReceived++;
        try {
            var existing = SHAPES.remove(payload.shapeId());
            if (existing != null) {
                existing.close();
            }
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
                SHAPES.put(payload.shapeId(), new ShapeEntry(baked, payload.initialYaw(), payload.bogeys()));
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
        state.dimension = payload.dimension();
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
        }
    }

    public static void removeTrain(UUID trainId) {
        //Poses only: trains drift in and out of the window constantly and the server sends each
        //shape once per session, so baked meshes stay cached until logout or replacement.
        TRAINS.remove(trainId);
    }

    public static void clearAll() {
        TRAINS.clear();
        for (var baked : SHAPES.values()) {
            baked.close();
        }
        SHAPES.clear();
    }

    public static Map<UUID, TrainState> trains() {
        return TRAINS;
    }

    public static ShapeEntry shape(long shapeId) {
        return SHAPES.get(shapeId);
    }

    public static boolean hasRenderable(UUID trainId, int carriageIndex, ResourceLocation dimension) {
        var state = TRAINS.get(trainId);
        if (state == null || !dimension.equals(state.dimension)) {
            return false;
        }
        var track = state.carriages.get(carriageIndex);
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
