package me.cortex.voxy.client.compat.distant;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL45C.*;

public final class DistantMesh {
    public static final int STRIDE = 28;
    private static int indexBuffer;
    private static int indexCapacity;

    private final int vao;
    private final int vbo;
    private final int quads;

    public DistantMesh(ByteBuffer data, int quads) {
        this.quads = quads;
        ensureIndices(quads);
        int vbo = 0;
        int vao = 0;
        try {
            vbo = glCreateBuffers();
            if (vbo == 0) throw new IllegalStateException("Creating distant mesh buffer");
            glNamedBufferData(vbo, data, GL_STATIC_DRAW);
            vao = glCreateVertexArrays();
            if (vao == 0) throw new IllegalStateException("Creating distant mesh vertex array");
            glVertexArrayVertexBuffer(vao, 0, vbo, 0, STRIDE);
            glVertexArrayElementBuffer(vao, indexBuffer);
            glEnableVertexArrayAttrib(vao, 0);
            glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
            glVertexArrayAttribBinding(vao, 0, 0);
            glEnableVertexArrayAttrib(vao, 1);
            glVertexArrayAttribFormat(vao, 1, 2, GL_FLOAT, false, 12);
            glVertexArrayAttribBinding(vao, 1, 0);
            glEnableVertexArrayAttrib(vao, 2);
            glVertexArrayAttribFormat(vao, 2, 2, GL_UNSIGNED_BYTE, true, 20);
            glVertexArrayAttribBinding(vao, 2, 0);
            glEnableVertexArrayAttrib(vao, 3);
            glVertexArrayAttribFormat(vao, 3, 4, GL_UNSIGNED_BYTE, true, 22);
            glVertexArrayAttribBinding(vao, 3, 0);
            glEnableVertexArrayAttrib(vao, 4);
            glVertexArrayAttribIFormat(vao, 4, 1, GL_UNSIGNED_BYTE, 26);
            glVertexArrayAttribBinding(vao, 4, 0);
        } catch (Throwable failure) {
            // 构造未完成时仍归还已创建对象，清理异常不能遮住原始失败。
            try {
                if (vao != 0) glDeleteVertexArrays(vao);
            } catch (Throwable cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            try {
                if (vbo != 0) glDeleteBuffers(vbo);
            } catch (Throwable cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        this.vbo = vbo;
        this.vao = vao;
    }

    public static DistantMesh fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % (STRIDE * 4) != 0) return null;
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        try {
            data.put(bytes).flip();
            return new DistantMesh(data, bytes.length / (STRIDE * 4));
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static void ensureIndices(int quads) {
        if (quads <= indexCapacity) return;
        int capacity = Math.max(quads, Math.max(indexCapacity * 2, 1024));
        int[] indices = new int[capacity * 6];
        for (int q = 0; q < capacity; q++) {
            int v = q * 4, i = q * 6;
            indices[i] = v; indices[i + 1] = v + 1; indices[i + 2] = v + 2;
            indices[i + 3] = v + 2; indices[i + 4] = v + 3; indices[i + 5] = v;
        }
        // 保持对象名，已建立的 VAO 才能继续引用扩容后的索引。
        int buffer = indexBuffer == 0 ? glCreateBuffers() : indexBuffer;
        if (buffer == 0) throw new IllegalStateException("Creating distant mesh index buffer");
        try {
            glNamedBufferData(buffer, indices, GL_STATIC_DRAW);
        } catch (Throwable failure) {
            if (indexBuffer == 0) {
                try {
                    glDeleteBuffers(buffer);
                } catch (Throwable cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
        indexBuffer = buffer;
        indexCapacity = capacity;
    }

    public void draw() {
        glBindVertexArray(this.vao);
        glDrawElements(GL_TRIANGLES, this.quads * 6, GL_UNSIGNED_INT, 0);
    }

    public void free() {
        glDeleteVertexArrays(this.vao);
        glDeleteBuffers(this.vbo);
    }

    public static final class Builder {
        private ByteBuffer data = MemoryUtil.memAlloc(4096);
        private int vertices;
        private final RandomSource random = RandomSource.create(42);

        public void vertex(float x, float y, float z, float u, float v, int rgb, int face) {
            this.vertex(x, y, z, u, v, rgb, 255, 8, 248, face);
        }

        public void vertex(float x, float y, float z, float u, float v, int rgb, int alpha,
                           int blockLight, int skyLight, int face) {
            if (this.data.remaining() < STRIDE) this.data = MemoryUtil.memRealloc(this.data, this.data.capacity() * 2);
            this.data.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
            this.data.put((byte) blockLight).put((byte) skyLight);
            this.data.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) alpha);
            this.data.put((byte) face).put((byte) 0);
            this.vertices++;
        }

        public void blockModel(BlockState state, BakedModel model, BlockPos tintPos, float ox, float oy, float oz) {
            for (Direction direction : Direction.values()) {
                this.random.setSeed(42);
                for (BakedQuad quad : model.getQuads(state, direction, this.random)) {
                    this.quad(state, tintPos, quad, ox, oy, oz);
                }
            }
            this.random.setSeed(42);
            for (BakedQuad quad : model.getQuads(state, null, this.random)) {
                this.quad(state, tintPos, quad, ox, oy, oz);
            }
        }

        public void blockModelLit(BlockState state, BakedModel model, BlockPos tintPos,
                                  float ox, float oy, float oz, int blockLight, int skyLight) {
            this.random.setSeed(42);
            var layers = model.getRenderTypes(state, this.random, net.minecraftforge.client.model.data.ModelData.EMPTY);
            for (var layer : layers) {
                for (Direction direction : Direction.values()) {
                    this.random.setSeed(42);
                    for (BakedQuad quad : model.getQuads(state, direction, this.random,
                            net.minecraftforge.client.model.data.ModelData.EMPTY, layer)) {
                        this.quadLit(state, tintPos, quad, null, ox, oy, oz, blockLight, skyLight);
                    }
                }
                this.random.setSeed(42);
                for (BakedQuad quad : model.getQuads(state, null, this.random,
                        net.minecraftforge.client.model.data.ModelData.EMPTY, layer)) {
                    this.quadLit(state, tintPos, quad, null, ox, oy, oz, blockLight, skyLight);
                }
            }
        }

        public void transformedModel(BakedModel model, Matrix4f transform, int blockLight, int skyLight) {
            for (Direction direction : Direction.values()) {
                this.random.setSeed(42);
                for (BakedQuad quad : model.getQuads(null, direction, this.random)) {
                    this.quadLit(null, BlockPos.ZERO, quad, transform, 0, 0, 0, blockLight, skyLight);
                }
            }
            this.random.setSeed(42);
            for (BakedQuad quad : model.getQuads(null, null, this.random)) {
                this.quadLit(null, BlockPos.ZERO, quad, transform, 0, 0, 0, blockLight, skyLight);
            }
        }

        private void quadLit(BlockState state, BlockPos tintPos, BakedQuad quad, Matrix4f transform,
                             float ox, float oy, float oz, int blockLight, int skyLight) {
            Direction face = quad.getDirection();
            if (transform != null) {
                Vector3f normal = transform.transformDirection(new Vector3f(
                        face.getStepX(), face.getStepY(), face.getStepZ()));
                float ax = Math.abs(normal.x), ay = Math.abs(normal.y), az = Math.abs(normal.z);
                face = ay >= ax && ay >= az ? (normal.y >= 0 ? Direction.UP : Direction.DOWN)
                        : az >= ax ? (normal.z >= 0 ? Direction.SOUTH : Direction.NORTH)
                        : (normal.x >= 0 ? Direction.EAST : Direction.WEST);
            }
            float shade = switch (face) {
                case DOWN -> 0.5f;
                case NORTH, SOUTH -> 0.8f;
                case WEST, EAST -> 0.6f;
                default -> 1.0f;
            };
            int color = state != null && quad.isTinted() ? Minecraft.getInstance().getBlockColors().getColor(
                    state, Minecraft.getInstance().level, tintPos, quad.getTintIndex()) : 0xFFFFFF;
            if (color == -1) color = 0xFFFFFF;
            int r = Math.min(255, Math.round(((color >> 16) & 255) * shade));
            int g = Math.min(255, Math.round(((color >> 8) & 255) * shade));
            int b = Math.min(255, Math.round((color & 255) * shade));
            color = r << 16 | g << 8 | b;
            int[] vertices = quad.getVertices();
            int stride = vertices.length / 4;
            Vector4f point = new Vector4f();
            for (int vertex = 0; vertex < 4; vertex++) {
                int base = vertex * stride;
                float x = Float.intBitsToFloat(vertices[base]);
                float y = Float.intBitsToFloat(vertices[base + 1]);
                float z = Float.intBitsToFloat(vertices[base + 2]);
                if (transform != null) {
                    transform.transform(point.set(x, y, z, 1.0f));
                    x = point.x; y = point.y; z = point.z;
                }
                this.vertex(x + ox, y + oy, z + oz,
                        Float.intBitsToFloat(vertices[base + 4]), Float.intBitsToFloat(vertices[base + 5]),
                        color, 255, Math.min(255, blockLight + 8), Math.min(255, skyLight + 8), face.ordinal());
            }
        }

        private void quad(BlockState state, BlockPos tintPos, BakedQuad quad, float ox, float oy, float oz) {
            int[] vertices = quad.getVertices();
            int stride = vertices.length / 4;
            float shade = switch (quad.getDirection()) {
                case DOWN -> 0.5f;
                case NORTH, SOUTH -> 0.8f;
                case WEST, EAST -> 0.6f;
                default -> 1.0f;
            };
            int color = quad.isTinted() ? Minecraft.getInstance().getBlockColors().getColor(
                    state, Minecraft.getInstance().level, tintPos, quad.getTintIndex()) : 0xFFFFFF;
            if (color == -1) color = 0xFFFFFF;
            int r = Math.min(255, Math.round(((color >> 16) & 255) * shade));
            int g = Math.min(255, Math.round(((color >> 8) & 255) * shade));
            int b = Math.min(255, Math.round((color & 255) * shade));
            color = (r << 16) | (g << 8) | b;
            for (int vertex = 0; vertex < 4; vertex++) {
                int base = vertex * stride;
                this.vertex(Float.intBitsToFloat(vertices[base]) + ox,
                        Float.intBitsToFloat(vertices[base + 1]) + oy,
                        Float.intBitsToFloat(vertices[base + 2]) + oz,
                        Float.intBitsToFloat(vertices[base + 4]),
                        Float.intBitsToFloat(vertices[base + 5]), color, quad.getDirection().ordinal());
            }
        }

        public DistantMesh build() {
            return this.finish(false).mesh();
        }

        public Built buildPersistent() {
            return this.finish(true);
        }

        public void discard() {
            if (this.data != null) {
                MemoryUtil.memFree(this.data);
                this.data = null;
            }
        }

        private Built finish(boolean keepData) {
            if ((this.vertices & 3) != 0) {
                this.discard();
                throw new IllegalStateException("Incomplete distant mesh quad: " + this.vertices + " vertices");
            }
            int quads = this.vertices / 4;
            if (quads == 0) {
                MemoryUtil.memFree(this.data);
                this.data = null;
                return new Built(null, null);
            }
            this.data.flip();
            this.data.limit(quads * 4 * STRIDE);
            try {
                byte[] bytes = null;
                if (keepData) {
                    bytes = new byte[this.data.remaining()];
                    this.data.duplicate().get(bytes);
                }
                return new Built(new DistantMesh(this.data, quads), bytes);
            } finally {
                MemoryUtil.memFree(this.data);
                this.data = null;
            }
        }
    }

    public record Built(DistantMesh mesh, byte[] bytes) {}
}
