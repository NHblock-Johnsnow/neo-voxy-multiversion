package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.config.VoxyConfig;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/** 26.1.2 NeoForge 的模型、材质和元数据缓存协调器。 */
public class ModelFactory {
   public static final int MODEL_TEXTURE_SIZE = 16;
   public static final int LAYERS = Integer.numberOfTrailingZeros(16);
   private final Biome DEFAULT_BIOME = (Biome)Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BIOME).getValue(Biomes.PLAINS);
   public final SoftwareModelTextureBakery bakery2;
   private final long bakeScratchBuffer = MemoryUtil.nmemAlloc(12288L);
   private final long[] metadataCache;
   private final int[] fluidStateLUT;
   private final byte[] vanillaFluidKinds;
   private final int[] idMappings;
   private final Object2IntOpenHashMap<ModelFactory.ModelEntry> modelTexture2id = new Object2IntOpenHashMap();
   private final IntOpenHashSet blockStatesInFlight = new IntOpenHashSet();
   private final ReentrantLock blockStatesInFlightLock = new ReentrantLock();
   private final List<Biome> biomes = new ArrayList<>();
   private final List<Pair<Integer, BlockState>> modelsRequiringBiomeColours = new ArrayList<>();
   private static final ObjectSet<BlockState> LOGGED_SELF_CULLING_WARNING = new ObjectOpenHashSet();
   private final Mapper mapper;
   private final ModelStore storage;
   private final BiomeBlendPalette blendPalette = new BiomeBlendPalette();
   private final ConcurrentLinkedDeque<ModelFactory.BlockBake> bakeQueue = new ConcurrentLinkedDeque<>();
   private final ConcurrentLinkedDeque<ModelFactory.ResultUploader> uploadResults = new ConcurrentLinkedDeque<>();
   private Object2IntMap<BlockState> customBlockStateIdMapping;
   private final ConcurrentLinkedDeque<Mapper.BiomeEntry> biomeQueue = new ConcurrentLinkedDeque<>();

   // ---- 模型登记与异步上传 -------------------------------------------

   public ModelFactory(Mapper mapper, ModelStore storage) {
      this.mapper = mapper;
      this.storage = storage;
      this.bakery2 = new SoftwareModelTextureBakery();
      this.bakery2.setupTexture();
      this.metadataCache = new long[65536];
      this.fluidStateLUT = new int[65536];
      this.vanillaFluidKinds = new byte[65536];
      this.idMappings = new int[1048576];
      Arrays.fill(this.idMappings, -1);
      Arrays.fill(this.fluidStateLUT, -1);
      this.modelTexture2id.defaultReturnValue(-1);
      this.addEntry(0);
   }

   public BiomeBlendPalette blendPalette() { return this.blendPalette; }

   public void setCustomBlockStateMapping(Object2IntMap<BlockState> mapping) {
      this.customBlockStateIdMapping = mapping;
   }

   public boolean addEntry(int blockId) {
      if (this.idMappings[blockId] != -1) {
         return false;
      } else {
         BlockState blockState = this.mapper.getBlockStateFromBlockId(blockId);
         if (blockState.getBlock() instanceof StairBlock sb) {
            blockState = sb.baseState.getBlock().withPropertiesOf(blockState);
         }

         boolean isFluid = blockState.getBlock() instanceof LiquidBlock;
         if (!isFluid && !blockState.getFluidState().isEmpty()) {
            BlockState fluidState = blockState.getFluidState().createLegacyBlock();
            int fluidStateId = this.mapper.getIdForBlockState(fluidState);
            if (this.idMappings[fluidStateId] == -1) {
               this.addEntry(fluidStateId);
            }
         }

         this.blockStatesInFlightLock.lock();

         boolean var11;
         try {
            if (!this.blockStatesInFlight.add(blockId)) {
               return false;
            }

            VarHandle.loadLoadFence();
            if (this.idMappings[blockId] == -1) {
               this.bakeQueue.add(new ModelFactory.BlockBake(blockId, blockState));
               return true;
            }

            var11 = false;
         } finally {
            this.blockStatesInFlightLock.unlock();
         }

         return var11;
      }
   }

   private boolean processModelResult() {
      ModelFactory.BlockBake bake = this.bakeQueue.poll();
      if (bake == null) {
         return false;
      } else {
         ColourDepthTextureData[] textureData = new ColourDepthTextureData[6];
         int flags = this.bakery2.renderToOutput(bake.state, this.bakeScratchBuffer);
         long ptr = this.bakeScratchBuffer;
         int FACE_SIZE = 256;

         for (int face = 0; face < 6; face++) {
            long faceDataPtr = ptr + 1024 * face * 2;
            int[] colour = new int[256];
            int[] depth = new int[256];

            for (int i = 0; i < 256; i++) {
               long value = MemoryUtil.memGetLong(faceDataPtr + i * 8);
               colour[i] = (int)value;
               depth[i] = (int)(value >>> 32);
            }

            textureData[face] = new ColourDepthTextureData(colour, depth, 16, 16);
         }

         boolean hasDarkenedTextures = (flags & 2) != 0;
         boolean isShaded = (flags & 1) != 0;
         ChunkSectionLayer layer = null;
         if (layer == null && (flags & 4) != 0) {
            boolean anyTranslucent = false;

            for (ColourDepthTextureData face : textureData) {
               anyTranslucent |= TextureUtils.hasTranslucentPixel(face);
               if (anyTranslucent) {
                  break;
               }
            }

            if (anyTranslucent) {
               layer = ChunkSectionLayer.TRANSLUCENT;
            } else {
               boolean solid = true;

               for (ColourDepthTextureData facex : textureData) {
                  solid &= TextureUtils.isSolidWhereDrawn(facex);
                  if (!solid) {
                     break;
                  }
               }

               if (solid) {
                  layer = ChunkSectionLayer.SOLID;
               } else {
                  layer = ChunkSectionLayer.CUTOUT;
               }
            }
         }

         if (layer == null && (flags & 8) != 0) {
            layer = ChunkSectionLayer.CUTOUT;
         }

         if (isLeafBlockState(bake.state)) {
            layer = VoxyConfig.CONFIG.getLeafLodMode() == VoxyConfig.LeafLodMode.FAST
               ? ChunkSectionLayer.SOLID
               : ChunkSectionLayer.CUTOUT;
         }

         if (layer == null) {
            layer = ChunkSectionLayer.SOLID;
         }

         boolean centeredGroundCross = bake.state.getBlock() instanceof BushBlock && !isLeafBlockState(bake.state);
         ModelFactory.ModelBakeResultUpload bakeResult = this.processTextureBakeResult(
            bake.blockId, bake.state, textureData, isShaded, hasDarkenedTextures, layer, centeredGroundCross
         );
         if (bakeResult != null) {
            this.uploadResults.add(bakeResult);
         }

         return !this.bakeQueue.isEmpty();
      }
   }

   public void addBiome(Mapper.BiomeEntry biome) {
      this.biomeQueue.add(biome);
   }

   public boolean processAllThings() {
      for (Mapper.BiomeEntry biomeEntry = this.biomeQueue.poll(); biomeEntry != null; biomeEntry = this.biomeQueue.poll()) {
         Registry<Biome> biomeRegistry = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BIOME);
         Optional<Reference<Biome>> mcbiomeEntry = biomeRegistry.get(Identifier.parse(biomeEntry.biome));
         if (!mcbiomeEntry.isPresent()) {
            Logger.warn("Could not find biome: " + biomeEntry.biome + " using default");
         }

         ModelFactory.BiomeUploadResult res = this.addBiome0(
            biomeEntry.id, mcbiomeEntry.isPresent() ? (Biome)mcbiomeEntry.orElseThrow().value() : this.DEFAULT_BIOME
         );
         if (res != null) {
            this.uploadResults.add(res);
         }
      }

      while (this.processModelResult()) {
      }

      return this.blockStatesInFlight.size() != 0 || !this.bakeQueue.isEmpty() || !this.biomeQueue.isEmpty();
   }

   public void drainBlendPalette() {
      if (this.blendPalette.drainUploads(this.storage.modelColourBuffer)) UploadStream.INSTANCE.commit();
   }

   public void processUploads(long totalBudgetNanos) {
      ModelFactory.ResultUploader upload = this.uploadResults.poll();
      if (upload != null) {
         long started = System.nanoTime();
         long budget = Math.max(0L, totalBudgetNanos);
         GL11.glPixelStorei(3314, 0);
         GL11.glPixelStorei(3316, 0);
         GL11.glPixelStorei(3315, 0);
         GL11.glPixelStorei(3317, 4);

         try {
            do {
               try {
                  upload.upload(this.storage);
               } finally {
                  upload.free();
               }
               // 预算耗尽后不再出队，剩余结果留待下一帧处理。
            } while (System.nanoTime() - started < budget
               && (upload = this.uploadResults.poll()) != null);
         } finally {
            UploadStream.INSTANCE.commit();
         }
      }
   }

   private ModelFactory.ModelBakeResultUpload processTextureBakeResult(
      int blockId, BlockState blockState, ColourDepthTextureData[] textureData, boolean isShaded, boolean darkenedTinting,
      ChunkSectionLayer layer, boolean centeredGroundCross
   ) {
      if (this.idMappings[blockId] != -1) {
         throw new IllegalStateException("Block id already added: " + blockId + " for state: " + blockState);
      } else {
         this.blockStatesInFlightLock.lock();
         if (!this.blockStatesInFlight.contains(blockId)) {
            this.blockStatesInFlightLock.unlock();
            throw new IllegalStateException("processing a texture bake result but the block state was not in flight!!");
         } else {
            this.blockStatesInFlightLock.unlock();
            boolean isFluid = blockState.getBlock() instanceof LiquidBlock;
            byte vanillaFluidKind = !isFluid ? 0
               : blockState.getFluidState().is(FluidTags.WATER) ? (byte)1
               : blockState.getFluidState().is(FluidTags.LAVA) ? (byte)2
               : 0;
            int modelId = -1;
            int clientFluidStateId = -1;
            if (!isFluid && !blockState.getFluidState().isEmpty()) {
               BlockState fluidState = blockState.getFluidState().createLegacyBlock();
               int fluidStateId = this.mapper.getIdForBlockState(fluidState);
               clientFluidStateId = this.idMappings[fluidStateId];
               if (clientFluidStateId == -1) {
                  throw new IllegalStateException("Block has a fluid state but fluid state is not already baked!!!");
               }
            }

            List<BlockTintSource> tintSources = getTintSources(blockState);
            boolean isBiomeColourDependent = false;
            if (tintSources != null) {
               isBiomeColourDependent = isBiomeDependentColour(tintSources, blockState);
            }

            ModelFactory.ModelEntry entry = new ModelFactory.ModelEntry(
               textureData,
               clientFluidStateId,
               !isBiomeColourDependent && tintSources != null ? captureColourConstant(tintSources, blockState, this.DEFAULT_BIOME) | 0xFF000000 : -1,
               isLeafBlockState(blockState)
            );
            int possibleDuplicate = this.modelTexture2id.getInt(entry);
            if (possibleDuplicate != -1) {
               this.idMappings[blockId] = possibleDuplicate;
               if (vanillaFluidKind != 0) {
                  this.vanillaFluidKinds[possibleDuplicate] = vanillaFluidKind;
               }
               this.blockStatesInFlightLock.lock();
               if (!this.blockStatesInFlight.remove(blockId)) {
                  this.blockStatesInFlightLock.unlock();
                  throw new IllegalStateException();
               } else {
                  this.blockStatesInFlightLock.unlock();
                  return null;
               }
            } else {
               modelId = this.modelTexture2id.size();
               this.modelTexture2id.put(entry, modelId);
               if (isFluid) {
                  this.fluidStateLUT[modelId] = modelId;
                  this.vanillaFluidKinds[modelId] = vanillaFluidKind;
               } else if (clientFluidStateId != -1) {
                  this.fluidStateLUT[modelId] = clientFluidStateId;
               }

               possibleDuplicate = layer == ChunkSectionLayer.SOLID ? 1 : 3;
               ModelFactory.ModelBakeResultUpload uploadResult = new ModelFactory.ModelBakeResultUpload();
               uploadResult.modelId = modelId;
               long uploadPtr = uploadResult.model.address;
               boolean leafModel = isLeafBlockState(blockState);
               if (!isFluid && !blockState.getFluidState().isEmpty() && clientFluidStateId != -1) {
                  isBiomeColourDependent |= ModelQueries.isBiomeColoured(this.getModelMetadataFromClientId(clientFluidStateId));
               }

               float[] depths = computeModelDepth(textureData, possibleDuplicate, layer != ChunkSectionLayer.SOLID ? 3 : 1);
               if (centeredGroundCross) {
                  for (Direction direction : Direction.Plane.HORIZONTAL) {
                     int face = direction.ordinal();
                     if (depths[face] >= -0.1F) depths[face] = 0.5F;
                  }
               }
               boolean needsDoubleSidedQuads = depths[0] < -0.1 && depths[1] < -0.1
                  || depths[2] < -0.1 && depths[3] < -0.1
                  || depths[4] < -0.1 && depths[5] < -0.1;
               boolean cullsSame = false;
               boolean allTrue = true;
               boolean allFalse = true;

               for (Direction dir : Direction.values()) {
                  if (blockState.skipRendering(blockState, dir)) {
                     allFalse = false;
                  } else {
                     allTrue = false;
                  }
               }

               if (allFalse == allTrue) {
                  cullsSame = false;
               }

               if (allTrue) {
                  cullsSame = true;
               }

               if (leafModel
                   && VoxyConfig.CONFIG.getLeafLodMode() == VoxyConfig.LeafLodMode.BALANCED) {
                  cullsSame = true;
               }

               long metadata = 0L;
               long var46 = metadata | (isBiomeColourDependent ? 1L : 0L);
               long var47 = var46 | (layer == ChunkSectionLayer.TRANSLUCENT ? 2L : 0L);
               long var48 = var47 | (needsDoubleSidedQuads ? 4L : 0L);
               long var49 = var48 | (!isFluid && !blockState.getFluidState().isEmpty() ? 8L : 0L);
               long var50 = var49 | (isFluid ? 16L : 0L);
               long var51 = var50 | (cullsSame ? 32L : 0L);
               boolean fullyOpaque = true;

               for (int face = 5; face != -1; face--) {
                  long faceUploadPtr = uploadPtr + 4L * face;
                  long var52 = var51 << 8;
                  float offset = depths[face];
                  if (offset < -0.1) {
                     var51 = var52 | 255L;
                     MemoryUtil.memPutInt(faceUploadPtr, -1);
                     fullyOpaque = false;
                  } else {
                     int[] faceSize = TextureUtils.computeBounds(textureData[face], possibleDuplicate);
                     int writeCount = TextureUtils.getWrittenPixelCount(textureData[face], possibleDuplicate);
                     boolean faceCoversFullBlock = faceSize[0] == 0 && faceSize[2] == 0 && faceSize[1] == 15 && faceSize[3] == 15;
                     long var53 = var52 | (faceCoversFullBlock ? 2L : 0L);
                     boolean occludesFace = true;
                     occludesFace &= layer != ChunkSectionLayer.TRANSLUCENT;
                     occludesFace &= offset < 0.1;
                     if (occludesFace) {
                        occludesFace &= writeCount / 256.0F > 0.9;
                     }

                     occludesFace &= faceCoversFullBlock;

                     long var54 = var53 | (occludesFace ? 1L : 0L);
                     fullyOpaque &= occludesFace;
                     boolean canBeOccluded = true;
                     canBeOccluded &= offset < 0.3;
                     long var55 = var54 | (canBeOccluded ? 4L : 0L);
                     var51 = var55 | (!(offset > 0.01) && layer != ChunkSectionLayer.TRANSLUCENT ? 0L : 8L);
                     int faceModelData = 0;
                     faceModelData |= faceSize[0] | faceSize[1] << 4 | faceSize[2] << 8 | faceSize[3] << 12;
                     int enc = Math.round(offset * 64.0F);
                     faceModelData |= Math.min(enc, 62) << 16;
                     int area = (faceSize[1] - faceSize[0] + 1) * (faceSize[3] - faceSize[2] + 1);
                     boolean needsAlphaDiscard = (float)writeCount / area < 0.9;
                     needsAlphaDiscard |= layer != ChunkSectionLayer.SOLID;
                     needsAlphaDiscard &= layer != ChunkSectionLayer.TRANSLUCENT;
                     faceModelData |= needsAlphaDiscard ? 4194304 : 0;
                     faceModelData |= !faceCoversFullBlock && layer != ChunkSectionLayer.TRANSLUCENT ? 8388608 : 0;
                     if (tintSources != null) {
                        int tintState = leafModel ? 3 : TextureUtils.computeFaceTint(textureData[face], possibleDuplicate);
                        if (tintState == 2) {
                           faceModelData |= 16777216;
                        } else if (tintState == 3) {
                           faceModelData |= 33554432;
                        }
                     }

                     MemoryUtil.memPutInt(faceUploadPtr, faceModelData);
                  }
               }

               long var56 = var51 | (fullyOpaque ? 18014398509481984L : 0L);
               boolean canBeCorrectlyRendered = true;
               FluidState fluidState = blockState.getFluidState();
               int fluidHeight = isFluid ? Math.clamp(Math.round(fluidState.getOwnHeight() * 9.0F), 1, 9) : 0;
               long var57 = var56 | (long)getBlockLightEmission(blockState) << 55;
               var57 |= (long)fluidHeight << 59;
               this.metadataCache[modelId] = var57;
               uploadPtr += 24L;
               int modelFlags = 0;
               modelFlags |= tintSources != null ? 1 : 0;
               modelFlags |= isBiomeColourDependent ? 2 : 0;
               modelFlags |= layer == ChunkSectionLayer.TRANSLUCENT ? 4 : 0;
               modelFlags |= isShaded ? 8 : 0;
               modelFlags |= isFluid && blockState.getFluidState().is(FluidTags.WATER) ? 16 : 0;
               modelFlags |= leafModel && VoxyConfig.CONFIG.getLeafLodMode() == VoxyConfig.LeafLodMode.BALANCED ? 32 : 0;
               modelFlags |= isFluid && blockState.getFluidState().is(FluidTags.LAVA) ? 64 : 0;
               modelFlags |= leafModel ? 128 : 0;
               modelFlags |= fluidHeight << 8;
               modelFlags |= isFluid ? (1 << 12) : 0;
               MemoryUtil.memPutInt(uploadPtr, modelFlags);
               uploadPtr += 4L;
               if (tintSources == null) {
                  MemoryUtil.memPutInt(uploadPtr, -1);
               } else if (!isBiomeColourDependent) {
                  MemoryUtil.memPutInt(uploadPtr, entry.tintingColour);
               } else {
                  int biomeIndex = this.modelsRequiringBiomeColours.size() * this.biomes.size();
                  MemoryUtil.memPutInt(uploadPtr, biomeIndex);
                  this.modelsRequiringBiomeColours.add(new Pair<>(modelId, blockState));
                  if (!this.biomes.isEmpty()) {
                     uploadResult.biomeUploadIndex = biomeIndex;
                     long clrUploadPtr = (uploadResult.biomeUpload = new MemoryBuffer(4L * this.biomes.size())).address;

                     for (Biome biome : this.biomes) {
                        MemoryUtil.memPutInt(clrUploadPtr, captureColourConstant(tintSources, blockState, biome) | 0xFF000000);
                        clrUploadPtr += 4L;
                     }
                     this.blendPalette.mirrorRow(modelId, biomeIndex, uploadResult.biomeUpload.address, this.biomes.size());
                  }
               }

               uploadPtr += 4L;
               if (this.customBlockStateIdMapping != null && this.customBlockStateIdMapping.containsKey(blockState)) {
                  MemoryUtil.memPutInt(uploadPtr, this.customBlockStateIdMapping.getInt(blockState));
               } else {
                  MemoryUtil.memPutInt(uploadPtr, 0);
               }

               uploadPtr += 4L;
               MipGen.putTextures(darkenedTinting, textureData, uploadResult.texture);
               this.idMappings[blockId] = modelId;
               this.blockStatesInFlightLock.lock();
               if (!this.blockStatesInFlight.remove(blockId)) {
                  this.blockStatesInFlightLock.unlock();
                  throw new IllegalStateException("processing a texture bake result but the block state was not in flight!!");
               } else {
                  this.blockStatesInFlightLock.unlock();
                  return uploadResult;
               }
            }
         }
      }
   }

   private static int getBlockLightEmission(final BlockState state) {
      boolean isEmissive = state.emissiveRendering(new BlockGetter() {
         public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return null;
         }

         public BlockState getBlockState(BlockPos pos) {
            return state;
         }

         public FluidState getFluidState(BlockPos pos) {
            return state.getFluidState();
         }

         public int getHeight() {
            return 0;
         }

         public int getMinY() {
            return 0;
         }
      }, BlockPos.ZERO);
      return isEmissive ? 15 : Math.clamp((long)state.getLightEmission(), 0, 15);
   }

   private ModelFactory.BiomeUploadResult addBiome0(int id, Biome biome) {
      if (biome == null) {
         throw new IllegalStateException("Null biome");
      } else {
         for (int i = this.biomes.size(); i <= id; i++) {
            this.biomes.add(null);
         }

         Biome oldBiome = this.biomes.set(id, biome);
         if (oldBiome != null && oldBiome != biome) {
            throw new IllegalStateException("Biome was put in an id that was not null");
         } else if (oldBiome == biome) {
            Logger.error("Biome added was a duplicate: " + id);
            return null;
         } else if (this.modelsRequiringBiomeColours.isEmpty()) {
            return null;
         } else {
            ModelFactory.BiomeUploadResult result = new ModelFactory.BiomeUploadResult(this.biomes.size(), this.modelsRequiringBiomeColours.size());
            int i = 0;
            long modelUpPtr = result.modelBiomeIndexPairs.address;

            for (Pair<Integer, BlockState> entry : this.modelsRequiringBiomeColours) {
               List<BlockTintSource> colourProvider = getTintSources(entry.right());
               if (colourProvider == null) {
                  throw new IllegalStateException();
               }

               int biomeIndex = i++ * this.biomes.size();
               MemoryUtil.memPutLong(modelUpPtr, Integer.toUnsignedLong(entry.left()) | Integer.toUnsignedLong(biomeIndex) << 32);
               modelUpPtr += 8L;
               long clrUploadPtr = result.biomeColourBuffer.address + biomeIndex * 4L;

               for (Biome biomeE : this.biomes) {
                  if (biomeE != null) {
                     MemoryUtil.memPutInt(clrUploadPtr, captureColourConstant(colourProvider, entry.right(), biomeE) | 0xFF000000);
                     clrUploadPtr += 4L;
                  }
               }
            }


            this.blendPalette.mirrorRebuild(result.modelBiomeIndexPairs.address,
               this.modelsRequiringBiomeColours.size(), result.biomeColourBuffer.address,
               (int)(result.biomeColourBuffer.size / 4L), this.biomes.size());
            return result;
         }
      }
   }

   private static List<BlockTintSource> getTintSources(BlockState block) {
      if (block.getBlock() instanceof LiquidBlock) {
         BlockTintSource tintSource = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(block.getFluidState()).tintSource();
         return tintSource == null ? null : List.of(tintSource);
      } else {
         List<BlockTintSource> tints = Minecraft.getInstance().getBlockColors().getTintSources(block);
         return tints.isEmpty() ? null : tints;
      }
   }

   private static int captureColourConstant(List<BlockTintSource> tintSources, final BlockState state, final Biome biome) {
      var getter = new BlockAndTintGetter() {
         public int getBrightness(LightLayer type, BlockPos pos) {
            return 0;
         }

         public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
         }

         public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
         }

         public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return colorResolver.getColor(biome, 0.0, 0.0);
         }

         @org.jetbrains.annotations.Nullable
         public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
         }

         public BlockState getBlockState(BlockPos pos) {
            return state;
         }

         public FluidState getFluidState(BlockPos pos) {
            return state.getFluidState();
         }

         public int getHeight() {
            return 0;
         }

         public int getMinY() {
            return 0;
         }
      };

      for (BlockTintSource source : tintSources) {
         if (source != null) {
            int c = source.colorInWorld(state, getter, BlockPos.ZERO);
            if (c != -1) {
               return c;
            }
         }
      }

      return -1;
   }

   public static boolean isLeafBlockState(BlockState state) {
      return state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock;
   }

   private static boolean isBiomeDependentColour(List<BlockTintSource> tintSources, final BlockState state) {
      final boolean[] biomeDependent = new boolean[1];
      var getter = new BlockAndTintGetter() {
         public int getBrightness(LightLayer type, BlockPos pos) {
            return 0;
         }

         public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
         }

         public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
         }

         public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            biomeDependent[0] = true;
            return 0;
         }

         @org.jetbrains.annotations.Nullable
         public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
         }

         public BlockState getBlockState(BlockPos pos) {
            return state;
         }

         public FluidState getFluidState(BlockPos pos) {
            return state.getFluidState();
         }

         public int getHeight() {
            return 0;
         }

         public int getMinY() {
            return 0;
         }
      };

      for (BlockTintSource source : tintSources) {
         if (source != null) {
            source.colorInWorld(state, getter, BlockPos.ZERO);
         }
      }

      return biomeDependent[0];
   }

   private static float[] computeModelDepth(ColourDepthTextureData[] textures, int checkMode) {
      return computeModelDepth(textures, checkMode, 1);
   }

   private static float[] computeModelDepth(ColourDepthTextureData[] textures, int checkMode, int computeMode) {
      float[] res = new float[6];

      for (Direction dir : Direction.values()) {
         ColourDepthTextureData data = textures[dir.get3DDataValue()];
         float fd = TextureUtils.computeDepth(data, computeMode, checkMode);
         if (fd < -0.1) {
            res[dir.ordinal()] = -1.0F;
         } else {
            res[dir.ordinal()] = fd;
         }
      }

      return res;
   }

   public int[] _unsafeRawAccess() {
      return this.idMappings;
   }

   public int getModelId(int blockId) {
      int map = this.idMappings[blockId];
      if (map == -1) {
         throw new IdNotYetComputedException(blockId, true);
      } else {
         return map;
      }
   }

   public boolean hasModelForBlockId(int blockId) {
      return this.idMappings[blockId] != -1;
   }

   public int getFluidClientStateId(int clientBlockStateId) {
      int map = this.fluidStateLUT[clientBlockStateId];
      if (map == -1) {
         throw new IdNotYetComputedException(clientBlockStateId, false);
      } else {
         return map;
      }
   }

   public int getVanillaFluidKind(int clientId) {
      return this.vanillaFluidKinds[clientId];
   }

   public final long getModelMetadataFromClientId(int clientId) {
      return this.metadataCache[clientId];
   }

   public void free() {
      this.bakery2.free();
      MemoryUtil.nmemFree(this.bakeScratchBuffer);

      while (!this.uploadResults.isEmpty()) {
         this.uploadResults.poll().free();
      }
   }

   public int getBakedCount() {
      return this.modelTexture2id.size();
   }

   public int getInflightCount() {
      int size = this.blockStatesInFlight.size();
      size += this.uploadResults.size();
      size += this.biomeQueue.size();
      return size + this.bakeQueue.size();
   }

   private static int computeSizeWithMips(int size) {
      int total = 0;

      while (size != 0) {
         total += size * size;
         size >>= 1;
      }

      return total;
   }

   private static final class BiomeUploadResult implements ModelFactory.ResultUploader {
      private final MemoryBuffer biomeColourBuffer;
      private final MemoryBuffer modelBiomeIndexPairs;

      private BiomeUploadResult(int biomes, int models) {
         this.biomeColourBuffer = new MemoryBuffer(biomes * models * 4);
         this.modelBiomeIndexPairs = new MemoryBuffer(models * 8);
      }

      @Override
      public void upload(ModelStore store) {
         store.ensureBiomeColourCapacity(this.biomeColourBuffer.size / 4);
         this.upload(store.modelBuffer, store.modelColourBuffer);
      }

      public void upload(GlBuffer modelBuffer, GlBuffer modelColourBuffer) {
         this.biomeColourBuffer.cpyTo(UploadStream.INSTANCE.upload(modelColourBuffer, BiomeBlendPalette.NORMAL_COLOUR_BASE * 4L, this.biomeColourBuffer.size));
         long ptr = this.modelBiomeIndexPairs.address;

         for (long offset = 0L; offset < this.modelBiomeIndexPairs.size; offset += 8L) {
            long v = MemoryUtil.memGetLong(ptr);
            ptr += 8L;
            MemoryUtil.memPutInt(UploadStream.INSTANCE.upload(modelBuffer, 64L * (v & 4294967295L) + 24L + 4L, 4L), (int)(v >>> 32));
         }

         this.biomeColourBuffer.free();
         this.modelBiomeIndexPairs.free();
      }

      @Override
      public void free() {
         if (!this.biomeColourBuffer.isFreed()) {
            this.biomeColourBuffer.free();
            this.modelBiomeIndexPairs.free();
         }
      }
   }

   private record BlockBake(int blockId, BlockState state) {
   }

   private static final class ModelBakeResultUpload implements ModelFactory.ResultUploader {
      private final MemoryBuffer model = new MemoryBuffer(64L).zero();
      private final MemoryBuffer texture = new MemoryBuffer(6L * ModelFactory.computeSizeWithMips(16) * 4L);
      public int modelId = -1;
      public int biomeUploadIndex = -1;
      @org.jetbrains.annotations.Nullable
      public MemoryBuffer biomeUpload;

      @Override
      public void upload(ModelStore store) {
         if (this.biomeUploadIndex != -1) {
            store.ensureBiomeColourCapacity(this.biomeUploadIndex + this.biomeUpload.size / 4);
         }
         this.upload(store.modelBuffer, store.modelColourBuffer, store.textures);
      }

      public void upload(GlBuffer modelBuffer, GlBuffer colourBuffer, GlTexture atlas) {
         this.model.cpyTo(UploadStream.INSTANCE.upload(modelBuffer, this.modelId * 64L, 64L));
         if (this.biomeUploadIndex != -1) {
            this.biomeUpload.cpyTo(UploadStream.INSTANCE.upload(colourBuffer, (BiomeBlendPalette.NORMAL_COLOUR_BASE + this.biomeUploadIndex) * 4L, this.biomeUpload.size));
            this.biomeUploadIndex = -1;
            this.biomeUpload.free();
            this.biomeUpload = null;
         }

         int X = (this.modelId & 0xFF) * 16 * 3;
         int Y = (this.modelId >> 8 & 0xFF) * 16 * 2;
         long cAddr = this.texture.address;

         for (int lvl = 0; lvl < ModelFactory.LAYERS; lvl++) {
            ARBDirectStateAccess.nglTextureSubImage2D(atlas.id, lvl, X >> lvl, Y >> lvl, 48 >> lvl, 32 >> lvl, 6408, 5121, cAddr);
            cAddr += 6144 >> (lvl << 1);
         }

         this.modelId = -1;
      }

      @Override
      public void free() {
         this.model.free();
         this.texture.free();
         if (this.biomeUpload != null) {
            this.biomeUpload.free();
         }
      }
   }

   private record ModelEntry(
      ColourDepthTextureData down,
      ColourDepthTextureData up,
      ColourDepthTextureData north,
      ColourDepthTextureData south,
      ColourDepthTextureData west,
      ColourDepthTextureData east,
      int fluidBlockStateId,
      int tintingColour,
      boolean leafModel
   ) {
      public ModelEntry(ColourDepthTextureData[] textures, int fluidBlockStateId, int tintingColour, boolean leafModel) {
         this(textures[0], textures[1], textures[2], textures[3], textures[4], textures[5], fluidBlockStateId, tintingColour, leafModel);
      }
   }

   private interface ResultUploader {
      void upload(ModelStore var1);

      void free();
   }
}
