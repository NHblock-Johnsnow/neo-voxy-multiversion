package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.common.world.WorldEngine;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** 专属网格复用地形区段的投影面积判据，不读回 GPU 绘制列表。 */
public final class NativeLodSelection {
    private static final int CACHE_SIZE = 4096;
    private static final int MAX_VISITS = 128;
    private final int[] cacheX = new int[CACHE_SIZE], cacheY = new int[CACHE_SIZE], cacheZ = new int[CACHE_SIZE];
    private final byte[] cacheLevel = new byte[CACHE_SIZE], cacheDecision = new byte[CACHE_SIZE];
    private final int[] stamps = new int[CACHE_SIZE];
    private final Matrix4f mvp = new Matrix4f();
    private final LodViewState view = new LodViewState();
    private final float[] px = new float[8], py = new float[8];
    private double cameraX, cameraY, cameraZ;
    private int generation, visits;
    private int minX, minY, minZ, maxX, maxY, maxZ;
    private boolean valid;
    public float minScreenArea, fullDetailDistanceSquared, invP00, invP11, stretchMax;
    public int evaluations, cacheHits;

    public void update(Matrix4fc mvp, Matrix4fc projection, int width, int height,
                       double cameraX, double cameraY, double cameraZ, float subdivision, float fullDetailDistance) {
        float invP00 = 1.0f / Math.max(.0001f, projection.m00());
        float invP11 = 1.0f / Math.max(.0001f, projection.m11());
        if (this.cameraX == cameraX && this.cameraY == cameraY && this.cameraZ == cameraZ
                && this.invP00 == invP00 && this.invP11 == invP11
                && this.view.matches(mvp, width, height, subdivision, fullDetailDistance, 0)) return;
        this.cameraX = cameraX; this.cameraY = cameraY; this.cameraZ = cameraZ;
        this.mvp.set(mvp);
        this.view.set(mvp, width, height, subdivision, fullDetailDistance);
        this.minScreenArea = subdivision * subdivision / (width * (float) height);
        this.fullDetailDistanceSquared = fullDetailDistance * fullDetailDistance;
        this.invP00 = invP00; this.invP11 = invP11;
        this.stretchMax = (float) Math.pow(1.0 + (double) invP00 * invP00 + (double) invP11 * invP11, 1.5);
        this.valid = width > 0 && height > 0 && Float.isFinite(this.minScreenArea) && this.minScreenArea > 0
                && this.mvp.isFinite();
        if (++this.generation == 0) {
            java.util.Arrays.fill(this.stamps, 0);
            this.generation = 1;
        }
        this.evaluations = this.cacheHits = 0;
    }

    /** 返回模型局部坐标的粗化尺寸；跨区段模型采用所覆盖区域的最细层级。 */
    public float gridSize(Matrix4fc m, double worldX, double worldY, double worldZ,
                          float x0, float y0, float z0, float x1, float y1, float z1) {
        if (!this.valid) return 0;
        if (!m.isFinite()) return 0;
        double x = (x0 + x1) * .5, y = (y0 + y1) * .5, z = (z0 + z1) * .5;
        double hx = (x1 - x0) * .5, hy = (y1 - y0) * .5, hz = (z1 - z0) * .5;
        double cx = m.m00() * x + m.m10() * y + m.m20() * z + m.m30() + worldX;
        double cy = m.m01() * x + m.m11() * y + m.m21() * z + m.m31() + worldY;
        double cz = m.m02() * x + m.m12() * y + m.m22() * z + m.m32() + worldZ;
        double ex = Math.abs(m.m00()) * hx + Math.abs(m.m10()) * hy + Math.abs(m.m20()) * hz;
        double ey = Math.abs(m.m01()) * hx + Math.abs(m.m11()) * hy + Math.abs(m.m21()) * hz;
        double ez = Math.abs(m.m02()) * hx + Math.abs(m.m12()) * hy + Math.abs(m.m22()) * hz;
        int level = this.level(cx - ex, cy - ey, cz - ez, cx + ex, cy + ey, cz + ez);
        if (level == 0) return 0;
        double scale = Math.max(Math.abs(m.m00()) + Math.abs(m.m10()) + Math.abs(m.m20()),
                Math.max(Math.abs(m.m01()) + Math.abs(m.m11()) + Math.abs(m.m21()),
                        Math.abs(m.m02()) + Math.abs(m.m12()) + Math.abs(m.m22())));
        return scale > 0 ? (float) ((1 << level) / scale) : 0;
    }

    public int revision() {
        return this.generation;
    }

    public int level(double x0, double y0, double z0, double x1, double y1, double z1) {
        if (!this.valid || !Double.isFinite(x0 + y0 + z0 + x1 + y1 + z1)
                || x0 > x1 || y0 > y1 || z0 > z1) return 0;
        this.minX = (int) Math.floor(x0 / 32); this.minY = (int) Math.floor(y0 / 32); this.minZ = (int) Math.floor(z0 / 32);
        this.maxX = upper(x0, x1); this.maxY = upper(y0, y1); this.maxZ = upper(z0, z1);
        int top = WorldEngine.MAX_LOD_LAYER;
        int ax = this.minX >> top, ay = this.minY >> top, az = this.minZ >> top;
        int bx = this.maxX >> top, by = this.maxY >> top, bz = this.maxZ >> top;
        long nx = (long) bx - ax + 1, ny = (long) by - ay + 1, nz = (long) bz - az + 1;
        if (nx > MAX_VISITS || ny > MAX_VISITS || nz > MAX_VISITS) return 0;
        long roots = nx * ny * nz;
        if (roots <= 0 || roots > MAX_VISITS) return 0;
        this.visits = 0;
        int result = top;
        for (int z = az; z <= bz; z++) for (int y = ay; y <= by; y++) for (int x = ax; x <= bx; x++) {
            result = Math.min(result, this.visit(x, y, z, top));
            if (result == 0) return 0;
        }
        return result;
    }

    private static int upper(double min, double max) {
        // 包围盒上边界不属于相邻区段；平面则保留所在区段。
        return (int) (max > min ? Math.ceil(max / 32) - 1 : Math.floor(max / 32));
    }

    private int visit(int x, int y, int z, int level) {
        if (level == 0 || ++this.visits > MAX_VISITS) return 0;
        int slot = (x * 0x1f1f1f1f ^ y * 0x6c8e9cf5 ^ z * 0x9e3779b9 ^ level * 73428767) & (CACHE_SIZE - 1);
        boolean descend;
        if (this.stamps[slot] == this.generation && this.cacheX[slot] == x && this.cacheY[slot] == y
                && this.cacheZ[slot] == z && this.cacheLevel[slot] == level) {
            descend = this.cacheDecision[slot] != 0;
            this.cacheHits++;
        } else {
            descend = this.shouldDescend(x, y, z, level);
            this.stamps[slot] = this.generation;
            this.cacheX[slot] = x; this.cacheY[slot] = y; this.cacheZ[slot] = z;
            this.cacheLevel[slot] = (byte) level;
            this.cacheDecision[slot] = (byte) (descend ? 1 : 0);
        }
        if (!descend) return level;
        int child = level - 1, result = level;
        int ax = Math.max(x * 2, this.minX >> child), bx = Math.min(x * 2 + 1, this.maxX >> child);
        int ay = Math.max(y * 2, this.minY >> child), by = Math.min(y * 2 + 1, this.maxY >> child);
        int az = Math.max(z * 2, this.minZ >> child), bz = Math.min(z * 2 + 1, this.maxZ >> child);
        for (int cz = az; cz <= bz; cz++) for (int cy = ay; cy <= by; cy++) for (int cx = ax; cx <= bx; cx++) {
            result = Math.min(result, this.visit(cx, cy, cz, child));
            if (result == 0) return 0;
        }
        return result;
    }

    /** 与 screenspace.glsl 的面积、视野拉伸及近景强制细分一致。 */
    public boolean shouldDescend(int x, int y, int z, int level) {
        this.evaluations++;
        float size = 32 << level;
        float bx = (float) (x * (double) size - this.cameraX);
        float by = (float) (y * (double) size - this.cameraY);
        float bz = (float) (z * (double) size - this.cameraZ);
        float dx = Math.max(bx, Math.max(0, -bx - size)), dz = Math.max(bz, Math.max(0, -bz - size));
        if (this.fullDetailDistanceSquared > 0 && dx * dx + dz * dz <= this.fullDetailDistanceSquared) return true;
        var m = this.mvp;
        float baseX = m.m00() * bx + m.m10() * by + m.m20() * bz + m.m30();
        float baseY = m.m01() * bx + m.m11() * by + m.m21() * bz + m.m31();
        float baseW = m.m03() * bx + m.m13() * by + m.m23() * bz + m.m33();
        float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
        for (int i = 0; i < 8; i++) {
            float px = baseX, py = baseY, w = baseW;
            if ((i & 1) != 0) { px += m.m00() * size; py += m.m01() * size; w += m.m03() * size; }
            if ((i & 4) != 0) { px += m.m20() * size; py += m.m21() * size; w += m.m23() * size; }
            if ((i & 2) != 0) { px += m.m10() * size; py += m.m11() * size; w += m.m13() * size; }
            // 穿过近裁剪面的区段保守细分，避免透视除法翻转。
            if (!(w > 0.0001f)) return true;
            this.px[i] = px / w * .5f + .5f;
            this.py[i] = py / w * .5f + .5f;
            minX = Math.min(minX, this.px[i]); maxX = Math.max(maxX, this.px[i]);
            minY = Math.min(minY, this.py[i]); maxY = Math.max(maxY, this.py[i]);
        }
        float area = (this.cross(0, 1, 2) + this.cross(0, 1, 4) + this.cross(0, 4, 2)
                + this.cross(7, 6, 5) + this.cross(7, 6, 3) + this.cross(7, 3, 5)) * .5f;
        if (!Float.isFinite(area) || area > this.minScreenArea) return true;
        float tx = (Math.clamp(minX, 0, 1) + Math.clamp(maxX, 0, 1) - 1) * this.invP00;
        float ty = (Math.clamp(minY, 0, 1) + Math.clamp(maxY, 0, 1) - 1) * this.invP11;
        float stretch = (float) Math.pow(1.0f + tx * tx + ty * ty, 1.5);
        return area * (this.stretchMax / stretch) > this.minScreenArea;
    }

    private float cross(int origin, int a, int b) {
        return Math.abs((this.px[a] - this.px[origin]) * (this.py[b] - this.py[origin])
                - (this.px[b] - this.px[origin]) * (this.py[a] - this.py[origin]));
    }
}
