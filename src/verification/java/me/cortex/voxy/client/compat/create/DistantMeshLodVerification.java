package me.cortex.voxy.client.compat.create;

import org.joml.Matrix4f;
import me.cortex.voxy.client.core.rendering.LodViewState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class DistantMeshLodVerification {
    private static int checks;

    public static void main(String[] args) throws Exception {
        ByteBuffer vertices = tiledPlane(64);
        byte[] original = vertices.array().clone();
        var lod = DistantMeshLod.build(vertices, 4096);
        expect(lod.grids.length == 3, "密集平面应生成三个有效级别");
        expect(java.util.Arrays.equals(original, vertices.array()), "不能改写原始顶点和材质属性");
        expect(lod.byteSize() <= 4096 * 6L * 4, "附加索引内存上限");
        int previous = 4096 * 6;
        for (int level = 0; level < lod.grids.length; level++) {
            int[] indices = lod.indices[level];
            expect(indices.length <= previous / 2 && indices.length > 0, "保留级别至少减半且不为空");
            previous = indices.length;
            double area = 0;
            for (int t = 0; t < indices.length; t += 3) {
                int a = indices[t] * 32, b = indices[t + 1] * 32, c = indices[t + 2] * 32;
                float ax = snap(vertices.getFloat(a), lod.grids[level]), ay = snap(vertices.getFloat(a + 4), lod.grids[level]);
                float bx = snap(vertices.getFloat(b), lod.grids[level]), by = snap(vertices.getFloat(b + 4), lod.grids[level]);
                float cx = snap(vertices.getFloat(c), lod.grids[level]), cy = snap(vertices.getFloat(c + 4), lod.grids[level]);
                double triangle = ((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) * 0.5;
                expect(triangle > 0, "保持绕序和有效三角形");
                area += triangle;
            }
            expect(Math.abs(area - 1) < 1e-6, "简化平面仍覆盖完整面积");
        }
        expect(DistantMeshLod.build(tiledPlane(4), 16) == DistantMeshLod.EMPTY, "简单模型不增加索引缓存");
        ByteBuffer invalid = tiledPlane(16);
        invalid.putFloat(0, Float.NaN);
        expect(DistantMeshLod.build(invalid, 256) == DistantMeshLod.EMPTY, "无效坐标保留原始路径");
        var projection = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9, .1f, 50000);
        expect(!DistantMeshLod.isSubpixel(projection,-.001f,-10,-100,.001f,10,-99,1920,1080,1,0),
                "长而细的玻璃不能按面积剔除");
        expect(DistantMeshLod.isSubpixel(projection,-.001f,-.001f,-1000,.001f,.001f,-999,1920,1080,1,0),
                "整个网格低于像素阈值");
        verifyZoom();
        verifyViewReuse();
        DistantMeshCoarseningVerification.run();
        me.cortex.voxy.client.core.rendering.NativeLodSelectionVerification.run();
        System.out.println("Passed " + checks + " distant mesh LOD checks; plane triangles 8192 -> "
                + lod.indices[0].length / 3 + " -> " + lod.indices[1].length / 3 + " -> " + lod.indices[2].length / 3);
    }

    private static void verifyZoom() {
        var normal = new Matrix4f().perspective((float) Math.toRadians(70), 16f/9, .1f, 50000);
        var zoom = new Matrix4f().perspective((float) Math.toRadians(7), 16f/9, .1f, 50000);
        expect(DistantMeshLod.isSubpixel(normal,-.125f,-.125f,-1000,.125f,.125f,-999,1920,1080,.5,64),
                "缩小的透明小模型可剔除");
        expect(!DistantMeshLod.isSubpixel(zoom,-.125f,-.125f,-1000,.125f,.125f,-999,1920,1080,.5,64),
                "放大后透明小模型立即恢复，不依赖相机移动");
        expect(!DistantMeshLod.isSubpixel(normal,-.001f,-.001f,-1000,.001f,.001f,-999,0,0,.5,64),
                "窗口暂时无尺寸时保守保留");
    }

    private static void verifyViewReuse() {
        var view = new LodViewState();
        var m = new Matrix4f().perspective((float) Math.toRadians(70),16f/9,.1f,50000);
        expect(!view.matches(m,1920,1080,256,64,0), "首帧没有可复用结果");
        view.set(m,1920,1080,256,64);
        expect(view.matches(new Matrix4f(m),1920,1080,256,64,0), "相同视图可复用");
        expect(!view.matches(m,3840,2160,256,64,0), "分辨率变化作废旧结果");
        expect(!view.matches(m,1920,1080,28,64,0), "精度变化作废旧结果");
        expect(!view.matches(m,1920,1080,256,128,0), "近景完整细节范围变化作废旧结果");
        m.scale(2,2,1);
        expect(!view.matches(m,1920,1080,256,64,0), "同帧投影原地改变也必须重算");
        view.set(m,1920,1080,256,64);
        view.invalidate();
        expect(!view.matches(m,1920,1080,256,64,0), "禁用后不能复用旧视图");
    }

    static ByteBuffer tiledPlane(int cells) {
        var buffer = ByteBuffer.allocate(cells * cells * 4 * 32).order(ByteOrder.nativeOrder());
        for (int y = 0; y < cells; y++) for (int x = 0; x < cells; x++) {
            for (int i : new int[]{0,1,3,2}) {
                buffer.putFloat((x + (i & 1)) / (float) cells).putFloat((y + (i >> 1)) / (float) cells).putFloat(0);
                buffer.putFloat((i & 1)).putFloat(i >> 1);
                buffer.putLong(-1L).putInt(0);
            }
        }
        return buffer.flip();
    }

    private static float snap(float value, float grid) { return DistantMeshLod.snap(value, grid); }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
