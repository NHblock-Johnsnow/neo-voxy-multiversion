package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.BogeyPose;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBogey;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.joml.Matrix4f;
import org.joml.Math;
import org.lwjgl.system.MemoryStack;

import java.util.List;
import java.util.ArrayList;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_REPLACE;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.glDepthFunc;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glColorMask;
import static org.lwjgl.opengl.GL11C.glStencilFunc;
import static org.lwjgl.opengl.GL11C.glStencilOp;
import static org.lwjgl.opengl.GL20C.glUniform2f;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;
import static org.lwjgl.opengl.GL11C.GL_GEQUAL;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL11C.glViewport;

//Draws distant train carriages as rigid meshes in the self-contained distant vertex format, inside
//voxy's render pipeline on both backends (LOD-depth perfect occlusion; iris uses the patched shader).
public final class DistantTrainRenderer implements LodPipelineHooks.Renderer {
    //Resolves a bogey description to its captured snapshot mesh; set from Voxy init when Create is
    //present (the provider class touches Create's registries).
    public static java.util.function.Function<ShapeBogey, DistantMesh> bogeyMeshProvider;
    public static java.util.function.BooleanSupplier bogeyMeshCaptureActive;
    public static Runnable bogeyMeshResetter;
    static int bogeyMeshGeneration;

    @SubscribeEvent
    public void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        DistantTrainManager.expirePackets();
        var cfg = VoxyConfig.CONFIG;
        if (Minecraft.getInstance().level != null && cfg.isRenderingEnabled() && cfg.distantTrains
                && me.cortex.voxy.client.ServerCapabilities.trains()) {
            DistantTrainManager.rebuildPending();
        }
    }
    //Diagnostics for /voxy debug trains: how many carriages the last frame actually drew
    public static volatile int lastFrameCarriagesDrawn;
    private static final ArrayList<DepthDraw> DEPTH_DRAWS = new ArrayList<>();
    private static int depthDrawCount;
    private static Viewport<?> depthViewport;

    private static final class DepthDraw {
        DistantMesh mesh;
        int level;
        float blend;
        final Matrix4f model = new Matrix4f();
    }

    //Handover boundary shared with the live-side culls - see TrainHandover.

    @Override
    public void render(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        pipeline.setupAndBindOpaque(viewport);
        //renderCommon only reads viewProjection (copies via transform.set), never mutates it
        this.renderCommon(pipeline, viewport, viewport.MVP,
                viewport.cameraX, viewport.cameraY, viewport.cameraZ, depthFunc);
    }

    private void renderCommon(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, Matrix4f viewProjection, double camX, double camY, double camZ, int depthFunc) {
        lastFrameCarriagesDrawn = 0;
        beginDepthFrame(viewport);
        if (DistantTrainManager.isEmpty()) {
            return;
        }
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantTrains
                || !me.cortex.voxy.client.ServerCapabilities.trains()) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        var dimension = mc.level.dimension().location();
        double maxDist = cfg.createRenderDistance(cfg.distantTrainMaxChunks);
        double maxDistSq = maxDist * maxDist;
        double liveProbe = TrainHandover.handoverDist() + 64;
        double liveProbeSq = liveProbe * liveProbe;
        long now = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        Vec3 cameraPos = new Vec3(camX, camY, camZ);
        boolean flywheelRendering = VisualizationManager.supportsVisualization(mc.level);

        var drawState = new DrawState(pipeline, mc.level, depthFunc, nowMs);
        int drawn = 0;
        var transform = new Matrix4f();
        var model = new Matrix4f();

        try {
            for (var trainEntry : DistantTrainManager.trains().entrySet()) {
                var train = trainEntry.getValue();
                if (!dimension.equals(train.dimension)) {
                    continue;
                }
                for (var carriageEntry : train.carriages.entrySet()) {
                    var track = carriageEntry.getValue();
                    if (track.cur == null) {
                        continue;
                    }
                    //Interpolate into locals - no per-carriage CarriagePose allocation, and done
                    //before the maxDistSq cull so far carriages (still in the client map out to the
                    //server stream range) cost nothing but a few lerps.
                    float t = interpFactor(track, now);
                    var cur = track.cur;
                    var prev = track.prev;
                    double px, py, pz;
                    float yaw, pitch;
                    if (prev == null || prev == cur) {
                        px = cur.x(); py = cur.y(); pz = cur.z();
                        yaw = cur.yaw(); pitch = cur.pitch();
                    } else {
                        px = Mth.lerp(t, prev.x(), cur.x());
                        py = Mth.lerp(t, prev.y(), cur.y());
                        pz = Mth.lerp(t, prev.z(), cur.z());
                        yaw = prev.yaw() + Mth.wrapDegrees(cur.yaw() - prev.yaw()) * t;
                        pitch = Math.lerp(prev.pitch(), cur.pitch(), t);
                    }
                    double dx = px - camX, dy = py - camY, dz = pz - camZ;
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > maxDistSq) {
                        continue;
                    }
                    var entry = DistantTrainManager.shape(track.shapeId);
                    if (entry == null) {
                        continue;
                    }
                    if (distSq < liveProbeSq
                            && TrainHandover.liveCarriageOwns(trainEntry.getKey(), carriageEntry.getKey(),
                                    cameraPos, flywheelRendering)) {
                        continue;
                    }

                    var cm = entry.mesh().mesh;
                    model.identity()
                            .translate((float) dx, (float) dy + 0.5f, (float) dz)
                            .rotateY((float) java.lang.Math.toRadians(-yaw))
                            .rotateZ((float) java.lang.Math.toRadians(pitch))
                            .rotateY((float) java.lang.Math.toRadians(entry.initialYaw()))
                            .translate(-0.5f, -0.5f, -0.5f);
                    drawState.beginCarriage(track, px, py, pz);
                    // 车体与转向架各用实际变换剔除，车体不可见时仍可能看见独立转向架。
                    if (viewport == null || DistantVisibility.isTransformedBoxVisible(viewport, model,
                            camX, camY, camZ, cm.minX, cm.minY, cm.minZ, cm.maxX, cm.maxY, cm.maxZ)) {
                        drawState.prepare();
                        transform.set(viewProjection).mul(model);
                        DistantShaders.uploadTransform(transform);
                        DistantShaders.uploadFaceRotation(-yaw + entry.initialYaw());
                        cm.drawModel(viewport, model, camX, camY, camZ);
                        recordDepthDraw(cm, model);
                        drawn++;
                    }

                    // 转向架使用同一车厢光照，独立判断可见性。
                    if (bogeyMeshProvider != null && !entry.bogeys().isEmpty()) {
                        drawBogeys(track, entry, t, camX, camY, camZ, viewport, viewProjection, transform, model, drawState);
                    }
                }
            }

        } finally {
            if (drawState.active) {
                glBindVertexArray(0);
                glUseProgram(0);
                //Pipeline contract state (set by initDepthStencil, expected by every later pass)
                glStencilFunc(GL_EQUAL, 1, 0x1);
                glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            }
            lastFrameCarriagesDrawn = drawn;
        }
    }

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        DistantTrainManager.clearAll();
        this.onResourceReload();
    }

    @Override
    public void onResourceReload() {
        DistantTrainManager.onResourceReload();
        if (bogeyMeshResetter != null) bogeyMeshResetter.run();
        bogeyMeshGeneration++;
        beginDepthFrame(null);
    }

    //Interpolates each bogey pose and draws its snapshot mesh. Transform mirrors the tail of
    //the -1.5078125 style offset is baked into the captured mesh. Wheel spin is a P4 follow-up.
    private static void drawBogeys(DistantTrainManager.CarriageTrack track, DistantTrainManager.ShapeEntry entry,
                                   float t, double camX, double camY, double camZ, Viewport<?> viewport, Matrix4f viewProjection,
                                   Matrix4f transform, Matrix4f model, DrawState drawState) {
        List<BogeyPose> cur = track.cur.bogeys();
        List<BogeyPose> prev = track.prev != null ? track.prev.bogeys() : cur;
        int count = java.lang.Math.min(cur.size(), entry.bogeys().size());
        for (int i = 0; i < count; i++) {
            var mesh = entry.bogeyMesh(i);
            if (mesh == null) {
                continue;
            }
            BogeyPose c = cur.get(i);
            BogeyPose p = i < prev.size() ? prev.get(i) : c;
            double x = Mth.lerp(t, p.x(), c.x());
            double y = Mth.lerp(t, p.y(), c.y());
            double z = Mth.lerp(t, p.z(), c.z());
            float yaw = p.yaw() + Mth.wrapDegrees(c.yaw() - p.yaw()) * t;
            float pitch = Math.lerp(p.pitch(), c.pitch(), t);

            model.identity()
                    .translate((float) (x - camX), (float) (y - camY), (float) (z - camZ))
                    .rotateY((float) java.lang.Math.toRadians(yaw))
                    .rotateX((float) java.lang.Math.toRadians(pitch))
                    .translate(0.0f, 0.5f, 0.0f);
            if (c.upsideDown()) {
                model.rotateZ((float) java.lang.Math.PI);
            }
            if (viewport != null && !DistantVisibility.isTransformedBoxVisible(viewport, model,
                    camX, camY, camZ, mesh.minX, mesh.minY, mesh.minZ, mesh.maxX, mesh.maxY, mesh.maxZ)) {
                continue;
            }
            drawState.prepare();
            transform.set(viewProjection).mul(model);
            DistantShaders.uploadTransform(transform);
            DistantShaders.uploadFaceRotation(yaw);
            mesh.drawModel(viewport, model, camX, camY, camZ);
            recordDepthDraw(mesh, model);
        }
    }

    private static final class DrawState {
        private final AbstractRenderPipeline pipeline;
        private final net.minecraft.client.multiplayer.ClientLevel level;
        private final int depthFunc;
        private final long nowMs;
        private DistantTrainManager.CarriageTrack track;
        private double x, y, z;
        private boolean lightReady;
        private boolean active;

        DrawState(AbstractRenderPipeline pipeline, net.minecraft.client.multiplayer.ClientLevel level, int depthFunc, long nowMs) {
            this.pipeline = pipeline;
            this.level = level;
            this.depthFunc = depthFunc;
            this.nowMs = nowMs;
        }

        void beginCarriage(DistantTrainManager.CarriageTrack track, double x, double y, double z) {
            this.track = track;
            this.x = x; this.y = y; this.z = z;
            this.lightReady = false;
        }

        void prepare() {
            // 仅有可见几何时绑定管线、采样光照；同车厢的转向架复用光照上传。
            if (!this.active) {
                DistantShaders.forPipeline(this.pipeline, true).bind();
                DistantShaders.bindTextures();
                glEnable(GL_DEPTH_TEST);
                glDepthFunc(this.depthFunc);
                glDepthMask(true);
                glDisable(GL_CULL_FACE);
                glEnable(GL_STENCIL_TEST);
                glStencilFunc(GL_ALWAYS, 2, 0xFF);
                glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
                this.active = true;
            }
            if (!this.lightReady) {
                if (this.track.lightPacked < 0 || this.nowMs - this.track.lightSampledAtMs > 1000) {
                    this.track.lightPacked = DistantLightSampler.samplePeek(this.level,
                            (int) java.lang.Math.floor(this.x), (int) java.lang.Math.floor(this.y), (int) java.lang.Math.floor(this.z));
                    this.track.lightSampledAtMs = this.nowMs;
                }
                glUniform2f(4,
                        (DistantLightSampler.block(this.track.lightPacked) * 16 + 8) / 256.0f,
                        (DistantLightSampler.sky(this.track.lightPacked) * 16 + 8) / 256.0f);
                this.lightReady = true;
            }
        }
    }

    private static void beginDepthFrame(Viewport<?> viewport) {
        for (int i = 0; i < depthDrawCount; i++) {
            DEPTH_DRAWS.get(i).mesh = null;
        }
        depthDrawCount = 0;
        depthViewport = viewport;
    }

    private static void recordDepthDraw(DistantMesh mesh, Matrix4f model) {
        DepthDraw draw;
        if (depthDrawCount == DEPTH_DRAWS.size()) {
            draw = new DepthDraw();
            DEPTH_DRAWS.add(draw);
        } else {
            draw = DEPTH_DRAWS.get(depthDrawCount);
        }
        depthDrawCount++;
        draw.mesh = mesh;
        draw.level = mesh.lastDrawLevel();
        draw.blend = mesh.lastDrawBlend();
        draw.model.set(model);
    }

    public static void replayDepthToSource(AbstractRenderPipeline pipeline, Viewport<?> viewport,
                                           int lodDepthTexture, int framebuffer, int width, int height, int depthFunc) {
        if (depthViewport != viewport || depthDrawCount == 0 || width <= 0 || height <= 0) {
            return;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, width, height);
        glColorMask(false, false, false, false);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(depthFunc);
        glDepthMask(true);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_CULL_FACE);
        DistantShaders.depthOnly(pipeline).bind();
        pipeline.bindUniforms();
        glBindTextureUnit(2, lodDepthTexture);
        glBindSampler(2, 0);
        boolean halfNdc = RenderProperties.windowIsHalfNdc();
        glUniform2f(12, halfNdc ? 0.5f : 1.0f, halfNdc ? 0.5f : 0.0f);
        glUniform1i(13, depthFunc == GL_GEQUAL ? 1 : 0);
        DistantShaders.bindTextures();

        var sourceViewProjection = new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView);
        var transform = new Matrix4f();
        try (var stack = MemoryStack.stackPush()) {
            var lodTransform = stack.mallocFloat(16);
            for (int i = 0; i < depthDrawCount; i++) {
                var draw = DEPTH_DRAWS.get(i);
                transform.set(viewport.MVP).mul(draw.model).get(lodTransform);
                glUniformMatrix4fv(8, false, lodTransform);
                DistantShaders.uploadTransform(transform.set(sourceViewProjection).mul(draw.model));
                draw.mesh.draw(draw.level, draw.blend);
            }
        }
        glBindVertexArray(0);
        glUseProgram(0);
        glColorMask(true, true, true, true);
        glEnable(GL_CULL_FACE);
    }

    private static float interpFactor(DistantTrainManager.CarriageTrack track, long now) {
        if (track.prev == null || track.prev == track.cur) {
            return 1.0f;
        }
        //Allow slight extrapolation past the newest sample so motion stays continuous between packets
        return Mth.clamp((now - track.curTimeNanos) / (float) track.sampleIntervalNanos, 0.0f, 1.25f);
    }
}
