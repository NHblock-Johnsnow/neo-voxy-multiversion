package me.cortex.voxy.client.compat.create;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

public final class SectionReingestQueue {
    private SectionReingestQueue() {}

    private static final int SECTIONS_PER_TICK = 16;

    private static final it.unimi.dsi.fastutil.longs.Long2ObjectRBTreeMap<it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue> QUEUE =
            new it.unimi.dsi.fastutil.longs.Long2ObjectRBTreeMap<>();
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet QUEUED =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private static long clientTick;
    private static ClientLevel queueLevel;

    public static void scheduleSection(ClientLevel level, net.minecraft.core.BlockPos pos) {
        if (level != queueLevel) {
            clear();
            queueLevel = level;
        }
        int sy = pos.getY() >> 4;
        if (sy < level.getMinSection() || sy >= level.getMaxSection()) return;
        long key = SectionPos.asLong(pos);
        if (QUEUED.add(key)) queueFor(clientTick).enqueue(key);
    }

    public static void scheduleBox(ClientLevel level, AABB box, int delayTicks) {
        if (level != queueLevel) {
            clear();
            queueLevel = level;
        }
        int minX = SectionPos.blockToSectionCoord((int) Math.floor(box.minX));
        int maxX = SectionPos.blockToSectionCoord((int) Math.floor(box.maxX));
        int minY = Math.max(level.getMinSection(), SectionPos.blockToSectionCoord((int) Math.floor(box.minY)));
        int maxY = Math.min(level.getMaxSection() - 1, SectionPos.blockToSectionCoord((int) Math.floor(box.maxY)));
        int minZ = SectionPos.blockToSectionCoord((int) Math.floor(box.minZ));
        int maxZ = SectionPos.blockToSectionCoord((int) Math.floor(box.maxZ));
        long fire = clientTick + delayTicks;
        it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue pending = null;
        for (int sx = minX; sx <= maxX; sx++) {
            for (int sy = minY; sy <= maxY; sy++) {
                for (int sz = minZ; sz <= maxZ; sz++) {
                    long key = SectionPos.asLong(sx, sy, sz);
                    if (QUEUED.add(key)) {
                        if (pending == null) pending = queueFor(fire);
                        pending.enqueue(key);
                    }
                }
            }
        }
    }

    public static void tick(Minecraft mc) {
        clientTick++;
        var level = mc.level;
        if (level != queueLevel) {
            clear();
            queueLevel = level;
        }
        if (level == null) return;
        int done = 0;
        while (done < SECTIONS_PER_TICK && !QUEUE.isEmpty()) {
            var first = QUEUE.long2ObjectEntrySet().first();
            if (first.getLongKey() > clientTick) return;
            var pending = first.getValue();
            // 到期时间之间排序，同一时间仍按通知顺序处理。
            while (done < SECTIONS_PER_TICK && !pending.isEmpty()) {
                long sectionPos = pending.dequeueLong();
                QUEUED.remove(sectionPos);
                done++;
                reingest(level, sectionPos);
            }
            if (pending.isEmpty()) QUEUE.remove(first.getLongKey());
        }
    }

    private static it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue queueFor(long fireTick) {
        var pending = QUEUE.get(fireTick);
        if (pending == null) {
            pending = new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();
            QUEUE.put(fireTick, pending);
        }
        return pending;
    }

    //Same recipe as the per-block trigger in MixinClientLevel: live section reference plus copied
    //light layers, through the shared ingest pipeline.
    private static void reingest(ClientLevel level, long sectionPos) {
        if (me.cortex.voxy.commonImpl.VoxyCommon.getInstance() == null
                || !me.cortex.voxy.client.config.VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }
        var wi = me.cortex.voxy.commonImpl.WorldIdentifier.of(level);
        if (wi == null) {
            return;
        }
        var csp = SectionPos.of(sectionPos);
        if (csp.y() < level.getMinSection() || csp.y() >= level.getMaxSection()) return;
        var chunk = level.getChunk(csp.x(), csp.z(), ChunkStatus.FULL, false);
        if (!(chunk instanceof LevelChunk levelChunk)) {
            return;
        }
        var section = levelChunk.getSection(level.getSectionIndexFromSectionY(csp.y()));
        var lp = level.getLightEngine();
        var blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
        var slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);
        me.cortex.voxy.common.world.service.VoxelIngestService.rawIngest(wi, levelChunk, section,
                csp.x(), csp.y(), csp.z(), blp == null ? null : blp.copy(), slp == null ? null : slp.copy());
    }

    public static void clear() {
        QUEUE.clear();
        QUEUED.clear();
        queueLevel = null;
    }
}


