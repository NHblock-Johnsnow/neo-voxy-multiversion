package me.cortex.voxy.client.compat.create;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import me.cortex.voxy.client.compat.SectionHandoff;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class TrainHandover {
    public static final double CREATE_TRACKING_CAP = 224;

    private TrainHandover() {}

    public static double handoverDist() {
        //Full view distance: the switch belongs at the vanilla->LOD transition, and handing over any
        //earlier is glaring at small view distances (4 chunks rendered, trains going distant at 2).
        return Math.min(CREATE_TRACKING_CAP,
                Minecraft.getInstance().options.getEffectiveRenderDistance() * 16);
    }

    public static boolean beyondLive(Vec3 entityPos, Vec3 cam) {
        double d = handoverDist();
        return entityPos.distanceToSqr(cam) > d * d;
    }

    public static boolean shouldCullLive(CarriageContraptionEntity entity, Vec3 cam) {
        var config = me.cortex.voxy.client.config.VoxyConfig.CONFIG;
        var dimension = entity.level().dimension().location();
        if (!config.isRenderingEnabled() || !config.distantTrains
                || !me.cortex.voxy.client.ServerCapabilities.trains()
                || !DistantTrainManager.hasRenderable(entity.trainId, entity.carriageIndex, dimension)) {
            return false;
        }
        var track = DistantTrainManager.track(entity.trainId, entity.carriageIndex, dimension);
        if (track == null) {
            return false;
        }
        boolean flywheel = VisualizationManager.supportsVisualization(entity.level());
        return !resolveLiveOwnership(track, entity, cam, flywheel);
    }

    public static boolean liveCarriageOwns(UUID trainId, int carriageIndex, Vec3 cam,
                                            boolean flywheelRendering) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
        var track = DistantTrainManager.track(trainId, carriageIndex, mc.level.dimension().location());
        if (track == null) return false;
        if (track.handoff.evaluated(SectionHandoff.frameId())) return track.handoff.liveOwns();
        var train = Create.RAILWAYS.sided(mc.level).trains.get(trainId);
        if (train == null || carriageIndex < 0 || carriageIndex >= train.carriages.size()) {
            return resolveLiveOwnership(track, null, cam, flywheelRendering);
        }
        var dimensional = train.carriages.get(carriageIndex).getDimensionalIfPresent(mc.level.dimension());
        var entity = dimensional == null ? null : dimensional.entity.get();
        return resolveLiveOwnership(track, entity, cam, flywheelRendering);
    }

    private static boolean resolveLiveOwnership(DistantTrainManager.CarriageTrack track,
                                                CarriageContraptionEntity entity, Vec3 cam,
                                                boolean flywheelRendering) {
        long frame = SectionHandoff.frameId();
        if (track.handoff.evaluated(frame)) return track.handoff.liveOwns();
        // 重返原版时留一块区块的回差，边界往返不会反复切换模型。
        double range = handoverDist();
        if (track.handoff.reacquiring()) range = Math.max(16.0, range - 16.0);
        boolean ready = entity != null
                && entity.position().distanceToSqr(cam) <= range * range
                && entity.isAlive()
                && entity.getContraption() != null
                && entity.validForRender
                && !entity.firstPositionUpdate
                && (flywheelRendering
                    ? FlywheelVisuals.hasVisual(entity)
                    : Minecraft.getInstance().levelRenderer.isSectionCompiled(entity.blockPosition()));
        // 原版、Flywheel 与 LOD 共用帧时间，不能在同帧跨过等待阈值后改变归属。
        return track.handoff.update(frame, SectionHandoff.frameTimeNanos(), ready);
    }
}
