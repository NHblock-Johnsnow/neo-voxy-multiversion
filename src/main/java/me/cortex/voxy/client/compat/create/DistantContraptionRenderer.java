package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

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
import static org.lwjgl.opengl.GL11C.glStencilFunc;
import static org.lwjgl.opengl.GL11C.glStencilOp;
import static org.lwjgl.opengl.GL20C.glUniform2f;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

public final class DistantContraptionRenderer implements LodPipelineHooks.Renderer {
    public static volatile int lastFrameDrawn;

    //Snapshot refresh runs on the client tick, not in the render hook: baking uploads to GL, which
    //mid-pipeline would clobber the LOD buffer setup (same reason DistantTrackRenderer bakes on tick).
    @net.neoforged.bus.api.SubscribeEvent
    public void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        double maxDist = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantContraptionMaxChunks);
        DistantContraptionManager.update(mc.level, cam.x, cam.y, cam.z, maxDist);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        DistantContraptionManager.clearAll();
    }


    @Override
    public void render(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        pipeline.setupAndBindOpaque(viewport);
        this.renderCommon(pipeline, viewport, viewport.MVP, viewport.cameraX, viewport.cameraY, viewport.cameraZ, depthFunc);
    }

    private void renderCommon(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, Matrix4f viewProjection,
                              double camX, double camY, double camZ, int depthFunc) {
        lastFrameDrawn = 0;
        var snapshots = DistantContraptionManager.snapshots();
        if (snapshots.isEmpty()) {
            return;
        }
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantContraptions) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        var dimension = mc.level.dimension().location();
        double maxDist = cfg.createRenderDistance(cfg.distantContraptionMaxChunks);
        double maxDistSq = maxDist * maxDist;
        //The vanilla/Flywheel render (and its anti-float cull) owns everything inside the render
        //distance; we take over exactly at that radius, holding the frozen snapshot.
        double reach = mc.options.getEffectiveRenderDistance() * 16.0;
        double reachSq = reach * reach;
        //The same camera the live render's cull uses, not the viewport's copy: the yield below must be
        //the exact complement of that cull's predicate.
        var entityCam = mc.gameRenderer.getMainCamera().getPosition();

        boolean renderStateActive = false;
        int drawn = 0;
        long nowNanos = System.nanoTime();
        var transform = new Matrix4f();
        try {
            for (var snapEntry : snapshots.entrySet()) {
                var snap = snapEntry.getValue();
                if (snap.mesh() == null || !dimension.equals(snap.dim())) {
                    continue;
                }
                double dx = snap.x() - camX, dy = snap.y() - camY, dz = snap.z() - camZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > maxDistSq) {
                    continue;
                }
                var live = DistantContraptionManager.trackedEntity(snapEntry.getKey(), snap);
                if (live != null && !DistantContraptionManager.hiddenThisFrame(live)) {
                    if (live.position().distanceToSqr(entityCam) <= reachSq) {
                        continue;
                    }
                } else if (live == null && distSq < reachSq && snap.movedWhileSeen()
                        && !DistantContraptionManager.hasFreshRemotePose(snap, nowNanos)) {
                    continue;
                }
                if (viewport != null && !DistantVisibility.isTransformedBoxVisible(
                        viewport, snap.local(), snap.x(), snap.y(), snap.z(), snap.mesh().localBounds)) {
                    continue;
                }

                if (!renderStateActive) {
                    DistantShaders.forPipeline(pipeline, true).bind();
                    DistantShaders.bindTextures();
                    glEnable(GL_DEPTH_TEST);
                    glDepthFunc(depthFunc);
                    glDepthMask(true);
                    glDisable(GL_CULL_FACE);
                    glEnable(GL_STENCIL_TEST);
                    glStencilFunc(GL_ALWAYS, 3, 0xFF);
                    glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
                    renderStateActive = true;
                }

                //VP · T(worldPos - camera) · M_local  (M_local = applyLocalTransforms, frozen)
                transform.set(viewProjection).translate((float) dx, (float) dy, (float) dz).mul(snap.local());
                DistantShaders.uploadTransform(transform);
                int light = snap.lightPacked();
                glUniform2f(4,
                        (DistantLightSampler.block(light) * 16 + 8) / 256.0f,
                        (DistantLightSampler.sky(light) * 16 + 8) / 256.0f);
                snap.mesh().mesh.drawModel(viewport, snap.local(), snap.x(), snap.y(), snap.z());
                drawn++;
            }
            if (renderStateActive) {
                glBindVertexArray(0);
                glUseProgram(0);
            }
        } finally {
            if (renderStateActive) {
                //Restore the pipeline's ambient stencil contract
                glStencilFunc(GL_EQUAL, 1, 0x1);
                glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            }
            lastFrameDrawn = drawn;
        }
    }
}
