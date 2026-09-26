package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_REPLACE;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.glDepthFunc;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glStencilFunc;
import static org.lwjgl.opengl.GL11C.glStencilOp;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

public final class DistantKineticRenderer implements LodPipelineHooks.Renderer {
    public static volatile int lastFrameSectionsDrawn;

    //Snapshot maintenance runs on the client tick, not in the render hook: capture and rebake upload
    //GL buffers, which mid-pipeline would clobber the LOD buffer setup.
    @net.neoforged.bus.api.SubscribeEvent
    public void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        long t = me.cortex.voxy.commonImpl.VoxyProfile.begin();
        KineticSnapshots.tick(Minecraft.getInstance());
        SectionReingestQueue.tick(Minecraft.getInstance());
        me.cortex.voxy.commonImpl.VoxyProfile.end("tick/kineticSnapshots", t);
    }

    //Horizontal leave-behind: a chunk unloading takes its kinetic BEs with it - freeze them now,
    //while they are still reachable.
    @net.neoforged.bus.api.SubscribeEvent
    public void onChunkUnload(net.neoforged.neoforge.event.level.ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel level
                && event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
            KineticSnapshots.captureUnloadingChunk(level, chunk);
        }
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        KineticSnapshots.clearAll();
        SectionReingestQueue.clear();
    }

    @Override
    public void render(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        pipeline.setupAndBindOpaque(viewport);
        this.renderCommon(pipeline, viewport, viewport.MVP, viewport.cameraX, viewport.cameraY, viewport.cameraZ, depthFunc);
    }

    private void renderCommon(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, Matrix4f viewProjection,
                              double camX, double camY, double camZ, int depthFunc) {
        lastFrameSectionsDrawn = 0;
        var sections = KineticSnapshots.sections();
        if (sections.isEmpty()) {
            return;
        }
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantKinetics) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        double reach = Math.max(0, mc.options.getEffectiveRenderDistance() * 16.0 - 14.0);
        double reachSq = reach * reach;
        double maxDist = cfg.createRenderDistance(cfg.distantKineticMaxChunks);
        double maxDistSq = maxDist * maxDist;

        boolean renderStateActive = false;
        int drawn = 0;
        var transform = new Matrix4f();
        try {
            for (var entry : sections.long2ObjectEntrySet()) {
                var bucket = entry.getValue();
                if (bucket.mesh == null) {
                    continue;
                }
                long key = entry.getLongKey();
                double ox = (BlockPos.getX(key) << 4), oy = (BlockPos.getY(key) << 4), oz = (BlockPos.getZ(key) << 4);
                double dx = ox + 8 - camX, dy = oy + 8 - camY, dz = oz + 8 - camZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < reachSq || distSq > maxDistSq) {
                    continue;
                }
                //Before any state setup, so a frame looking away from every machine never binds the
                //shader at all. Snapshots are captured per 16-block section, hence the cube.
                if (viewport != null && !DistantVisibility.isBoxVisible(viewport,
                        ox, oy, oz, ox + 16, oy + 16, oz + 16)) {
                    continue;
                }

                if (!renderStateActive) {
                    //Vertex-baked light (per BE), like the track meshes - no light uniform
                    DistantShaders.forPipeline(pipeline, false).bind();
                    DistantShaders.bindTextures();
                    glEnable(GL_DEPTH_TEST);
                    glDepthFunc(depthFunc);
                    glDepthMask(true);
                    //Captured vertex streams carry no canonical winding (rotation transforms flip
                    //triangles freely) - with face culling on, half the faces vanish and backfaces
                    //show through, reading as jumbled overlapping models at LOD range
                    org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_CULL_FACE);
                    glEnable(GL_STENCIL_TEST);
                    glStencilFunc(GL_ALWAYS, 3, 0xFF);
                    glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
                    renderStateActive = true;
                }

                transform.set(viewProjection).translate((float) (ox - camX), (float) (oy - camY), (float) (oz - camZ));
                DistantShaders.uploadTransform(transform);
                bucket.mesh.drawAt(viewport, ox, oy, oz);
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
            lastFrameSectionsDrawn = drawn;
        }
    }
}
