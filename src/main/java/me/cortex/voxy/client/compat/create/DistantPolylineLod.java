package me.cortex.voxy.client.compat.create;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/** 合并电线分段，保留端点截面；误差包含线径，不能用粗体素把电线变粗。 */
final class DistantPolylineLod {
    private static final float[] ERRORS = {.125f, .25f, .5f, 1, 2, 4, 8, 16, 32};

    private DistantPolylineLod() {}

    static DistantMeshLod build(ByteBuffer source, int quads) {
        if (quads < 8 || quads % 4 != 0) return DistantMeshLod.EMPTY;
        int segments = quads / 4;
        double[] lengths = new double[segments + 1];
        for (int i = 0; i < segments; i++) {
            double distance = 0;
            for (int axis = 0; axis < 3; axis++) {
                double delta = position(source, i, 0, 3, axis) - position(source, i, 0, 0, axis);
                if (!Double.isFinite(delta)) return DistantMeshLod.EMPTY;
                distance += delta * delta;
            }
            lengths[i + 1] = lengths[i] + Math.sqrt(distance);
        }
        var errors = new ArrayList<Float>();
        var meshes = new ArrayList<int[]>();
        int previous = segments;
        for (float error : ERRORS) {
            int[] ranges = new int[segments * 2];
            int count = split(source, lengths, 0, segments - 1, error, ranges, 0);
            if (count == 0 || count > previous / 2) continue;
            int[] vertices = new int[count * 128];
            int cursor = 0;
            for (int r = 0; r < count; r++) for (int face = 0; face < 4; face++) for (int corner = 0; corner < 4; corner++) {
                int segment = ranges[r * 2 + (corner < 2 ? 0 : 1)];
                int offset = ((segment * 4 + face) * 4 + corner) * 32;
                for (int word = 0; word < 8; word++) vertices[cursor++] = source.getInt(offset + word * 4);
            }
            meshes.add(vertices);
            errors.add(error);
            previous = count;
            if (count == 1) break;
        }
        if (errors.isEmpty()) return DistantMeshLod.EMPTY;
        float[] grids = new float[errors.size()];
        for (int i = 0; i < grids.length; i++) grids[i] = errors.get(i);
        return new DistantMeshLod(grids, null, meshes.toArray(int[][]::new));
    }

    private static int split(ByteBuffer source, double[] lengths, int first, int last, float limit, int[] ranges, int count) {
        if (first == last || fits(source, lengths, first, last, limit)) {
            ranges[count * 2] = first;
            ranges[count * 2 + 1] = last;
            return count + 1;
        }
        // 二分保证长折线的调用深度有界，所有级别均对照原始折线计算误差。
        int middle = (first + last) >>> 1;
        count = split(source, lengths, first, middle, limit, ranges, count);
        return split(source, lengths, middle + 1, last, limit, ranges, count);
    }

    private static boolean fits(ByteBuffer source, double[] lengths, int first, int last, float limit) {
        double length = lengths[last + 1] - lengths[first];
        if (length < 1e-10) return false;
        double chord = 0;
        for (int axis = 0; axis < 3; axis++) {
            double delta = position(source,last,0,3,axis) - position(source,first,0,0,axis);
            chord += delta * delta;
        }
        if (chord < 1e-10) return false;
        for (int segment = first; segment <= last; segment++) for (int face = 0; face < 4; face++) {
            for (int corner = 0; corner < 4; corner++) {
                double t = (lengths[segment + (corner < 2 ? 0 : 1)] - lengths[first]) / length;
                for (int axis = 0; axis < 3; axis++) {
                    double a = position(source, first, face, corner < 2 ? corner : 3 - corner, axis);
                    double b = position(source, last, face, corner < 2 ? 3 - corner : corner, axis);
                    if (Math.abs(position(source, segment, face, corner, axis) - (a + (b - a) * t)) > limit) return false;
                }
            }
        }
        return true;
    }

    private static float position(ByteBuffer data, int segment, int face, int corner, int axis) {
        return data.getFloat(((segment * 4 + face) * 4 + corner) * 32 + axis * 4);
    }
}
