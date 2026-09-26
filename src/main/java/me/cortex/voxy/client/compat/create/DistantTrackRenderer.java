package me.cortex.voxy.client.compat.create;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.LodBoundaryFade;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL20C.glUseProgram;

public final class DistantTrackRenderer implements LodPipelineHooks.Renderer {
    private static final long RECHECK_INTERVAL_MS = 5000;
    private static final long COMPILED_GRACE_MS = 400;

    private static final class MeshUnit {
        final DistantMesh mesh;
        final double ox, oy, oz;
        final BlockPos gate;
        final boolean bezier;
        //Last time isSectionCompiled was true, for the straight-track handover hysteresis
        long lastCompiledMs;

        MeshUnit(DistantMesh mesh, double ox, double oy, double oz, BlockPos gate, boolean bezier) {
            this.mesh = mesh;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.gate = gate;
            this.bezier = bezier;
        }

        void close() {
            this.mesh.free();
        }
    }

    private final List<MeshUnit> units = new ArrayList<>();
    private long lastCheckMs;
    private long lastChecksum;
    private ResourceKey<Level> bakedDimension;

    //Diagnostics for /voxy debug trains
    public static volatile int tileCount;
    public static volatile int lastFrameTilesDrawn;

    @Override
    public void render(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        pipeline.setupAndBindOpaque(viewport);
        //renderCommon only reads viewProjection (copies via transform.set), never mutates it
        this.renderCommon(pipeline, viewport, viewport.MVP,
                viewport.cameraX, viewport.cameraY, viewport.cameraZ, depthFunc);
    }

    private void renderCommon(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, Matrix4f viewProjection, double camX, double camY, double camZ, int depthFunc) {
        lastFrameTilesDrawn = 0;
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantTracks) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (this.units.isEmpty()) {
            return;
        }

        double maxDist = cfg.createRenderDistance(cfg.distantTrackMaxChunks) + 64;
        double maxDistSq = maxDist * maxDist;
        var boundary = LodBoundaryFade.getDistances();
        double handoffDist = boundary.enabled()
                ? boundary.fadeStart()
                : mc.options.getEffectiveRenderDistance() * 16.0;
        double handoffDistSq = handoffDist * handoffDist;

        DistantShaders.forPipeline(pipeline, false).bind();
        DistantShaders.bindTextures();
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(depthFunc);
        glDepthMask(true);
        glEnable(GL_STENCIL_TEST);
        glStencilFunc(GL_ALWAYS, 3, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        int drawn = 0;
        long nowMs = System.currentTimeMillis();
        boolean occlusionDebug = DistantOcclusionDebug.isActive();
        var transform = new Matrix4f();
        try {
            for (MeshUnit unit : this.units) {
                double gdx = unit.gate.getX() + 0.5 - camX;
                double gdy = unit.gate.getY() + 0.5 - camY;
                double gdz = unit.gate.getZ() + 0.5 - camZ;
                double distSq = gdx * gdx + gdy * gdy + gdz * gdz;
                if (distSq > maxDistSq) {
                    continue;
                }
                var m = unit.mesh;
                boolean vanillaDraws = farthestDistanceSquared(unit, m, camX, camY, camZ) < handoffDistSq;
                if (occlusionDebug) {
                    boolean rawCompiled = mc.levelRenderer.isSectionCompiled(unit.gate);
                    if (rawCompiled) {
                        unit.lastCompiledMs = nowMs;
                    }
                    boolean compiled = nowMs - unit.lastCompiledMs < COMPILED_GRACE_MS;
                    DistantOcclusionDebug.logUnit(unit.ox, unit.oy, unit.oz, unit.bezier,
                            Math.sqrt(distSq), rawCompiled, compiled && !rawCompiled, vanillaDraws);
                }
                if (vanillaDraws) {
                    continue;
                }
                if (viewport != null && !DistantVisibility.isBoxVisible(viewport,
                        unit.ox + m.minX, unit.oy + m.minY, unit.oz + m.minZ,
                        unit.ox + m.maxX, unit.oy + m.maxY, unit.oz + m.maxZ)) {
                    continue;
                }
                transform.set(viewProjection).translate(
                        (float) (unit.ox - camX),
                        (float) (unit.oy - camY),
                        (float) (unit.oz - camZ));
                DistantShaders.uploadTransform(transform);
                unit.mesh.drawAt(viewport, unit.ox, unit.oy, unit.oz);
                drawn++;
            }
        } finally {
            glBindVertexArray(0);
            glUseProgram(0);
            //Pipeline contract state (set by initDepthStencil, expected by every later pass)
            glStencilFunc(GL_EQUAL, 1, 0x1);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            lastFrameTilesDrawn = drawn;
        }
    }

    private static double farthestDistanceSquared(MeshUnit unit, DistantMesh mesh,
                                                  double camX, double camY, double camZ) {
        double dx = Math.max(Math.abs(unit.ox + mesh.minX - camX), Math.abs(unit.ox + mesh.maxX - camX));
        double dy = Math.max(Math.abs(unit.oy + mesh.minY - camY), Math.abs(unit.oy + mesh.maxY - camY));
        double dz = Math.max(Math.abs(unit.oz + mesh.minZ - camZ), Math.abs(unit.oz + mesh.maxZ - camZ));
        return dx * dx + dy * dy + dz * dz;
    }

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        this.clearAll();
    }

    //Baking runs between frames, not inside the render hooks, where mid-pipeline GL state would
    //otherwise interfere with buffer setup.
    @SubscribeEvent
    public void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        var cfg = VoxyConfig.CONFIG;
        if (mc.level == null || !cfg.isRenderingEnabled() || !cfg.distantTracks) {
            return;
        }
        this.maybeRebake(mc);
    }

    private void clearAll() {
        LodPipelineHooks.distantTrackMeshesReady = false;
        for (MeshUnit unit : this.units) {
            unit.close();
        }
        this.units.clear();
        tileCount = 0;
        this.bakedDimension = null;
        this.lastChecksum = 0;
        this.lastCheckMs = 0;
        this.graphStates.clear();
        this.lastVersion = 0;
        this.backstopArmed = false;
    }

    //Per-graph checksums from the last check, and whether each graph reaches into the baked dimension.
    //Relevance is only recomputed when that graph's checksum moved - the node scan is what checksums
    //exist to avoid.
    private final java.util.Map<java.util.UUID, long[]> graphStates = new java.util.HashMap<>();
    private long lastVersion;
    private long versionQuietSinceMs;
    private boolean backstopArmed;
    private static final long VERSION_QUIET_MS = 15000;

    private void maybeRebake(Minecraft mc) {
        var dimension = mc.level.dimension();
        boolean dimensionChanged = !dimension.equals(this.bakedDimension);
        long now = System.currentTimeMillis();
        if (!dimensionChanged && now - this.lastCheckMs < RECHECK_INTERVAL_MS) {
            return;
        }
        this.lastCheckMs = now;

        boolean relevantChanged = dimensionChanged;
        try {
            var seen = new java.util.HashSet<java.util.UUID>();
            for (TrackGraph graph : CreateClient.RAILWAYS.trackNetworks.values()) {
                long checksum = graph.getChecksum();
                var id = graph.id;
                seen.add(id);
                long[] state = this.graphStates.get(id);
                if (state == null || state[0] != checksum || dimensionChanged) {
                    boolean relevant = false;
                    for (TrackNodeLocation location : new ArrayList<>(graph.getNodes())) {
                        if (dimension.equals(location.getDimension())) {
                            relevant = true;
                            break;
                        }
                    }
                    boolean wasRelevant = state != null && state[1] != 0;
                    if (relevant || wasRelevant) {
                        relevantChanged = true;
                    }
                    this.graphStates.put(id, new long[]{checksum, relevant ? 1 : 0});
                }
            }
            var removed = this.graphStates.keySet().iterator();
            while (removed.hasNext()) {
                var id = removed.next();
                if (!seen.contains(id)) {
                    if (this.graphStates.get(id)[1] != 0) {
                        relevantChanged = true;
                    }
                    removed.remove();
                }
            }
        } catch (Throwable e) {
            return; //graph mid-sync; retry next interval
        }

        long version = CreateClient.RAILWAYS.version;
        if (version != this.lastVersion) {
            this.lastVersion = version;
            this.versionQuietSinceMs = now;
            if (!relevantChanged) {
                this.backstopArmed = true;
            }
        } else if (this.backstopArmed && now - this.versionQuietSinceMs >= VERSION_QUIET_MS) {
            this.backstopArmed = false;
            relevantChanged = true;
        }

        if (!relevantChanged) {
            return;
        }
        this.backstopArmed = false;
        this.bakedDimension = dimension;
        try {
            this.rebake(mc, dimension);
        } catch (Throwable e) {
            Logger.error("Distant track bake failed", e);
        }
    }

    private void rebake(Minecraft mc, ResourceKey<Level> dimension) {
        LodPipelineHooks.distantTrackMeshesReady = false;
        for (MeshUnit unit : this.units) {
            unit.close();
        }
        this.units.clear();

        //Global block map: edges meeting at a node would otherwise each bake their own differently
        //shaped model into the shared corner block (visible as a stray extra track piece)
        Map<BlockPos, BlockState> straightBlocks = new LinkedHashMap<>();
        List<Turn> turns = new ArrayList<>();
        List<PendingStraight> straights = new ArrayList<>();

        for (TrackGraph graph : new ArrayList<>(CreateClient.RAILWAYS.trackNetworks.values())) {
            for (TrackNodeLocation location : new ArrayList<>(graph.getNodes())) {
                if (!dimension.equals(location.getDimension())) {
                    continue;
                }
                TrackNode node = graph.locateNode(location);
                if (node == null) {
                    continue;
                }
                for (var connection : graph.getConnectionsFrom(node).entrySet()) {
                    TrackNode other = connection.getKey();
                    if (!dimension.equals(other.getLocation().getDimension())) {
                        continue;
                    }
                    TrackEdge edge = connection.getValue();
                    if (edge.isTurn()) {
                        //Both directions are stored; only the primary connection bakes
                        var bc = edge.getTurn();
                        if (bc != null && bc.isPrimary()) {
                            turns.add(new Turn(graph, edge, bc));
                        }
                    } else if (node.getNetId() < other.getNetId()) {
                        straights.add(new PendingStraight(node, other, edge));
                    }
                }
            }
        }

        for (Turn turn : turns) {
            var bc = turn.bc();
            putAnchorBlock(mc, bc.bePositions.getFirst(), straightBlocks);
            putAnchorBlock(mc, bc.bePositions.getSecond(), straightBlocks);
        }

        //Straight edges must not drop a straight-shaped block into the first bend of a bezier -
        //exclude the blocks the curve passes through near its endpoints
        var turnExclusion = new java.util.HashSet<BlockPos>();
        for (Turn turn : turns) {
            collectTurnExclusion(turn, turnExclusion);
        }
        for (PendingStraight straight : straights) {
            collectStraight(straight.node(), straight.other(), straight.edge(), straightBlocks, turnExclusion);
        }

        Map<Long, List<StraightBlock>> straightsBySection = new HashMap<>();
        for (var entry : straightBlocks.entrySet()) {
            BlockPos pos = entry.getKey();
            long key = BlockPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
            straightsBySection.computeIfAbsent(key, k -> new ArrayList<>()).add(new StraightBlock(pos, entry.getValue()));
        }
        for (var entry : straightsBySection.entrySet()) {
            this.bakeSection(mc, entry.getKey(), entry.getValue());
        }
        for (Turn turn : turns) {
            this.bakeTurn(mc, turn.bc());
        }
        tileCount = this.units.size();
        LodPipelineHooks.distantTrackMeshesReady = !this.units.isEmpty();
    }

    private record Turn(TrackGraph graph, TrackEdge edge, com.simibubi.create.content.trains.track.BezierConnection bc) {}

    private record PendingStraight(TrackNode node, TrackNode other, TrackEdge edge) {}

    private record StraightBlock(BlockPos pos, BlockState state) {}

    private static void putAnchorBlock(Minecraft mc, BlockPos pos, Map<BlockPos, BlockState> out) {
        if (mc.level == null) {
            return;
        }
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() instanceof TrackBlock) {
            out.put(pos, state);
        }
    }

    //Blocks the curve passes through just inside each endpoint; straight edges skip these so a
    //straight-shaped block never overlaps the first bend. Kept tight (1.2 blocks) - wider reach
    //swallows short connector straights entirely.
    private static void collectTurnExclusion(Turn turn, java.util.Set<BlockPos> out) {
        double length = turn.edge().getLength();
        if (length < 0.5) {
            return;
        }
        double reach = Math.min(1.2, length / 2);
        for (double d = 0.4; d <= reach; d += 0.4) {
            double t = d / length;
            out.add(BlockPos.containing(turn.edge().getPosition(turn.graph(), t)));
            out.add(BlockPos.containing(turn.edge().getPosition(turn.graph(), 1.0 - t)));
        }
    }

    private static void collectStraight(TrackNode node, TrackNode other, TrackEdge edge, Map<BlockPos, BlockState> out, java.util.Set<BlockPos> exclusion) {
        Vec3 from = node.getLocation().getLocation();
        Vec3 to = other.getLocation().getLocation();
        Vec3 diff = to.subtract(from);
        double length = diff.length();
        if (length < 0.05) {
            return;
        }
        BlockState state;
        try {
            state = edge.getTrackMaterial().getBlock().defaultBlockState().setValue(TrackBlock.SHAPE, pickShape(diff));
        } catch (Throwable e) {
            return; //material without a block or a foreign shape set; skip this edge
        }
        //Dense sampling with dedup walks every block the line passes through, diagonals included;
        //putIfAbsent keeps corner blocks shared between edges from stacking two shapes
        var visited = new LinkedHashSet<BlockPos>();
        int steps = Math.max(1, (int) Math.ceil(length / 0.4));
        for (int i = 0; i <= steps; i++) {
            visited.add(BlockPos.containing(from.add(diff.scale((double) i / steps))));
        }
        //Fallback: a short connector straight fully inside two exclusion zones must still bake,
        //otherwise the span goes missing entirely
        boolean anyPlaced = false;
        for (BlockPos pos : visited) {
            if (!exclusion.contains(pos)) {
                out.putIfAbsent(pos, state);
                anyPlaced = true;
            }
        }
        if (!anyPlaced) {
            for (BlockPos pos : visited) {
                out.putIfAbsent(pos, state);
            }
        }
    }

    private static TrackShape pickShape(Vec3 diff) {
        if (Math.abs(diff.y) > 0.25) {
            Vec3 ascent = diff.y > 0 ? diff : diff.scale(-1);
            if (Math.abs(ascent.x) > Math.abs(ascent.z)) {
                return ascent.x > 0 ? TrackShape.AE : TrackShape.AW;
            }
            return ascent.z > 0 ? TrackShape.AS : TrackShape.AN;
        }
        double ax = Math.abs(diff.x), az = Math.abs(diff.z);
        if (ax < az * 0.1) {
            return TrackShape.ZO;
        }
        if (az < ax * 0.1) {
            return TrackShape.XO;
        }
        return (diff.x > 0) == (diff.z > 0) ? TrackShape.PD : TrackShape.ND;
    }

    private void bakeSection(Minecraft mc, long sectionKey, List<StraightBlock> blocks) {
        int sx = BlockPos.getX(sectionKey) << 4, sy = BlockPos.getY(sectionKey) << 4, sz = BlockPos.getZ(sectionKey) << 4;
        var dispatcher = mc.getBlockRenderer();
        var builder = new DistantMeshBuilder();
        for (StraightBlock entry : blocks) {
            //Per-block light from voxy's voxel store (the air right above the track) - a section
            //centre sample often lands inside solid ground and darkens the whole span
            int light = DistantLightSampler.sample(mc.level, entry.pos().getX(), entry.pos().getY(), entry.pos().getZ());
            try {
                builder.blockModel(entry.state(), dispatcher.getBlockModel(entry.state()),
                        entry.pos().getX() - sx, entry.pos().getY() - sy, entry.pos().getZ() - sz,
                        DistantLightSampler.sky(light), DistantLightSampler.block(light), null);
            } catch (Throwable ignored) {
            }
        }
        var mesh = builder.build();
        if (mesh != null) {
            BlockPos gate = new BlockPos(sx + 8, sy + 8, sz + 8);
            this.units.add(new MeshUnit(mesh, sx, sy, sz, gate, false));
        }
    }

    //Smooth bezier turns: replay the segment transforms with the material's partial models. The
    //transforms and BakedQuads are immutable assets; the emission path is entirely ours.
    private void bakeTurn(Minecraft mc, com.simibubi.create.content.trains.track.BezierConnection bc) {
        BlockPos anchor = bc.bePositions.getFirst();
        //Sample both endpoints and lerp along the span - curve segments then shade plausibly even
        //when one end sits in a tunnel mouth
        int lightA = DistantLightSampler.sample(mc.level, anchor.getX(), anchor.getY(), anchor.getZ());
        BlockPos far = bc.bePositions.getSecond();
        int lightB = DistantLightSampler.sample(mc.level, far.getX(), far.getY(), far.getZ());
        var builder = new DistantMeshBuilder();
        try {
            var segments = bc.getBakedSegments();
            var holder = bc.getMaterial().getModelHolder();
            var tie = holder.tie().get();
            var left = holder.leftSegment().get();
            var right = holder.rightSegment().get();
            for (int i = 1; i < segments.length; i++) {
                float frac = (float) i / (segments.length - 1);
                int sky = java.lang.Math.round(net.minecraft.util.Mth.lerp(frac, DistantLightSampler.sky(lightA), DistantLightSampler.sky(lightB)));
                int block = java.lang.Math.round(net.minecraft.util.Mth.lerp(frac, DistantLightSampler.block(lightA), DistantLightSampler.block(lightB)));
                builder.transformedModel(tie, segments.tieTransform[i].pose(), sky, block);
                builder.transformedModel(left, segments.railTransforms[i].getFirst().pose(), sky, block);
                builder.transformedModel(right, segments.railTransforms[i].getSecond().pose(), sky, block);
            }
            if (bc.hasGirder) {
                var girders = bc.getBakedGirders();
                var middle = AllPartialModels.GIRDER_SEGMENT_MIDDLE.get();
                var top = AllPartialModels.GIRDER_SEGMENT_TOP.get();
                var bottom = AllPartialModels.GIRDER_SEGMENT_BOTTOM.get();
                for (int i = 1; i < girders.length; i++) {
                    float frac = (float) i / (girders.length - 1);
                    int sky = java.lang.Math.round(net.minecraft.util.Mth.lerp(frac, DistantLightSampler.sky(lightA), DistantLightSampler.sky(lightB)));
                    int block = java.lang.Math.round(net.minecraft.util.Mth.lerp(frac, DistantLightSampler.block(lightA), DistantLightSampler.block(lightB)));
                    for (boolean first : new boolean[]{true, false}) {
                        builder.transformedModel(middle, girders.beams[i].get(first).pose(), sky, block);
                        for (boolean isTop : new boolean[]{true, false}) {
                            builder.transformedModel(isTop ? top : bottom, girders.beamCaps[i].get(isTop).get(first).pose(), sky, block);
                        }
                    }
                }
            }
        } catch (Throwable e) {
            builder.discard();
            Logger.error("Distant track bezier bake failed at " + anchor, e);
            return;
        }
        var mesh = builder.build();
        if (mesh != null) {
            this.units.add(new MeshUnit(mesh, anchor.getX(), anchor.getY(), anchor.getZ(), anchor, true));
        }
    }
}
