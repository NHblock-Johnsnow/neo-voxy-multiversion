package me.cortex.voxy.client.compat.create;

import java.nio.ByteBuffer;

/** 仅合并连续、共面的透明面，保持 UV 插值和叠加顺序，不删除背面或透明层。 */
final class DistantQuadMerger {
    private DistantQuadMerger() {}

    static int compact(ByteBuffer data, int quads) {
        int count = 0;
        int[] merged = new int[32];
        for (int q = 0; q < quads; q++) {
            for (int word = 0; word < 32; word++) data.putInt(count * 128 + word * 4, data.getInt(q * 128 + word * 4));
            count++;
            while (count >= 2 && merge(data, (count - 2) * 128, (count - 1) * 128, merged)) {
                count--;
                for (int word = 0; word < 32; word++) data.putInt((count - 1) * 128 + word * 4, merged[word]);
            }
        }
        return count;
    }

    private static boolean merge(ByteBuffer data, int a, int b, int[] out) {
        // 离散材质属性必须完全一致，光照/染色渐变保留原面。
        for (int v = 0; v < 8; v++) for (int word = 5; word < 8; word++) {
            if (data.getInt((v < 4 ? a + v * 32 : b + (v - 4) * 32) + word * 4) != data.getInt(a + word * 4)) return false;
        }
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            int a0 = a + i * 32, a1 = a + ((i + 1) & 3) * 32;
            int b0 = b + j * 32, b1 = b + ((j + 1) & 3) * 32;
            if (!equal(data, a0, b1) || !equal(data, a1, b0)) continue;
            int left0 = a + ((i + 3) & 3) * 32, left1 = a + ((i + 2) & 3) * 32;
            int right0 = b + ((j + 2) & 3) * 32, right1 = b + ((j + 3) & 3) * 32;
            double t = fraction(data, left0, a0, right0);
            if (!(t > 0 && t < 1) || !linear(data, left0, a0, right0, t)
                    || !linear(data, left1, a1, right1, t) || !parallelogram(data, left0, left1, right1, right0)) continue;
            int[] corners = {left0, right0, right1, left1};
            for (int v = 0; v < 4; v++) for (int word = 0; word < 8; word++) out[v * 8 + word] = data.getInt(corners[v] + word * 4);
            return true;
        }
        return false;
    }

    private static boolean equal(ByteBuffer data, int a, int b) {
        for (int word = 0; word < 8; word++) if (data.getInt(a + word * 4) != data.getInt(b + word * 4)) return false;
        return true;
    }

    private static double fraction(ByteBuffer data, int a, int middle, int b) {
        double largest = 0, result = Double.NaN;
        for (int axis = 0; axis < 3; axis++) {
            double delta = data.getFloat(b + axis * 4) - data.getFloat(a + axis * 4);
            if (Math.abs(delta) <= largest) continue;
            largest = Math.abs(delta);
            result = (data.getFloat(middle + axis * 4) - data.getFloat(a + axis * 4)) / delta;
        }
        return result;
    }

    private static boolean linear(ByteBuffer data, int a, int middle, int b, double t) {
        for (int word = 0; word < 5; word++) {
            double value = data.getFloat(a + word * 4) * (1 - t) + data.getFloat(b + word * 4) * t;
            if (Math.abs(value - data.getFloat(middle + word * 4)) > 1e-7) return false;
        }
        return true;
    }

    private static boolean parallelogram(ByteBuffer data, int a, int b, int c, int d) {
        // 位置和 UV 均为仿射面，改变三角剖分不会改变纹理插值。
        for (int word = 0; word < 5; word++) {
            double diagonal = (double) data.getFloat(a + word * 4) + data.getFloat(c + word * 4)
                    - data.getFloat(b + word * 4) - data.getFloat(d + word * 4);
            if (!Double.isFinite(diagonal) || Math.abs(diagonal) > 1e-7) return false;
        }
        return true;
    }
}
