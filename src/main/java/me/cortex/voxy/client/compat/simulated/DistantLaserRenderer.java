package me.cortex.voxy.client.compat.simulated;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.create.DistantMesh;
import me.cortex.voxy.client.compat.create.DistantMeshBuilder;
import me.cortex.voxy.client.compat.create.DistantShaders;
import me.cortex.voxy.client.compat.create.DistantVisibility;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.IdentityHashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL20C.glUniform4f;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

public final class DistantLaserRenderer implements LodPipelineHooks.TranslucentRenderer {
    private static final double NATIVE_RANGE = 256.0;
    private static final double HANDOFF_OVERLAP = 12.0;
    private static final ThreadLocal<Capture> CAPTURE = new ThreadLocal<>();
    private static volatile DistantLaserRenderer active;

    private final Map<Object, Entry> beams = new IdentityHashMap<>();
    private Object level;
    private DistantMesh unitBeam;

    private record Source(double x, double y, double z, float dx, float dy, float dz,
                          float halfWidth, float red, float green, float blue, float alpha) {}

    private static final class Entry {
        Source source;

        Entry(Source source) {
            this.source = source;
        }
    }

    private static final class Capture {
        final Object source;
        boolean emitted;

        Capture(Object source) {
            this.source = source;
        }
    }

    public DistantLaserRenderer() {
        active = this;
    }

    public static void beginCapture(Object blockEntity) {
        if (active == null || blockEntity == null
                || !blockEntity.getClass().getName().endsWith(".laser_pointer.LaserPointerBlockEntity")) {
            CAPTURE.remove();
            return;
        }
        CAPTURE.set(new Capture(blockEntity));
    }

    public static void capture(PoseStack pose, Vector4f color, float maxLength, float length) {
        Capture context = CAPTURE.get();
        DistantLaserRenderer renderer = active;
        if (context == null || renderer == null || length <= 0.0f || color.w <= 0.0f) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) return;

        Matrix4f matrix = pose.last().pose();
        Vector3f start = matrix.transformPosition(new Vector3f(0.5f, 0.5f, 0.0f));
        Vector3f end = matrix.transformPosition(new Vector3f(0.5f, 0.5f, length + 0.5f));
        Vector3f side = matrix.transformPosition(new Vector3f(1.0f, 0.5f, 0.0f));
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float halfWidth = Math.max(0.015f, start.distance(side));
        Source source = new Source(camera.x + start.x, camera.y + start.y, camera.z + start.z,
                end.x - start.x, end.y - start.y, end.z - start.z, halfWidth,
                color.x, color.z, color.y, color.w);
        renderer.accept(context.source, source);
        context.emitted = true;
    }

    public static void endCapture() {
        Capture context = CAPTURE.get();
        CAPTURE.remove();
        DistantLaserRenderer renderer = active;
        if (renderer != null && context != null && !context.emitted) renderer.remove(context.source);
    }

    private void accept(Object key, Source source) {
        ensureLevel();
        Entry entry = beams.get(key);
        if (entry == null) beams.put(key, new Entry(source));
        else entry.source = source;
    }

    private void remove(Object key) {
        beams.remove(key);
    }

    private void ensureLevel() {
        Object current = Minecraft.getInstance().level;
        if (level == current) return;
        clear();
        level = current;
    }

    @Override
    public void renderTranslucent(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        ensureLevel();
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantSimulatedLasers || beams.isEmpty()) return;

        double max = cfg.createRenderDistance(cfg.distantSimulatedLaserMaxChunks);
        double maxSq = max * max;
        double vanilla = Math.min(NATIVE_RANGE,
                Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0) - HANDOFF_OVERLAP;
        double vanillaSq = Math.max(0.0, vanilla * vanilla);

        if (unitBeam == null) unitBeam = bakeUnitBeam();
        if (unitBeam == null) return;
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        pipeline.setupAndBindTranslucent(viewport);
        var shader = DistantLaserShaders.forPipeline(pipeline);
        shader.bind();
        int colorUniform = glGetUniformLocation(shader.id(), "uLaserColor");
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(depthFunc);
        glDepthMask(true);
        glDisable(GL_CULL_FACE);

        Matrix4f transform = new Matrix4f();
        try {
            for (Entry entry : beams.values()) {
                Source source = entry.source;
                double x = source.x - viewport.cameraX, y = source.y - viewport.cameraY,
                        z = source.z - viewport.cameraZ;
                double distanceSq = x * x + y * y + z * z;
                if (distanceSq < vanillaSq || distanceSq > maxSq) continue;
                double ex = source.x + source.dx, ey = source.y + source.dy, ez = source.z + source.dz;
                double w = source.halfWidth * 1.5;
                if (!DistantVisibility.isBoxVisible(viewport,
                        Math.min(source.x, ex) - w, Math.min(source.y, ey) - w, Math.min(source.z, ez) - w,
                        Math.max(source.x, ex) + w, Math.max(source.y, ey) + w, Math.max(source.z, ez) + w)) continue;
                float length = (float) Math.sqrt(source.dx * source.dx + source.dy * source.dy + source.dz * source.dz);
                if (length <= 0.001f) continue;
                Quaternionf rotation = new Quaternionf().rotationTo(0, 0, 1,
                        source.dx / length, source.dy / length, source.dz / length);
                transform.set(viewport.MVP).translate((float) x, (float) y, (float) z)
                        .rotate(rotation).scale(source.halfWidth, source.halfWidth, length);
                DistantShaders.uploadTransform(transform);
                glUniform4f(colorUniform, source.red, source.green, source.blue, source.alpha);
                unitBeam.drawTranslucent(viewport, transform);
            }
        } finally {
            glBindVertexArray(0);
            glUseProgram(0);
        }
    }

    private static DistantMesh bakeUnitBeam() {
        var builder = new DistantMeshBuilder(false);
        try {
            Vec3 start = Vec3.ZERO;
            Vec3 end = new Vec3(0, 0, 1);
            Vec3 side = new Vec3(1, 0, 0);
            Vec3 up = new Vec3(0, 1, 0);
            Vec3[] a = {start.add(side).add(up), start.subtract(side).add(up),
                    start.subtract(side).subtract(up), start.add(side).subtract(up)};
            Vec3[] b = {end.add(side).add(up), end.subtract(side).add(up),
                    end.subtract(side).subtract(up), end.add(side).subtract(up)};
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) & 3;
                Direction face = face(a[i].add(a[j]).scale(0.5));
                vertex(builder, a[i], face, 255);
                vertex(builder, a[j], face, 255);
                vertex(builder, b[j], face, 0);
                vertex(builder, b[i], face, 0);
            }
            return builder.build();
        } catch (Throwable ignored) {
            builder.discard();
            return null;
        }
    }

    private static void vertex(DistantMeshBuilder builder, Vec3 p, Direction face, int alpha) {
        builder.rawVertex((float) p.x, (float) p.y, (float) p.z, 0, 0,
                15, 15, 1.0f, face.get3DDataValue(), 0xFFFFFF, alpha);
    }

    private static Direction face(Vec3 normal) {
        double ax = Math.abs(normal.x), ay = Math.abs(normal.y), az = Math.abs(normal.z);
        if (ay >= ax && ay >= az) return normal.y >= 0 ? Direction.UP : Direction.DOWN;
        if (az >= ax) return normal.z >= 0 ? Direction.SOUTH : Direction.NORTH;
        return normal.x >= 0 ? Direction.EAST : Direction.WEST;
    }

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        level = null;
    }

    private void clear() {
        beams.clear();
        if (unitBeam != null) unitBeam.free();
        unitBeam = null;
    }
}
