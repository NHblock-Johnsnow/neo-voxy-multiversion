package me.cortex.voxy.client.core.beacon;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.distant.DistantBeamShader;
import me.cortex.voxy.client.compat.distant.DistantMesh;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

public final class DistantBeaconRenderer implements LodPipelineHooks.Renderer {
    public static final DistantBeaconRenderer INSTANCE = new DistantBeaconRenderer();
    private static final float RADIUS = 0.12f;
    private final Long2ObjectOpenHashMap<Beam> beams = new Long2ObjectOpenHashMap<>();
    private Level level;

    private record Segment(int bottom, int top, int rgb) {}
    private record Beam(int x, int y, int z, int signature, DistantMesh mesh) {}
    private DistantBeaconRenderer() {}

    public static void observe(BeaconBlockEntity beacon) { INSTANCE.observe0(beacon); }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onLogout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        this.clear();
        this.level = null;
    }

    private void observe0(BeaconBlockEntity beacon) {
        if (beacon.getLevel() == null) return;
        Level current = beacon.getLevel();
        if (current != this.level) {
            this.clear();
            this.level = current;
        }
        var sections = beacon.getBeamSections();
        long key = beacon.getBlockPos().asLong();
        if (sections.isEmpty()) {
            Beam old = this.beams.remove(key);
            if (old != null) old.mesh.free();
            return;
        }
        int y = beacon.getBlockPos().getY();
        int cursor = y + 1;
        int signature = 1;
        List<Segment> segments = new ArrayList<>(sections.size());
        for (var section : sections) {
            float[] color = section.getColor();
            int rgb = ((int) (color[0] * 255.0f) << 16)
                    | ((int) (color[1] * 255.0f) << 8) | (int) (color[2] * 255.0f);
            int top = cursor + section.getHeight();
            segments.add(new Segment(cursor, top, rgb));
            signature = 31 * signature + rgb;
            signature = 31 * signature + section.getHeight();
            cursor = top;
        }
        Beam old = this.beams.get(key);
        if (old != null && old.signature == signature) return;
        DistantMesh mesh = bake(segments, y);
        if (old != null) old.mesh.free();
        var pos = beacon.getBlockPos();
        this.beams.put(key, new Beam(pos.getX(), y, pos.getZ(), signature, mesh));
    }

    @Override
    public void render(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        var mc = Minecraft.getInstance();
        if (mc.level != this.level) {
            this.clear();
            this.level = mc.level;
        }
        if (mc.level == null || !VoxyConfig.CONFIG.distantBeacons) return;
        double vanilla = Math.min(256.0, mc.options.getEffectiveRenderDistance() * 16.0);
        double min = Math.max(16.0, vanilla - 8.0), minSq = min * min;
        double max = VoxyConfig.CONFIG.distantBeaconMaxChunks == 0
                ? VoxyConfig.CONFIG.getLodRenderDistanceBlocks()
                : VoxyConfig.CONFIG.distantBeaconMaxChunks * 16.0;
        double maxSq = max * max;
        boolean bound = false;
        Matrix4f transform = new Matrix4f();
        pipeline.setupAndBindOpaque(viewport);
        for (Beam beam : this.beams.values()) {
            double dx = beam.x + 0.5 - viewport.cameraX;
            double dy = beam.y - viewport.cameraY;
            double dz = beam.z + 0.5 - viewport.cameraZ;
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq < minSq || distanceSq > maxSq || beam.mesh == null) continue;
            if (!bound) {
                DistantBeamShader.get().bind();
                DistantBeamShader.bindTextures(BeaconRenderer.BEAM_LOCATION);
                glEnable(GL_DEPTH_TEST);
                glDepthFunc(depthFunc);
                glDepthMask(false);
                glDisable(GL_CULL_FACE);
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE);
                bound = true;
            }
            transform.set(viewport.MVP).translate((float) dx, (float) dy, (float) dz);
            DistantBeamShader.uploadTransform(transform);
            beam.mesh.draw();
        }
        if (bound) {
            glBindVertexArray(0);
            glUseProgram(0);
        }
    }

    private static DistantMesh bake(List<Segment> segments, int baseY) {
        var b = new DistantMesh.Builder();
        for (Segment s : segments) {
            float y0 = s.bottom - baseY, y1 = s.top - baseY;
            side(b, -RADIUS, y0, -RADIUS, RADIUS, y1, -RADIUS, s.bottom, s.top, s.rgb, 2);
            side(b, RADIUS, y0, RADIUS, -RADIUS, y1, RADIUS, s.bottom, s.top, s.rgb, 3);
            side(b, RADIUS, y0, -RADIUS, RADIUS, y1, RADIUS, s.bottom, s.top, s.rgb, 4);
            side(b, -RADIUS, y0, RADIUS, -RADIUS, y1, -RADIUS, s.bottom, s.top, s.rgb, 5);
        }
        return b.build();
    }

    private static void side(DistantMesh.Builder b, float x0, float y0, float z0,
                             float x1, float y1, float z1, float v0, float v1, int rgb, int face) {
        b.vertex(x0, y0, z0, 0, v0, rgb, face);
        b.vertex(x1, y0, z1, 1, v0, rgb, face);
        b.vertex(x1, y1, z1, 1, v1, rgb, face);
        b.vertex(x0, y1, z0, 0, v1, rgb, face);
    }

    private void clear() {
        for (Beam beam : this.beams.values()) beam.mesh.free();
        this.beams.clear();
    }
}
