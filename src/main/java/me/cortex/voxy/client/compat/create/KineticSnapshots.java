package me.cortex.voxy.client.compat.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

public final class KineticSnapshots {
    private KineticSnapshots() {}

    private static final int CAPTURES_PER_TICK = 64;
    private static final ThreadLocal<BlockPos.MutableBlockPos> LIGHT_SAMPLE =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static int captureBudget;
    private static long maintenanceTick;
    private static final Map<KineticBlockEntity, FailedCapture> FAILED_CAPTURES =
            new com.google.common.collect.MapMaker().weakKeys().makeMap();

    private record FailedCapture(net.minecraft.world.level.block.state.BlockState state,
                                 int light, long retryAfterTick) {}
    private static boolean countsDirty;
    private static boolean meshesDirty;
    private static boolean gpuBytesDirty;
    private static long residentGpuBytes;
    private static final int REBAKES_PER_TICK = 4;
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet PRIORITY_REBAKE =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    record Snap(net.minecraft.world.level.block.state.BlockState state,
                net.minecraft.core.Direction.Axis axis, float angleRad, int sky, int block,
                boolean bakeJson, net.minecraft.core.Direction shaftHalfFacing,
                net.minecraft.core.Direction bearingFacing, float bearingTopAngleRad, boolean woodenTop,
                float[][] chains, float[] bnbChain, int[] bnbLights, float[] generic, boolean gantryCarriage) {

        boolean hollow() {
            return !this.bakeJson && this.shaftHalfFacing == null && this.bearingFacing == null
                    && this.chains == null && this.bnbChain == null && this.generic == null
                    && !this.gantryCarriage;
        }
    }

    private static final boolean BNB_LOADED = net.neoforged.fml.ModList.get().isLoaded("bits_n_bobs");

    private static volatile Thread captureThread = null;

    public static boolean isCapturingOnThisThread() {
        return captureThread == Thread.currentThread();
    }

    static final class Bucket {
        final Map<BlockPos, Snap> geoms = new HashMap<>();
        final java.util.Set<BlockPos> invalidated = new java.util.HashSet<>();
        DistantMesh mesh;
        java.util.Set<BlockPos> meshPositions = java.util.Set.of();
        boolean dirty;
        long retryAfterTick;

        void markDirty() {
            this.dirty = true;
            meshesDirty = true;
        }

        void close() {
            if (this.mesh != null) {
                gpuBytesDirty = true;
                this.mesh.free();
                this.mesh = null;
            }
            this.meshPositions = java.util.Set.of();
            countsDirty = true;
        }
    }

    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Bucket> SECTIONS =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final PositionQueue CAPTURE_QUEUE = new PositionQueue();
    private static final PositionQueue REMOVE_QUEUE = new PositionQueue();

    // 只排队尚未处理的位置；重复通知不改变首次入队顺序。
    private static final class PositionQueue {
        private final java.util.LinkedHashSet<BlockPos> positions = new java.util.LinkedHashSet<>();

        synchronized void add(BlockPos pos) {
            if (!this.positions.contains(pos)) this.positions.add(pos.immutable());
        }

        synchronized BlockPos poll() {
            return this.positions.isEmpty() ? null : this.positions.removeFirst();
        }

        synchronized int size() {
            return this.positions.size();
        }

        synchronized void clear() {
            this.positions.clear();
        }
    }
    private static ResourceLocation dim;
    private static final java.util.Set<BlockPos> BEARING_POSITIONS = new java.util.HashSet<>();

    //Diagnostics for /voxy debug trains + /voxy debug kinetics
    public static volatile int sectionCount;
    public static volatile int snapshotCount;
    private static final java.util.ArrayDeque<String> RECENT_CAPTURES = new java.util.ArrayDeque<>();

    private static void logCapture(String line) {
        synchronized (RECENT_CAPTURES) {
            if (RECENT_CAPTURES.size() >= 10) {
                RECENT_CAPTURES.removeFirst();
            }
            RECENT_CAPTURES.addLast(line);
        }
    }

    private static int hollowIn(Bucket bucket) {
        int n = 0;
        for (Snap s : bucket.geoms.values()) {
            if (s.hollow()) n++;
        }
        return n;
    }

    public static String debugDump(double camX, double camY, double camZ) {
        var sb = new StringBuilder();
        sb.append("sections=").append(SECTIONS.size())
                .append(" snapshots=").append(snapshotCount)
                .append(" capQ=").append(CAPTURE_QUEUE.size())
                .append(" remQ=").append(REMOVE_QUEUE.size())
                .append(" sweepCursor=").append(sweepCursor)
                .append(" dim=").append(dim);
        sb.append("\nrecent captures:");
        synchronized (RECENT_CAPTURES) {
            if (RECENT_CAPTURES.isEmpty()) {
                sb.append(" <none>");
            }
            for (String line : RECENT_CAPTURES) {
                sb.append("\n  ").append(line);
            }
        }
        sb.append("\nbuckets within 96 blocks:");
        int shown = 0;
        for (var entry : SECTIONS.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            double ox = (BlockPos.getX(key) << 4) + 8, oy = (BlockPos.getY(key) << 4) + 8, oz = (BlockPos.getZ(key) << 4) + 8;
            double dx = ox - camX, dy = oy - camY, dz = oz - camZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > 96 && shown > 0) {
                continue;
            }
            var bucket = entry.getValue();
            sb.append("\n  section(").append(BlockPos.getX(key) << 4).append(',')
                    .append(BlockPos.getY(key) << 4).append(',').append(BlockPos.getZ(key) << 4)
                    .append(") dist=").append((int) dist)
                    .append(" entries=").append(bucket.geoms.size() - hollowIn(bucket))
                    .append(hollowIn(bucket) > 0 ? "+" + hollowIn(bucket) + "skip" : "")
                    .append(" mesh=").append(bucket.mesh != null ? "yes" : "NULL")
                    .append(" dirty=").append(bucket.dirty);
            if (++shown >= 12) {
                sb.append("\n  ... (").append(SECTIONS.size() - shown).append(" more)");
                break;
            }
        }
        return sb.toString();
    }

    public static void queueCapture(BlockPos pos) {
        if (me.cortex.voxy.client.compat.ShipBorne.isShipBorne(pos)) {
            return;
        }
        CAPTURE_QUEUE.add(pos);
    }

    //Called on return to the live path or when a fresh visual spawns for the position
    public static void queueRemove(BlockPos pos) {
        REMOVE_QUEUE.add(pos);
    }

    public static void onDataUpdated(net.minecraft.world.level.block.entity.BlockEntity be) {
        var mc = Minecraft.getInstance();
        if (!(be instanceof KineticBlockEntity kinetic) || be.getLevel() != mc.level || isCapturingOnThisThread()) return;
        if (!mc.isSameThread()) {
            mc.execute(() -> onDataUpdated(be));
            return;
        }
        FAILED_CAPTURES.remove(kinetic);
        BlockPos pos = be.getBlockPos();
        Bucket bucket = SECTIONS.get(sectionKey(pos));
        if (bucket != null && bucket.geoms.containsKey(pos) && bucket.invalidated.add(pos.immutable())) {
            queueCapture(pos);
        }
    }

    private static final java.util.Set<BlockPos> ANCHOR_RECAPTURED = new java.util.HashSet<>();
    private static boolean inManagerRecapture;

    public static void recaptureAt(BlockPos pos) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || me.cortex.voxy.client.compat.ShipBorne.isShipBorne(pos)) {
            return;
        }
        if (!(loadedBlockEntity(mc.level, pos) instanceof KineticBlockEntity kbe)) {
            return;
        }
        //Manager-driven: don't post the anchor back, or next tick's consume would re-refresh the
        //structure one tick after this capture and reintroduce the offset
        inManagerRecapture = true;
        try {
            capture(mc.level, kbe);
        } finally {
            inManagerRecapture = false;
        }
    }

    public static boolean consumeAnchorRecapture(BlockPos pos) {
        return ANCHOR_RECAPTURED.remove(pos);
    }


    private record GpuEviction(long sectionKey, double distanceSquared) {}

    private static void enforceGpuBudget(double camX, double camY, double camZ) {
        long budget = (long) VoxyConfig.CONFIG.distantKineticGpuBudgetMiB * 1024L * 1024L;
        if (budget <= 0) {
            return;
        }
        if (gpuBytesDirty) {
            long resident = 0;
            for (var bucket : SECTIONS.values()) {
                if (bucket.mesh != null) {
                    resident += bucket.mesh.gpuByteSize();
                }
            }
            residentGpuBytes = resident;
            gpuBytesDirty = false;
        }
        long resident = residentGpuBytes;
        if (resident <= budget) return;
        var candidates = new java.util.ArrayList<GpuEviction>();
        for (var entry : SECTIONS.long2ObjectEntrySet()) {
            if (entry.getValue().mesh == null) continue;
            long key = entry.getLongKey();
            double dx = ((BlockPos.getX(key) << 4) + 8) - camX;
            double dy = ((BlockPos.getY(key) << 4) + 8) - camY;
            double dz = ((BlockPos.getZ(key) << 4) + 8) - camZ;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared >= 0.0) candidates.add(new GpuEviction(key, distanceSquared));
        }
        // 超预算才建立距离顺序；连续逐出不再重复扫描所有区段。
        candidates.sort(java.util.Comparator.comparingDouble(GpuEviction::distanceSquared).reversed());
        long target = (budget * 9L) / 10L;
        for (var candidate : candidates) {
            if (resident <= target) break;
            var bucket = SECTIONS.remove(candidate.sectionKey);
            if (bucket == null) continue;
            resident -= bucket.mesh == null ? 0 : bucket.mesh.gpuByteSize();
            for (BlockPos snapPos : bucket.geoms.keySet()) {
                BEARING_POSITIONS.remove(snapPos);
            }
            bucket.close();
            me.cortex.voxy.commonImpl.PerfStats.kineticSnapshotEvicted.increment();
        }
        residentGpuBytes = resident;
        gpuBytesDirty = false;
    }

    public static it.unimi.dsi.fastutil.longs.Long2ObjectMap<Bucket> sections() {
        return SECTIONS;
    }

    public static void clearAll() {
        KineticAtlasUvs.clear();
        FAILED_CAPTURES.clear();
        for (Bucket bucket : SECTIONS.values()) {
            bucket.close();
        }
        SECTIONS.clear();
        CAPTURE_QUEUE.clear();
        REMOVE_QUEUE.clear();
        BEARING_POSITIONS.clear();
        ANCHOR_RECAPTURED.clear();
        PRIORITY_REBAKE.clear();
        KineticCull.cachedReachSq = -1;
        sectionCount = 0;
        snapshotCount = 0;
        countsDirty = false;
        meshesDirty = false;
        gpuBytesDirty = false;
        residentGpuBytes = 0;
    }

    public static void onResourceReload() {
        KineticAtlasUvs.onResourceReload();
        FAILED_CAPTURES.clear();
        for (Bucket bucket : SECTIONS.values()) {
            bucket.close();
            bucket.invalidated.addAll(bucket.geoms.keySet());
            bucket.markDirty();
            bucket.retryAfterTick = 0;
        }
    }

    //Client tick (render thread): drain the transition queues, capture, and rebake dirty sections
    public static void tick(Minecraft mc) {
        maintenanceTick++;
        ClientLevel level = mc.level;
        if (level == null) return;
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantKinetics) {
            clearAll();
            return;
        }
        var levelDim = level.dimension().location();
        if (!levelDim.equals(dim)) {
            clearAll();
            dim = levelDim;
        }

        BlockPos pos;
        while ((pos = REMOVE_QUEUE.poll()) != null) {
            Bucket bucket = SECTIONS.get(sectionKey(pos));
            Snap removed = bucket == null ? null : bucket.geoms.remove(pos);
            if (bucket != null) bucket.invalidated.remove(pos);
            if (removed != null && !removed.hollow()) {
                countsDirty = true;
                bucket.markDirty();
                PRIORITY_REBAKE.add(sectionKey(pos));
            }
            BEARING_POSITIONS.remove(pos);
        }

        var cam = mc.gameRenderer.getMainCamera().getPosition();
        double reach = mc.options.getEffectiveRenderDistance() * 16.0;
        double reachSq = reach * reach;
        KineticCull.cachedReachSq = reachSq;
        captureBudget = CAPTURES_PER_TICK;
        while (captureBudget > 16 && (pos = CAPTURE_QUEUE.poll()) != null) {
            if (pos.distToCenterSqr(cam.x, cam.y, cam.z) < reachSq) continue;
            if (loadedBlockEntity(level, pos) instanceof KineticBlockEntity be) {
                if (hasCurrentSnap(level, pos, be.getBlockState())) continue;
                if (capture(level, be)) captureBudget--;
            }
        }

        sweep(mc, level, cam.x, cam.y, cam.z, reachSq);
        rebakeDirty();
        double maxDist = cfg.createRenderDistance(cfg.distantKineticMaxChunks) + 32.0;
        pruneSections(cam.x, cam.y, cam.z, maxDist * maxDist);
        enforceGpuBudget(cam.x, cam.y, cam.z);
        sectionCount = SECTIONS.size();
        updateSnapshotCount();
    }

    private static void rebakeDirty() {
        int rebaked = 0;
        var prio = PRIORITY_REBAKE.iterator();
        while (prio.hasNext() && rebaked < REBAKES_PER_TICK) {
            Bucket bucket = SECTIONS.get(prio.nextLong());
            prio.remove();
            if (bucket != null && bucket.dirty && maintenanceTick >= bucket.retryAfterTick) {
                bucket.dirty = false;
                rebake(bucket);
                rebaked++;
            }
        }
        if (!meshesDirty || rebaked >= REBAKES_PER_TICK) return;
        // 稳定时跳过重建搜索；退避或额度用尽时保留下次搜索。
        meshesDirty = false;
        for (Bucket bucket : SECTIONS.values()) {
            if (rebaked >= REBAKES_PER_TICK) {
                meshesDirty = true;
                break;
            }
            if (!bucket.dirty) continue;
            if (maintenanceTick < bucket.retryAfterTick) {
                meshesDirty = true;
                continue;
            }
            bucket.dirty = false;
            rebake(bucket);
            rebaked++;
        }
    }

    private static void pruneSections(double camX, double camY, double camZ, double maxDistSq) {
        SECTIONS.long2ObjectEntrySet().removeIf(entry -> {
            Bucket bucket = entry.getValue();
            // 空桶先释放，避免旧网格占用预算而误逐出有效快照。
            if (bucket.geoms.isEmpty()) {
                bucket.close();
                return true;
            }
            long key = entry.getLongKey();
            double dx = (BlockPos.getX(key) << 4) + 8 - camX;
            double dy = (BlockPos.getY(key) << 4) + 8 - camY;
            double dz = (BlockPos.getZ(key) << 4) + 8 - camZ;
            if (dx * dx + dy * dy + dz * dz <= maxDistSq) return false;
            for (BlockPos snapPos : bucket.geoms.keySet()) BEARING_POSITIONS.remove(snapPos);
            bucket.close();
            me.cortex.voxy.commonImpl.PerfStats.kineticSnapshotEvicted.increment();
            return true;
        });
    }

    private static void updateSnapshotCount() {
        if (!countsDirty) return;
        int count = 0;
        for (Bucket bucket : SECTIONS.values()) {
            for (Snap snap : bucket.geoms.values()) {
                if (!snap.hollow()) count++;
            }
        }
        snapshotCount = count;
        countsDirty = false;
    }

    private static int sweepCursor;
    private static final int SWEEP_CHUNKS_PER_TICK = 48;

    private static void sweep(Minecraft mc, ClientLevel level, double camX, double camY, double camZ, double reachSq) {
        int radius = mc.options.getEffectiveRenderDistance() + 2;
        int diameter = radius * 2 + 1;
        int total = diameter * diameter;
        int centerX = ((int) Math.floor(camX)) >> 4;
        int centerZ = ((int) Math.floor(camZ)) >> 4;
        // 回收阈值向内留一格区段，避免边界来回移动时反复捕获。
        double innerReach = Math.max(0, Math.sqrt(reachSq) - 16.0);
        double innerSq = innerReach * innerReach;
        for (int i = 0; i < SWEEP_CHUNKS_PER_TICK; i++) {
            int idx = Math.floorMod(sweepCursor++, total);
            int cx = centerX + (idx % diameter) - radius;
            int cz = centerZ + (idx / diameter) - radius;
            var chunk = level.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
            if (!(chunk instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk)) {
                continue;
            }
            if (!SECTIONS.isEmpty()) {
                for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) {
                    long bucketKey = BlockPos.asLong(cx, sy, cz);
                    Bucket bucket = SECTIONS.get(bucketKey);
                    if (bucket == null) {
                        continue;
                    }
                    boolean wasDirty = bucket.dirty;
                    bucket.geoms.keySet().removeIf(snapPos -> {
                        if ((snapPos.getX() >> 4) != cx || (snapPos.getZ() >> 4) != cz) {
                            return false;
                        }
                        if (levelChunk.getBlockEntities().get(snapPos) instanceof KineticBlockEntity) {
                            return false;
                        }
                        bucket.markDirty();
                        countsDirty = true;
                        bucket.invalidated.remove(snapPos);
                        BEARING_POSITIONS.remove(snapPos);
                        return true;
                    });
                    //A ghost dropped here floats over loaded terrain until the rebake lands
                    if (bucket.dirty && !wasDirty) {
                        PRIORITY_REBAKE.add(bucketKey);
                    }
                }
            }
            //Ship membership is decided by chunk, so it is the same answer for every machine in this
            //chunk - resolving it per block entity re-walks sable's plot lookup for each one.
            boolean chunkIsShipBorne = me.cortex.voxy.client.compat.ShipBorne.isShipBorne(
                    (double) (cx << 4), (double) (cz << 4));
            for (var be : levelChunk.getBlockEntities().values()) {
                if (!(be instanceof KineticBlockEntity kbe)) {
                    continue;
                }
                BlockPos bePos = kbe.getBlockPos();
                double distSq = bePos.distToCenterSqr(camX, camY, camZ);
                if (distSq <= reachSq || chunkIsShipBorne) {
                    if (distSq <= innerSq) {
                        Bucket bucket = SECTIONS.get(sectionKey(bePos));
                        Snap removed = bucket == null ? null : bucket.geoms.remove(bePos);
                        if (bucket != null) bucket.invalidated.remove(bePos);
                        if (removed != null) {
                            countsDirty = true;
                            if (!removed.hollow()) {
                                bucket.markDirty();
                                PRIORITY_REBAKE.add(sectionKey(bePos));
                            }
                            BEARING_POSITIONS.remove(bePos);
                        }
                    }
                    continue;
                }
                if (hasCurrentSnap(level, bePos, kbe.getBlockState())) {
                    continue;
                }
                if (captureBudget <= 0) {
                    //Budget spent mid-slice: re-run this chunk next tick instead of waiting out a full
                    //pass. Already-captured positions dedup on re-entry, so the redo is cheap.
                    sweepCursor--;
                    return;
                }
                if (capture(level, kbe)) captureBudget--;
            }
        }
    }

    private static net.minecraft.world.level.block.entity.BlockEntity loadedBlockEntity(
            ClientLevel level, BlockPos pos) {
        var chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4,
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        if (!(chunk instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk)) {
            return null;
        }
        return levelChunk.getBlockEntities().get(pos);
    }

    static boolean drawsSnapAt(BlockPos pos) {
        Bucket bucket = SECTIONS.get(sectionKey(pos));
        if (bucket == null || bucket.mesh == null) {
            return false;
        }
        return bucket.meshPositions.contains(pos);
    }

    private static boolean hasCurrentSnap(ClientLevel level, BlockPos pos,
                                          net.minecraft.world.level.block.state.BlockState state) {
        Bucket bucket = SECTIONS.get(sectionKey(pos));
        if (bucket == null) {
            return false;
        }
        Snap prior = bucket.geoms.get(pos);
        if (prior == null || prior.state() != state || bucket.invalidated.contains(pos)) return false;
        if (prior.hollow()) return true;
        int light = sampleCaptureLight(level, pos);
        if (prior.sky() != DistantLightSampler.sky(light) || prior.block() != DistantLightSampler.block(light)) return false;
        // 链条另一端的光照也参与网格，源端未更新时仍须核对。
        if (prior.chains() != null) {
            for (float[] chain : prior.chains()) {
                int farLight = sampleChainLight(level, pos.getX() + (int) chain[8],
                        pos.getY() + (int) chain[9], pos.getZ() + (int) chain[10]);
                if ((int) chain[6] != DistantLightSampler.sky(farLight)
                        || (int) chain[7] != DistantLightSampler.block(farLight)) return false;
            }
        }
        if (prior.bnbLights() != null) {
            int[] samples = prior.bnbLights();
            for (int i = 0; i < samples.length; i += 4) {
                if (sampleChainLight(level, samples[i], samples[i + 1], samples[i + 2]) != samples[i + 3]) return false;
            }
        }
        return true;
    }

    private static int sampleCaptureLight(ClientLevel level, BlockPos pos) {
        return sampleCaptureLight(level, pos.getX(), pos.getY(), pos.getZ());
    }

    private static int sampleCaptureLight(ClientLevel level, int x, int y, int z) {
        // 活体仍可访问时读取原生光照，边缘区段的远景缓存可能缺失或尚未更新。
        var sample = LIGHT_SAMPLE.get().set(x, y + 1, z);
        int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, sample);
        int block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, sample);
        if ((sky | block) == 0) {
            sample.set(x, y, z);
            sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, sample);
            block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, sample);
        }
        return sky | (block << 4);
    }

    static int sampleChainLight(ClientLevel level, int x, int y, int z) {
        if (level.getChunk(x >> 4, z >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false)
                instanceof net.minecraft.world.level.chunk.LevelChunk) {
            return sampleCaptureLight(level, x, y, z);
        }
        return DistantLightSampler.samplePeek(level, x, y, z);
    }

    //Horizontal path: the chunk is unloading and its BEs are about to vanish - capture immediately.
    //Runs on the main thread from the unload event, while the block entities are still reachable.
    public static void captureUnloadingChunk(ClientLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantKinetics) {
            return;
        }
        if (level != Minecraft.getInstance().level) {
            return; //leaving the dimension entirely - clearAll handles it
        }
        for (var be : chunk.getBlockEntities().values()) {
            if (!(be instanceof KineticBlockEntity kbe)) {
                continue;
            }
            if (hasCurrentSnap(level, kbe.getBlockPos(), kbe.getBlockState())) {
                continue;
            }
            // 实体即将不可达，卸载前仍尝试一次；失败记录不延长其生命周期。
            FAILED_CAPTURES.remove(kbe);
            try {
                capture(level, kbe);
            } finally {
                FAILED_CAPTURES.remove(kbe);
            }
        }
    }

    private static boolean capture(ClientLevel level, KineticBlockEntity be) {
        BlockPos pos = be.getBlockPos();
        net.minecraft.world.level.block.state.BlockState state = null;
        int light = -1;
        try {
            state = be.getBlockState();
            light = sampleCaptureLight(level, pos);
            FailedCapture failed = FAILED_CAPTURES.get(be);
            if (failed != null && failed.state == state && failed.light == light
                    && maintenanceTick < failed.retryAfterTick) return false;
            var model = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);

            net.minecraft.core.Direction shaftHalfFacing = null;
            net.minecraft.core.Direction bearingFacing = null;
            float bearingTopAngle = 0.0f;
            boolean woodenTop = false;
            if (be instanceof com.simibubi.create.content.contraptions.bearing.IBearingBlockEntity bearing
                    && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
                bearingFacing = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
                shaftHalfFacing = bearingFacing.getOpposite();
                //partialTicks = 1, matching the contraption snapshot's applyLocalTransforms(1.0f) so
                //the frozen disc and the frozen structure land on the same interpolated angle
                bearingTopAngle = (float) Math.toRadians(bearing.getInterpolatedAngle(1.0f));
                woodenTop = bearing.isWoodenTop();
            }

            //Chain conveyors: shaft + wheel partials plus every chain connection (start offset, yaw,
            //pitch, length, far-end light) so the strap geometry survives at LOD range
            float[][] chains = null;
            if (be instanceof com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity conveyor) {
                var list = new java.util.ArrayList<float[]>();
                var center = net.minecraft.world.phys.Vec3.atCenterOf(pos);
                for (BlockPos rel : conveyor.connections) {
                    var stats = conveyor.connectionStats.get(rel);
                    if (stats == null) {
                        continue;
                    }
                    var diff = stats.end().subtract(stats.start());
                    double yaw = Math.toDegrees(net.minecraft.util.Mth.atan2(diff.x, diff.z));
                    double pitch = Math.toDegrees(net.minecraft.util.Mth.atan2(diff.y,
                            diff.multiply(1.0, 0.0, 1.0).length()));
                    var startOff = stats.start().subtract(center);
                    int farLight = sampleChainLight(level,
                            pos.getX() + rel.getX(), pos.getY() + rel.getY(), pos.getZ() + rel.getZ());
                    list.add(new float[]{(float) startOff.x, (float) startOff.y, (float) startOff.z,
                            (float) yaw, (float) pitch, stats.chainLength(),
                            DistantLightSampler.sky(farLight), DistantLightSampler.block(farLight),
                            rel.getX(), rel.getY(), rel.getZ()});
                }
                chains = list.toArray(new float[0][]);
            }

            //bits_n_bobs cogwheel chains: the controlling wheel carries the whole loop's strap geometry
            float[] bnbChain = null;
            int[] bnbLights = null;
            if (BNB_LOADED) {
                try {
                    var captured = BnbChainSnapshots.captureWithLight(level, be);
                    if (captured != null) {
                        bnbChain = captured.vertices();
                        bnbLights = captured.lights();
                    }
                } catch (Throwable ignored) {
                    //the addon's internals moved - the wheel itself still snapshots via its json
                }
            }

            int skyLight = DistantLightSampler.sky(light), blockLight = DistantLightSampler.block(light);

            float[] generic = null;
            var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
            if (renderer instanceof KineticBlockEntityRenderer<?>) {
                generic = captureRenderer(renderer, be, pos, skyLight, blockLight,
                        net.minecraft.client.renderer.LightTexture.pack(blockLight, skyLight));
            }
            if (generic != null) {
                shaftHalfFacing = null;
                bearingFacing = null;
            }

            boolean bakeJson = generic == null && !chunkVisible(model, state)
                    && !(model.getQuads(null, null, net.minecraft.util.RandomSource.create(42)).isEmpty()
                    && model.getQuads(null, net.minecraft.core.Direction.UP, net.minecraft.util.RandomSource.create(42)).isEmpty());
            boolean gantryCarriage = generic == null
                    && be instanceof com.simibubi.create.content.contraptions.gantry.GantryCarriageBlockEntity;
            FAILED_CAPTURES.remove(be);
            if (generic == null && !bakeJson && shaftHalfFacing == null && bearingFacing == null
                    && chains == null && bnbChain == null && !gantryCarriage) {
                Bucket bucket = SECTIONS.computeIfAbsent(sectionKey(pos), k -> new Bucket());
                Snap prior = bucket.geoms.put(pos.immutable(),
                        new Snap(state, null, 0.0f, 0, 0, false, null, null, 0.0f, false, null, null, null, null, false));
                bucket.invalidated.remove(pos);
                countsDirty = true;
                if (prior != null && !prior.hollow()) {
                    bucket.markDirty(); //the old state had geometry; rebuild the mesh without it
                }
                logCapture(pos.toShortString() + " SKIP " + state.getBlock().getDescriptionId()
                        + " (chunk-visible or empty json, no partials)");
                return true;
            }
            net.minecraft.core.Direction.Axis axis = null;
            try {
                axis = KineticBlockEntityRenderer.getRotationAxisOf(be);
            } catch (Throwable ignored) {
            }
            float angle = axis != null
                    ? KineticBlockEntityRenderer.getRotationOffsetForPosition(be, pos, axis) % 360.0f / 180.0f * (float) Math.PI
                    : 0.0f;
            KineticAtlasUvs.remember(generic);
            KineticAtlasUvs.remember(bnbChain);
            Bucket bucket = SECTIONS.computeIfAbsent(sectionKey(pos), k -> new Bucket());
            bucket.geoms.put(pos.immutable(), new Snap(state, axis, angle,
                    skyLight, blockLight,
                    bakeJson, shaftHalfFacing, bearingFacing, bearingTopAngle, woodenTop, chains, bnbChain, bnbLights, generic,
                    gantryCarriage));
            bucket.invalidated.remove(pos);
            countsDirty = true;
            bucket.markDirty();
            if (bearingFacing != null) {
                BEARING_POSITIONS.add(pos.immutable());
                if (!inManagerRecapture) {
                    ANCHOR_RECAPTURED.add(pos.immutable());
                }
            }
            logCapture(pos.toShortString() + " OK block=" + state.getBlock().getDescriptionId()
                    + " generic=" + (generic != null ? generic.length / 9 + "v" : "no")
                    + " json=" + bakeJson
                    + " angle=" + String.format("%.1f", Math.toDegrees(angle)));
        } catch (Throwable e) {
            // 同一失败输入退避；新数据、状态、光照或资源重载可立即恢复。
            FAILED_CAPTURES.put(be, new FailedCapture(state, light, maintenanceTick + 20));
            logCapture(pos.toShortString() + " ERROR " + e);
        }
        return true;
    }

    private static float[] captureRenderer(
            net.minecraft.client.renderer.blockentity.BlockEntityRenderer<KineticBlockEntity> renderer,
            KineticBlockEntity be, BlockPos pos, int skyLight, int blockLight, int light) {
        var consumer = new Capture(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, skyLight, blockLight);
        Thread previous = captureThread;
        captureThread = Thread.currentThread();
        try {
            renderer.render(be, 1.0f, new com.mojang.blaze3d.vertex.PoseStack(), consumer, light,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
            consumer.flush();
            float[] vertices = consumer.toArray();
            if (vertices.length % (9 * 4) != 0) {
                throw new IllegalStateException("Incomplete kinetic quad stream");
            }
            return vertices.length == 0 ? null : vertices;
        } finally {
            captureThread = previous;
        }
    }

    static void rotateAboutAxis(org.joml.Matrix4f transform, net.minecraft.core.Direction.Axis axis, float angleRad) {
        if (axis == null || angleRad == 0.0f) {
            return;
        }
        transform.rotate(angleRad,
                axis == net.minecraft.core.Direction.Axis.X ? 1 : 0,
                axis == net.minecraft.core.Direction.Axis.Y ? 1 : 0,
                axis == net.minecraft.core.Direction.Axis.Z ? 1 : 0);
    }

    static void bakeGantryCarriage(DistantMeshBuilder builder, org.joml.Matrix4f transform,
                                   net.minecraft.world.level.block.state.BlockState state,
                                   BlockPos parityPos, float lx, float ly, float lz, int sky, int block) {
        var rotationAxis = ((com.simibubi.create.content.kinetics.base.IRotate) state.getBlock())
                .getRotationAxis(state);
        var shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();

        var shaftModel = shaper.getBlockModel(KineticBlockEntityRenderer.shaft(rotationAxis));
        float shaftAngle = rad(com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual
                .rotationOffset(state, rotationAxis, parityPos) % 360.0f);
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
        rotateAboutAxis(transform, rotationAxis, shaftAngle);
        transform.translate(-0.5f, -0.5f, -0.5f);
        builder.transformedModel(shaftModel, transform, sky, block);

        var facing = state.getValue(com.simibubi.create.content.contraptions.gantry.GantryCarriageBlock.FACING);
        boolean alongFirst = state.getValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE);
        var visualPos = facing.getAxisDirection() == net.minecraft.core.Direction.AxisDirection.POSITIVE
                ? parityPos : parityPos.relative(facing.getOpposite());
        float angleDeg = com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual
                .rotationOffset(state, rotationAxis, visualPos) % 360.0f;
        //The axis that is neither the rotation axis nor the facing axis decides the spin sign
        var gantryAxis = net.minecraft.core.Direction.Axis.Z;
        for (var candidate : net.minecraft.core.Direction.Axis.values()) {
            if (candidate != rotationAxis && candidate != facing.getAxis()) {
                gantryAxis = candidate;
                break;
            }
        }
        if ((gantryAxis == net.minecraft.core.Direction.Axis.X && facing == net.minecraft.core.Direction.UP)
                || (gantryAxis == net.minecraft.core.Direction.Axis.Y
                && (facing == net.minecraft.core.Direction.NORTH || facing == net.minecraft.core.Direction.EAST))) {
            angleDeg = -angleDeg;
        }
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f)
                .rotateY(rad(horizontalAngle(facing)))
                .rotateX(rad(facing == net.minecraft.core.Direction.UP ? 0.0f
                        : facing == net.minecraft.core.Direction.DOWN ? 180.0f : 90.0f))
                .rotateY(rad((alongFirst ^ (facing.getAxis() == net.minecraft.core.Direction.Axis.X)) ? 0.0f : 90.0f))
                .translate(0.0f, -9.0f / 16.0f, 0.0f)
                .rotateX(rad(-angleDeg))
                .translate(0.0f, 9.0f / 16.0f, 0.0f)
                .translate(-0.5f, -0.5f, -0.5f);
        builder.transformedModel(com.simibubi.create.AllPartialModels.GANTRY_COGS.get(), transform, sky, block);
    }

    private static void bakeShaftHalf(DistantMeshBuilder builder, org.joml.Matrix4f transform, Snap snap,
                                      float lx, float ly, float lz) {
        var facing = snap.shaftHalfFacing();
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
        rotateAboutAxis(transform, facing.getAxis(), snap.angleRad());
        transform.rotateY(rad(horizontalAngle(facing)))
                .rotateX(rad(verticalAngle(facing)))
                .translate(-0.5f, -0.5f, -0.5f);
        builder.transformedModel(com.simibubi.create.AllPartialModels.SHAFT_HALF.get(), transform,
                snap.sky(), snap.block());
    }

    //Reproduces BearingRenderer.renderSafe's top disc
    private static void bakeBearingTop(DistantMeshBuilder builder, org.joml.Matrix4f transform, Snap snap,
                                       float lx, float ly, float lz) {
        var facing = snap.bearingFacing();
        var opposite = facing.getOpposite();
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
        rotateAboutAxis(transform, facing.getAxis(), snap.bearingTopAngleRad());
        if (facing.getAxis().isHorizontal()) {
            transform.rotateY(rad(horizontalAngle(opposite)));
        }
        transform.rotateX(rad(-90.0f - verticalAngle(facing)))
                .translate(-0.5f, -0.5f, -0.5f);
        var top = snap.woodenTop()
                ? com.simibubi.create.AllPartialModels.BEARING_TOP_WOODEN
                : com.simibubi.create.AllPartialModels.BEARING_TOP;
        builder.transformedModel(top.get(), transform, snap.sky(), snap.block());
    }

    //Chain conveyor
    private static void bakeChainConveyor(DistantMeshBuilder builder, org.joml.Matrix4f transform, Snap snap,
                                          float lx, float ly, float lz) {
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
        rotateAboutAxis(transform, snap.axis(), snap.angleRad());
        transform.translate(-0.5f, -0.5f, -0.5f);
        builder.transformedModel(com.simibubi.create.AllPartialModels.CHAIN_CONVEYOR_SHAFT.get(), transform,
                snap.sky(), snap.block());

        transform.identity().translate(lx, ly, lz);
        builder.transformedModel(com.simibubi.create.AllPartialModels.CHAIN_CONVEYOR_WHEEL.get(), transform,
                snap.sky(), snap.block());

        var chainSprite = net.minecraft.client.Minecraft.getInstance()
                .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                .apply(net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/chain"));
        var vertex = new org.joml.Vector3f();
        for (float[] chain : snap.chains()) {
            float yawDeg = chain[3], pitchDeg = chain[4], length = chain[5];
            int sky2 = (int) chain[6], block2 = (int) chain[7];

            transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f)
                    .rotateY(rad(yawDeg))
                    .translate(-0.5f, -0.5f, -0.5f);
            builder.transformedModel(com.simibubi.create.AllPartialModels.CHAIN_CONVEYOR_GUARD.get(), transform,
                    snap.sky(), snap.block());

            //ChainConveyorRenderer.renderChains transform chain, then renderChain's (0.5, 0, 0.5)
            transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f)
                    .translate(chain[0], chain[1], chain[2])
                    .rotateY(rad(yawDeg))
                    .rotateX(rad(90.0f - pitchDeg))
                    .rotateY(rad(45.0f))
                    .translate(0.0f, 0.5f, 0.0f)
                    .translate(-0.5f, -0.5f, -0.5f)
                    .translate(0.5f, 0.0f, 0.5f);

            //Far-mip strap: radius 1/16, static texture window, two crossed double-sided ribbons
            float radius = 0.0625f;
            float minU = chainSprite.getU(0.1875f), maxU = chainSprite.getU(0.25f);
            float minV = chainSprite.getV(0.0f), maxV = chainSprite.getV(0.0625f);
            chainQuad(builder, transform, vertex, length, 0, radius, 0, -radius, minU, maxU, minV, maxV, snap.sky(), snap.block(), sky2, block2);
            chainQuad(builder, transform, vertex, length, 0, -radius, 0, radius, minU, maxU, minV, maxV, snap.sky(), snap.block(), sky2, block2);
            chainQuad(builder, transform, vertex, length, radius, 0, -radius, 0, minU, maxU, minV, maxV, snap.sky(), snap.block(), sky2, block2);
            chainQuad(builder, transform, vertex, length, -radius, 0, radius, 0, minU, maxU, minV, maxV, snap.sky(), snap.block(), sky2, block2);
        }
    }

    //One chain ribbon quad in ChainConveyorRenderer.renderQuad's vertex order: top(light2), bottom,
    //bottom, top - light blends from the near wheel to the far one along the strap
    private static void chainQuad(DistantMeshBuilder builder, org.joml.Matrix4f transform, org.joml.Vector3f vertex,
                                  float length, float x0, float z0, float x1, float z1,
                                  float minU, float maxU, float minV, float maxV,
                                  int sky1, int block1, int sky2, int block2) {
        emitChainVertex(builder, transform, vertex, x0, length, z0, maxU, minV, sky2, block2);
        emitChainVertex(builder, transform, vertex, x0, 0.0f, z0, maxU, maxV, sky1, block1);
        emitChainVertex(builder, transform, vertex, x1, 0.0f, z1, minU, maxV, sky1, block1);
        emitChainVertex(builder, transform, vertex, x1, length, z1, minU, minV, sky2, block2);
    }

    private static void emitChainVertex(DistantMeshBuilder builder, org.joml.Matrix4f transform, org.joml.Vector3f vertex,
                                        float x, float y, float z, float u, float v, int sky, int block) {
        vertex.set(x, y, z);
        transform.transformPosition(vertex);
        builder.rawVertex(vertex.x, vertex.y, vertex.z, u, v, sky, block, 1.0f, 1);
    }

    //catnip AngleHelper, inlined
    private static float horizontalAngle(net.minecraft.core.Direction facing) {
        if (facing.getAxis().isVertical()) {
            return 0.0f;
        }
        float angle = facing.toYRot();
        return facing.getAxis() == net.minecraft.core.Direction.Axis.X ? -angle : angle;
    }

    private static float verticalAngle(net.minecraft.core.Direction facing) {
        return facing == net.minecraft.core.Direction.UP ? -90.0f
                : facing == net.minecraft.core.Direction.DOWN ? 90.0f : 0.0f;
    }

    private static float rad(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    static boolean chunkVisible(net.minecraft.client.resources.model.BakedModel model,
                                net.minecraft.world.level.block.state.BlockState state) {
        if (state.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) {
            return false;
        }
        var random = net.minecraft.util.RandomSource.create(42);
        for (var layer : new net.minecraft.client.renderer.RenderType[]{
                net.minecraft.client.renderer.RenderType.solid(),
                net.minecraft.client.renderer.RenderType.cutoutMipped(),
                net.minecraft.client.renderer.RenderType.cutout()}) {
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                random.setSeed(42);
                if (!model.getQuads(state, direction, random,
                        net.neoforged.neoforge.client.model.data.ModelData.EMPTY, layer).isEmpty()) {
                    return true;
                }
            }
            random.setSeed(42);
            if (!model.getQuads(state, null, random,
                    net.neoforged.neoforge.client.model.data.ModelData.EMPTY, layer).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void rebake(Bucket bucket) {
        var builder = new DistantMeshBuilder();
        try {
            var meshPositions = new java.util.HashSet<BlockPos>();
            var shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();
            var transform = new org.joml.Matrix4f();
            for (var entry : bucket.geoms.entrySet()) {
                BlockPos pos = entry.getKey();
                Snap snap = entry.getValue();
                if (!snap.hollow()) meshPositions.add(pos);
                float lx = pos.getX() & 15, ly = pos.getY() & 15, lz = pos.getZ() & 15;
                if (snap.generic() != null) {
                    //Full-pass capture, already section-local
                    float[] v = snap.generic();
                    for (int i = 0; i + 9 <= v.length; i += 9) {
                        builder.rawVertex(v[i], v[i + 1], v[i + 2], v[i + 3], v[i + 4],
                                (int) v[i + 5], (int) v[i + 6], v[i + 7], (int) v[i + 8]);
                    }
                }
                if (snap.bakeJson()) {
                    var model = shaper.getBlockModel(snap.state());
                    //Section-local block offset, then the frozen spin about the block centre. The
                    //baked model already carries the blockstate's axis alignment (variant rotations).
                    transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
                    rotateAboutAxis(transform, snap.axis(), snap.angleRad());
                    transform.translate(-0.5f, -0.5f, -0.5f);
                    builder.transformedModel(model, transform, snap.sky(), snap.block());
                }
                if (snap.gantryCarriage()) {
                    bakeGantryCarriage(builder, transform, snap.state(), pos, lx, ly, lz,
                            snap.sky(), snap.block());
                }
                if (snap.shaftHalfFacing() != null) {
                    bakeShaftHalf(builder, transform, snap, lx, ly, lz);
                }
                if (snap.bearingFacing() != null) {
                    bakeBearingTop(builder, transform, snap, lx, ly, lz);
                }
                if (snap.chains() != null) {
                    bakeChainConveyor(builder, transform, snap, lx, ly, lz);
                }
                if (snap.bnbChain() != null) {
                    float[] v = snap.bnbChain();
                    for (int i = 0; i + 9 <= v.length; i += 9) {
                        builder.rawVertex(v[i] + lx, v[i + 1] + ly, v[i + 2] + lz, v[i + 3], v[i + 4],
                                (int) v[i + 5], (int) v[i + 6], v[i + 7], (int) v[i + 8]);
                    }
                }
            }
            DistantMesh replacement = builder.build();
            DistantMesh previous = bucket.mesh;
            bucket.mesh = replacement;
            gpuBytesDirty = true;
            bucket.meshPositions = meshPositions;
            bucket.retryAfterTick = 0;
            if (previous != null) previous.free();
        } catch (Throwable e) {
            builder.discard();
            bucket.markDirty();
            bucket.retryAfterTick = maintenanceTick + 20;
        }
    }

    private static long sectionKey(BlockPos pos) {
        return BlockPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }


    //Discards render passes the snapshot must not carry (non-block-atlas layers: chain straps on
    //standalone textures, glowing overlays)
    private static final com.mojang.blaze3d.vertex.VertexConsumer NOOP = new com.mojang.blaze3d.vertex.VertexConsumer() {
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) { return this; }
    };

    static final class Capture implements net.minecraft.client.renderer.MultiBufferSource, com.mojang.blaze3d.vertex.VertexConsumer {
        private final float ox, oy, oz;
        private final int sky, block;
        private final it.unimi.dsi.fastutil.floats.FloatArrayList verts =
                new it.unimi.dsi.fastutil.floats.FloatArrayList();
        private boolean pending;
        private float x, y, z, u, v, nx, ny, nz;

        Capture(float ox, float oy, float oz, int sky, int block) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.sky = sky;
            this.block = block;
        }

        float[] toArray() {
            return this.verts.toFloatArray();
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(net.minecraft.client.renderer.RenderType type) {
            String name = type.toString();
            if (name.contains("solid") || name.contains("cutout")) {
                return this;
            }
            return NOOP;
        }

        void flush() {
            if (!this.pending) {
                return;
            }
            float shade;
            int face;
            if (this.ny > 0.6f) {
                shade = 1.0f;
                face = 1;
            } else if (this.ny < -0.6f) {
                shade = 0.5f;
                face = 0;
            } else if (Math.abs(this.nz) > Math.abs(this.nx)) {
                shade = 0.8f;
                face = this.nz > 0 ? 3 : 2;
            } else {
                shade = 0.6f;
                face = this.nx > 0 ? 5 : 4;
            }
            this.verts.add(this.x + this.ox); this.verts.add(this.y + this.oy); this.verts.add(this.z + this.oz);
            this.verts.add(this.u); this.verts.add(this.v);
            this.verts.add(this.sky); this.verts.add(this.block);
            this.verts.add(shade); this.verts.add(face);
            this.pending = false;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            this.flush();
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = 0;
            this.v = 0;
            this.nx = 0;
            this.ny = 1;
            this.nz = 0;
            this.pending = true;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
            this.nx = x;
            this.ny = y;
            this.nz = z;
            return this;
        }
    }
}
