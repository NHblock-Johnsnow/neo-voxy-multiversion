package me.cortex.voxy.client.compat.sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;

import static org.lwjgl.opengl.GL45C.*;

/** 地形渐变不能接管 Sable 已写入的结构深度。 */
public final class SableNativeDepthProtection {
    private static final double OVERHANG = 2.0;

    private SableNativeDepthProtection() {}

    private static final Prepared TERRAIN_PASS = new Prepared();

    public static void restore(Viewport<?> viewport, FullscreenBlit setup, float fadeStart) {
        if (!TERRAIN_PASS.prepare(viewport, fadeStart)) return;
        boolean scissorEnabled = glIsEnabled(GL_SCISSOR_TEST);
        glGetIntegerv(GL_SCISSOR_BOX, TERRAIN_PASS.scissor);
        try {
            TERRAIN_PASS.draw(setup, -1, -1, scissorEnabled, TERRAIN_PASS.scissor);
        } finally {
            int[] scissor = TERRAIN_PASS.scissor;
            glScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
            if (!scissorEnabled) glDisable(GL_SCISSOR_TEST);
        }
    }

    // 只在渲染线程使用；矩阵、数组按需扩容后复用，不缓存跨帧姿态。
    static final class Prepared {
        final Matrix4d inverseNativeMvp = new Matrix4d();
        private final Matrix4d pose = new Matrix4d();
        private final Matrix4d modelView = new Matrix4d();
        private final Matrix4f nativeMvp = new Matrix4f();
        private final Vector3d corner = new Vector3d();
        private final Vector4f clip = new Vector4f();
        private final java.util.ArrayList<Ship> ships = new java.util.ArrayList<>();
        private final int[] scissor = new int[4];
        private int count;

        boolean prepare(Viewport<?> viewport, float fadeStart) {
            this.count = 0;
            var level = Minecraft.getInstance().level;
            if (level == null) return false;
            var container = SubLevelContainer.getContainer(level);
            if (container == null || container.getAllSubLevels().isEmpty()) return false;
            this.nativeMvp.set(viewport.vanillaProjection).mul(viewport.modelView);
            for (var subLevel : container.getAllSubLevels()) {
                var bounds = subLevel.getPlot().getBoundingBox();
                if (bounds == null) continue;
                double cx = ((double) bounds.minX() + bounds.maxX() + 1) * 0.5;
                double cy = ((double) bounds.minY() + bounds.maxY() + 1) * 0.5;
                double cz = ((double) bounds.minZ() + bounds.maxZ() + 1) * 0.5;
                double hx = ((double) bounds.maxX() - bounds.minX() + 1) * 0.5 + OVERHANG;
                double hy = ((double) bounds.maxY() - bounds.minY() + 1) * 0.5 + OVERHANG;
                double hz = ((double) bounds.maxZ() - bounds.minZ() + 1) * 0.5 + OVERHANG;

                // 先用 double 消除相机和地块的大坐标，再上传以船体中心为原点的矩阵。
                subLevel.renderPose().bakeIntoMatrix(pose).translate(cx, cy, cz);
                pose.m30(pose.m30() - viewport.cameraX);
                pose.m31(pose.m31() - viewport.cameraY);
                pose.m32(pose.m32() - viewport.cameraZ);
                float minX = Float.POSITIVE_INFINITY, minY = minX;
                float maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
                double farthest = 0;
                boolean crossesCamera = false;
                boolean inFrontOfCamera = false;
                for (int i = 0; i < 8; i++) {
                    corner.set((i & 1) == 0 ? -hx : hx, (i & 2) == 0 ? -hy : hy,
                            (i & 4) == 0 ? -hz : hz);
                    pose.transformPosition(corner);
                    farthest = Math.max(farthest, corner.lengthSquared());
                    clip.set((float) corner.x, (float) corner.y, (float) corner.z, 1);
                    nativeMvp.transform(clip);
                    inFrontOfCamera |= clip.w > 0;
                    if (clip.w <= 1e-4f) {
                        crossesCamera = true;
                        continue;
                    }
                    minX = Math.min(minX, clip.x / clip.w);
                    minY = Math.min(minY, clip.y / clip.w);
                    maxX = Math.max(maxX, clip.x / clip.w);
                    maxY = Math.max(maxY, clip.y / clip.w);
                }
                // 完全位于相机后方的包围盒无需退化为全屏补写。
                if (!inFrontOfCamera || farthest <= (double) fadeStart * fadeStart) continue;
                if (crossesCamera) {
                    minX = minY = -1;
                    maxX = maxY = 1;
                }
                if (minX > 1 || minY > 1 || maxX < -1 || maxY < -1) continue;
                minX = Math.max(-1, minX);
                minY = Math.max(-1, minY);
                maxX = Math.min(1, maxX);
                maxY = Math.min(1, maxY);
                int x0 = Math.max(0, (int) Math.floor((minX * 0.5 + 0.5) * viewport.width) - 1);
                int y0 = Math.max(0, (int) Math.floor((minY * 0.5 + 0.5) * viewport.height) - 1);
                int x1 = Math.min(viewport.width, (int) Math.ceil((maxX * 0.5 + 0.5) * viewport.width) + 1);
                int y1 = Math.min(viewport.height, (int) Math.ceil((maxY * 0.5 + 0.5) * viewport.height) + 1);
                if (x1 <= x0 || y1 <= y0) continue;
                if (this.count == 0) {
                    this.inverseNativeMvp.set(viewport.vanillaProjection)
                            .mul(this.modelView.set(viewport.modelView)).invert();
                }
                if (this.count == this.ships.size()) this.ships.add(new Ship());
                Ship ship = this.ships.get(this.count++);
                // 在 double 中合成逆矩阵，避免远处倾斜表面的重建误差逐级放大。
                pose.invert().mul(this.inverseNativeMvp).get(ship.matrix);
                ship.hx = (float) hx;
                ship.hy = (float) hy;
                ship.hz = (float) hz;
                ship.x = x0;
                ship.y = y0;
                ship.width = x1 - x0;
                ship.height = y1 - y0;
            }
            return this.count != 0;
        }

        void draw(FullscreenBlit setup, int firstFramebuffer, int secondFramebuffer,
                  boolean scissorEnabled, int[] scissor) {
            try {
                glUniform1i(10, 2);
                glEnable(GL_SCISSOR_TEST);
                for (int i = 0; i < this.count; i++) {
                    Ship ship = this.ships.get(i);
                    int x0 = ship.x, y0 = ship.y;
                    int x1 = x0 + ship.width, y1 = y0 + ship.height;
                    if (scissorEnabled) {
                        x0 = Math.max(x0, scissor[0]);
                        y0 = Math.max(y0, scissor[1]);
                        x1 = Math.min(x1, scissor[0] + scissor[2]);
                        y1 = Math.min(y1, scissor[1] + scissor[3]);
                    }
                    if (x1 <= x0 || y1 <= y0) continue;
                    glUniformMatrix4fv(11, false, ship.matrix);
                    glUniform3f(15, ship.hx, ship.hy, ship.hz);
                    glScissor(x0, y0, x1 - x0, y1 - y0);
                    setup.blit();
                    if (secondFramebuffer >= 0) {
                        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, secondFramebuffer);
                        setup.blit();
                        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, firstFramebuffer);
                    }
                }
            } finally {
                glUniform1i(10, 0);
            }
        }
    }

    private static final class Ship {
        final float[] matrix = new float[16];
        float hx, hy, hz;
        int x, y, width, height;
    }
}
