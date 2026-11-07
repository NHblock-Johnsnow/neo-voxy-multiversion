package me.cortex.voxy.common.world.service;

import java.util.concurrent.ConcurrentLinkedDeque;
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
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LayerLightSectionStorage.SectionType;
import org.jetbrains.annotations.NotNull;

/** 26.1.2 NeoForge 的区段摄取服务；将主线程快照交给后台转换器。 */
public class VoxelIngestService {
   private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
   private final Service service;
   private final ConcurrentLinkedDeque<VoxelIngestService.IngestSection> ingestQueue = new ConcurrentLinkedDeque<>();

   public VoxelIngestService(ServiceManager pool) {
      this.service = pool.createServiceNoCleanup(() -> this::processJob, 5000L, "Ingest service");
   }

   private void processJob() {
      VoxelIngestService.IngestSection task = this.ingestQueue.pop();

      try {
         LevelChunkSection section = task.section;
         VoxelizedSection vs = SECTION_CACHE.get().setPosition(task.cx, task.cy, task.cz);
         if (section.hasOnlyAir() && task.blockLight == null && task.skyLight == null) {
            WorldUpdater.insertUpdate(task.world, vs.zero());
         } else {
            VoxelizedSection csec = WorldConversionFactory.convert(
               vs, task.world.getMapper(), section.getStates(), section.getBiomes(), getLightingSupplier(task)
            );
            WorldVoxilizedSectionMipper.mipSection(csec, task.world.getMapper());
            WorldUpdater.insertUpdate(task.world, csec);
         }
      } finally {
         task.world.releaseRef();
      }
   }

   @NotNull
   private static ILightingSupplier getLightingSupplier(VoxelIngestService.IngestSection task) {
      ILightingSupplier supplier = (x, y, z) -> 0;
      DataLayer sla = task.skyLight;
      DataLayer bla = task.blockLight;
      boolean sl = sla != null && !sla.isEmpty();
      boolean bl = bla != null && !bla.isEmpty();
      if (sl || bl) {
         if (sl && bl) {
            supplier = (x, y, z) -> {
               int block = Math.min(15, bla.get(x, y, z));
               int sky = Math.min(15, sla.get(x, y, z));
               return (byte)(sky | block << 4);
            };
         } else if (bl) {
            supplier = (x, y, z) -> {
               int block = Math.min(15, bla.get(x, y, z));
               int sky = 0;
               return (byte)(sky | block << 4);
            };
         } else {
            supplier = (x, y, z) -> {
               int block = 0;
               int sky = Math.min(15, sla.get(x, y, z));
               return (byte)(sky | block << 4);
            };
         }
      }

      return supplier;
   }

   private static boolean shouldIngestSection(LevelChunkSection section, int cx, int cy, int cz) {
      return true;
   }

   public boolean enqueueIngest(WorldEngine engine, LevelChunk chunk) {
      if (!this.service.isLive()) {
         return false;
      } else if (!engine.isLive()) {
         throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
      } else {
         engine.markActive();
         LevelLightEngine lightingProvider = chunk.getLevel().getLightEngine();
         boolean gotLighting = false;
         int i = chunk.getMinSectionY() - 1;
         boolean allEmpty = true;

         for (LevelChunkSection section : chunk.getSections()) {
               ++i;
            if (section != null && shouldIngestSection(section, chunk.getPos().x(), i, chunk.getPos().z())) {
               allEmpty &= section.hasOnlyAir();
               SectionPos pos = SectionPos.of(chunk.getPos(), i);
               if (lightingProvider.getDebugSectionType(LightLayer.SKY, pos) == SectionType.LIGHT_AND_DATA
                  || lightingProvider.getDebugSectionType(LightLayer.BLOCK, pos) == SectionType.LIGHT_AND_DATA) {
                  gotLighting = true;
               }
            }
         }

         if (allEmpty && !gotLighting) {
            i = chunk.getMinSectionY() - 1;

            for (LevelChunkSection sectionx : chunk.getSections()) {
               ++i;
               if (sectionx != null && shouldIngestSection(sectionx, chunk.getPos().x(), i, chunk.getPos().z())) {
                  engine.acquireRef();
                  this.ingestQueue.add(new VoxelIngestService.IngestSection(chunk.getPos().x(), i, chunk.getPos().z(), engine, sectionx, null, null));

                  try {
                     this.service.execute();
                  } catch (Exception var18) {
                     Logger.error("Executing had an error: assume shutting down, aborting", var18);
                     engine.releaseRef();
                     break;
                  }
               }
            }
         }

         if (!gotLighting) {
            return false;
         } else {
            LayerLightEventListener blp = lightingProvider.getLayerListener(LightLayer.BLOCK);
            LayerLightEventListener slp = lightingProvider.getLayerListener(LightLayer.SKY);
            i = chunk.getMinSectionY() - 1;

            for (LevelChunkSection sectionxx : chunk.getSections()) {
               ++i;
               if (sectionxx != null && shouldIngestSection(sectionxx, chunk.getPos().x(), i, chunk.getPos().z())) {
                  SectionPos pos = SectionPos.of(chunk.getPos(), i);
                  DataLayer bl = blp.getDataLayerData(pos);
                  if (bl != null) {
                     bl = bl.copy();
                  }

                  DataLayer sl = slp.getDataLayerData(pos);
                  if (sl != null) {
                     sl = sl.copy();
                  }

                  engine.acquireRef();
                  this.ingestQueue.add(new VoxelIngestService.IngestSection(chunk.getPos().x(), i, chunk.getPos().z(), engine, sectionxx, bl, sl));

                  try {
                     this.service.execute();
                  } catch (Exception var17) {
                     Logger.error("Executing had an error: assume shutting down, aborting", var17);
                     engine.releaseRef();
                     break;
                  }
               }
            }

            return true;
         }
      }
   }

   public int getTaskCount() {
      return this.service.numJobs();
   }

   public void shutdown() {
      this.service.shutdown();

      while (!this.ingestQueue.isEmpty()) {
         this.ingestQueue.pop().world.releaseRef();
      }
   }

   public static boolean tryIngestChunk(WorldIdentifier worldId, LevelChunk chunk) {
      if (worldId == null) {
         return false;
      } else {
         VoxyInstance instance = VoxyCommon.getInstance();
         if (instance == null) {
            return false;
         } else if (!instance.isIngestEnabled(worldId)) {
            return false;
         } else {
            WorldEngine engine = instance.getOrCreate(worldId);
            return engine == null ? false : instance.getIngestService().enqueueIngest(engine, chunk);
         }
      }
   }

   public static boolean tryAutoIngestChunk(LevelChunk chunk) {
      return tryIngestChunk(WorldIdentifier.of(chunk.getLevel()), chunk);
   }

   private boolean rawIngest0(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
      engine.acquireRef();
      this.ingestQueue.add(new VoxelIngestService.IngestSection(x, y, z, engine, section, bl, sl));

      try {
         this.service.execute();
         return true;
      } catch (Exception var9) {
         Logger.error("Executing had an error: assume shutting down, aborting", var9);
         engine.releaseRef();
         return false;
      }
   }

   public static boolean rawIngest(WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
      if (id == null) {
         return false;
      } else {
         WorldEngine engine = id.getOrCreateEngine();
         return engine == null ? false : rawIngest(engine, section, x, y, z, bl, sl);
      }
   }

   public static boolean rawIngest(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
      if (!shouldIngestSection(section, x, y, z)) {
         return false;
      } else if (engine.instanceIn == null) {
         return false;
      } else {
         return !engine.instanceIn.isIngestEnabled(null) ? false : engine.instanceIn.getIngestService().rawIngest0(engine, section, x, y, z, bl, sl);
      }
   }

   private record IngestSection(int cx, int cy, int cz, WorldEngine world, LevelChunkSection section, DataLayer blockLight, DataLayer skyLight) {
   }
}
