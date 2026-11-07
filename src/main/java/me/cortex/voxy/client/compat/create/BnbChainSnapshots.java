package me.cortex.voxy.client.compat.create;

import com.kipti.bnb.content.kinetics.cogwheel_chain.behaviour.CogwheelChainBehaviour;
import com.kipti.bnb.content.kinetics.cogwheel_chain.render.CogwheelChainRenderGeometryBuilder;
import com.kipti.bnb.content.kinetics.cogwheel_chain.types.CogwheelChainType;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

public final class BnbChainSnapshots {
    private BnbChainSnapshots() {}

    //9 floats per vertex, positions relative to the block-entity origin:
    //x,y,z, atlasU,atlasV, sky,block, shade, face
    public record CapturedChain(float[] vertices, int[] lights) {}

    public static float[] capture(ClientLevel level, KineticBlockEntity be) {
        var captured = captureWithLight(level, be);
        return captured == null ? null : captured.vertices();
    }

    public static CapturedChain captureWithLight(ClientLevel level, KineticBlockEntity be) {
        if (!(be instanceof SmartBlockEntity smart)) {
            return null;
        }
        CogwheelChainBehaviour behaviour = smart.getBehaviour(CogwheelChainBehaviour.TYPE);
        if (behaviour == null) {
            return null;
        }
        var chain = behaviour.getControlledChain();
        if (chain == null) {
            return null; //not the controlling wheel - the controller's snapshot draws the loop
        }
        var type = chain.getChainType();
        CogwheelChainType.ChainRenderInfo info = type.getRenderType();

        var segments = CogwheelChainRenderGeometryBuilder.buildSegments(chain, Vec3.ZERO);
        if (segments.isEmpty()) {
            return null;
        }

        ResourceLocation texture = type.getRenderTexture();
        var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ResourceLocation.fromNamespaceAndPath(texture.getNamespace(),
                        texture.getPath().replace("textures/", "").replace(".png", "")));

        BlockPos origin = be.getBlockPos();
        var verts = new FloatArrayList(Math.multiplyExact(segments.size(), 16 * 9));
        var lightSamples = new Long2IntOpenHashMap();
        var transform = new Matrix4f();
        var vertex = new Vector3f();

        for (var segment : segments) {
            Vec3 from = segment.from(), to = segment.to();
            Vec3 diff = to.subtract(from);
            float yaw = (float) Math.toDegrees(Mth.atan2(diff.x, diff.z));
            float pitch = (float) Math.toDegrees(Mth.atan2(diff.y, diff.multiply(1.0, 0.0, 1.0).length()));
            float length = (float) from.distanceTo(to) + 0.05f;

            int light1 = sampleLight(level, origin, from, lightSamples);
            int light2 = sampleLight(level, origin, to, lightSamples);
            int sky1 = DistantLightSampler.sky(light1), block1 = DistantLightSampler.block(light1);
            int sky2 = DistantLightSampler.sky(light2), block2 = DistantLightSampler.block(light2);

            //renderChain's far-branch transform chain, then renderChainFastButWithGaps' (0.5, 0, 0.5)
            transform.identity()
                    .translate((float) from.x, (float) from.y, (float) from.z)
                    .rotate((float) Math.toRadians(yaw), 0, 1, 0)
                    .rotate((float) Math.toRadians(90.0f - pitch), 1, 0, 0)
                    .rotate((float) Math.toRadians(45.0), 0, 1, 0)
                    .translate(0.0f, 0.475f, 0.0f)
                    .translate(-0.5f, -0.5f, -0.5f)
                    .translate(0.5f, 0.0f, 0.5f);

            if (info.getVertexShape() == CogwheelChainType.VertexShape.CROSS) {
                float radius = 0.0625f;
                float minU = sprite.getU(0.1875f), maxU = sprite.getU(0.25f);
                float minV = sprite.getV(0.0f), maxV = sprite.getV(0.0625f);
                quad(verts, transform, vertex, length, 0, radius, 0, -radius, minU, maxU, minV, maxV, sky1, block1, sky2, block2);
                quad(verts, transform, vertex, length, 0, -radius, 0, radius, minU, maxU, minV, maxV, sky1, block1, sky2, block2);
                quad(verts, transform, vertex, length, radius, 0, -radius, 0, minU, maxU, minV, maxV, sky1, block1, sky2, block2);
                quad(verts, transform, vertex, length, -radius, 0, radius, 0, minU, maxU, minV, maxV, sky1, block1, sky2, block2);
            } else {
                float w = info.getWidth() / 16.0f, h = info.getHeight() / 16.0f;
                float half = 0.0625f;
                float minV = sprite.getV(0.0f), maxV = sprite.getV(h);
                quad(verts, transform, vertex, length, -half, half, half, half,
                        sprite.getU(h), sprite.getU(h + w), minV, maxV, sky1, block1, sky2, block2);
                quad(verts, transform, vertex, length, -half, -half, -half, half,
                        sprite.getU(0.0f), sprite.getU(h), minV, maxV, sky1, block1, sky2, block2);
                quad(verts, transform, vertex, length, half, -half, -half, -half,
                        sprite.getU(h + w + h), sprite.getU(h + w + h + w), minV, maxV, sky1, block1, sky2, block2);
                quad(verts, transform, vertex, length, half, half, half, -half,
                        sprite.getU(h + w), sprite.getU(h + w + h), minV, maxV, sky1, block1, sky2, block2);
            }
        }
        if (verts.isEmpty()) {
            return null;
        }
        int[] lights = new int[lightSamples.size() * 4];
        int index = 0;
        for (var sample : lightSamples.long2IntEntrySet()) {
            long pos = sample.getLongKey();
            lights[index++] = BlockPos.getX(pos);
            lights[index++] = BlockPos.getY(pos);
            lights[index++] = BlockPos.getZ(pos);
            lights[index++] = sample.getIntValue();
        }
        // 每段固定四个 quad，预分配容量恰为最终顶点数，无逐顶点数组或末尾拼接。
        return new CapturedChain(verts.elements(), lights);
    }

    private static int sampleLight(ClientLevel level, BlockPos origin, Vec3 endpoint,
                                   Long2IntOpenHashMap samples) {
        int x = (int) Math.floor(origin.getX() + 0.5 + endpoint.x);
        int y = (int) Math.floor(origin.getY() + 0.5 + endpoint.y);
        int z = (int) Math.floor(origin.getZ() + 0.5 + endpoint.z);
        long pos = BlockPos.asLong(x, y, z);
        int light = samples.getOrDefault(pos, -1);
        if (light < 0) {
            // 相邻链段共用端点；加载时读原生光，缺区段只查缓存，不额外叠加高度。
            light = KineticSnapshots.sampleChainLight(level, x, y, z);
            samples.put(pos, light);
        }
        return light;
    }

    //renderQuad's vertex order: top(light2), bottom(light1), bottom, top
    private static void quad(FloatArrayList verts, Matrix4f transform, Vector3f vertex, float length,
                             float x0, float z0, float x1, float z1,
                             float minU, float maxU, float minV, float maxV,
                             int sky1, int block1, int sky2, int block2) {
        emit(verts, transform, vertex, x0, length, z0, maxU, minV, sky2, block2);
        emit(verts, transform, vertex, x0, 0.0f, z0, maxU, maxV, sky1, block1);
        emit(verts, transform, vertex, x1, 0.0f, z1, minU, maxV, sky1, block1);
        emit(verts, transform, vertex, x1, length, z1, minU, minV, sky2, block2);
    }

    private static void emit(FloatArrayList verts, Matrix4f transform, Vector3f vertex,
                             float x, float y, float z, float u, float v, int sky, int block) {
        vertex.set(x, y, z);
        transform.transformPosition(vertex);
        verts.add(vertex.x); verts.add(vertex.y); verts.add(vertex.z);
        verts.add(u); verts.add(v); verts.add(sky); verts.add(block); verts.add(1.0f); verts.add(1);
    }
}
