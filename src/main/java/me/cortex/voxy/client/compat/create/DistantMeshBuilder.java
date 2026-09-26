package me.cortex.voxy.client.compat.create;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.ToIntFunction;
import java.util.function.Predicate;

/** 在 CPU 端收集兼容模组网格，并按固定顶点步长上传到 GPU。 */
public final class DistantMeshBuilder {
    private ByteBuffer buffer;
    private int vertexCount;
    private final boolean allowLod;
    private boolean mergeTranslucent;
    private final Vector4f scratch = new Vector4f();
    private final org.joml.Vector3f normalScratch = new org.joml.Vector3f();
    private final RandomSource random = RandomSource.create(42);

    public DistantMeshBuilder() {
        this(true);
    }

    public DistantMeshBuilder(boolean allowLod) {
        this.allowLod = allowLod;
        this.buffer = MemoryUtil.memAlloc(64 * 1024);
    }

    public static DistantMeshBuilder translucent() {
        var builder = new DistantMeshBuilder(false);
        builder.mergeTranslucent = true;
        return builder;
    }

    // ---- 顶点写入 ------------------------------------------------------

    // 捕获渲染器流式提交的原始顶点（例如 Create bogey）。
    public void rawVertex(float x, float y, float z, float u, float v, int skyLight, int blockLight, float shade, int face) {
        this.rawVertex(x, y, z, u, v, skyLight, blockLight, shade, face, 0xFFFFFF);
    }

    public void rawVertex(float x, float y, float z, float u, float v, int skyLight, int blockLight, float shade, int face, int tintRgb) {
        this.rawVertex(x, y, z, u, v, skyLight, blockLight, shade, face, tintRgb, 255);
    }

    public void rawVertex(float x, float y, float z, float u, float v, int skyLight, int blockLight,
                          float shade, int face, int tintRgb, int alpha) {
        this.rawVertex(x, y, z, u, v, skyLight, blockLight, shade, face, tintRgb, alpha, 0);
    }

    public void rawVertex(float x, float y, float z, float u, float v, int skyLight, int blockLight,
                          float shade, int face, int tintRgb, int alpha, int customId) {
        this.ensure(DistantMesh.STRIDE);
        if (x < this.minX) this.minX = x;
        if (y < this.minY) this.minY = y;
        if (z < this.minZ) this.minZ = z;
        if (x > this.maxX) this.maxX = x;
        if (y > this.maxY) this.maxY = y;
        if (z > this.maxZ) this.maxZ = z;
        this.buffer.putFloat(x).putFloat(y).putFloat(z);
        this.buffer.putFloat(u).putFloat(v);
        this.buffer.put((byte) (blockLight * 16 + 8)).put((byte) (skyLight * 16 + 8));
        this.buffer.put((byte) (int) (Math.max(0, Math.min(1, shade)) * 255.0f));
        this.buffer.put((byte) face);
        this.buffer.put((byte) (tintRgb >> 16)).put((byte) (tintRgb >> 8)).put((byte) tintRgb)
                .put((byte) Math.clamp(alpha, 0, 255));
        this.buffer.putInt(customId);
        this.vertexCount++;
    }

    //Emits every quad of a block model at the given block offset. faceHidden filters culled sides
    //(e.g. faces buried between adjacent carriage blocks).
    public void blockModel(BlockState state, BakedModel model, float ox, float oy, float oz,
                           int skyLight, int blockLight, Predicate<Direction> faceHidden) {
        this.blockModel(state, model, ox, oy, oz, skyLight, blockLight, faceHidden, 0xFFFFFF);
    }

    //tintRgb applies to quads that declare a tint index (grass tops, foliage); untinted quads stay white
    public void blockModel(BlockState state, BakedModel model, float ox, float oy, float oz,
                           int skyLight, int blockLight, Predicate<Direction> faceHidden, int tintRgb) {
        this.blockModel(state, model, ox, oy, oz, skyLight, blockLight, faceHidden, tintRgb,
                net.neoforged.neoforge.client.model.data.ModelData.EMPTY);
    }

    //modelData feeds connected-texture wrappers (casings, glass): with EMPTY they bake every block as
    //the disconnected default
    public void blockModel(BlockState state, BakedModel model, float ox, float oy, float oz,
                           int skyLight, int blockLight, Predicate<Direction> faceHidden, int tintRgb,
                           net.neoforged.neoforge.client.model.data.ModelData modelData) {
        this.random.setSeed(42);
        for (var layer : model.getRenderTypes(state, this.random, modelData)) {
            for (Direction direction : Direction.values()) {
                if (faceHidden != null && faceHidden.test(direction)) {
                    continue;
                }
                this.random.setSeed(42);
                for (BakedQuad quad : model.getQuads(state, direction, this.random, modelData, layer)) {
                    this.quad(null, quad, ox, oy, oz, skyLight, blockLight, quad.isTinted() ? tintRgb : 0xFFFFFF);
                }
            }
            this.random.setSeed(42);
            for (BakedQuad quad : model.getQuads(state, null, this.random, modelData, layer)) {
                this.quad(null, quad, ox, oy, oz, skyLight, blockLight, quad.isTinted() ? tintRgb : 0xFFFFFF);
            }
        }
    }

    public void blockModelLayer(BlockState state, BakedModel model, float ox, float oy, float oz,
                                 int skyLight, int blockLight, RenderType layer,
                                 ToIntFunction<BakedQuad> tintResolver,
                                 net.neoforged.neoforge.client.model.data.ModelData modelData) {
        this.blockModelLayer(state, model, ox, oy, oz, skyLight, blockLight, layer,
                tintResolver, modelData, null);
    }

    public void blockModelLayer(BlockState state, BakedModel model, float ox, float oy, float oz,
                                int skyLight, int blockLight, RenderType layer,
                                ToIntFunction<BakedQuad> tintResolver,
                                net.neoforged.neoforge.client.model.data.ModelData modelData,
                                Predicate<Direction> faceHidden) {
        this.random.setSeed(42);
        for (Direction direction : Direction.values()) {
            if (faceHidden != null && faceHidden.test(direction)) {
                continue;
            }
            this.random.setSeed(42);
            for (BakedQuad quad : model.getQuads(state, direction, this.random, modelData, layer)) {
                int tint = quad.isTinted() ? tintResolver.applyAsInt(quad) : 0xFFFFFF;
                this.quad(null, quad, ox, oy, oz, skyLight, blockLight, tint);
            }
        }
        this.random.setSeed(42);
        for (BakedQuad quad : model.getQuads(state, null, this.random, modelData, layer)) {
            int tint = quad.isTinted() ? tintResolver.applyAsInt(quad) : 0xFFFFFF;
            this.quad(null, quad, ox, oy, oz, skyLight, blockLight, tint);
        }
    }

    //Emits every quad of a model under an arbitrary transform (bezier track segments etc.)
    public void transformedModel(BakedModel model, Matrix4f transform, int skyLight, int blockLight) {
        for (Direction direction : Direction.values()) {
            this.random.setSeed(42);
            for (BakedQuad quad : model.getQuads(null, direction, this.random)) {
                this.quad(transform, quad, 0, 0, 0, skyLight, blockLight, 0xFFFFFF);
            }
        }
        this.random.setSeed(42);
        for (BakedQuad quad : model.getQuads(null, null, this.random)) {
            this.quad(transform, quad, 0, 0, 0, skyLight, blockLight, 0xFFFFFF);
        }
    }

    public void quad(Matrix4f transform, BakedQuad quad, float ox, float oy, float oz, int skyLight, int blockLight) {
        this.quad(transform, quad, ox, oy, oz, skyLight, blockLight, 0xFFFFFF);
    }

    public void quad(Matrix4f transform, BakedQuad quad, float ox, float oy, float oz, int skyLight, int blockLight, int tintRgb) {
        this.ensure(4 * DistantMesh.STRIDE);
        int[] vertices = quad.getVertices();
        byte lightU = (byte) (blockLight * 16 + 8);
        byte lightV = (byte) (skyLight * 16 + 8);
        Direction lit = quad.getDirection();
        if (transform != null) {
            var n = this.normalScratch.set(lit.getStepX(), lit.getStepY(), lit.getStepZ());
            transform.transformDirection(n);
            lit = faceOf(n.x, n.y, n.z);
        }
        byte shade = (byte) (int) (shadeOf(lit) * 255.0f);
        byte face = (byte) lit.get3DDataValue();
        byte tr = (byte) (tintRgb >> 16), tg = (byte) (tintRgb >> 8), tb = (byte) tintRgb;
        for (int i = 0; i < 4; i++) {
            int base = i * 8;
            float x = Float.intBitsToFloat(vertices[base]);
            float y = Float.intBitsToFloat(vertices[base + 1]);
            float z = Float.intBitsToFloat(vertices[base + 2]);
            if (transform != null) {
                this.scratch.set(x, y, z, 1.0f);
                transform.transform(this.scratch);
                x = this.scratch.x;
                y = this.scratch.y;
                z = this.scratch.z;
            }
            float wx = x + ox, wy = y + oy, wz = z + oz;
            if (wx < this.minX) this.minX = wx;
            if (wy < this.minY) this.minY = wy;
            if (wz < this.minZ) this.minZ = wz;
            if (wx > this.maxX) this.maxX = wx;
            if (wy > this.maxY) this.maxY = wy;
            if (wz > this.maxZ) this.maxZ = wz;
            this.buffer.putFloat(wx).putFloat(wy).putFloat(wz);
            this.buffer.putFloat(Float.intBitsToFloat(vertices[base + 4]));
            this.buffer.putFloat(Float.intBitsToFloat(vertices[base + 5]));
            this.buffer.put(lightU).put(lightV).put(shade).put(face);
            this.buffer.put(tr).put(tg).put(tb).put((byte) 0xFF);
            this.buffer.putInt(0);
        }
        this.vertexCount += 4;
    }

    private static float shadeOf(Direction direction) {
        return switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }

    private static Direction faceOf(float nx, float ny, float nz) {
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ay >= ax && ay >= az) {
            return ny >= 0 ? Direction.UP : Direction.DOWN;
        }
        if (az >= ax) {
            return nz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return nx >= 0 ? Direction.EAST : Direction.WEST;
    }

    private void ensure(int bytes) {
        if (this.buffer.remaining() < bytes) {
            this.buffer = MemoryUtil.memRealloc(this.buffer, Math.max(this.buffer.capacity() * 2, this.buffer.capacity() + bytes));
        }
    }

    //Mesh-local extent of everything written so far, or an inverted box if nothing was
    private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
    private float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

    public float minX() { return this.minX; }
    public float minY() { return this.minY; }
    public float minZ() { return this.minZ; }
    public float maxX() { return this.maxX; }
    public float maxY() { return this.maxY; }
    public float maxZ() { return this.maxZ; }

    public boolean isEmpty() {
        return this.vertexCount < 4;
    }

    public static final class CpuMesh {
        private ByteBuffer buffer;
        public final int quadCount;
        private DistantMeshLod lod;
        public float minX, minY, minZ, maxX, maxY, maxZ;

        private CpuMesh(ByteBuffer buffer, int quadCount) {
            this.buffer = buffer;
            this.quadCount = quadCount;
        }

        public int byteSize() {
            return this.buffer == null ? 0 : this.buffer.limit() + (int) this.lod.byteSize();
        }

        public void free() {
            if (this.buffer != null) {
                MemoryUtil.memFree(this.buffer);
                this.buffer = null;
                this.lod = null;
            }
        }
    }

    //Hands the assembled buffer over; returns null for empty meshes. No GL, safe off the render thread.
    public CpuMesh assemble() {
        int quadCount = this.vertexCount / 4;
        if (quadCount == 0) {
            MemoryUtil.memFree(this.buffer);
            this.buffer = null;
            return null;
        }
        //Truncate any trailing partial quad from a capture stream
        this.buffer.flip();
        this.buffer.limit(quadCount * 4 * DistantMesh.STRIDE);
        if (this.mergeTranslucent) {
            quadCount = DistantQuadMerger.compact(this.buffer, quadCount);
            this.buffer.limit(quadCount * 4 * DistantMesh.STRIDE);
        }
        var cpu = new CpuMesh(this.buffer, quadCount);
        cpu.lod = this.allowLod ? DistantMeshLod.build(this.buffer, quadCount) : DistantMeshLod.EMPTY;
        cpu.minX = this.minX; cpu.minY = this.minY; cpu.minZ = this.minZ;
        cpu.maxX = this.maxX; cpu.maxY = this.maxY; cpu.maxZ = this.maxZ;
        //Ownership moves to the CpuMesh - discard() must not free it from under the new owner
        this.buffer = null;
        return cpu;
    }

    //Render thread only. Consumes the CpuMesh, freeing it whether or not the upload succeeds.
    public static DistantMesh upload(CpuMesh cpu) {
        if (cpu == null) {
            return null;
        }
        try {
            var mesh = new DistantMesh(cpu.buffer, cpu.quadCount, cpu.lod);
            mesh.minX = cpu.minX; mesh.minY = cpu.minY; mesh.minZ = cpu.minZ;
            mesh.maxX = cpu.maxX; mesh.maxY = cpu.maxY; mesh.maxZ = cpu.maxZ;
            return mesh;
        } finally {
            cpu.free();
        }
    }

    //Uploads and frees the CPU buffer; returns null for empty meshes
    public DistantMesh build() {
        return upload(this.assemble());
    }

    public DistantMesh buildPolyline() {
        var cpu = this.assemble();
        if (cpu == null) return null;
        try {
            cpu.lod = DistantPolylineLod.build(cpu.buffer, cpu.quadCount);
            return upload(cpu);
        } finally {
            cpu.free();
        }
    }

    //Frees the native buffer without building - for exception paths that abandon a partial bake, so
    //the memAlloc'd buffer (which only build() frees) does not leak on every failed rebake.
    public void discard() {
        if (this.buffer != null) {
            MemoryUtil.memFree(this.buffer);
            this.buffer = null;
        }
    }
}
