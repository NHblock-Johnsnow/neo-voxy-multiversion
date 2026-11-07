package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public final class DistantVisibility {
    private DistantVisibility() {}

    public static boolean isBoxVisible(Viewport<?> viewport,
                                       double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
        float x0 = (float) (minX - viewport.cameraX);
        float y0 = (float) (minY - viewport.cameraY);
        float z0 = (float) (minZ - viewport.cameraZ);
        float x1 = (float) (maxX - viewport.cameraX);
        float y1 = (float) (maxY - viewport.cameraY);
        float z1 = (float) (maxZ - viewport.cameraZ);
        return viewport.frustum.testAab(x0, y0, z0, x1, y1, z1);
    }

    public static boolean isTransformedBoxVisible(Viewport<?> viewport, Matrix4f local,
                                                  double originX, double originY, double originZ,
                                                  net.minecraft.world.phys.AABB localBounds) {
        return isTransformedBoxVisible(viewport, local, originX, originY, originZ,
                localBounds.minX, localBounds.minY, localBounds.minZ,
                localBounds.maxX, localBounds.maxY, localBounds.maxZ);
    }

    public static boolean isTransformedBoxVisible(Viewport<?> viewport, Matrix4fc local,
                                                  double originX, double originY, double originZ,
                                                  double minX, double minY, double minZ,
                                                  double maxX, double maxY, double maxZ) {
        // 仿射包围盒按中心与半长变换，等价于八角点并集，无临时向量或角点循环。
        double cx = (minX + maxX) * 0.5, cy = (minY + maxY) * 0.5, cz = (minZ + maxZ) * 0.5;
        double hx = (maxX - minX) * 0.5, hy = (maxY - minY) * 0.5, hz = (maxZ - minZ) * 0.5;
        double x = originX - viewport.cameraX + local.m30() + local.m00() * cx + local.m10() * cy + local.m20() * cz;
        double y = originY - viewport.cameraY + local.m31() + local.m01() * cx + local.m11() * cy + local.m21() * cz;
        double z = originZ - viewport.cameraZ + local.m32() + local.m02() * cx + local.m12() * cy + local.m22() * cz;
        double rx = Math.abs(local.m00()) * hx + Math.abs(local.m10()) * hy + Math.abs(local.m20()) * hz;
        double ry = Math.abs(local.m01()) * hx + Math.abs(local.m11()) * hy + Math.abs(local.m21()) * hz;
        double rz = Math.abs(local.m02()) * hx + Math.abs(local.m12()) * hy + Math.abs(local.m22()) * hz;
        return viewport.frustum.testAab(
                Math.nextDown((float) (x - rx)), Math.nextDown((float) (y - ry)), Math.nextDown((float) (z - rz)),
                Math.nextUp((float) (x + rx)), Math.nextUp((float) (y + ry)), Math.nextUp((float) (z + rz)));
    }
}
