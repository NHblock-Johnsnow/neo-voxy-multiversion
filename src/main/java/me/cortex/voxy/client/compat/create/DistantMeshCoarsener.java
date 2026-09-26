package me.cortex.voxy.client.compat.create;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/** 烘焙保守体素外壳，给低细分的轨道、车厢和装饰模型生成真正的粗网格。 */
final class DistantMeshCoarsener {
    private static final float[] SIZES = {.25f, .5f, 1, 2, 4, 8, 16, 32};
    private static final int MAX_CELLS = 131072;
    private static final int MAX_TRIANGLE_TESTS = 1000000;
    private static final int[] AXIS = {1, 1, 2, 2, 0, 0};
    private static final int[][] CORNERS = {
            {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 2, 3, 1},
            {4, 5, 7, 6}, {0, 4, 6, 2}, {1, 3, 7, 5}
    };

    private DistantMeshCoarsener() {}

    static DistantMeshLod build(ByteBuffer source, int quads, float[] bounds) {
        var sizes = new ArrayList<Float>();
        var meshes = new ArrayList<int[]>();
        int previous = quads;
        for (float size : SIZES) {
            int[] mesh = buildLevel(source, quads, bounds, size, previous / 2);
            if (mesh == null) continue;
            previous = mesh.length / 32;
            sizes.add(size);
            meshes.add(mesh);
            if (previous <= 6) break;
        }
        if (sizes.isEmpty()) return DistantMeshLod.EMPTY;
        float[] grids = new float[sizes.size()];
        for (int i = 0; i < grids.length; i++) grids[i] = sizes.get(i);
        return new DistantMeshLod(grids, null, meshes.toArray(int[][]::new));
    }

    private static int[] buildLevel(ByteBuffer source, int quads, float[] bounds, float size, int maxQuads) {
        if (maxQuads < 6) return null;
        int ox = (int) Math.floor(bounds[0] / size), oy = (int) Math.floor(bounds[1] / size);
        int oz = (int) Math.floor(bounds[2] / size);
        int nx = Math.max(1, (int) Math.ceil(bounds[3] / size) - ox);
        int ny = Math.max(1, (int) Math.ceil(bounds[4] / size) - oy);
        int nz = Math.max(1, (int) Math.ceil(bounds[5] / size) - oz);
        long cells = (long) nx * ny * nz;
        if (cells <= 0 || cells > MAX_CELLS) return null;
        int[] faces = new int[(int) cells * 6];
        boolean[] occupied = new boolean[(int) cells];
        double[] triangle = new double[9];
        int tests = 0;
        for (int q = 0; q < quads; q++) {
            for (int part = 0; part < 2; part++) {
                for (int corner = 0; corner < 3; corner++) {
                    int vertex = q * 4 + (part == 0 ? corner : (corner + 2) & 3);
                    triangle[corner * 3] = source.getFloat(vertex * 32) / size - ox;
                    triangle[corner * 3 + 1] = source.getFloat(vertex * 32 + 4) / size - oy;
                    triangle[corner * 3 + 2] = source.getFloat(vertex * 32 + 8) / size - oz;
                }
                double ax = triangle[3] - triangle[0], ay = triangle[4] - triangle[1], az = triangle[5] - triangle[2];
                double bx = triangle[6] - triangle[0], by = triangle[7] - triangle[1], bz = triangle[8] - triangle[2];
                double normalX = ay * bz - az * by, normalY = az * bx - ax * bz, normalZ = ax * by - ay * bx;
                if (normalX * normalX + normalY * normalY + normalZ * normalZ < 1e-20) continue;
                int face = Math.abs(normalY) >= Math.max(Math.abs(normalX), Math.abs(normalZ))
                        ? (normalY >= 0 ? 1 : 0) : Math.abs(normalZ) >= Math.abs(normalX)
                        ? (normalZ >= 0 ? 3 : 2) : (normalX >= 0 ? 5 : 4);
                int x0 = lower(triangle, 0, normalX, nx), x1 = upper(triangle, 0, nx);
                int y0 = lower(triangle, 1, normalY, ny), y1 = upper(triangle, 1, ny);
                int z0 = lower(triangle, 2, normalZ, nz), z1 = upper(triangle, 2, nz);
                x1 = Math.max(x0, x1); y1 = Math.max(y0, y1); z1 = Math.max(z0, z1);
                long candidates = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
                if (candidates > MAX_TRIANGLE_TESTS - tests) return null;
                tests += (int) candidates;
                for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                    if (!intersects(triangle, x + .5, y + .5, z + .5, normalX, normalY, normalZ)) continue;
                    int cell = (z * ny + y) * nx + x;
                    occupied[cell] = true;
                    if (faces[cell * 6 + face] == 0) faces[cell * 6 + face] = q + 1;
                }
            }
        }

        int count = 0;
        for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
            int cell = (z * ny + y) * nx + x;
            if (!occupied[cell]) continue;
            for (int face = 0; face < 6; face++) {
                if (exposed(occupied, cell, x, y, z, nx, ny, nz, face) && ++count > maxQuads) return null;
            }
        }
        if (count == 0) return null;
        int[] result = new int[count * 32];
        int cursor = 0;
        float[] box = new float[6];
        for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
            int cell = (z * ny + y) * nx + x;
            if (!occupied[cell]) continue;
            box[0] = Math.max(bounds[0], (x + ox) * size); box[3] = Math.min(bounds[3], (x + ox + 1) * size);
            box[1] = Math.max(bounds[1], (y + oy) * size); box[4] = Math.min(bounds[4], (y + oy + 1) * size);
            box[2] = Math.max(bounds[2], (z + oz) * size); box[5] = Math.min(bounds[5], (z + oz + 1) * size);
            for (int face = 0; face < 6; face++) {
                if (!exposed(occupied, cell, x, y, z, nx, ny, nz, face)) continue;
                int sample = faces[cell * 6 + face];
                if (sample == 0) {
                    for (int side = 0; side < 6; side++) {
                        if (faces[cell * 6 + side] != 0) { sample = faces[cell * 6 + side]; break; }
                    }
                }
                for (int corner = 0; corner < 4; corner++) {
                    int bits = CORNERS[face][corner];
                    int original = ((sample - 1) * 4 + corner) * 32;
                    result[cursor++] = Float.floatToRawIntBits(box[(bits & 1) == 0 ? 0 : 3]);
                    result[cursor++] = Float.floatToRawIntBits(box[(bits & 2) == 0 ? 1 : 4]);
                    result[cursor++] = Float.floatToRawIntBits(box[(bits & 4) == 0 ? 2 : 5]);
                    result[cursor++] = source.getInt(original + 12);
                    result[cursor++] = source.getInt(original + 16);
                    int shade = face == 0 ? 127 : face == 1 ? 255 : AXIS[face] == 2 ? 204 : 153;
                    result[cursor++] = (source.getInt(original + 20) & 65535) | (shade << 16) | (face << 24);
                    result[cursor++] = source.getInt(original + 24);
                    result[cursor++] = source.getInt(original + 28);
                }
            }
        }
        return result;
    }

    private static int lower(double[] t, int axis, double normal, int size) {
        double min = Math.min(t[axis], Math.min(t[axis + 3], t[axis + 6]));
        double max = Math.max(t[axis], Math.max(t[axis + 3], t[axis + 6]));
        // 格线上的外表面归入实体一侧，避免给每个方块包上双层体素。
        if (max - min < 1e-7 && normal > 0 && Math.abs(min - Math.rint(min)) < 1e-7) min -= 1;
        return Math.clamp((int) Math.floor(min), 0, size - 1);
    }

    private static int upper(double[] t, int axis, int size) {
        double max = Math.max(t[axis], Math.max(t[axis + 3], t[axis + 6]));
        return Math.clamp((int) Math.ceil(max) - 1, 0, size - 1);
    }

    private static boolean exposed(boolean[] filled, int cell, int x, int y, int z, int nx, int ny, int nz, int face) {
        return switch (face) {
            case 0 -> y == 0 || !filled[cell - nx];
            case 1 -> y == ny - 1 || !filled[cell + nx];
            case 2 -> z == 0 || !filled[cell - nx * ny];
            case 3 -> z == nz - 1 || !filled[cell + nx * ny];
            case 4 -> x == 0 || !filled[cell - 1];
            default -> x == nx - 1 || !filled[cell + 1];
        };
    }

    private static boolean intersects(double[] t, double cx, double cy, double cz, double nx, double ny, double nz) {
        if (separated(t, cx, cy, cz, nx, ny, nz)) return false;
        for (int i = 0; i < 3; i++) {
            int a = i * 3, b = ((i + 1) % 3) * 3;
            double x = t[b] - t[a], y = t[b + 1] - t[a + 1], z = t[b + 2] - t[a + 2];
            if (separated(t, cx, cy, cz, 0, z, -y) || separated(t, cx, cy, cz, -z, 0, x)
                    || separated(t, cx, cy, cz, y, -x, 0)) return false;
        }
        return true;
    }

    private static boolean separated(double[] t, double cx, double cy, double cz, double x, double y, double z) {
        double center = x * cx + y * cy + z * cz;
        double a = x * t[0] + y * t[1] + z * t[2] - center;
        double b = x * t[3] + y * t[4] + z * t[5] - center;
        double c = x * t[6] + y * t[7] + z * t[8] - center;
        double radius = .5 * (Math.abs(x) + Math.abs(y) + Math.abs(z)) + 1e-8;
        return Math.min(a, Math.min(b, c)) > radius || Math.max(a, Math.max(b, c)) < -radius;
    }
}
