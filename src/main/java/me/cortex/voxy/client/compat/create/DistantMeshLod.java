package me.cortex.voxy.client.compat.create;

import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** 立体模型使用粗体素外壳；单平面保留共用顶点的细分折叠。 */
public final class DistantMeshLod {
    private static final float[] GRIDS = {1.0f / 16, 1.0f / 4, 1.0f};
    private static final int STRIDE = 32;
    public static final DistantMeshLod EMPTY = new DistantMeshLod(new float[0], new int[0][]);
    public final float[] grids;
    public final int[][] indices;
    public final int[][] vertices;

    private DistantMeshLod(float[] grids, int[][] indices) {
        this(grids, indices, null);
    }

    DistantMeshLod(float[] grids, int[][] indices, int[][] vertices) {
        this.grids = grids;
        this.indices = indices;
        this.vertices = vertices;
    }

    public static DistantMeshLod build(ByteBuffer vertices, int quads) {
        if (quads < 12) return EMPTY;
        float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int vertex = 0; vertex < quads * 4; vertex++) for (int axis = 0; axis < 3; axis++) {
            float value = vertices.getFloat(vertex * STRIDE + axis * 4);
            if (!Float.isFinite(value) || Math.abs(value) > 1e6) return EMPTY;
            bounds[axis] = Math.min(bounds[axis], value);
            bounds[axis + 3] = Math.max(bounds[axis + 3], value);
        }
        if (bounds[3] - bounds[0] > 1e-5 && bounds[4] - bounds[1] > 1e-5 && bounds[5] - bounds[2] > 1e-5) {
            return DistantMeshCoarsener.build(vertices, quads, bounds);
        }
        if (quads < 128) return EMPTY;
        int fullCount = quads * 6;
        int[] scratch = new int[fullCount];
        float[] positions = new float[12];
        float[] grids = new float[GRIDS.length];
        int[][] indices = new int[GRIDS.length][];
        int levels = 0, previousCount = fullCount;
        for (float grid : GRIDS) {
            int count = 0;
            for (int quad = 0; quad < quads; quad++) {
                int vertex = quad * 4;
                for (int i = 0; i < 4; i++) {
                    for (int axis = 0; axis < 3; axis++) {
                        float value = vertices.getFloat((vertex + i) * STRIDE + axis * 4);
                        if (!Float.isFinite(value)) return EMPTY;
                        positions[i * 3 + axis] = snap(value, grid);
                    }
                }
                // 只删除顶点重合的三角形；嵌套网格保证它们在更粗级别不会重新展开。
                if (!same(positions, 0, 1) && !same(positions, 1, 2) && !same(positions, 2, 0)) {
                    scratch[count++] = vertex;
                    scratch[count++] = vertex + 1;
                    scratch[count++] = vertex + 2;
                }
                if (!same(positions, 2, 3) && !same(positions, 3, 0) && !same(positions, 0, 2)) {
                    scratch[count++] = vertex + 2;
                    scratch[count++] = vertex + 3;
                    scratch[count++] = vertex;
                }
            }
            // 至少减半才值得保留；全部附加索引不超过一份完整索引，空模型不采用。
            if (count > 0 && count <= previousCount / 2) {
                grids[levels] = grid;
                indices[levels++] = Arrays.copyOf(scratch, count);
                previousCount = count;
            }
        }
        return levels == 0 ? EMPTY : new DistantMeshLod(Arrays.copyOf(grids, levels), Arrays.copyOf(indices, levels));
    }

    public static float snap(float value, float grid) {
        return (float) Math.floor(value / grid) * grid;
    }

    private static boolean same(float[] p, int a, int b) {
        return p[a * 3] == p[b * 3] && p[a * 3 + 1] == p[b * 3 + 1] && p[a * 3 + 2] == p[b * 3 + 2];
    }

    public long byteSize() {
        long bytes = 0;
        for (int[] level : this.vertices != null ? this.vertices : this.indices) bytes += level.length * 4L;
        return bytes;
    }

    public int indexCount(int level) {
        return this.vertices == null ? this.indices[level].length : this.vertices[level].length / 32 * 6;
    }

    public static double visibilityPixels(float subdivision) {
        if (!Float.isFinite(subdivision) || subdivision <= 0) subdivision = 256;
        return Math.clamp(Math.sqrt(subdivision / 256.0) * 0.5, 0.125, 1.0);
    }

    /** 只有整个包围盒小于阈值才剔除，长条玻璃、轨道和光柱不能按面积消失。 */
    public static boolean isSubpixel(Matrix4fc m, float x0, float y0, float z0, float x1, float y1, float z1,
                                     int width, int height, double pixels, double nearDetail) {
        if (width <= 0 || height <= 0) return false;
        double minX = Double.POSITIVE_INFINITY, minY = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? x0 : x1, y = (i & 2) == 0 ? y0 : y1, z = (i & 4) == 0 ? z0 : z1;
            double w = m.m03() * x + m.m13() * y + m.m23() * z + m.m33();
            if (!(w > Math.max(1.0e-4, nearDetail))) return false;
            double nx = (m.m00() * x + m.m10() * y + m.m20() * z + m.m30()) / w;
            double ny = (m.m01() * x + m.m11() * y + m.m21() * z + m.m31()) / w;
            minX = Math.min(minX, nx); maxX = Math.max(maxX, nx);
            minY = Math.min(minY, ny); maxY = Math.max(maxY, ny);
        }
        return (maxX - minX) * width * 0.5 < pixels && (maxY - minY) * height * 0.5 < pixels;
    }

}
