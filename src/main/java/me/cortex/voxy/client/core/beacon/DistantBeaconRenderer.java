package me.cortex.voxy.client.core.beacon;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.create.DistantMesh;
import me.cortex.voxy.client.compat.create.DistantMeshBuilder;
import me.cortex.voxy.client.compat.create.DistantShaders;
import me.cortex.voxy.client.compat.create.DistantVisibility;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

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
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;

public final class DistantBeaconRenderer implements LodPipelineHooks.Renderer {
    //Vanilla's BeaconRenderer stops here, and it measures horizontally - getViewDistance is squared
    //against dx/dz only, so a beacon directly overhead is still drawn
    private static final double VANILLA_BEAM_RANGE = 256.0;
    private static final ResourceLocation BEAM_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/beacon_beam.png");

    private static final float CORE_RADIUS = 0.2f;
    private static final float FAR_WIDTH_PER_BLOCK = 0.00022f;
    private static final double WIDTH_EASE_BLOCKS = 512.0;
    private static final double HANDOFF_OVERLAP = 32.0;

    public static int lastFrameBeamsDrawn;
    //Re-solves per frame. A solve is a bounded column walk, but the first sight of a beacon-heavy
    //world queues them all at once - the budget turns that into a short ramp instead of one frame.
    private static final int SOLVES_PER_FRAME = 4;
    private static final long LOOKUP_RETRY_MS = 5000;
    private static final long WARM_RETRY_MS = 500;

    private static volatile DistantBeaconRenderer active;

    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BeaconState> states =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final class BeaconState {
        DistantMesh mesh;
        double topY;
        //Mapper did not know an id mid-solve; provisional, retried after a backoff
        long retryAtMs;
    }

    private final List<Built> built = new ArrayList<>();
    private boolean builtStale;
    private long lastFilterMs = -1;
    private double lastFilterX, lastFilterZ;
    private double lastFilterRange = -1;
    private long nextRetryMs = Long.MAX_VALUE;
    private me.cortex.voxy.common.world.WorldEngine boundEngine;
    private final it.unimi.dsi.fastutil.longs.LongArrayList drainDirty = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private final it.unimi.dsi.fastutil.longs.LongArrayList drainRemoved = new it.unimi.dsi.fastutil.longs.LongArrayList();

    //topY is kept so the draw can frustum-test the beam: it is a tall thin column, and testing only its
    //base rejects it whenever the base is below the view while the visible part is not.
    private record Built(DistantMesh mesh, double x, double y, double z, double topY, BlockPos pos) {}

    public DistantBeaconRenderer() {
        active = this;
    }

    @Override
    public void render(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.distantBeacons || !cfg.isRenderingEnabled()) {
            this.discard();
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            this.discard();
            return;
        }
        var engine = WorldIdentifier.ofEngineNullable(mc.level);
        if (engine == null) {
            this.discard();
            return;
        }

        if (this.boundEngine != engine) {
            //Engine changed under us (dimension switch, world reload): every cached verdict was solved
            //against the old store, and the tracker's column map with it
            this.discard();
            BeaconBeamTracker.reset(this.boundEngine);
            BeaconBeamTracker.bind(engine);
            this.boundEngine = engine;
            this.lastFilterMs = -1;
        }

        this.filterIfStale(viewport);
        this.processChanges(engine, viewport, mc);
        if (this.builtStale) {
            this.builtStale = false;
            this.built.clear();
            for (var entry : this.states.long2ObjectEntrySet()) {
                var state = entry.getValue();
                if (state.mesh != null) {
                    long pos = entry.getLongKey();
                    this.built.add(new Built(state.mesh,
                            BlockPos.getX(pos) + 0.5, BlockPos.getY(pos), BlockPos.getZ(pos) + 0.5,
                            state.topY, BlockPos.of(pos)));
                }
            }
            lastBuiltCount = this.built.size();
        }
        if (this.built.isEmpty()) {
            lastFrameBeamsDrawn = 0;
            return;
        }

        pipeline.setupAndBindOpaque(viewport);
        // 外层联动调度已保存 GL 状态，无需为光柱再读取一遍驱动状态。
        this.draw(pipeline, viewport, depthFunc);
    }

    private void draw(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        var mc = Minecraft.getInstance();
        var shader = DistantShaders.forPipeline(pipeline, false);
        if (shader == null) {
            return;
        }
        shader.bind();
        DistantShaders.bindTextures();
        //Over the atlas that bindTextures just put on unit 0: the beam has its own texture and the
        //shader only ever samples one 2D image, so swapping the binding is the whole difference
        glBindTextureUnit(0, Minecraft.getInstance().getTextureManager().getTexture(BEAM_TEXTURE).getId());

        // The beam skips circular terrain fade, but still uses the terrain depth buffer for occlusion.
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(depthFunc);
        glDepthMask(true);
        glEnable(GL_STENCIL_TEST);
        glStencilFunc(GL_ALWAYS, 3, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        try {
            var transform = new Matrix4f();
            int drawn = 0;
            double vanillaRange = Math.min(VANILLA_BEAM_RANGE,
                    Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0);
            double readyRange = Math.max(16.0, vanillaRange - HANDOFF_OVERLAP);
            double readyRangeSq = readyRange * readyRange;
            double maxRange = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantBeaconMaxChunks);
            lastVanillaRange = (int) vanillaRange;
            int vanillaOwned = 0;
            for (var beam : this.built) {
                double bdx = beam.x - viewport.cameraX, bdz = beam.z - viewport.cameraZ;
                double horizontalSq = bdx * bdx + bdz * bdz;
                if (horizontalSq > maxRange * maxRange) continue;
                if (vanillaBeamReady(mc, beam, horizontalSq, readyRangeSq)) {
                    vanillaOwned++;
                    continue;
                }
                float radius = radiusAt(Math.sqrt(horizontalSq), vanillaRange);
                //A beam is a 1024-block column, so its box is tall and thin
                if (!DistantVisibility.isBoxVisible(viewport,
                        beam.x - radius, beam.y, beam.z - radius,
                        beam.x + radius, beam.topY, beam.z + radius)) {
                    continue;
                }
                transform.set(viewport.MVP).translate(
                        (float) (beam.x - viewport.cameraX),
                        (float) (beam.y - viewport.cameraY),
                        (float) (beam.z - viewport.cameraZ))
                        .scale(radius / CORE_RADIUS, 1.0f, radius / CORE_RADIUS);
                DistantShaders.uploadTransform(transform);
                beam.mesh.draw();
                drawn++;
            }
            lastFrameBeamsDrawn = drawn;
            lastVanillaOwned = vanillaOwned;
        } finally {
            glStencilFunc(GL_EQUAL, 1, 0x1);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        }
    }

    private void processChanges(me.cortex.voxy.common.world.WorldEngine engine, Viewport<?> viewport, Minecraft mc) {
        this.drainDirty.clear();
        this.drainRemoved.clear();
        BeaconBeamTracker.drain(this.drainDirty, this.drainRemoved);

        for (int i = 0; i < this.drainRemoved.size(); i++) {
            BeaconState state = this.states.remove(this.drainRemoved.getLong(i));
            if (state != null) {
                if (state.mesh != null) {
                    state.mesh.free();
                }
                this.builtStale = true;
            }
        }
        if (this.drainDirty.isEmpty()) {
            return;
        }

        double maxDist = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantBeaconMaxChunks) + 128.0;
        double maxDistSq = maxDist * maxDist;
        long now = System.currentTimeMillis();
        int solved = 0;
        try {
            for (int i = 0; i < this.drainDirty.size(); i++) {
                long pos = this.drainDirty.getLong(i);
                if (solved >= SOLVES_PER_FRAME) {
                    //Budget spent: what is left stays queued and drains over the coming frames
                    BeaconBeamTracker.queueDirty(pos);
                    continue;
                }
                if (!BeaconBeamTracker.isTracked(pos)) {
                    BeaconState stale = this.states.remove(pos);
                    if (stale != null && stale.mesh != null) {
                        stale.mesh.free();
                        this.builtStale = true;
                    }
                    continue;
                }
                int bx = BlockPos.getX(pos), by = BlockPos.getY(pos), bz = BlockPos.getZ(pos);
                double dx = (bx + 0.5) - viewport.cameraX;
                double dz = (bz + 0.5) - viewport.cameraZ;
                if (dx * dx + dz * dz > maxDistSq) {
                    //Out of range: drop whatever was held; the range filter re-queues it on approach
                    BeaconState state = this.states.remove(pos);
                    if (state != null && state.mesh != null) {
                        state.mesh.free();
                        this.builtStale = true;
                    }
                    continue;
                }
                solved++;
                this.solveOne(engine, mc, pos, bx, by, bz, now);
            }
        } catch (Throwable t) {
            Logger.error("Building distant beacon beams", t);
        }
    }

    private void solveOne(me.cortex.voxy.common.world.WorldEngine engine, Minecraft mc,
                          long pos, int bx, int by, int bz, long now) {
        var result = BeaconBeamSolver.solve(engine, bx, by, bz, mc.level == null ? by : mc.level.getMaxBuildHeight());
        BeaconState state = this.states.get(pos);
        if (state == null) {
            state = new BeaconState();
            this.states.put(pos, state);
        }
        if (result.cacheMissed()) {
            state.retryAtMs = now + WARM_RETRY_MS;
            this.nextRetryMs = Math.min(this.nextRetryMs, state.retryAtMs);
            return;
        }
        if (result.lookupFailed()) {
            //Solved against a mapper that had not registered an id yet - a wrong verdict cached now
            //would stick until the next voxel change, so hold it provisional and retry
            state.retryAtMs = now + LOOKUP_RETRY_MS;
            this.nextRetryMs = Math.min(this.nextRetryMs, state.retryAtMs);
            return;
        }
        state.retryAtMs = 0;
        if (result.segments().isEmpty()) {
            if (state.mesh != null) {
                state.mesh.free();
                state.mesh = null;
                this.builtStale = true;
            }
            return;
        }
        var mesh = bake(result.segments(), by);
        if (mesh != null) {
            if (state.mesh != null) state.mesh.free();
            double topY = by;
            for (var seg : result.segments()) {
                topY = Math.max(topY, seg.yTop());
            }
            state.mesh = mesh;
            state.topY = topY;
            this.builtStale = true;
        }
    }

    private static boolean vanillaBeamReady(Minecraft mc, Built beam,
                                             double horizontalSq, double readyRangeSq) {
        if (horizontalSq >= readyRangeSq || mc.level == null) {
            return false;
        }
        BlockPos pos = beam.pos;
        // 光柱是跨区段的方块实体渲染，底座不可见时仍可能显示，不能套用地形可见集合。
        if (!mc.levelRenderer.isSectionCompiled(pos)) return false;
        if (!mc.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        return mc.level.getBlockEntity(pos) instanceof BeaconBlockEntity beacon
                && !beacon.getBeamSections().isEmpty();
    }

    private static float radiusAt(double distance, double vanillaRange) {
        double excess = Math.max(0.0, distance - vanillaRange);
        double eased = excess * excess / (excess + WIDTH_EASE_BLOCKS);
        return CORE_RADIUS + (float) (eased * FAR_WIDTH_PER_BLOCK);
    }

    /** Adds minimal opaque prisms to Iris' shadow-map batch; called only during a shadow pass. */
    public static int renderShadowCasters(RenderBuffers buffers, PoseStack poses,
                                          double cameraX, double cameraY, double cameraZ,
                                          double shadowDistance) {
        DistantBeaconRenderer renderer = active;
        if (renderer == null || renderer.built.isEmpty()
                || !VoxyConfig.CONFIG.distantBeacons || !VoxyConfig.CONFIG.isRenderingEnabled()) {
            return 0;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null || renderer.boundEngine != WorldIdentifier.ofEngineNullable(mc.level)) {
            return 0;
        }
        double rangeSq = shadowDistance * shadowDistance;
        double vanillaRange = Math.min(VANILLA_BEAM_RANGE,
                mc.options.getEffectiveRenderDistance() * 16.0);
        VertexConsumer consumer = buffers.bufferSource().getBuffer(RenderType.entitySolid(BEAM_TEXTURE));
        int count = 0;
        for (Built beam : renderer.built) {
            double dx = beam.x - cameraX;
            double dz = beam.z - cameraZ;
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq > rangeSq) {
                continue;
            }
            float radius = radiusAt(Math.sqrt(distanceSq), vanillaRange);
            float height = (float) Math.max(0.0, beam.topY - beam.y);
            if (height <= 0.0f) {
                continue;
            }
            poses.pushPose();
            poses.translate(dx, beam.y - cameraY, dz);
            var pose = poses.last();
            shadowSide(consumer, pose, -radius, -radius, radius, -radius, height, 0.0f, -1.0f);
            shadowSide(consumer, pose, radius, radius, -radius, radius, height, 0.0f, 1.0f);
            shadowSide(consumer, pose, radius, -radius, radius, radius, height, 1.0f, 0.0f);
            shadowSide(consumer, pose, -radius, radius, -radius, -radius, height, -1.0f, 0.0f);
            poses.popPose();
            count++;
        }
        return count;
    }

    private static void shadowSide(VertexConsumer consumer, PoseStack.Pose pose,
                                   float x0, float z0, float x1, float z1, float height,
                                   float normalX, float normalZ) {
        shadowVertex(consumer, pose, x0, 0.0f, z0, 0.0f, 0.0f, normalX, normalZ);
        shadowVertex(consumer, pose, x1, 0.0f, z1, 1.0f, 0.0f, normalX, normalZ);
        shadowVertex(consumer, pose, x1, height, z1, 1.0f, height, normalX, normalZ);
        shadowVertex(consumer, pose, x0, height, z0, 0.0f, height, normalX, normalZ);
    }

    private static void shadowVertex(VertexConsumer consumer, PoseStack.Pose pose,
                                     float x, float y, float z, float u, float v,
                                     float normalX, float normalZ) {
        consumer.addVertex(pose, x, y, z)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, normalX, 0.0f, normalZ);
    }

    //The only thing left on a timer, because distance is the one input with no event: membership of
    //the LOD range as the camera moves. One distance check per index entry, no acquires, no GL.
    private void filterIfStale(Viewport<?> viewport) {
        long now = System.currentTimeMillis();
        double maxDist = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantBeaconMaxChunks);
        double movedX = viewport.cameraX - this.lastFilterX;
        double movedZ = viewport.cameraZ - this.lastFilterZ;
        long elapsed = now - this.lastFilterMs;
        if (this.lastFilterMs != -1 && maxDist == this.lastFilterRange && elapsed < 2000
                && now < this.nextRetryMs
                && (elapsed < 100 || movedX * movedX + movedZ * movedZ < 32.0 * 32.0)) {
            return;
        }
        this.lastFilterMs = now;
        this.lastFilterX = viewport.cameraX;
        this.lastFilterZ = viewport.cameraZ;
        this.lastFilterRange = maxDist;
        this.nextRetryMs = Long.MAX_VALUE;
        // 提前准备、延后回收，避免跨越范围边界时反复烘焙。
        double prepareSq = (maxDist + 64.0) * (maxDist + 64.0);
        double retainSq = (maxDist + 128.0) * (maxDist + 128.0);
        int[] outOfRange = new int[1];
        var engine = this.boundEngine;
        if (engine == null) {
            return;
        }
        engine.getBeaconIndex().forEach((bx, by, bz) -> {
            long pos = BlockPos.asLong(bx, by, bz);
            double dx = (bx + 0.5) - viewport.cameraX;
            double dz = (bz + 0.5) - viewport.cameraZ;
            BeaconState state = this.states.get(pos);
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq > retainSq) {
                outOfRange[0]++;
                if (state != null) {
                    this.states.remove(pos);
                    if (state.mesh != null) {
                        state.mesh.free();
                        this.builtStale = true;
                    }
                }
                return;
            }
            if (state == null) {
                //Came into range without a voxel change - first sight, or returning after eviction
                if (distanceSq <= prepareSq) BeaconBeamTracker.queueDirty(pos);
            } else if (state.retryAtMs != 0 && now >= state.retryAtMs) {
                state.retryAtMs = 0;
                BeaconBeamTracker.queueDirty(pos);
            } else if (state.retryAtMs != 0) {
                this.nextRetryMs = Math.min(this.nextRetryMs, state.retryAtMs);
            }
        });
        lastOutOfRange = outOfRange[0];
    }

    //Four sides of a square column per segment, textured along Y the way vanilla's beam is
    private static DistantMesh bake(List<BeaconBeamSolver.Segment> segments, int beaconY) {
        var builder = new DistantMeshBuilder();
        for (var segment : segments) {
            float y0 = segment.yBottom() - beaconY;
            float y1 = segment.yTop() - beaconY;
            float r = CORE_RADIUS;
            //V follows world height so the texture scrolls with length rather than stretching
            float v0 = segment.yBottom();
            float v1 = segment.yTop();
            int rgb = segment.colorRgb();
            //Full sky light: the beam is its own light source and must not be shaded by where it sits
            side(builder, -r, y0, -r, r, y1, -r, v0, v1, rgb, 2);
            side(builder, r, y0, r, -r, y1, r, v0, v1, rgb, 3);
            side(builder, r, y0, -r, r, y1, r, v0, v1, rgb, 4);
            side(builder, -r, y0, r, -r, y1, -r, v0, v1, rgb, 5);
        }
        return builder.isEmpty() ? null : builder.build();
    }

    private static void side(DistantMeshBuilder builder, float x0, float y0, float z0,
                             float x1, float y1, float z1, float v0, float v1, int rgb, int face) {
        builder.rawVertex(x0, y0, z0, 0.0f, v0, 15, 15, 1.0f, face, rgb);
        builder.rawVertex(x1, y0, z1, 1.0f, v0, 15, 15, 1.0f, face, rgb);
        builder.rawVertex(x1, y1, z1, 1.0f, v1, 15, 15, 1.0f, face, rgb);
        builder.rawVertex(x0, y1, z0, 0.0f, v1, 15, 15, 1.0f, face, rgb);
    }

    private void discard() {
        for (var state : this.states.values()) {
            if (state.mesh != null) {
                state.mesh.free();
            }
        }
        this.states.clear();
        this.built.clear();
        this.builtStale = false;
        this.nextRetryMs = Long.MAX_VALUE;
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        this.discard();
        BeaconBeamTracker.reset(this.boundEngine);
        this.boundEngine = null;
        this.lastFilterMs = -1;
    }

    //Why a beacon is not drawn splits four ways and only one of them is a bug, so each rejection is
    //counted separately rather than leaving a zero to be guessed at
    public static volatile int lastVanillaOwned;
    public static volatile int lastOutOfRange;
    public static volatile int lastNoSegments;

    public static String debugDump() {
        return "distant beacons: enabled=" + VoxyConfig.CONFIG.distantBeacons
                + " built=" + lastBuiltCount
                + " drawnLastFrame=" + lastFrameBeamsDrawn
                + " handoverAt=" + lastVanillaRange
                + " (skipped: vanillaOwns=" + lastVanillaOwned
                + " outOfLodRange=" + lastOutOfRange
                + " emptyBeam=" + lastNoSegments + ")";
    }

    public static volatile int lastBuiltCount;
    public static volatile int lastVanillaRange;
}
