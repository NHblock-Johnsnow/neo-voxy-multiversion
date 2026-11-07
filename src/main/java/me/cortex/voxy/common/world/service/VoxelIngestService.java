package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.compat.DomumOrnamentumCompat;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentLinkedDeque;

/** 将 Minecraft 区段快照排队转换为 Voxy 的体素区段。 */
public class VoxelIngestService {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private final Service service;
    /** 队列元素持有一个 WorldEngine 引用，任务完成或丢弃时必须释放。 */
    private record IngestSection(
            int cx, int cy, int cz,
            WorldEngine world,
            LevelChunk chunk,
            DomumOrnamentumCompat.CapturedBlock[] domumBlockEntities,
            LevelChunkSection section,
            DataLayer blockLight,
            DataLayer skyLight,
            me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.SectionSnapshot littleTiles) {
    }
    private final ConcurrentLinkedDeque<IngestSection> ingestQueue = new ConcurrentLinkedDeque<>();
    private boolean accepting = true;

    public VoxelIngestService(ServiceManager pool) {
        this.service = pool.createServiceNoCleanup(()->this::processJob, 5000, "Ingest service");
    }

    // ---- 后台任务 ------------------------------------------------------

    private void processJob() {
        var task = this.ingestQueue.pop();

        var section = task.section;
        long tIngest = me.cortex.voxy.commonImpl.VoxyProfile.begin();
        try {
            //Inside the try: the queue holds a world ref per task and the finally below releases it, so
            //anything that can throw has to be covered or the world can never be closed again
            DomumOrnamentumCompat.beginSection(
                    task.world.getMapper(), task.world.storage, task.domumBlockEntities,
                    task.section, task.cx, task.cy, task.cz);
            me.cortex.voxy.commonImpl.compat.CreateCopycatCompat.beginSection(task.world.getMapper(), task.world.storage, task.chunk, task.section, task.cx, task.cy, task.cz);
            me.cortex.voxy.commonImpl.compat.FramedBlocksCompat.beginSection(task.world.getMapper(), task.world.storage, task.chunk, task.section, task.cx, task.cy, task.cz);
            me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.beginSection(
                    task.world.storage, task.littleTiles, task.section, task.cx, task.cy, task.cz);
            //Read off the section rather than the chunk's block entities: sections streamed by VSS arrive
            //with no chunk at all, and a beacon is a block whether or not its block entity is here.
            long tBeacon = me.cortex.voxy.commonImpl.VoxyProfile.begin();
            me.cortex.voxy.common.world.other.BeaconScanner.scan(
                    task.world.getBeaconIndex(), section, task.cx, task.cy, task.cz);
            me.cortex.voxy.commonImpl.VoxyProfile.end("ingest/beaconScan", tBeacon);
            var vs = SECTION_CACHE.get().setPosition(task.cx, task.cy, task.cz);

            if (section.hasOnlyAir() && task.blockLight==null && task.skyLight==null) {//If the chunk section has lighting data, propagate it
                WorldUpdater.insertUpdate(task.world, vs.uniformAir(me.cortex.voxy.common.world.other.Mapper.airWithLight(0x0F)));
            } else {
                VoxelizedSection csec = WorldConversionFactory.convert(
                        vs,
                        task.world.getMapper(),
                        section.getStates(),
                        section.getBiomes(),
                        getLightingSupplier(task)
                );
                WorldVoxilizedSectionMipper.mipSection(csec, task.world.getMapper());
                WorldUpdater.insertUpdate(task.world, csec);
            }
        } finally {
            DomumOrnamentumCompat.endSection();
            me.cortex.voxy.commonImpl.compat.CreateCopycatCompat.endSection();
            me.cortex.voxy.commonImpl.compat.FramedBlocksCompat.endSection();
            me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.endSection();
            //The queue holds a ref per task rather than a one-shot markActive stamp, so a large backlog
            //on a laggy system cannot let the idle cleaner close the world out from under its own
            //pending ingests
            task.world.releaseRef();
            me.cortex.voxy.commonImpl.VoxyProfile.end("ingest/section", tIngest);
        }
    }

    // ---- 光照快照 ------------------------------------------------------

    @NotNull
    private static ILightingSupplier getLightingSupplier(IngestSection task) {
        ILightingSupplier supplier = (x,y,z) -> (byte) 0;
        var sla = task.skyLight;
        var bla = task.blockLight;
        boolean sl = sla != null && !sla.isEmpty();
        boolean bl = bla != null && !bla.isEmpty();
        if (sl || bl) {
            if (sl && bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            } else if (bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = 0;
                    return (byte) (sky|(block<<4));
                };
            } else {
                supplier = (x,y,z)-> {
                    int block = 0;
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            }
        }
        return supplier;
    }

    private static boolean shouldIngestSection(LevelChunkSection section, int cx, int cy, int cz) {
        return true;
    }

    // ---- 区段入队 ------------------------------------------------------

    public boolean enqueueIngest(WorldEngine engine, LevelChunk chunk) {
        if (!this.service.isLive()) {
            return false;
        }
        if (!engine.isLive()) {
            throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
        }

        engine.markActive();

        // Snapshot and group once on the caller thread. Each section job receives only its Domum
        // entities, avoiding repeated traversal of the live block-entity map on ingest workers.
        var domumBlockEntities = DomumOrnamentumCompat.captureBlockEntitiesBySection(chunk);
        var littleTiles = me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.capture(chunk);

        var lightingProvider = chunk.getLevel().getLightEngine();
        boolean gotLighting = false;

        int i = chunk.getMinSection() - 1;
        boolean allEmpty = true;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            allEmpty&=section.hasOnlyAir();
            var pos = SectionPos.of(chunk.getPos(), i);
            if (lightingProvider.getDebugSectionType(LightLayer.SKY, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA && lightingProvider.getDebugSectionType(LightLayer.BLOCK, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA)
                continue;
            gotLighting = true;
        }

        if (allEmpty&&!gotLighting) {
            //Special case all empty chunk columns, we need to clear it out
            i = chunk.getMinSection() - 1;
            for (var section : chunk.getSections()) {
                i++;
                if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
                if (!this.queueIngest(new IngestSection(
                        chunk.getPos().x, i, chunk.getPos().z, engine, chunk,
                        domumBlockEntities.forSection(i), snapshotCustomSection(section), null, null,
                        littleTiles == null ? null : littleTiles.section(i)))) {
                    break;
                }
            }
        }

        if (!gotLighting) {
            return false;
        }

        var blp = lightingProvider.getLayerListener(LightLayer.BLOCK);
        var slp = lightingProvider.getLayerListener(LightLayer.SKY);


        i = chunk.getMinSection() - 1;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            var pos = SectionPos.of(chunk.getPos(), i);

            var bl = blp.getDataLayerData(pos);
            if (bl != null) {
                bl = bl.copy();
            }

            var sl = slp.getDataLayerData(pos);
            if (sl != null) {
                sl = sl.copy();
            } else {
                //Sections above the sky-light storage range have no DataLayer but are implicitly fully lit.
                //Null-data sections are uniform, so probe one block for the value; dark sections and
                //skylight-less dimensions probe 0 and stay unchanged.
                int uniform = slp.getLightValue(pos.origin());
                if (uniform > 0) {
                    sl = new DataLayer(uniform);
                }
            }

            if (!this.queueIngest(new IngestSection(
                    chunk.getPos().x, i, chunk.getPos().z, engine, chunk,
                    domumBlockEntities.forSection(i), snapshotCustomSection(section), bl, sl,
                    littleTiles == null ? null : littleTiles.section(i)))) {
                break;
            }
        }
        return true;
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }

    /** 停止服务并释放队列中尚未执行任务持有的世界引用。 */
    public void shutdown() {
        synchronized (this) {
            this.accepting = false;
        }
        this.service.shutdown();
        //Every queued task still holds a world ref - drain and release so worlds can close
        IngestSection task;
        while ((task = this.ingestQueue.poll()) != null) {
            task.world().releaseRef();
        }
    }

    /** 快照成功后才持有引用；与停止入队共用锁，避免清队列后再追加任务。 */
    private synchronized boolean queueIngest(IngestSection task) {
        if (!this.accepting || !this.service.isLive()) return false;
        task.world.acquireRef();
        boolean queued = false;
        try {
            this.ingestQueue.add(task);
            queued = true;
            this.service.execute();
            return true;
        } catch (Exception e) {
            Logger.error("Executing had an error: assume shutting down, aborting", e);
            return false;
        } finally {
            // 入队后由 worker 或 shutdown 释放，调度失败也不能在此重复释放。
            if (!queued) task.world.releaseRef();
        }
    }

    // ---- 公共入口 ------------------------------------------------------

    /** 将已加载区块送入对应世界的摄取队列。 */
    public static boolean tryIngestChunk(WorldIdentifier worldId, LevelChunk chunk) {
        if (worldId == null) return false;
        var instance = VoxyCommon.getInstance();
        if (instance == null) return false;
        if (!instance.isIngestEnabled(worldId)) return false;
        var engine = instance.getOrCreate(worldId);
        if (engine == null) return false;
        return instance.getIngestService().enqueueIngest(engine, chunk);
    }

    /** 根据区块所在维度自动选择目标世界。 */
    public static boolean tryAutoIngestChunk(LevelChunk chunk) {
        return tryIngestChunk(WorldIdentifier.of(chunk.getLevel()), chunk);
    }

    /** 供网络/兼容层提交单个区段，调用方不直接接触队列引用计数。 */
    private boolean rawIngest0(WorldEngine engine, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        DomumOrnamentumCompat.CapturedBlock[] domumBlockEntities =
                DomumOrnamentumCompat.captureBlockEntities(chunk, section);
        var littleTiles = me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.capture(chunk);
        return this.queueIngest(new IngestSection(
                x, y, z, engine, chunk, domumBlockEntities, snapshotCustomSection(section), bl, sl,
                littleTiles == null ? null : littleTiles.section(y)));
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        return rawIngest(id, recoverChunk(id, x, z), section, x, y, z, bl, sl);
    }

    private static LevelChunk recoverChunk(WorldIdentifier id, int chunkX, int chunkZ) {
        if (id == null) {
            return null;
        }
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            return null;
        }
        try {
            //Named only here, so this class never resolves a client type on a dedicated server
            return me.cortex.voxy.client.ClientChunkRecovery.find(id, chunkX, chunkZ);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (id == null) return false;
        var engine = id.getOrCreateEngine();
        if (engine == null) return false;
        return rawIngest(engine, chunk, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldEngine engine, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (!shouldIngestSection(section, x, y, z)) return false;
        if (chunk == null) {
            me.cortex.voxy.commonImpl.PerfStats.sectionIngestedChunkless.increment();
        } else {
            me.cortex.voxy.commonImpl.PerfStats.sectionIngestedWithChunk.increment();
        }
        if (engine.instanceIn == null) return false;
        if (!engine.instanceIn.isIngestEnabled(null)) return false;
        return engine.instanceIn.getIngestService().rawIngest0(engine, chunk, section, x, y, z, bl, sl);
    }

    /** 非标准调色板需要拷贝，避免异步线程读取主线程正在修改的容器。 */
    private static LevelChunkSection snapshotCustomSection(LevelChunkSection section) {
        if (section == null || section.getStates().getClass() == PalettedContainer.class) {
            return section;
        }
        return new LevelChunkSection(section.getStates().copy(), section.getBiomes());
    }
}
