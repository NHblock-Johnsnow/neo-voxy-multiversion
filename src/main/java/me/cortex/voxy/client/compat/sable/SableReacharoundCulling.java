package me.cortex.voxy.client.compat.sable;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import me.cortex.voxy.client.compat.SectionHandoff;
import me.cortex.voxy.client.compat.create.DistantMeshLod;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.core.rendering.LodViewState;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;

public final class SableReacharoundCulling {
    private static final double HYSTERESIS_BLOCKS = 32.0D;
    private static final IdentityHashMap<ClientSubLevel, Boolean> SUBPIXEL = new IdentityHashMap<>();
    private static final LodViewState VIEW = new LodViewState();
    private static final Matrix4f MVP = new Matrix4f();
    private static long cachedFrame = Long.MIN_VALUE;
    private static double cachedX, cachedY, cachedZ;

    private SableReacharoundCulling() {
    }

    public static Iterable<ClientSubLevel> filter(Iterable<ClientSubLevel> subLevels,
                                                 double cameraX, double cameraY, double cameraZ,
                                                 Matrix4f modelView, Matrix4f projection) {
        if (!SableClientRenderDistance.isVoxyRenderDistanceActive()) {
            SUBPIXEL.clear();
            VIEW.invalidate();
            return subLevels;
        }

        int vanillaRenderDistanceChunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
        double renderDistanceBlocks = SableClientRenderDistance.getRenderDistanceBlocks(vanillaRenderDistanceChunks) + HYSTERESIS_BLOCKS;
        if (!Double.isFinite(renderDistanceBlocks) || renderDistanceBlocks <= 0.0D) {
            return subLevels;
        }

        var target = Minecraft.getInstance().getMainRenderTarget();
        float subdivision = VoxyConfig.CONFIG.subDivisionSize;
        double nearDetail = (vanillaRenderDistanceChunks + 2) * 16.0;
        boolean shadow = IrisUtil.irisShadowActive();
        boolean projected = !shadow && modelView != null && projection != null;
        if (projected) MVP.set(projection).mul(modelView);
        if (!shadow && (cachedFrame != SectionHandoff.frameId()
                || cachedX != cameraX || cachedY != cameraY || cachedZ != cameraZ
                || (projected && !VIEW.matches(MVP, target.width, target.height, subdivision, nearDetail, 0)))) {
            SUBPIXEL.clear();
            VIEW.invalidate();
            cachedFrame = SectionHandoff.frameId();
            cachedX = cameraX; cachedY = cameraY; cachedZ = cameraZ;
        }
        if (projected) VIEW.set(MVP, target.width, target.height, subdivision, nearDetail);
        var center = new Vector3d();
        var corner = new Vector3d();
        double pixels = DistantMeshLod.visibilityPixels(subdivision);
        List<ClientSubLevel> visibleSubLevels = new ArrayList<>();
        for (ClientSubLevel subLevel : subLevels) {
            if (!isInRenderDistance(subLevel, cameraX, cameraZ, renderDistanceBlocks, center)) continue;
            // 无投影的方块实体阶段保守保留，不能沿用另一个视图的小目标结论。
            if (projected) {
                Boolean small = SUBPIXEL.get(subLevel);
                if (small == null) {
                    small = isSubpixel(subLevel, cameraX, cameraY, cameraZ, MVP,
                            target.width, target.height, pixels, nearDetail, corner);
                    SUBPIXEL.put(subLevel, small);
                }
                if (small) continue;
            }
            visibleSubLevels.add(subLevel);
        }
        return visibleSubLevels;
    }

    private static boolean isInRenderDistance(ClientSubLevel subLevel, double cameraX, double cameraZ,
                                               double renderDistanceBlocks, Vector3d center) {
        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        if (bounds == null) {
            return true;
        }

        center.set(
                (bounds.minX() + bounds.maxX() + 1) * 0.5D,
                (bounds.minY() + bounds.maxY() + 1) * 0.5D,
                (bounds.minZ() + bounds.maxZ() + 1) * 0.5D
        );
        subLevel.renderPose().transformPosition(center);

        double halfWidth = Math.max(0.5D, bounds.width() * 0.5D);
        double halfLength = Math.max(0.5D, bounds.length() * 0.5D);
        double horizontalRadius = Math.sqrt(halfWidth * halfWidth + halfLength * halfLength);

        double dx = center.x - cameraX;
        double dz = center.z - cameraZ;
        return (dx * dx + dz * dz) <= (renderDistanceBlocks + horizontalRadius) * (renderDistanceBlocks + horizontalRadius);
    }

    private static boolean isSubpixel(ClientSubLevel subLevel, double cx, double cy, double cz,
                                       Matrix4f mvp, int width, int height, double pixels, double nearDetail,
                                       Vector3d corner) {
        var bounds = subLevel.getPlot().getBoundingBox();
        if (bounds == null) return false;
        double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0;
        double x1 = Double.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        for (int i = 0; i < 8; i++) {
            // 额外包含方块实体与附属构件，不能只按船体底面判断。
            corner.set((i & 1) == 0 ? bounds.minX() - 16.0 : bounds.maxX() + 17.0,
                    (i & 2) == 0 ? bounds.minY() - 16.0 : bounds.maxY() + 17.0,
                    (i & 4) == 0 ? bounds.minZ() - 16.0 : bounds.maxZ() + 17.0);
            subLevel.renderPose().transformPosition(corner);
            x0 = Math.min(x0, corner.x); x1 = Math.max(x1, corner.x);
            y0 = Math.min(y0, corner.y); y1 = Math.max(y1, corner.y);
            z0 = Math.min(z0, corner.z); z1 = Math.max(z1, corner.z);
        }
        return DistantMeshLod.isSubpixel(mvp, (float) (x0 - cx), (float) (y0 - cy), (float) (z0 - cz),
                (float) (x1 - cx), (float) (y1 - cy), (float) (z1 - cz), width, height, pixels, nearDetail);
    }
}
