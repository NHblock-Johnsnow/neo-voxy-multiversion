package me.cortex.voxy.commonImpl.importers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.PalettedContainer.CountConsumer;
import net.minecraft.world.level.chunk.PalettedContainerRO.PackedData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipFile.Builder;
import org.lwjgl.system.MemoryUtil;

public class WorldImporter implements IDataImporter {
   private final WorldEngine world;
   private final PalettedContainerRO<Holder<Biome>> defaultBiomeProvider;
   private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
   private final Codec<PalettedContainer<BlockState>> blockStateCodec;
   private final int minBuildHeight;
   private final int heightmapBits;
   private final boolean hasSkyLight;
   private final AtomicInteger estimatedTotalChunks = new AtomicInteger();
   private final AtomicInteger totalChunks = new AtomicInteger();
   private final AtomicInteger chunksProcessed = new AtomicInteger();
   private final ConcurrentLinkedDeque<Runnable> jobQueue = new ConcurrentLinkedDeque<>();
   private final Service service;
   private volatile boolean isRunning;
   private final AtomicBoolean isShutdown = new AtomicBoolean();
   private volatile Thread worker;
   private IDataImporter.IUpdateCallback updateCallback;
   private IDataImporter.ICompletionCallback completionCallback;
   private static final byte[] EMPTY = new byte[0];
   private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);

   public WorldImporter(WorldEngine worldEngine, Level mcWorld, ServiceManager sm, BooleanSupplier runChecker) {
      this.world = worldEngine;
        this.minBuildHeight = mcWorld.getMinY();
      this.heightmapBits = 32 - Integer.numberOfLeadingZeros(mcWorld.getHeight());
      this.hasSkyLight = mcWorld.dimensionType().hasSkyLight();
      this.service = sm.createService(() -> new Pair<>(() -> this.jobQueue.poll().run(), () -> {}), 3L, "World importer", runChecker);
      Registry<Biome> biomeRegistry = mcWorld.registryAccess().lookupOrThrow(Registries.BIOME);
      final Reference<Biome> defaultBiome = biomeRegistry.getOrThrow(Biomes.PLAINS);
      this.defaultBiomeProvider = new PalettedContainerRO<Holder<Biome>>() {
         {
            Objects.requireNonNull(WorldImporter.this);
         }

         public Holder<Biome> get(int x, int y, int z) {
            return defaultBiome;
         }

         public void getAll(Consumer<Holder<Biome>> action) {
            action.accept(defaultBiome);
         }

         public void write(FriendlyByteBuf buf) {
         }

         public int getSerializedSize() {
            return 0;
         }

         public int bitsPerEntry() {
            return 0;
         }

         public boolean maybeHas(Predicate<Holder<Biome>> predicate) {
            return predicate.test(defaultBiome);
         }

         public void count(CountConsumer<Holder<Biome>> counter) {
            counter.accept(defaultBiome, 1);
         }

         public PalettedContainer<Holder<Biome>> copy() {
            return null;
         }

         public PalettedContainer<Holder<Biome>> recreate() {
            return null;
         }

         public PackedData<Holder<Biome>> pack(Strategy<Holder<Biome>> provider) {
            return null;
         }
      };
      PalettedContainerFactory factory = PalettedContainerFactory.create(mcWorld.registryAccess());
      this.biomeCodec = factory.biomeContainerCodec();
      this.blockStateCodec = factory.blockStatesContainerCodec();
   }

   @Override
   public void runImport(IDataImporter.IUpdateCallback updateCallback, IDataImporter.ICompletionCallback completionCallback) {
      if (this.isRunning) {
         throw new IllegalStateException();
      } else if (this.worker == null) {
         completionCallback.onCompletion(0);
      } else {
         this.isRunning = true;
         this.world.acquireRef();
         this.updateCallback = updateCallback;
         this.completionCallback = completionCallback;
         this.worker.start();
      }
   }

   @Override
   public WorldEngine getEngine() {
      return this.world;
   }

   @Override
   public void shutdown() {
      if (!this.isShutdown.getAndSet(true)) {
         this.isRunning = false;
         if (this.worker != null) {
            try {
               this.worker.join();
            } catch (InterruptedException var2) {
               throw new RuntimeException(var2);
            }
         }

         if (this.service.isLive()) {
            this.world.releaseRef();
            this.service.shutdown();
         }

         while (!this.jobQueue.isEmpty()) {
            this.jobQueue.poll().run();
         }
      }
   }

   public void importRegionDirectoryAsync(File directory) {
      File[] files = directory.listFiles((dir, name) -> {
         String[] sections = name.split("\\.");
         if (sections.length == 4 && sections[0].equals("r") && sections[3].equals("mca")) {
            return true;
         } else {
            Logger.error("Unknown file: " + name);
            return false;
         }
      });
      if (files != null) {
         Arrays.sort(files, File::compareTo);
         this.importRegionsAsync(files, this::importRegionFile);
      }
   }

   public void importZippedRegionDirectoryAsync(File zip, String innerDirectory) {
      try {
         innerDirectory = innerDirectory.replace("\\\\", "\\").replace("\\", "/");
         ZipFile file = ((Builder)ZipFile.builder().setFile(zip)).get();
         ArrayList<ZipArchiveEntry> regions = new ArrayList<>();
         Enumeration<ZipArchiveEntry> e = file.getEntries();

         while (e.hasMoreElements()) {
            ZipArchiveEntry entry = e.nextElement();
            if (!entry.isDirectory() && entry.getName().startsWith(innerDirectory)) {
               String[] parts = entry.getName().split("/");
               String name = parts[parts.length - 1];
               String[] sections = name.split("\\.");
               if (sections.length == 4 && sections[0].equals("r") && sections[3].equals("mca")) {
                  regions.add(entry);
               } else {
                  Logger.error("Unknown file: " + name);
               }
            }
         }

         this.importRegionsAsync(regions.toArray(ZipArchiveEntry[]::new), entryx -> {
            if (entryx.getSize() != 0L) {
               MemoryBuffer buf = new MemoryBuffer(entryx.getSize());

               try (ReadableByteChannel channel = Channels.newChannel(file.getInputStream(entryx))) {
                  if (channel.read(buf.asByteBuffer()) != buf.size) {
                     buf.free();
                     throw new IllegalStateException("Could not read full zip entry");
                  }
               }

               String[] partsx = entryx.getName().split("/");
               String namex = partsx[partsx.length - 1];
               String[] sectionsx = namex.split("\\.");

               try {
                  this.importRegion(buf, Integer.parseInt(sectionsx[1]), Integer.parseInt(sectionsx[2]));
               } catch (NumberFormatException var9x) {
                  Logger.error("Invalid format for region position, x: \"" + sectionsx[1] + "\" z: \"" + sectionsx[2] + "\" skipping region");
               }

               buf.free();
            }
         });
      } catch (Exception var10) {
         throw new RuntimeException(var10);
      }
   }

   private <T> void importRegionsAsync(T[] regionFiles, WorldImporter.IImporterMethod<T> importer) {
      this.totalChunks.set(0);
      this.estimatedTotalChunks.set(0);
      this.chunksProcessed.set(0);
      this.worker = new Thread(() -> {
         this.estimatedTotalChunks.addAndGet(regionFiles.length * 1024);

         for (T file : regionFiles) {
            this.estimatedTotalChunks.addAndGet(-1024);

            try {
               importer.importRegion(file);
            } catch (Exception var10) {
               throw new RuntimeException(var10);
            }

            while (this.totalChunks.get() - this.chunksProcessed.get() > 10000 && this.isRunning) {
               try {
                  Thread.sleep(1L);
               } catch (InterruptedException var9) {
                  throw new RuntimeException(var9);
               }
            }

            if (!this.isRunning) {
               this.service.blockTillEmpty();
               this.completionCallback.onCompletion(this.totalChunks.get());
               this.worker = null;
               return;
            }
         }

         this.service.blockTillEmpty();

         while (this.chunksProcessed.get() != this.totalChunks.get() && this.isRunning) {
            Thread.yield();

            try {
               Thread.sleep(10L);
            } catch (InterruptedException var8) {
               throw new RuntimeException(var8);
            }
         }

         if (!this.isShutdown.getAndSet(true)) {
            this.worker = null;
            this.service.shutdown();
            this.world.releaseRef();
         }

         this.completionCallback.onCompletion(this.totalChunks.get());
      });
      this.worker.setName("World importer");
   }

   public boolean isBusy() {
      return this.isRunning || this.worker != null;
   }

   @Override
   public boolean isRunning() {
      return this.isRunning || this.worker != null && this.worker.isAlive();
   }

   private void importRegionFile(File file) throws IOException {
      String name = file.getName();
      String[] sections = name.split("\\.");
      if (sections.length == 4 && sections[0].equals("r") && sections[3].equals("mca")) {
         int rx = 0;
         int rz = 0;

         try {
            rx = Integer.parseInt(sections[1]);
            rz = Integer.parseInt(sections[2]);
         } catch (NumberFormatException var10) {
            Logger.error("Invalid format for region position, x: \"" + sections[1] + "\" z: \"" + sections[2] + "\" skipping region");
            return;
         }

         try (FileChannel fileStream = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            if (fileStream.size() == 0L) {
               return;
            }

            MemoryBuffer fileData = new MemoryBuffer(fileStream.size());
            if (fileStream.read(fileData.asByteBuffer(), 0L) < 8192) {
               fileData.free();
               Logger.warn("Header of region file invalid");
               return;
            }

            this.importRegion(fileData, rx, rz);
            fileData.free();
         }
      } else {
         Logger.error("Unknown file: " + name);
         throw new IllegalStateException();
      }
   }

   private void importRegion(MemoryBuffer regionFile, int x, int z) {
      if (regionFile.size < 8192L) {
         Logger.warn("Header of region file invalid");
      } else {
         for (int idx = 0; idx < 1024; idx++) {
            int sectorMeta = Integer.reverseBytes(MemoryUtil.memGetInt(regionFile.address + idx * 4));
            if (sectorMeta != 0) {
               int sectorStart = sectorMeta >>> 8;
               int sectorCount = sectorMeta & 0xFF;
               if (sectorCount != 0) {
                  if (regionFile.size < (sectorCount - 1 + sectorStart) * 4096L) {
                     Logger.warn(
                        "Cannot access chunk sector as it goes out of bounds. start bytes: "
                           + sectorStart * 4096
                           + " sector count: "
                           + sectorCount
                           + " fileSize: "
                           + regionFile.size
                     );
                  } else {
                     long base = regionFile.address + sectorStart * 4096L;
                     int chunkLen = sectorCount * 4096;
                     int m = Integer.reverseBytes(MemoryUtil.memGetInt(base));
                     byte b = MemoryUtil.memGetByte(base + 4L);
                     if (m == 0) {
                        Logger.error("Chunk is allocated, but stream is missing");
                     } else {
                        int n = m - 1;
                        if (regionFile.size < n + sectorStart * 4096L) {
                           Logger.warn("Chunk stream to small");
                        } else if ((b & 128) != 0) {
                           if (n != 0) {
                              Logger.error("Chunk has both internal and external streams");
                           }

                           Logger.error("Chunk has external stream which is not supported");
                        } else if (n > chunkLen - 5) {
                           Logger.error("Chunk stream is truncated: expected " + n + " but read " + (chunkLen - 5));
                        } else if (n < 0) {
                           Logger.error("Declared size of chunk is negative");
                        } else {
                           MemoryBuffer data = new MemoryBuffer(n).cpyFrom(base + 5L);
                           this.jobQueue.add(() -> {
                              if (!this.isRunning) {
                                 data.free();
                              } else {
                                 try (DataInputStream decompressedData = this.decompress(b, data)) {
                                    if (decompressedData == null) {
                                       Logger.error("Error decompressing chunk data");
                                    } else {
                                       CompoundTag nbt = NbtIo.read(decompressedData);
                                       this.importChunkNBT(nbt, x, z);
                                    }
                                 } catch (Exception var15) {
                                    throw new RuntimeException(var15);
                                 } finally {
                                    data.free();
                                 }
                              }
                           });
                           this.totalChunks.incrementAndGet();
                           this.estimatedTotalChunks.incrementAndGet();
                           this.service.execute();
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static InputStream createInputStream(final MemoryBuffer data) {
      return new InputStream() {
         private long offset = 0L;

         @Override
         public int read() {
            return MemoryUtil.memGetByte(data.address + this.offset++) & 0xFF;
         }

         @Override
         public int read(byte[] b, int off, int len) {
            len = Math.min(len, this.available());
            if (len == 0) {
               return -1;
            } else {
               UnsafeUtil.memcpy(data.address + this.offset, len, b, off);
               this.offset += len;
               return len;
            }
         }

         @Override
         public int available() {
            return (int)(data.size - this.offset);
         }
      };
   }

   private DataInputStream decompress(byte flags, MemoryBuffer stream) throws IOException {
      RegionFileVersion chunkStreamVersion = RegionFileVersion.fromId(flags);
      if (chunkStreamVersion == null) {
         Logger.error("Chunk has invalid chunk stream version");
         return null;
      } else {
         return new DataInputStream(chunkStreamVersion.wrap(createInputStream(stream)));
      }
   }

   private void importChunkNBT(CompoundTag chunk, int regionX, int regionZ) {
      if (!chunk.contains("Status")) {
         this.totalChunks.decrementAndGet();
      } else {
         ChunkStatus status = ChunkStatus.byName(chunk.getStringOr("Status", null));
         if (status != ChunkStatus.FULL && status != ChunkStatus.EMPTY) {
            this.totalChunks.decrementAndGet();
         } else {
            try {
               int x = chunk.getIntOr("xPos", Integer.MIN_VALUE);
               int z = chunk.getIntOr("zPos", Integer.MIN_VALUE);
               if (x >> 5 != regionX || z >> 5 != regionZ) {
                  Logger.error(
                     "Chunk position is not located in correct region, expected: ("
                        + regionX
                        + ", "
                        + regionZ
                        + "), got: ("
                        + (x >> 5)
                        + ", "
                        + (z >> 5)
                        + "), importing anyway"
                  );
               }

               int[] surfaceHeights = this.decodeSurfaceHeights(chunk);
               for (Tag sectionE : (ListTag)chunk.getList("sections").orElseThrow()) {
                  CompoundTag section = (CompoundTag)sectionE;
                  int y = section.getIntOr("Y", Integer.MIN_VALUE);
                  this.importSectionNBT(x, y, z, section, surfaceHeights);
               }
            } catch (Exception var11) {
               Logger.error("Exception importing world chunk:", var11);
            }

            this.updateCallback.onUpdate(this.chunksProcessed.incrementAndGet(), this.estimatedTotalChunks.get());
         }
      }
   }

   private void importSectionNBT(int x, int y, int z, CompoundTag section, int[] surfaceHeights) {
      if (!section.getCompound("block_states").isEmpty()) {
         byte[] blockLightData = section.getByteArray("BlockLight").orElse(EMPTY);
         byte[] skyLightData = section.getByteArray("SkyLight").orElse(EMPTY);
         DataLayer blockLight;
         if (blockLightData.length != 0) {
            blockLight = new DataLayer(blockLightData);
         } else {
            blockLight = null;
         }

         DataLayer skyLight;
         if (skyLightData.length != 0) {
            skyLight = new DataLayer(skyLightData);
         } else {
            skyLight = null;
         }

         DataResult<PalettedContainer<BlockState>> blockStatesRes = this.blockStateCodec.parse(NbtOps.INSTANCE, (Tag)section.getCompound("block_states").get());
         if (blockStatesRes.hasResultOrPartial()) {
            PalettedContainer<BlockState> blockStates = (PalettedContainer<BlockState>)blockStatesRes.getPartialOrThrow();
            PalettedContainerRO<Holder<Biome>> biomes = this.defaultBiomeProvider;
            Optional<CompoundTag> optBiomes = section.getCompound("biomes");
            if (optBiomes.isPresent()) {
               biomes = this.biomeCodec.parse(NbtOps.INSTANCE, (Tag)optBiomes.get()).result().orElse(this.defaultBiomeProvider);
            }

            VoxelizedSection csec = WorldConversionFactory.convert(
               SECTION_CACHE.get().setPosition(x, y, z), this.world.getMapper(), blockStates, biomes, (bx, by, bz) -> {
                  int block = 0;
               int sky = this.hasSkyLight && surfaceHeights != null && y * 16 + by >= surfaceHeights[bx | bz << 4] - 1 ? 15 : 0;
                  if (blockLight != null) {
                     block = blockLight.get(bx, by, bz);
                  }

                  if (skyLight != null) {
                     sky = skyLight.get(bx, by, bz);
                  }

                  return (byte)(sky | block << 4);
               }
            );
            WorldVoxilizedSectionMipper.mipSection(csec, this.world.getMapper());
            WorldUpdater.insertUpdate(this.world, csec);
         }
      }
   }

   private int[] decodeSurfaceHeights(CompoundTag chunk) {
      if (!this.hasSkyLight) return null;
      CompoundTag heightmaps = chunk.getCompound("Heightmaps").orElse(null);
      if (heightmaps == null) return null;
      long[] packed = heightmaps.getLongArray("WORLD_SURFACE").orElse(new long[0]);
      if (packed.length == 0) return null;
      int bits = this.heightmapBits;
      int perLong = 64 / bits;
      if (packed.length * perLong < 256) return null;
      long mask = (1L << bits) - 1L;
      int[] result = new int[256];
      for (int i = 0; i < result.length; i++) result[i] = this.minBuildHeight + (int)(packed[i / perLong] >>> (i % perLong * bits) & mask);
      return result;
   }

   private interface IImporterMethod<T> {
      void importRegion(T var1) throws Exception;
   }
}
