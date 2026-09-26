package me.cortex.voxy.client.compat.littletiles;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.create.DistantMesh;
import me.cortex.voxy.client.compat.create.DistantMeshBuilder;
import me.cortex.voxy.client.compat.create.DistantShaders;
import me.cortex.voxy.client.compat.create.DistantVisibility;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat;
import me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_BLEND;
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
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL11C.GL_ONE;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

/** LittleTiles 微方块快照的索引、异步烘焙和远景绘制。 */
public final class LittleTilesDistantRenderer implements LodPipelineHooks.Renderer, LodPipelineHooks.TranslucentRenderer {
    private static final int BUCKET_SHIFT = 3;
    private static final int BUCKET_BLOCKS = 16 << BUCKET_SHIFT;
    private static final int MAX_BAKES_IN_FLIGHT = 2;
    private static final int MAX_UPLOADS_PER_TICK = 2;
    private static volatile LittleTilesDistantRenderer active;

    private final ConcurrentLinkedQueue<Update> updates = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BakeResult> completedBakes = new ConcurrentLinkedQueue<>();
    private final Map<Long, Entry> sections = new HashMap<>();
    private final Map<Long, HashSet<Long>> spatialBuckets = new HashMap<>();
    private final ArrayList<Entry> candidates = new ArrayList<>();
    private final ArrayDeque<Long> bakeQueue = new ArrayDeque<>();
    private final HashSet<Long> queued = new HashSet<>();
    private final HashSet<Long> baking = new HashSet<>();
    private final ExecutorService bakeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Voxy LittleTiles baker");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private SectionStorage storage;
    private me.cortex.voxy.common.world.WorldEngine boundEngine;
    private ClientLevel level;
    private boolean storageLoaded;
    private int lastBucketX = Integer.MIN_VALUE, lastBucketZ = Integer.MIN_VALUE;
    private int lastRefreshX = Integer.MIN_VALUE, lastRefreshZ = Integer.MIN_VALUE;
    private int lastCandidateRadius = -1;
    private int candidateGeneration;
    private int worldGeneration;
    private int bakesInFlight;
    private boolean candidatesDirty = true;

    public LittleTilesDistantRenderer() {
        active = this;
    }

    public static void accept(SectionStorage storage, LittleTilesCompat.SectionSnapshot snapshot) {
        LittleTilesDistantRenderer renderer = active;
        if (renderer != null) {
            synchronized (renderer.updates) {
                renderer.updates.add(new Update(storage, snapshot, true));
            }
        }
    }

    public static void checkpointActive() {
        LittleTilesDistantRenderer renderer = active;
        if (renderer != null) renderer.checkpoint();
    }

    // ---- 生命周期与候选区段 -------------------------------------------

    @SubscribeEvent
    public void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            discardCompletedBakes();
            return;
        }
        var engine = WorldIdentifier.ofEngineNullable(mc.level);
        if (engine == null || !engine.isLive()) {
            discardCompletedBakes();
            return;
        }
        if (this.storage != engine.storage) {
            bindStorage(engine, mc.level);
        }
        if (!this.storageLoaded) {
            this.storageLoaded = true;
            for (var snapshot : LittleTilesStore.loadAll(this.storage)) {
                this.updates.add(new Update(this.storage, snapshot, false));
            }
        }

        var camera = mc.gameRenderer.getMainCamera().getPosition();
        double maxDistance = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantLittleTilesMaxChunks);
        drainUpdates(256, camera, maxDistance * maxDistance);
        if (!VoxyConfig.CONFIG.distantLittleTiles) {
            discardCompletedBakes();
            return;
        }
        refreshCandidates(camera.x, camera.y, camera.z, maxDistance);
        uploadCompleted(camera.x, camera.y, camera.z, maxDistance * maxDistance);
        scheduleBakes(camera.x, camera.y, camera.z, maxDistance * maxDistance);
    }

    private void bindStorage(me.cortex.voxy.common.world.WorldEngine engine, ClientLevel level) {
        synchronized (this.updates) {
            boolean sameLevel = this.level == level;
            var carried = new HashMap<Long, LittleTilesCompat.SectionSnapshot>();
            if (sameLevel) {
                for (var entry : this.sections.entrySet()) {
                    carried.put(entry.getKey(), entry.getValue().snapshot);
                }
            }
            var incoming = new ArrayList<Update>();
            // 旧存储可能已关闭；只迁移内存快照，空快照也要保留以免删除的模型复活。
            Update update;
            while ((update = this.updates.poll()) != null) {
                if (update.storage == engine.storage) incoming.add(update);
                else if (sameLevel && update.storage == this.storage) {
                    var snapshot = update.snapshot;
                    carried.put(LittleTilesStore.key(snapshot.sx(), snapshot.sy(), snapshot.sz()), snapshot);
                }
            }
            clearMeshes();
            this.level = level;
            this.storage = engine.storage;
            this.boundEngine = engine;
            this.storageLoaded = true;
            for (var snapshot : LittleTilesStore.loadAll(this.storage)) {
                this.updates.add(new Update(this.storage, snapshot, false));
            }
            for (var snapshot : carried.values()) this.updates.add(new Update(this.storage, snapshot, true));
            this.updates.addAll(incoming);
        }
    }

    @SubscribeEvent
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        checkpoint();
        clearMeshes();
        this.storage = null;
        this.boundEngine = null;
        this.level = null;
        this.storageLoaded = false;
        this.updates.clear();
    }

    // ---- 绘制 ----------------------------------------------------------

    @Override
    public void render(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        renderMeshes(pipeline, viewport, depthFunc, false);
    }

    @Override
    public void renderTranslucent(AbstractRenderPipeline pipeline,
                                  Viewport<?> viewport, int depthFunc) {
        renderMeshes(pipeline, viewport, depthFunc, true);
    }

    private void renderMeshes(AbstractRenderPipeline pipeline,
                              Viewport<?> viewport, int depthFunc, boolean translucent) {
        if (this.sections.isEmpty() || !VoxyConfig.CONFIG.isRenderingEnabled()
                || !VoxyConfig.CONFIG.distantLittleTiles) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (translucent) pipeline.setupAndBindTranslucent(viewport);
        else pipeline.setupAndBindOpaque(viewport);

        double handoffDistanceSq = me.cortex.voxy.client.compat.SectionHandoff.distanceSquared();
        double maxDistance = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantLittleTilesMaxChunks);
        double maxDistanceSq = maxDistance * maxDistance;
        boolean bound = false;
        var transform = new Matrix4f();
        try {
            for (Entry entry : this.candidates) {
                var source = entry.snapshot;
                DistantMesh mesh = translucent ? entry.translucentMesh : entry.opaqueMesh;
                if (mesh == null) continue;
                double ox = source.sx() * 16.0, oy = source.sy() * 16.0, oz = source.sz() * 16.0;
                double dx = ox + 8.0 - viewport.cameraX;
                double dy = oy + 8.0 - viewport.cameraY;
                double dz = oz + 8.0 - viewport.cameraZ;
                double farX = Math.abs(dx) + 8.0, farZ = Math.abs(dz) + 8.0;
                double handoffSq = farX * farX + farZ * farZ;
                if (handoffSq < handoffDistanceSq && me.cortex.voxy.client.compat.SectionHandoff.vanillaOwns(
                        source.sx(), source.sy(), source.sz())) continue;
                if (dx * dx + dy * dy + dz * dz > maxDistanceSq) continue;
                if (!DistantVisibility.isBoxVisible(viewport, ox, oy, oz, ox + 16, oy + 16, oz + 16)) continue;
                if (!bound) {
                    //LittleTiles snapshots already carry per-vertex sky/block light. The uniform-light
                    //variant is for moving structures and was previously fed (1,1), making these meshes
                    //effectively full-bright at night and in shade.
                    (translucent ? DistantShaders.forTranslucentPipeline(pipeline)
                            : DistantShaders.forPipeline(pipeline, false)).bind();
                    DistantShaders.bindTextures();
                    glEnable(GL_DEPTH_TEST);
                    glDepthFunc(depthFunc);
                    glDepthMask(!translucent);
                    glDisable(GL_CULL_FACE);
                    if (translucent) {
                        glEnable(GL_BLEND);
                        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
                    }
                    glEnable(GL_STENCIL_TEST);
                    glStencilFunc(GL_ALWAYS, 3, 0xFF);
                    glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
                    bound = true;
                }
                transform.set(viewport.MVP).translate((float) (ox - viewport.cameraX),
                        (float) (oy - viewport.cameraY), (float) (oz - viewport.cameraZ));
                DistantShaders.uploadTransform(transform);
                if (translucent) mesh.drawTranslucent(viewport, transform);
                else mesh.drawAt(viewport, ox, oy, oz);
            }
            if (bound) {
                glBindVertexArray(0);
                glUseProgram(0);
            }
        } finally {
            if (bound) {
                glStencilFunc(GL_EQUAL, 1, 0x1);
                glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            }
        }
    }

    // ---- CPU 烘焙与材质采样 -------------------------------------------

    private static CpuMeshes bake(LittleTilesCompat.SectionSnapshot snapshot, MaterialSample[] materials) {
        var occupied = new it.unimi.dsi.fastutil.ints.IntOpenHashSet(snapshot.cells().size() * 2);
        for (var cell : snapshot.cells()) occupied.add(cell.coordinate());
        var opaqueBuilder = new DistantMeshBuilder();
        var translucentBuilder = DistantMeshBuilder.translucent();
        try {
            for (var cell : snapshot.cells()) {
                int coordinate = cell.coordinate();
                int x = coordinate & 127, z = (coordinate >>> 7) & 127, y = (coordinate >>> 14) & 127;
                float x0 = x / 8.0f, y0 = y / 8.0f, z0 = z / 8.0f;
                float x1 = x0 + 0.125f, y1 = y0 + 0.125f, z1 = z0 + 0.125f;
                var material = materials[cell.material()];
                var builder = material.translucent ? translucentBuilder : opaqueBuilder;
                int sky = (cell.light() >>> 4) & 15;
                int block = Math.max(cell.light() & 15, material.emission);
                if (x == 0 || !occupied.contains(coordinate - 1)) face(builder, Direction.WEST, x0,y0,z0,x1,y1,z1,material,sky,block);
                if (x == 127 || !occupied.contains(coordinate + 1)) face(builder, Direction.EAST, x0,y0,z0,x1,y1,z1,material,sky,block);
                if (y == 0 || !occupied.contains(coordinate - (1 << 14))) face(builder, Direction.DOWN, x0,y0,z0,x1,y1,z1,material,sky,block);
                if (y == 127 || !occupied.contains(coordinate + (1 << 14))) face(builder, Direction.UP, x0,y0,z0,x1,y1,z1,material,sky,block);
                if (z == 0 || !occupied.contains(coordinate - (1 << 7))) face(builder, Direction.NORTH, x0,y0,z0,x1,y1,z1,material,sky,block);
                if (z == 127 || !occupied.contains(coordinate + (1 << 7))) face(builder, Direction.SOUTH, x0,y0,z0,x1,y1,z1,material,sky,block);
            }
            return new CpuMeshes(opaqueBuilder.assemble(), translucentBuilder.assemble());
        } catch (Throwable t) {
            opaqueBuilder.discard();
            translucentBuilder.discard();
            Logger.error("Baking LittleTiles LOD mesh", t);
            return null;
        }
    }

    private static void face(DistantMeshBuilder b, Direction d, float x0,float y0,float z0,float x1,float y1,float z1,
                             MaterialSample material, int sky, int block) {
        float shade = switch (d) { case DOWN -> 0.5f; case NORTH, SOUTH -> 0.8f; case WEST, EAST -> 0.6f; default -> 1.0f; };
        float[][] p = switch (d) {
            case DOWN -> new float[][]{{x0,y0,z1},{x1,y0,z1},{x1,y0,z0},{x0,y0,z0}};
            case UP -> new float[][]{{x0,y1,z0},{x1,y1,z0},{x1,y1,z1},{x0,y1,z1}};
            case NORTH -> new float[][]{{x1,y0,z0},{x1,y1,z0},{x0,y1,z0},{x0,y0,z0}};
            case SOUTH -> new float[][]{{x0,y0,z1},{x0,y1,z1},{x1,y1,z1},{x1,y0,z1}};
            case WEST -> new float[][]{{x0,y0,z0},{x0,y1,z0},{x0,y1,z1},{x0,y0,z1}};
            case EAST -> new float[][]{{x1,y0,z1},{x1,y1,z1},{x1,y1,z0},{x1,y0,z0}};
        };
        int face = d.get3DDataValue();
        for(int i=0;i<4;i++) b.rawVertex(p[i][0],p[i][1],p[i][2],material.u[face],material.v[face],
                sky,block,shade,face,material.tint,material.alpha,material.customId);
    }

    private static int multiply(int a, int b) {
        return (((((a >> 16) & 255) * ((b >> 16) & 255) / 255) & 255) << 16)
                | (((((a >> 8) & 255) * ((b >> 8) & 255) / 255) & 255) << 8)
                | (((a & 255) * (b & 255) / 255) & 255);
    }

    private static MaterialSample[] prepareMaterials(LittleTilesCompat.SectionSnapshot snapshot) {
        var mc = Minecraft.getInstance();
        var samples = new MaterialSample[snapshot.materials().size()];
        var pos = new BlockPos(snapshot.sx() * 16 + 8, snapshot.sy() * 16 + 8, snapshot.sz() * 16 + 8);
        for (int i = 0; i < samples.length; i++) {
            BlockState state = snapshot.materials().get(i).state();
            var modelData = ModelData.EMPTY;
            var model = mc.getBlockRenderer().getBlockModel(state);
            TextureAtlasSprite fallback = model.getParticleIcon(modelData);
            float[] u = new float[6], v = new float[6];
            boolean translucent = false;
            var random = RandomSource.create(42);
            var layers = model.getRenderTypes(state, random, modelData);
            for (RenderType layer : layers) if (layer == RenderType.translucent()) translucent = true;
            for (Direction direction : Direction.values()) {
                TextureAtlasSprite sprite = fallback;
                for (RenderType layer : layers) {
                    random.setSeed(42);
                    var quads = model.getQuads(state, direction, random, modelData, layer);
                    if (!quads.isEmpty()) {
                        sprite = quads.getFirst().getSprite();
                        break;
                    }
                }
                int face = direction.get3DDataValue();
                u[face] = (sprite.getU0() + sprite.getU1()) * 0.5f;
                v[face] = (sprite.getV0() + sprite.getV1()) * 0.5f;
            }
            int color = snapshot.materials().get(i).color();
            int alpha = (color >>> 24) & 0xFF;
            int tint = color & 0xFFFFFF;
            int blockTint = mc.getBlockColors().getColor(state, mc.level, pos, 0);
            if (blockTint != -1) tint = multiply(tint, blockTint);
            samples[i] = new MaterialSample(u, v, tint, alpha, translucent || alpha < 255,
                    Math.clamp(state.getLightEmission(mc.level, pos), 0, 15), shaderMaterialId(state));
        }
        return samples;
    }

    private static int shaderMaterialId(BlockState state) {
        try {
            if (!IrisUtil.IRIS_INSTALLED) return 0;
            var ids = net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getBlockStateIds();
            return ids != null && ids.containsKey(state) ? ids.getInt(state) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ---- 候选索引、异步队列与资源回收 -------------------------------

    private void refreshCandidates(double cameraX, double cameraY, double cameraZ, double maxDistance) {
        int bucketX = Math.floorDiv((int) Math.floor(cameraX), BUCKET_BLOCKS);
        int bucketZ = Math.floorDiv((int) Math.floor(cameraZ), BUCKET_BLOCKS);
        int refreshX = (int) Math.floor(cameraX);
        int refreshZ = (int) Math.floor(cameraZ);
        int radius = Math.max(1, (int) Math.ceil(maxDistance / BUCKET_BLOCKS) + 1);
        if (!this.candidatesDirty && bucketX == this.lastBucketX && bucketZ == this.lastBucketZ
                && radius == this.lastCandidateRadius && Math.abs(refreshX - this.lastRefreshX) < 32
                && Math.abs(refreshZ - this.lastRefreshZ) < 32) return;

        int generation = ++this.candidateGeneration;
        var oldCandidates = new ArrayList<>(this.candidates);
        this.candidates.clear();
        double maxDistanceSq = maxDistance * maxDistance;
        for (int z = bucketZ - radius; z <= bucketZ + radius; z++) {
            for (int x = bucketX - radius; x <= bucketX + radius; x++) {
                var keys = this.spatialBuckets.get(bucketKey(x, z));
                if (keys == null) continue;
                for (long key : keys) {
                    Entry entry = this.sections.get(key);
                    if (entry == null || distanceSq(entry.snapshot, cameraX, cameraY, cameraZ) > maxDistanceSq) continue;
                    entry.candidateGeneration = generation;
                    this.candidates.add(entry);
                    if (!entry.hasMesh() && !this.baking.contains(key) && this.queued.add(key)) this.bakeQueue.add(key);
                }
            }
        }

        double farSq = (maxDistance + BUCKET_BLOCKS * 2.0) * (maxDistance + BUCKET_BLOCKS * 2.0);
        for (Entry entry : oldCandidates) {
            if (entry.candidateGeneration != generation && entry.hasMesh()
                    && distanceSq(entry.snapshot, cameraX, cameraY, cameraZ) > farSq) {
                entry.closeMeshes();
            }
        }
        this.lastBucketX = bucketX;
        this.lastBucketZ = bucketZ;
        this.lastRefreshX = refreshX;
        this.lastRefreshZ = refreshZ;
        this.lastCandidateRadius = radius;
        this.candidatesDirty = false;
    }

    private void scheduleBakes(double cameraX, double cameraY, double cameraZ, double maxDistanceSq) {
        while (this.bakesInFlight < MAX_BAKES_IN_FLIGHT && !this.bakeQueue.isEmpty()) {
            long key = this.bakeQueue.removeFirst();
            this.queued.remove(key);
            Entry entry = this.sections.get(key);
            if (entry == null || entry.hasMesh() || this.baking.contains(key)) continue;
            if (distanceSq(entry.snapshot, cameraX, cameraY, cameraZ) > maxDistanceSq) continue;
            MaterialSample[] materials;
            try {
                materials = prepareMaterials(entry.snapshot);
            } catch (Throwable t) {
                Logger.error("Preparing LittleTiles LOD materials", t);
                continue;
            }
            int generation = this.worldGeneration;
            var snapshot = entry.snapshot;
            this.baking.add(key);
            this.bakesInFlight++;
            this.bakeExecutor.execute(() -> {
                CpuMeshes mesh = null;
                try {
                    mesh = bake(snapshot, materials);
                } catch (Throwable t) {
                    Logger.error("Baking LittleTiles LOD mesh", t);
                }
                this.completedBakes.add(new BakeResult(key, generation, snapshot, mesh));
            });
        }
    }

    private void uploadCompleted(double cameraX, double cameraY, double cameraZ, double maxDistanceSq) {
        int uploaded = 0;
        BakeResult result;
        while (uploaded < MAX_UPLOADS_PER_TICK && (result = this.completedBakes.poll()) != null) {
            this.bakesInFlight = Math.max(0, this.bakesInFlight - 1);
            this.baking.remove(result.key);
            Entry entry = this.sections.get(result.key);
            if (result.generation != this.worldGeneration || entry == null || entry.snapshot != result.snapshot
                    || distanceSq(result.snapshot, cameraX, cameraY, cameraZ) > maxDistanceSq) {
                if (result.mesh != null) result.mesh.free();
                if (entry != null && !entry.hasMesh()) this.candidatesDirty = true;
                continue;
            }
            entry.opaqueMesh = DistantMeshBuilder.upload(result.mesh == null ? null : result.mesh.opaque);
            entry.translucentMesh = DistantMeshBuilder.upload(result.mesh == null ? null : result.mesh.translucent);
            uploaded++;
        }
    }

    private void discardCompletedBakes() {
        BakeResult result;
        while ((result = this.completedBakes.poll()) != null) {
            this.bakesInFlight = Math.max(0, this.bakesInFlight - 1);
            this.baking.remove(result.key);
            if (result.mesh != null) result.mesh.free();
        }
    }

    private void addToSpatialIndex(long key, Entry entry) {
        long bucket = bucketKey(entry.snapshot.sx() >> BUCKET_SHIFT, entry.snapshot.sz() >> BUCKET_SHIFT);
        entry.bucket = bucket;
        this.spatialBuckets.computeIfAbsent(bucket, ignored -> new HashSet<>()).add(key);
        this.candidatesDirty = true;
    }

    private void removeFromSpatialIndex(long key, Entry entry) {
        var keys = this.spatialBuckets.get(entry.bucket);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) this.spatialBuckets.remove(entry.bucket);
        }
        this.candidatesDirty = true;
    }

    private static long bucketKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private void clearMeshes() {
        for (var entry : this.sections.values()) entry.closeMeshes();
        this.sections.clear();
        this.spatialBuckets.clear();
        this.candidates.clear();
        this.bakeQueue.clear();
        this.queued.clear();
        this.worldGeneration++;
        this.lastBucketX = this.lastBucketZ = Integer.MIN_VALUE;
        this.lastRefreshX = this.lastRefreshZ = Integer.MIN_VALUE;
        this.lastCandidateRadius = -1;
        this.candidatesDirty = true;
        discardCompletedBakes();
    }

    private void checkpoint() {
        if (this.storage == null || this.boundEngine == null || !this.boundEngine.isLive()) return;
        drainUpdates(Integer.MAX_VALUE, null, 0.0);
        for (var entry : this.sections.values()) {
            LittleTilesStore.save(this.storage, entry.snapshot);
        }
        try {
            this.storage.flush();
        } catch (Throwable t) {
            Logger.error("Flushing LittleTiles LOD snapshots", t);
        }
    }

    private void drainUpdates(int limit, Vec3 camera, double maxDistanceSq) {
        Update update;
        int applied = 0;
        while (applied < limit && (update = this.updates.poll()) != null) {
            if (update.storage != this.storage) continue;
            applied++;
            var snapshot = update.snapshot;
            long key = LittleTilesStore.key(snapshot.sx(), snapshot.sy(), snapshot.sz());
            Entry old = this.sections.remove(key);
            if (old != null) {
                removeFromSpatialIndex(key, old);
                old.closeMeshes();
            }
            this.queued.remove(key);
            if (update.persist) LittleTilesStore.save(this.storage, snapshot);
            if (snapshot.cells().isEmpty()) continue;
            Entry entry = new Entry(snapshot, null);
            this.sections.put(key, entry);
            addToSpatialIndex(key, entry);
            if (camera != null && distanceSq(snapshot, camera.x(), camera.y(), camera.z()) <= maxDistanceSq
                    && !this.baking.contains(key) && this.queued.add(key)) this.bakeQueue.add(key);
        }
    }

    private static double distanceSq(LittleTilesCompat.SectionSnapshot snapshot, double x, double y, double z) {
        double dx = snapshot.sx() * 16.0 + 8.0 - x;
        double dy = snapshot.sy() * 16.0 + 8.0 - y;
        double dz = snapshot.sz() * 16.0 + 8.0 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private record Update(SectionStorage storage, LittleTilesCompat.SectionSnapshot snapshot, boolean persist) {}
    private record MaterialSample(float[] u, float[] v, int tint, int alpha, boolean translucent,
                                  int emission, int customId) {}
    private record CpuMeshes(DistantMeshBuilder.CpuMesh opaque, DistantMeshBuilder.CpuMesh translucent) {
        void free() {
            if (opaque != null) opaque.free();
            if (translucent != null) translucent.free();
        }
    }
    private record BakeResult(long key, int generation, LittleTilesCompat.SectionSnapshot snapshot,
                              CpuMeshes mesh) {}
    private static final class Entry {
        final LittleTilesCompat.SectionSnapshot snapshot;
        DistantMesh opaqueMesh;
        DistantMesh translucentMesh;
        long bucket;
        int candidateGeneration;
        Entry(LittleTilesCompat.SectionSnapshot snapshot, DistantMesh mesh) {
            this.snapshot = snapshot;
            this.opaqueMesh = mesh;
        }
        boolean hasMesh() { return opaqueMesh != null || translucentMesh != null; }
        void closeMeshes() {
            if (opaqueMesh != null) opaqueMesh.free();
            if (translucentMesh != null) translucentMesh.free();
            opaqueMesh = null;
            translucentMesh = null;
        }
    }
}
