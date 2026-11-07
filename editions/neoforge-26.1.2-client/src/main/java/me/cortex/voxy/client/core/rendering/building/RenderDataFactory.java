package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.client.core.util.ScanMesher2D;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.SurfaceWorldView;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.Objects;

/** 26.1.2 NeoForge 的区段网格构建器；固定数组尺寸对应该版本的 32³ 区段。 */
public class RenderDataFactory {
   private static final boolean BUILD_OCCUPANCY_SET = false;
   private static final boolean CHECK_NEIGHBOR_FACE_OCCLUSION = true;
   private static final boolean DISABLE_CULL_SAME_OCCLUDES = false;
   private static final boolean VERIFY_MESHING = VoxyCommon.isVerificationFlagOn("verifyMeshing");
   private final WorldEngine world;
   private final ModelFactory modelMan;
   private final long[] sectionData = new long[65536];
   private final long[] neighboringFaces = new long[6144];
   private final int[] opaqueMasks = new int[1024];
   private final int[] nonOpaqueMasks = new int[1024];
   private final int[] fluidMasks = new int[1024];
   private final short[] biomeIds = new short[32768];
   private final MemoryBuffer quadBuffer = new MemoryBuffer(4194304L);
   private final long quadBufferPtr = this.quadBuffer.address;
   private static final int MAX_BUCKET_QUADS = (1 << 16) - 1;
   private int droppedQuads;
   private final int[] quadCounters = new int[8];
   private int minX;
   private int minY;
   private int minZ;
   private int maxX;
   private int maxY;
   private int maxZ;
   private int quadCount = 0;
   private final OccupancySet occupancy;
   private final RenderDataFactory.Mesher blockMesher = new RenderDataFactory.Mesher();
   // 非不透明或双面几何使用独立扫描器，避免覆盖普通面的合并状态。
   private final RenderDataFactory.Mesher secondaryBlockMesher = new RenderDataFactory.Mesher();
   // 流体描述、面剔除和结果输出按固定顺序排列，便于与主版本对照。
   private static final long LM = 9187343239835811840L;
    private int fluidModelId(int voxelIndex) {
        long quad = this.sectionData[voxelIndex * 2];
        long metadata = this.sectionData[voxelIndex * 2 + 1];
        int modelId = (int) ((quad >>> 26) & 0xFFFF);
        return ModelQueries.containsFluid(metadata)
                ? this.modelMan.getFluidClientStateId(modelId)
                : modelId;
    }

    private int fluidKind(int voxelIndex) {
        if (!hasFluid(voxelIndex)) return 0;
        return this.modelMan.getVanillaFluidKind(fluidModelId(voxelIndex));
    }

    private int rawFluidModelId(long raw) {
        if (Mapper.isAir(raw)) return 0;
        int modelId = this.modelMan.getModelId(Mapper.getBlockId(raw));
        long metadata = this.modelMan.getModelMetadataFromClientId(modelId);
        return ModelQueries.containsFluid(metadata)
                ? this.modelMan.getFluidClientStateId(modelId)
                : ModelQueries.isFluid(metadata) ? modelId : 0;
    }

    private int rawFluidKind(long raw) {
        int modelId = rawFluidModelId(raw);
        return modelId == 0 ? 0 : this.modelMan.getVanillaFluidKind(modelId);
    }

    private long rawModelMetadata(long raw) {
        if (Mapper.isAir(raw)) return 0;
        int modelId = this.modelMan.getModelId(Mapper.getBlockId(raw));
        return this.modelMan.getModelMetadataFromClientId(modelId);
    }

    private int rawFluidHeight(long raw) {
        int modelId = rawFluidModelId(raw);
        return modelId == 0 ? 0 : ModelQueries.fluidHeight(
                this.modelMan.getModelMetadataFromClientId(modelId));
    }

    private long horizontalNeighborRaw(int x, int y, int z) {
        if (this.horizontalHalo == null || y < 0 || y > 32) return 0;
        return this.horizontalHalo[haloIndex(x, y, z)];
    }

    private int effectiveRawFluidHeight(long raw, int x, int y, int z, int kind) {
        int height = rawFluidHeight(raw);
        if (kind == 0) return height;
        return rawFluidKind(horizontalNeighborRaw(x, y + 1, z)) == kind ? 9 : height;
    }

    private int fluidDescriptor(int voxelIndex) {
        int modelId = fluidModelId(voxelIndex);
        long metadata = this.modelMan.getModelMetadataFromClientId(modelId);
        return (ModelQueries.isBiomeColoured(metadata) ? 1 << 5 : 0) | ModelQueries.fluidHeight(metadata);
    }

    private int effectiveFluidHeight(int voxelIndex) {
        int height = fluidDescriptor(voxelIndex) & 31;
        int y = voxelIndex >>> 10;
        int kind = fluidKind(voxelIndex);
        if (kind != 0 && y < 31 && fluidKind(voxelIndex + 1024) == kind) return 9;
        if (kind != 0 && y == 31) {
            int x = voxelIndex & 31;
            int z = (voxelIndex >>> 5) & 31;
            if (rawFluidKind(this.neighboringFaces[3 * 32 * 32 + x + z * 32]) == kind) return 9;
        }
        return height;
    }

    private float fluidCornerHeight(int voxelIndex, int cornerX, int cornerZ) {
        int kind = fluidKind(voxelIndex);
        if (kind == 0) return effectiveFluidHeight(voxelIndex);
        int x = voxelIndex & 31;
        int z = (voxelIndex >>> 5) & 31;
        int y = voxelIndex >>> 10;
        float weightedHeight = 0.0f;
        float weight = 0.0f;
        for (int dz = cornerZ - 1; dz <= cornerZ; dz++) {
            for (int dx = cornerX - 1; dx <= cornerX; dx++) {
                int sx = x + dx;
                int sz = z + dz;
                float sampleHeight;
                if (sx >= 0 && sx < 32 && sz >= 0 && sz < 32) {
                    int sample = sx | (sz << 5) | (y << 10);
                    if (fluidKind(sample) == kind) {
                        sampleHeight = effectiveFluidHeight(sample);
                    } else {
                        sampleHeight = ModelQueries.isFullyOpaque(this.sectionData[sample * 2 + 1]) ? -1.0f : 0.0f;
                    }
                } else {
                    long raw = horizontalNeighborRaw(sx, y, sz);
                    if (rawFluidKind(raw) == kind) {
                        sampleHeight = effectiveRawFluidHeight(raw, sx, y, sz, kind);
                    } else {
                        sampleHeight = ModelQueries.isFullyOpaque(rawModelMetadata(raw)) ? -1.0f : 0.0f;
                    }
                }
                if (sampleHeight >= 9.0f) return 9.0f;
                if (sampleHeight >= 0.0f) {
                    float sampleWeight = sampleHeight >= 7.2f ? 10.0f : 1.0f;
                    weightedHeight += sampleHeight * sampleWeight;
                    weight += sampleWeight;
                }
            }
        }
        return weight > 0.0f ? weightedHeight / weight : effectiveFluidHeight(voxelIndex);
    }

    private static int encodeFluidCornerHeight(float height) {
        if (height <= 0.01f) return 0;
        if (height >= 8.5f) return 7;
        if (height >= 6.5f) return 6;
        return Math.clamp(Math.round(height) - 1, 1, 5);
    }

    private static int packFluidCorners(float c0, float c1, float c2, float c3) {
        return encodeFluidCornerHeight(c0)
                | (encodeFluidCornerHeight(c1) << 3)
                | (encodeFluidCornerHeight(c2) << 6)
                | (encodeFluidCornerHeight(c3) << 9);
    }

    private long encodeFluidStep(long quad, int lowerHeight, int voxelIndex) {
        if (lowerHeight <= 0) return quad;
        quad &= ~((0xFL << 42) | (0x1FFL << 46) | (1L << 63));
        quad |= ((long) lowerHeight) << 42;
        int paletteIndex = this.fluidBlendIds[voxelIndex];
        quad |= paletteIndex >= 0 ? (1L << 63) | ((long) paletteIndex << 46)
                : ((long) this.biomeIds[voxelIndex] & 0x1FFL) << 46;
        return quad;
    }

    private long encodeFluidShape(long quad, int payload, int voxelIndex) {
        quad &= ~((0xFL << 42) | (0x1FFL << 46) | (1L << 63));
        quad |= ((long) ((payload >>> 8) & 0xF)) << 42;
        int paletteIndex = this.fluidBlendIds[voxelIndex];
        quad |= paletteIndex >= 0 ? (1L << 63) | ((long) paletteIndex << 46)
                : ((long) this.biomeIds[voxelIndex] & 0x1FFL) << 46;
        return quad;
    }

    private static long maxQuadLight(long a, long b) {
        final long SKYMSK = 0xFL << 55;
        final long BLKMSK = 0xFL << 59;
        return Math.max(a & SKYMSK, b & SKYMSK) | Math.max(a & BLKMSK, b & BLKMSK);
    }
   private final RenderDataFactory.Mesher[] xAxisMeshers = new RenderDataFactory.Mesher[32];
   private final RenderDataFactory.Mesher[] secondaryXAxisMeshers = new RenderDataFactory.Mesher[32];
   private static final long X_I_MSK = 1162219258676257L;

   public RenderDataFactory(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets) {
      this(world, modelManager, emitMeshlets, false);
   }

   /** 创建并复用 32³ 区段的建面工作区。 */
   public RenderDataFactory(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets, boolean generateOccupancy) {
      for (int i = 0; i < 32; i++) {
         RenderDataFactory.Mesher mesher = new RenderDataFactory.Mesher();
         mesher.auxiliaryPosition = i;
         mesher.axis = 2;
         this.xAxisMeshers[i] = mesher;
      }

      for (int i = 0; i < 32; i++) {
         RenderDataFactory.Mesher mesher = new RenderDataFactory.Mesher();
         mesher.auxiliaryPosition = i;
         mesher.axis = 2;
         mesher.doAuxiliaryFaceOffset = false;
         this.secondaryXAxisMeshers[i] = mesher;
      }

      this.world = world;
      this.modelMan = modelManager;
      if (generateOccupancy) {
      }

      this.occupancy = null;
   }

   private static long getQuadTyping(long metadata) {
      return 7L & 20L >> (int)(ModelQueries._isTranslucent(metadata) * 6L + ModelQueries._isDoubleSided(metadata) * 3L);
   }

   private static long packPartialQuadData(int modelId, long state, long metadata) {
      long lightAndBiome = (state & -140737488355328L) >>> 1;
      lightAndBiome &= ~(ModelQueries._notIsBiomeColoured(metadata) * 35958428274786304L);
      lightAndBiome &= ~(ModelQueries._isFullyOpaque(metadata) * 9187343239835811840L);
      long quadData = lightAndBiome | Integer.toUnsignedLong(modelId) << 26;
      return quadData | getQuadTyping(metadata);
   }

   private final int[] blendBiomeScratch = new int[225];

   private static final int HALO_RADIUS = 7;
    private static final int HALO_STRIDE = 32 + 2 * HALO_RADIUS;
    private static final int HALO_LAYER = HALO_STRIDE * HALO_STRIDE;
    private long[] horizontalHalo;
    private final SurfaceWorldView surfaceView = new SurfaceWorldView();
    private final short[] fluidBlendIds = new short[32768];
    private final int[] blendHeads = new int[65536];
    private final int[] blendNext = new int[1024];
    private final int[] blendModels = new int[1024];
    private final int[][] blendPrefix = new int[5][47 * 47];

    private static int haloIndex(int x, int y, int z) {
        return (x + HALO_RADIUS) + (z + HALO_RADIUS) * HALO_STRIDE + y * HALO_LAYER;
    }

    private long sampleHorizontal(long[] raw, int x, int y, int z) {
        if (y < 32 && x >= 0 && x < 32 && z >= 0 && z < 32) return raw[x | (z << 5) | (y << 10)];
        return this.horizontalHalo[haloIndex(x, y, z)];
    }

    /** 每个邻区只获取一次，只复制半径内的边带；不在逐体素采样中访问存储。 */
    private void prepareHorizontalHalo(WorldSection section, boolean fluid, int radius) {
        if (this.horizontalHalo == null) this.horizontalHalo = new long[HALO_LAYER * 33];
        for (int dy = 0; dy <= (fluid ? 1 : 0); dy++) {
            int r = dy == 0 ? radius : 1;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dy == 0 && dx == 0 && dz == 0) continue;
                    int minX = dx < 0 ? -r : dx > 0 ? 32 : 0;
                    int maxX = dx < 0 ? 0 : dx > 0 ? 32 + r : 32;
                    int minZ = dz < 0 ? -r : dz > 0 ? 32 : 0;
                    int maxZ = dz < 0 ? 0 : dz > 0 ? 32 + r : 32;
                    for (int y = 0; y < (dy == 0 ? 32 : 1); y++) {
                        for (int z = minZ; z < maxZ; z++) {
                            int destination = haloIndex(minX, dy * 32 + y, z);
                            this.surfaceView.copyRow(minX, dy * 32 + y, z,
                                    this.horizontalHalo, destination, maxX - minX);
                        }
                    }

                }
            }
        }
    }

    private int blendModel(int index, boolean waterOnly) {
        long meta = this.sectionData[index * 2 + 1];
        if (ModelQueries.containsFluid(meta)) {
            int model = fluidModelId(index);
            return ModelQueries.isBiomeColoured(this.modelMan.getModelMetadataFromClientId(model)) ? model : -1;
        }
        if (meta == 0 || !ModelQueries.isBiomeColoured(meta) || (waterOnly && !ModelQueries.isFluid(meta))) return -1;
        return (int) ((this.sectionData[index * 2] >>> 26) & 0xFFFF);
    }

    private void buildBlendPrefix(long[] raw, me.cortex.voxy.client.core.model.BiomeBlendPalette.Snapshot colours,
                                  int model, int y, int radius) {
        int width = 32 + 2 * radius;
        int stride = width + 1;
        for (var prefix : this.blendPrefix) Arrays.fill(prefix, 0, stride, 0);
        for (int z = 0; z < width; z++) {
            for (var prefix : this.blendPrefix) prefix[(z + 1) * stride] = 0;
            for (int x = 0; x < width; x++) {
                long sample = sampleHorizontal(raw, x - radius, y, z - radius);
                boolean missing = Mapper.isAir(sample);
                int colour = missing ? 0 : colours.colourOf(model, Mapper.getBiomeId(sample));
                int at = (z + 1) * stride + x + 1;
                for (int c = 0; c < 5; c++) {
                    int value = c < 3 ? (colour >>> (c * 8)) & 255 : c == 3 ? (missing ? 1 : 0) : (!missing && colour == -1 ? 1 : 0);
                    // 缺失点按中心群系回退；不可用颜色则使整个窗口暂缓。
                    if (c < 3 && (missing || colour == -1)) value = 0;
                    int[] prefix = this.blendPrefix[c];
                    prefix[at] = value + prefix[at - 1] + prefix[at - stride] - prefix[at - stride - 1];
                }
            }
        }
    }

    private int blendSum(int channel, int x, int z, int window, int stride) {
        int[] p = this.blendPrefix[channel];
        int a = z * stride + x, b = a + window, c = a + window * stride, d = c + window;
        return p[d] - p[b] - p[c] + p[a];
    }

    private void applyBiomeBlend(long[] raw) {
        Arrays.fill(this.fluidBlendIds, (short) -1);
        int radius = Math.min(7, Math.max(0, me.cortex.voxy.client.config.VoxyConfig.CONFIG.biomeBlendRadius));
        if (radius == 0) return;
        var palette = this.modelMan.blendPalette();
        var colours = palette.snapshot();
        if (colours.rowBaseByModelId().length == 0) return;
        boolean waterOnly = !"water_grass".equals(me.cortex.voxy.client.config.VoxyConfig.CONFIG.biomeBlendScope);
        int window = radius * 2 + 1, samples = window * window, width = 32 + 2 * radius;
        for (int y = 0; y < 32; y++) {
            int modelCount = 0;
            try {
                for (int local = 0; local < 1024; local++) {
                    int model = blendModel((y << 10) | local, waterOnly);
                    if (model < 0) continue;
                    if (this.blendHeads[model] == 0) this.blendModels[modelCount++] = model;
                    this.blendNext[local] = this.blendHeads[model];
                    this.blendHeads[model] = local + 1;
                }
                for (int m = 0; m < modelCount; m++) {
                    int model = this.blendModels[m], count = 0;
                    for (int n = this.blendHeads[model]; n != 0; n = this.blendNext[n - 1]) count++;
                    // 密集同材质平面只算一次二维前缀和；稀疏模型保留小窗口直采。
                    boolean prefix = count * samples > width * width;
                    if (prefix) buildBlendPrefix(raw, colours, model, y, radius);
                    for (int n = this.blendHeads[model]; n != 0; n = this.blendNext[n - 1]) {
                        int local = n - 1, index = (y << 10) | local, x = local & 31, z = local >>> 5;
                        int center = colours.colourOf(model, Mapper.getBiomeId(raw[index]));
                        if (center == -1) continue;
                        int red = 0, green = 0, blue = 0;
                        if (prefix) {
                            if (blendSum(4, x, z, window, width + 1) != 0) continue;
                            int missing = blendSum(3, x, z, window, width + 1);
                            red = blendSum(0, x, z, window, width + 1) + missing * (center & 255);
                            green = blendSum(1, x, z, window, width + 1) + missing * ((center >>> 8) & 255);
                            blue = blendSum(2, x, z, window, width + 1) + missing * ((center >>> 16) & 255);
                        } else {
                            boolean unavailable = false;
                            for (int dz = -radius; dz <= radius && !unavailable; dz++) {
                                for (int dx = -radius; dx <= radius; dx++) {
                                    long sample = sampleHorizontal(raw, x + dx, y, z + dz);
                                    int colour = Mapper.isAir(sample) ? center : colours.colourOf(model, Mapper.getBiomeId(sample));
                                    if (colour == -1) { unavailable = true; break; }
                                    red += colour & 255; green += (colour >>> 8) & 255; blue += (colour >>> 16) & 255;
                                }
                            }
                            if (unavailable) continue;
                        }
                        int blended = (red / samples) | ((green / samples) << 8) | ((blue / samples) << 16);
                        if (blended == (center & 0xFFFFFF)) continue;
                        boolean fluid = hasFluid(index);
                        int paletteIndex = fluid ? palette.indexForFluid(blended) : palette.indexFor(blended);
                        if (paletteIndex < 0) continue;
                        if (fluid) this.fluidBlendIds[index] = (short) paletteIndex;
                        // 含水方块的本体保留自身颜色，覆盖层单独读取 fluidBlendIds。
                        if (ModelQueries.containsFluid(this.sectionData[index * 2 + 1])) continue;
                        long quad = this.sectionData[index * 2] & ~((0x1FFL << 46) | (0xFL << 42));
                        quad |= ((long) (paletteIndex & 0x1FF)) << 46;
                        if (!fluid) quad |= ((long) (paletteIndex >>> 9)) << 42;
                        this.sectionData[index * 2] = quad | (1L << 63);
                    }
                }
            } finally {
                for (int m = 0; m < modelCount; m++) this.blendHeads[this.blendModels[m]] = 0;
            }
        }
    }

   private int prepareSectionData(long[] rawSectionData) {
      long[] sectionData = this.sectionData;
      int[] rawModelIds = this.modelMan._unsafeRawAccess();
      long opaque = 0L;
      long notEmpty = 0L;
      long pureFluid = 0L;
      long partialFluid = 0L;
      int neighborAcquireMskAndFlags = 0;
      int i = 0;

      for (int q = 0; q < 512; q++) {
         for (int j = 0; j < 64; j++) {
            long block = rawSectionData[i];
            this.biomeIds[i] = (short)Mapper.getBiomeId(block);
            if (Mapper.isAir(block)) {
               sectionData[i * 2] = (block & -72057594037927936L) >>> 1;
               sectionData[i * 2 + 1] = 0L;
            } else {
               int modelId = rawModelIds[Mapper.getBlockId(block)];
               if (modelId == -1) {
                  return Mapper.getBlockId(block) | -2147483648;
               }

               if (modelId == 0) {
                  sectionData[i * 2] = (block & -72057594037927936L) >>> 1;
                  sectionData[i * 2 + 1] = 0L;
               } else {
                  long modelMetadata = this.modelMan.getModelMetadataFromClientId(modelId);
                  sectionData[i * 2] = packPartialQuadData(modelId, block, modelMetadata);
                  sectionData[i * 2 + 1] = modelMetadata;
                  notEmpty |= 1L << j;
                  opaque |= ModelQueries._isFullyOpaque(modelMetadata) << j;
                  pureFluid |= ModelQueries._isFluid(modelMetadata) << j;
                  partialFluid |= ModelQueries._containsFluid(modelMetadata) << j;
               }
            }

            i++;
         }

         if (notEmpty != 0L) {
            long nonOpaque = (notEmpty ^ opaque) & ~pureFluid;
            long fluid = pureFluid | partialFluid;
            this.opaqueMasks[(i >> 5) - 2] = (int)opaque;
            this.opaqueMasks[(i >> 5) - 1] = (int)(opaque >>> 32);
            this.nonOpaqueMasks[(i >> 5) - 2] = (int)nonOpaque;
            this.nonOpaqueMasks[(i >> 5) - 1] = (int)(nonOpaque >>> 32);
            this.fluidMasks[(i >> 5) - 2] = (int)fluid;
            this.fluidMasks[(i >> 5) - 1] = (int)(fluid >>> 32);
            neighborAcquireMskAndFlags |= getNeighborMsk(notEmpty, i);
            neighborAcquireMskAndFlags |= opaque != 0L ? 64 : 0;
            opaque = 0L;
            notEmpty = 0L;
            pureFluid = 0L;
            partialFluid = 0L;
         }
      }

      return neighborAcquireMskAndFlags;
   }

   private static int getNeighborMsk(long notEmpty, int i) {
      int packedEmpty = (int)(notEmpty >>> 32 | notEmpty);
      int neighborMsk = 0;
      neighborMsk += packedEmpty & 1;
      neighborMsk += packedEmpty >>> 30 & 2;
      neighborMsk += (i - 1 >> 10 == 0 ? 4 : 0) * (packedEmpty != 0 ? 1 : 0);
      neighborMsk += (i - 1 >> 10 == 31 ? 8 : 0) * (packedEmpty != 0 ? 1 : 0);
      neighborMsk += ((i - 33 >> 5 & 31) == 0 ? 16 : 0) * ((int)notEmpty != 0 ? 1 : 0);
      return neighborMsk + ((i - 1 >> 5 & 31) == 31 ? 32 : 0) * (notEmpty >>> 32 != 0L ? 1 : 0);
   }

   private void acquireNeighborData(WorldSection section, int msk) {
        int unavailable = 0;
        for (int face = 0; face < 6; face++) {
            if ((msk & (1 << face)) == 0) continue;
            long missing = 0L;
            if (!this.surfaceView.copyFace(face, this.neighboringFaces, face * 1024, missing)) {
                unavailable |= 1 << face;
            }
        }

    }

   private static boolean shouldMeshNonOpaqueBlockFace(int face, long quad, long meta, long neighborQuad, long neighborMeta) {
      if (((quad ^ neighborQuad) & 4397979402240L) == 0L && ModelQueries.cullsSame(meta)) {
         return false;
      } else {
         return !ModelQueries.faceExists(meta, face)
            ? false
            : !ModelQueries.faceCanBeOccluded(meta, face) || !ModelQueries.faceOccludes(neighborMeta, face ^ 1);
      }
   }

   private static void meshNonOpaqueFace(int face, long quad, long meta, long neighborQuad, long neighborMeta, RenderDataFactory.Mesher mesher) {
      if (shouldMeshNonOpaqueBlockFace(face, quad, meta, neighborQuad, neighborMeta)) {
         mesher.putNext(
            applyQuadLight(
               face & 1 | quad & -9187343239835811841L | (ModelQueries.faceUsesSelfLighting(meta, face) ? quad : neighborQuad) & 9187343239835811840L, meta
            )
         );
      } else {
         mesher.skip(1);
      }
   }

   private static long applyQuadLight(long quad, long selfmeta) {
      long BLMSK = 8646911284551352320L;
      long bl = quad & 8646911284551352320L;
      bl = Math.max(bl, ModelQueries.lightEmission(selfmeta) << 59);
      quad &= -8646911284551352321L;
      return quad | bl;
   }

   private void generateYZOpaqueInnerGeometry(int axis) {
      for (int layer = 0; layer < 31; layer++) {
         this.blockMesher.auxiliaryPosition = layer;
         int cSkip = 0;

         for (int other = 0; other < 32; other++) {
            int pidx = axis == 0 ? layer * 32 + other : other * 32 + layer;
            int skipAmount = axis == 0 ? 32 : 1;
            int current = this.opaqueMasks[pidx];
            int next = this.opaqueMasks[pidx + skipAmount];
            int msk = current ^ next;
            if (msk == 0) {
               cSkip += 32;
            } else {
               this.blockMesher.skip(cSkip);
               cSkip = 0;
               int faceForwardMsk = msk & current;
               int cIdx = -1;

               while (msk != 0) {
                  int index = Integer.numberOfTrailingZeros(msk);
                  int delta = index - cIdx - 1;
                  cIdx = index;
                  if (delta != 0) {
                     this.blockMesher.skip(delta);
                  }

                  msk &= ~Integer.lowestOneBit(msk);
                  int facingForward = faceForwardMsk >> index & 1;
                  int idx = index + pidx * 32;
                  int shift = skipAmount * 32 * 2;
                  int iA = idx * 2 + (facingForward == 1 ? 0 : shift);
                  int iB = idx * 2 + (facingForward == 1 ? shift : 0);
                  long selfModel = this.sectionData[iA];
                  long selfMeta = this.sectionData[iA + 1];
                  long nextModel = this.sectionData[iB];
                  long neighbor = this.sectionData[iB + 1];
                  boolean culls = false;
                  culls |= ((selfModel ^ nextModel) & 4397979402240L) == 0L && ModelQueries.cullsSame(neighbor);
                  culls |= ModelQueries.faceOccludes(neighbor, axis << 1 | 1 - facingForward);
                  if (culls) {
                     this.blockMesher.skip(1);
                  } else {
                     this.blockMesher.putNext(applyQuadLight(facingForward | selfModel & -9187343239835811841L | nextModel & 9187343239835811840L, selfMeta));
                  }
               }

               this.blockMesher.endRow();
            }
         }

         this.blockMesher.finish();
      }
   }

   private void generateYZOpaqueOuterGeometry(int axis) {
      this.blockMesher.doAuxiliaryFaceOffset = false;

      for (int side = 0; side < 2; side++) {
         int layer = side == 0 ? 0 : 31;
         this.blockMesher.auxiliaryPosition = layer;
         int cSkips = 0;

         for (int other = 0; other < 32; other++) {
            int pidx = axis == 0 ? layer * 32 + other : other * 32 + layer;
            int msk = this.opaqueMasks[pidx];
            if (msk == 0) {
               cSkips += 32;
            } else {
               this.blockMesher.skip(cSkips);
               cSkips = 0;
               int cIdx = -1;

               while (msk != 0) {
                  int index = Integer.numberOfTrailingZeros(msk);
                  int delta = index - cIdx - 1;
                  cIdx = index;
                  if (delta != 0) {
                     this.blockMesher.skip(delta);
                  }

                  msk &= ~Integer.lowestOneBit(msk);
                  int idx = index + pidx * 32;
                  int neighborIdx = (axis + 1) * 32 * 32 * 2 + side * 32 * 32;
                  long neighborId = this.neighboringFaces[neighborIdx + other * 32 + index];
                  long A = this.sectionData[idx * 2];
                  long selfMeta = this.sectionData[idx * 2 + 1];
                  int nib = Mapper.getBlockId(neighborId);
                  if (nib != 0) {
                     int cid = this.modelMan.getModelId(nib);
                     long meta = this.modelMan.getModelMetadataFromClientId(cid);
                     if (ModelQueries.isFullyOpaque(meta)) {
                        this.blockMesher.skip(1);
                        continue;
                     }

                     boolean culls = false;
                     culls |= cid == (A >> 26 & 65535L) && ModelQueries.cullsSame(meta);
                     culls |= ModelQueries.faceOccludes(meta, axis << 1 | 1 - side);
                     if (culls) {
                        this.blockMesher.skip(1);
                        continue;
                     }
                  }

                  this.blockMesher
                     .putNext(applyQuadLight((side == 0 ? 0L : 1L) | A & -9187343239835811841L | (neighborId & -72057594037927936L) >>> 1, selfMeta));
               }

               this.blockMesher.endRow();
            }
         }

         this.blockMesher.finish();
      }

      this.blockMesher.doAuxiliaryFaceOffset = true;
   }

   private void generateYZFluidInnerGeometry(int axis) {
      for (int layer = 0; layer < 31; layer++) {
         this.blockMesher.auxiliaryPosition = layer;
         int cSkip = 0;

         for (int other = 0; other < 32; other++) {
            int pidx = axis == 0 ? layer * 32 + other : other * 32 + layer;
            int skipAmount = axis == 0 ? 32 : 1;
            int current = this.fluidMasks[pidx];
            int next = this.fluidMasks[pidx + skipAmount];
            int msk = (current | this.opaqueMasks[pidx]) ^ (next | this.opaqueMasks[pidx + skipAmount]);
            msk &= current | next;
            if (msk == 0) {
               cSkip += 32;
            } else {
               this.blockMesher.skip(cSkip);
               cSkip = 0;
               int faceForwardMsk = msk & current;
               int cIdx = -1;

               while (msk != 0) {
                  int index = Integer.numberOfTrailingZeros(msk);
                  int delta = index - cIdx - 1;
                  cIdx = index;
                  if (delta != 0) {
                     this.blockMesher.skip(delta);
                  }

                  msk &= ~Integer.lowestOneBit(msk);
                  int facingForward = faceForwardMsk >> index & 1;
                  int idx = index + pidx * 32;
                  int a = idx * 2;
                  int b = (idx + skipAmount * 32) * 2;
                  int ai = facingForward == 1 ? a : b;
                  int bi = facingForward == 1 ? b : a;
                  if (ModelQueries.faceOccludes(this.sectionData[bi + 1], axis << 1 | 1 - facingForward)) {
                     this.blockMesher.skip(1);
                  } else {
                     long A = this.sectionData[ai];
                     long Am = this.sectionData[ai + 1];
                     if (ModelQueries.containsFluid(Am)) {
                        int modelId = (int)(A >> 26 & 65535L);
                        A &= -4397979402241L;
                        int fluidId = this.modelMan.getFluidClientStateId(modelId);
                        A |= Integer.toUnsignedLong(fluidId) << 26;
                        Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                        A &= -7L;
                        A |= getQuadTyping(Am);
                     }

                     if (axis == 0 && facingForward == 1 && this.modelMan.getVanillaFluidKind((int)(A >>> 26 & 65535L)) != 0) {
                        this.blockMesher.skip(1);
                        continue;
                     }

                     long lighter = this.sectionData[bi];
                     this.blockMesher.putNext(applyQuadLight(facingForward | A & -9187343239835811841L | lighter & 9187343239835811840L, Am));
                  }
               }

               this.blockMesher.endRow();
            }
         }

         this.blockMesher.finish();
      }
   }

   private void generateYZFluidOuterGeometry(int axis) {
      this.blockMesher.doAuxiliaryFaceOffset = false;

      for (int side = 0; side < 2; side++) {
         int layer = side == 0 ? 0 : 31;
         this.blockMesher.auxiliaryPosition = layer;
         int cSkips = 0;

         for (int other = 0; other < 32; other++) {
            int pidx = axis == 0 ? layer * 32 + other : other * 32 + layer;
            int msk = this.fluidMasks[pidx];
            if (msk == 0) {
               cSkips += 32;
            } else {
               this.blockMesher.skip(cSkips);
               cSkips = 0;
               int cIdx = -1;

               while (msk != 0) {
                  int index = Integer.numberOfTrailingZeros(msk);
                  int delta = index - cIdx - 1;
                  cIdx = index;
                  if (delta != 0) {
                     this.blockMesher.skip(delta);
                  }

                  msk &= ~Integer.lowestOneBit(msk);
                  int idx = index + pidx * 32;
                  int neighborIdx = (axis + 1) * 32 * 32 * 2 + side * 32 * 32;
                  long neighborId = this.neighboringFaces[neighborIdx + other * 32 + index];
                  long A = this.sectionData[idx * 2];
                  long Am = this.sectionData[idx * 2 + 1];
                  if (ModelQueries.containsFluid(Am)) {
                     int modelId = (int)(A >> 26 & 65535L);
                     A &= -4397979402241L;
                     int fluidId = this.modelMan.getFluidClientStateId(modelId);
                     A |= Integer.toUnsignedLong(fluidId) << 26;
                     Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                     A &= -7L;
                     A |= getQuadTyping(Am);
                  }

                  if (((axis == 0 && side == 1) || axis == 1)
                     && this.modelMan.getVanillaFluidKind((int)(A >>> 26 & 65535L)) != 0) {
                     this.blockMesher.skip(1);
                     continue;
                  }

                  if (Mapper.getBlockId(neighborId) != 0) {
                     int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                     long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                     if (ModelQueries.containsFluid(meta)) {
                        modelId = this.modelMan.getFluidClientStateId(modelId);
                     }

                     if (ModelQueries.cullsSame(Am) && modelId == (A >> 26 & 65535L)) {
                        this.blockMesher.skip(1);
                        continue;
                     }

                     if (ModelQueries.faceOccludes(meta, axis << 1 | 1 - side)) {
                        this.blockMesher.skip(1);
                        continue;
                     }
                  }

                  this.blockMesher.putNext(applyQuadLight((side == 0 ? 0L : 1L) | A & -9187343239835811841L | (neighborId & -72057594037927936L) >>> 1, Am));
               }

               this.blockMesher.endRow();
            }
         }

         this.blockMesher.finish();
      }

      this.blockMesher.doAuxiliaryFaceOffset = true;
   }

   private void generateYZNonOpaqueInnerGeometry(int axis) {
      this.secondaryBlockMesher.doAuxiliaryFaceOffset = false;
      this.blockMesher.axis = axis;
      this.secondaryBlockMesher.axis = axis;

      for (int layer = 1; layer < 31; layer++) {
         this.blockMesher.auxiliaryPosition = layer;
         this.secondaryBlockMesher.auxiliaryPosition = layer;
         int cSkip = 0;

         for (int other = 0; other < 32; other++) {
            int pidx = axis == 0 ? layer * 32 + other : other * 32 + layer;
            int skipAmount = axis == 0 ? 1024 : 32;
            int msk = this.nonOpaqueMasks[pidx];
            if (msk == 0) {
               cSkip += 32;
            } else {
               this.blockMesher.skip(cSkip);
               this.secondaryBlockMesher.skip(cSkip);
               cSkip = 0;
               int cIdx = -1;

               while (msk != 0) {
                  int index = Integer.numberOfTrailingZeros(msk);
                  int delta = index - cIdx - 1;
                  cIdx = index;
                  if (delta != 0) {
                     this.blockMesher.skip(delta);
                     this.secondaryBlockMesher.skip(delta);
                  }

                  msk &= ~Integer.lowestOneBit(msk);
                  int idx = index + pidx * 32;
                  long A = this.sectionData[idx * 2];
                  long B = this.sectionData[idx * 2 + 1];
                  meshNonOpaqueFace(
                     axis << 1 | 0, A, B, this.sectionData[(idx - skipAmount) * 2], this.sectionData[(idx - skipAmount) * 2 + 1], this.secondaryBlockMesher
                  );
                  meshNonOpaqueFace(
                     axis << 1 | 1, A, B, this.sectionData[(idx + skipAmount) * 2], this.sectionData[(idx + skipAmount) * 2 + 1], this.blockMesher
                  );
               }

               this.blockMesher.endRow();
               this.secondaryBlockMesher.endRow();
            }
         }

         this.blockMesher.finish();
         this.secondaryBlockMesher.finish();
      }
   }

   private void generateYZNonOpaqueOuterGeometry(int axis) {
      this.secondaryBlockMesher.doAuxiliaryFaceOffset = false;
      this.blockMesher.axis = axis;
      this.secondaryBlockMesher.axis = axis;

      for (int side = 0; side < 2; side++) {
         int layer = side == 0 ? 0 : 31;
         int skipAmount = (axis == 0 ? 1024 : 32) * (1 - side * 2);
         this.blockMesher.auxiliaryPosition = layer;
         this.secondaryBlockMesher.auxiliaryPosition = layer;
         int cSkips = 0;

         for (int other = 0; other < 32; other++) {
            int pidx = axis == 0 ? layer * 32 + other : other * 32 + layer;
            int msk = this.nonOpaqueMasks[pidx];
            if (msk == 0) {
               cSkips += 32;
            } else {
               this.blockMesher.skip(cSkips);
               this.secondaryBlockMesher.skip(cSkips);
               cSkips = 0;
               int cIdx = -1;

               while (msk != 0) {
                  int index = Integer.numberOfTrailingZeros(msk);
                  int delta = index - cIdx - 1;
                  cIdx = index;
                  if (delta != 0) {
                     this.blockMesher.skip(delta);
                     this.secondaryBlockMesher.skip(delta);
                  }

                  msk &= ~Integer.lowestOneBit(msk);
                  int idx = index + pidx * 32;
                  int neighborIdx = (axis + 1) * 32 * 32 * 2 + side * 32 * 32;
                  long neighborId = this.neighboringFaces[neighborIdx + other * 32 + index];
                  long A = this.sectionData[idx * 2];
                  long Am = this.sectionData[idx * 2 + 1];
                  boolean fail = false;
                  if (Mapper.getBlockId(neighborId) != 0) {
                     int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                     if (ModelQueries.cullsSame(Am) && modelId == (A >> 26 & 65535L)) {
                        fail = true;
                     } else {
                        long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                        if (ModelQueries.faceOccludes(meta, axis << 1 | 1 - side)) {
                           fail = true;
                        }
                     }
                  }

                  long nA = this.sectionData[(idx + skipAmount) * 2];
                  long nB = this.sectionData[(idx + skipAmount) * 2 + 1];
                  boolean failB = false;
                  if (ModelQueries.cullsSame(nB) && (nA & 4397979402240L) == (A & 4397979402240L)) {
                     failB = true;
                  } else if (ModelQueries.faceOccludes(nB, axis << 1 | side)) {
                     failB = true;
                  }

                  if (!ModelQueries.faceExists(Am, axis << 1 | 1) || (side != 1 || fail) && (side != 0 || failB)) {
                     this.blockMesher.skip(1);
                  } else {
                     this.blockMesher.putNext(applyQuadLight(1L | A | 0L, Am));
                  }

                  if (!ModelQueries.faceExists(Am, axis << 1 | 0) || (side != 0 || fail) && (side != 1 || failB)) {
                     this.secondaryBlockMesher.skip(1);
                  } else {
                     this.secondaryBlockMesher.putNext(applyQuadLight(0L | A | 0L, Am));
                  }
               }

               this.blockMesher.endRow();
               this.secondaryBlockMesher.endRow();
            }
         }

         this.blockMesher.finish();
         this.secondaryBlockMesher.finish();
      }
   }

   private void generateYZFaces() {
      for (int axis = 0; axis < 2; axis++) {
         this.blockMesher.axis = axis;
         this.generateYZOpaqueInnerGeometry(axis);
         this.generateYZOpaqueOuterGeometry(axis);
         this.generateYZNonOpaqueInnerGeometry(axis);
         this.generateYZNonOpaqueOuterGeometry(axis);
      }
   }

   private void generateXOpaqueInnerGeometry() {
      for (int y = 0; y < 32; y++) {
         long sumA = 0L;
         long sumB = 0L;
         long sumC = 0L;
         int partialHasCount = -1;
         int msk = 0;

         for (int z = 0; z < 32; z++) {
            int lMsk = this.opaqueMasks[y * 32 + z];
            int var27 = lMsk ^ lMsk >>> 1;
            msk = var27 & 2147483647;
            sumA += 1162219258676257L;
            sumB += 1162219258676257L;
            sumC += 1162219258676257L;
            partialHasCount &= ~msk;
            if (z == 30 && partialHasCount != 0) {
               int cmsk = partialHasCount;

               while (cmsk != 0) {
                  int index = Integer.numberOfTrailingZeros(cmsk);
                  cmsk &= ~Integer.lowestOneBit(cmsk);
                  this.xAxisMeshers[index].skip(31);
               }

               sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), 1162219258676257L) * 31L);
               sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 11, 1162219258676257L) * 31L);
               sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 22, 1162219258676257L) * 31L);
            }

            if (msk != 0) {
               int faceForwardMsk = msk & lMsk;
               int iter = msk;

               while (iter != 0) {
                  int index = Integer.numberOfTrailingZeros(iter);
                  iter &= ~Integer.lowestOneBit(iter);
                  RenderDataFactory.Mesher mesher = this.xAxisMeshers[index];
                  int skipCount;
                  if (index < 11) {
                     skipCount = (int)(sumA >> index * 5);
                     sumA &= ~(31L << index * 5);
                  } else if (index < 22) {
                     skipCount = (int)(sumB >> (index - 11) * 5);
                     sumB &= ~(31L << (index - 11) * 5);
                  } else {
                     skipCount = (int)(sumC >> (index - 22) * 5);
                     sumC &= ~(31L << (index - 22) * 5);
                  }

                  skipCount &= 31;
                  if (--skipCount != 0) {
                     mesher.skip(skipCount);
                  }

                  int facingForward = faceForwardMsk >> index & 1;
                  int idx = index + z * 32 + y * 32 * 32;
                  int iA = idx * 2 + (facingForward == 1 ? 0 : 2);
                  int iB = idx * 2 + (facingForward == 1 ? 2 : 0);
                  if (ModelQueries.faceOccludes(this.sectionData[iB + 1], 4 | 1 - facingForward)) {
                     mesher.skip(1);
                  } else {
                     long selfModel = this.sectionData[iA];
                     long selfMeta = this.sectionData[iA + 1];
                     long nextModel = this.sectionData[iB];
                     mesher.putNext(applyQuadLight(facingForward | selfModel & -9187343239835811841L | nextModel & 9187343239835811840L, selfMeta));
                  }
               }
            }
         }

         msk = ~msk;

         while (msk != 0) {
            int indexx = Integer.numberOfTrailingZeros(msk);
            msk &= ~Integer.lowestOneBit(msk);
            int skipCountx;
            if (indexx < 11) {
               skipCountx = (int)(sumA >> indexx * 5);
            } else if (indexx < 22) {
               skipCountx = (int)(sumB >> (indexx - 11) * 5);
            } else {
               skipCountx = (int)(sumC >> (indexx - 22) * 5);
            }

            skipCountx &= 31;
            if (skipCountx != 0) {
               this.xAxisMeshers[indexx].skip(skipCountx);
            }
         }
      }
   }

   private void generateXOuterOpaqueGeometry() {
      RenderDataFactory.Mesher ma = this.xAxisMeshers[0];
      RenderDataFactory.Mesher mb = this.xAxisMeshers[31];
      ma.finish();
      mb.finish();
      ma.doAuxiliaryFaceOffset = false;
      mb.doAuxiliaryFaceOffset = false;

      for (int y = 0; y < 32; y++) {
         int skipA = 0;
         int skipB = 0;

         for (int z = 0; z < 32; z++) {
            int i = y * 32 + z;
            int msk = this.opaqueMasks[i];
            if ((msk & 1) != 0) {
               long neighborId = this.neighboringFaces[i];
               boolean oki = true;
               if (Mapper.getBlockId(neighborId) != 0) {
                  long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
                  if (ModelQueries.isFullyOpaque(meta)) {
                     oki = false;
                  } else if (ModelQueries.faceOccludes(meta, 5)) {
                     oki = false;
                  }
               }

               if (oki) {
                  ma.skip(skipA);
                  skipA = 0;
                  long A = this.sectionData[(i << 5) * 2];
                  long Am = this.sectionData[(i << 5) * 2 + 1];
                  ma.putNext(applyQuadLight(0L | A & -9187343239835811841L | (neighborId & -72057594037927936L) >>> 1, Am));
               } else {
                  skipA++;
               }
            } else {
               skipA++;
            }

            if ((msk & -2147483648) != 0) {
               long neighborIdx = this.neighboringFaces[i + 1024];
               boolean okix = true;
               if (Mapper.getBlockId(neighborIdx) != 0) {
                  long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborIdx)));
                  if (ModelQueries.isFullyOpaque(meta)) {
                     okix = false;
                  } else if (ModelQueries.faceOccludes(meta, 4)) {
                     okix = false;
                  }
               }

               if (okix) {
                  mb.skip(skipB);
                  skipB = 0;
                  long A = this.sectionData[(i * 32 + 31) * 2];
                  long Am = this.sectionData[(i * 32 + 31) * 2 + 1];
                  mb.putNext(applyQuadLight(1L | A & -9187343239835811841L | (neighborIdx & -72057594037927936L) >>> 1, Am));
               } else {
                  skipB++;
               }
            } else {
               skipB++;
            }
         }

         ma.skip(skipA);
         mb.skip(skipB);
      }

      ma.finish();
      mb.finish();
      ma.doAuxiliaryFaceOffset = true;
      mb.doAuxiliaryFaceOffset = true;
   }

   private void generateXInnerFluidGeometry() {
      for (int y = 0; y < 32; y++) {
         long sumA = 0L;
         long sumB = 0L;
         long sumC = 0L;
         int partialHasCount = -1;
         int msk = 0;

         for (int z = 0; z < 32; z++) {
            int oMsk = this.opaqueMasks[y * 32 + z];
            int fMsk = this.fluidMasks[y * 32 + z];
            int lMsk = oMsk | fMsk;
            int var29 = lMsk ^ lMsk >>> 1;
            int var30 = var29 & 2147483647;
            msk = var30 & (fMsk | fMsk >> 1);
            sumA += 1162219258676257L;
            sumB += 1162219258676257L;
            sumC += 1162219258676257L;
            partialHasCount &= ~msk;
            if (z == 30 && partialHasCount != 0) {
               int cmsk = partialHasCount;

               while (cmsk != 0) {
                  int index = Integer.numberOfTrailingZeros(cmsk);
                  cmsk &= ~Integer.lowestOneBit(cmsk);
                  this.xAxisMeshers[index].skip(31);
               }

               sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), 1162219258676257L) * 31L);
               sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 11, 1162219258676257L) * 31L);
               sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 22, 1162219258676257L) * 31L);
            }

            if (msk != 0) {
               int faceForwardMsk = msk & lMsk;
               int iter = msk;

               while (iter != 0) {
                  int index = Integer.numberOfTrailingZeros(iter);
                  iter &= ~Integer.lowestOneBit(iter);
                  RenderDataFactory.Mesher mesher = this.xAxisMeshers[index];
                  int skipCount;
                  if (index < 11) {
                     skipCount = (int)(sumA >> index * 5);
                     sumA &= ~(31L << index * 5);
                  } else if (index < 22) {
                     skipCount = (int)(sumB >> (index - 11) * 5);
                     sumB &= ~(31L << (index - 11) * 5);
                  } else {
                     skipCount = (int)(sumC >> (index - 22) * 5);
                     sumC &= ~(31L << (index - 22) * 5);
                  }

                  skipCount &= 31;
                  if (--skipCount != 0) {
                     mesher.skip(skipCount);
                  }

                  int facingForward = faceForwardMsk >> index & 1;
                  int idx = index + z * 32 + y * 32 * 32;
                  int ai = (idx + (1 - facingForward)) * 2;
                  int bi = (idx + facingForward) * 2;
                  if (ModelQueries.faceOccludes(this.sectionData[bi + 1], 4 | 1 - facingForward)) {
                     mesher.skip(1);
                  } else {
                     long A = this.sectionData[ai];
                     long Am = this.sectionData[ai + 1];
                     if (ModelQueries.containsFluid(Am)) {
                        int modelId = (int)(A >> 26 & 65535L);
                        A &= -4397979402241L;
                        int fluidId = this.modelMan.getFluidClientStateId(modelId);
                        A |= Integer.toUnsignedLong(fluidId) << 26;
                        Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                        A &= -7L;
                        A |= getQuadTyping(Am);
                     }

                     long lighter = this.sectionData[bi];
                     mesher.putNext(applyQuadLight(facingForward | A & -9187343239835811841L | lighter & 9187343239835811840L, Am));
                  }
               }
            }
         }

         msk = ~msk;

         while (msk != 0) {
            int indexx = Integer.numberOfTrailingZeros(msk);
            msk &= ~Integer.lowestOneBit(msk);
            int skipCountx;
            if (indexx < 11) {
               skipCountx = (int)(sumA >> indexx * 5);
            } else if (indexx < 22) {
               skipCountx = (int)(sumB >> (indexx - 11) * 5);
            } else {
               skipCountx = (int)(sumC >> (indexx - 22) * 5);
            }

            skipCountx &= 31;
            if (skipCountx != 0) {
               this.xAxisMeshers[indexx].skip(skipCountx);
            }
         }
      }
   }

   private void generateXOuterFluidGeometry() {
      RenderDataFactory.Mesher ma = this.xAxisMeshers[0];
      RenderDataFactory.Mesher mb = this.xAxisMeshers[31];
      ma.finish();
      mb.finish();
      ma.doAuxiliaryFaceOffset = false;
      mb.doAuxiliaryFaceOffset = false;

      for (int y = 0; y < 32; y++) {
         int skipA = 0;
         int skipB = 0;

         for (int z = 0; z < 32; z++) {
            int i = y * 32 + z;
            int msk = this.fluidMasks[i];
            if ((msk & 1) != 0) {
               long neighborId = this.neighboringFaces[i];
               boolean oki = true;
               int sidx = (i << 5) * 2;
               long A = this.sectionData[sidx];
               long Am = this.sectionData[sidx + 1];
               if (ModelQueries.containsFluid(Am)) {
                  int modelId = (int)(A >> 26 & 65535L);
                  A &= -4397979402241L;
                  int fluidId = this.modelMan.getFluidClientStateId(modelId);
                  A |= Integer.toUnsignedLong(fluidId) << 26;
                  Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                  A &= -7L;
                  A |= getQuadTyping(Am);
               }
               if (this.modelMan.getVanillaFluidKind((int)(A >>> 26 & 65535L)) != 0) {
                  oki = false;
               }

               if (Mapper.getBlockId(neighborId) != 0) {
                  int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                  long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                  if (ModelQueries.isFullyOpaque(meta)) {
                     oki = false;
                  }

                  if (ModelQueries.faceOccludes(meta, 5)) {
                     oki = false;
                  }

                  if (ModelQueries.containsFluid(meta)) {
                     modelId = this.modelMan.getFluidClientStateId(modelId);
                  }

                  if (ModelQueries.cullsSame(Am) && modelId == (A >> 26 & 65535L)) {
                     oki = false;
                  }
               }

               if (oki) {
                  ma.skip(skipA);
                  skipA = 0;
                  long lightData = (neighborId & -72057594037927936L) >>> 1;
                  ma.putNext(applyQuadLight(0L | A & -9187343239835811841L | lightData, Am));
               } else {
                  skipA++;
               }
            } else {
               skipA++;
            }

            if ((msk & -2147483648) != 0) {
               long neighborIdx = this.neighboringFaces[i + 1024];
               boolean okix = true;
               int sidxx = (i * 32 + 31) * 2;
               long Ax = this.sectionData[sidxx];
               long Amx = this.sectionData[sidxx + 1];
               if (ModelQueries.containsFluid(Amx)) {
                  int modelIdx = (int)(Ax >> 26 & 65535L);
                  Ax &= -4397979402241L;
                  int fluidId = this.modelMan.getFluidClientStateId(modelIdx);
                  Ax |= Integer.toUnsignedLong(fluidId) << 26;
                  Amx = this.modelMan.getModelMetadataFromClientId(fluidId);
               }
               if (this.modelMan.getVanillaFluidKind((int)(Ax >>> 26 & 65535L)) != 0) {
                  okix = false;
               }

               if (Mapper.getBlockId(neighborIdx) != 0) {
                  int modelIdx = this.modelMan.getModelId(Mapper.getBlockId(neighborIdx));
                  long metax = this.modelMan.getModelMetadataFromClientId(modelIdx);
                  if (ModelQueries.isFullyOpaque(metax)) {
                     okix = false;
                  }

                  if (ModelQueries.faceOccludes(metax, 4)) {
                     okix = false;
                  }

                  if (ModelQueries.containsFluid(metax)) {
                     modelIdx = this.modelMan.getFluidClientStateId(modelIdx);
                  }

                  if (ModelQueries.cullsSame(Amx) && modelIdx == (Ax >> 26 & 65535L)) {
                     okix = false;
                  }
               }

               if (okix) {
                  mb.skip(skipB);
                  skipB = 0;
                  long lightData = (neighborIdx & -72057594037927936L) >>> 1;
                  mb.putNext(applyQuadLight(1L | Ax & -9187343239835811841L | lightData, Amx));
               } else {
                  skipB++;
               }
            } else {
               skipB++;
            }
         }

         ma.skip(skipA);
         mb.skip(skipB);
      }

      ma.finish();
      mb.finish();
      ma.doAuxiliaryFaceOffset = true;
      mb.doAuxiliaryFaceOffset = true;
   }

    private boolean hasFluid(int voxelIndex) {
        long metadata = this.sectionData[voxelIndex * 2 + 1];
        return ModelQueries.isFluid(metadata) || ModelQueries.containsFluid(metadata);
    }

    private long makeDirectFluidFace(int fluidIndex, int neighborIndex, int facingForward, int lowerHeight, int shapePayload) {
        long quad = this.sectionData[fluidIndex * 2];
        long metadata = this.sectionData[fluidIndex * 2 + 1];
        if (ModelQueries.containsFluid(metadata)) {
            int modelId = (int) ((quad >>> 26) & 0xFFFF);
            int fluidId = this.modelMan.getFluidClientStateId(modelId);
            quad = (quad & ~(0xFFFFL << 26)) | (Integer.toUnsignedLong(fluidId) << 26);
            metadata = this.modelMan.getModelMetadataFromClientId(fluidId);
            quad = (quad & ~0b110L) | getQuadTyping(metadata);
        }

        long neighborQuad = this.sectionData[neighborIndex * 2];
        quad = shapePayload >= 0
                ? encodeFluidShape(quad, shapePayload, fluidIndex)
                : encodeFluidStep(quad, lowerHeight, fluidIndex);
        return applyQuadLight(
                facingForward | (quad & ~LM) | maxQuadLight(quad, neighborQuad),
                metadata);
    }

    private long makeDirectFluidTop(int fluidIndex, long neighborRaw, int shapePayload) {
        long quad = this.sectionData[fluidIndex * 2];
        long metadata = this.sectionData[fluidIndex * 2 + 1];
        if (ModelQueries.containsFluid(metadata)) {
            int fluidId = this.modelMan.getFluidClientStateId((int) ((quad >>> 26) & 0xFFFF));
            quad = (quad & ~(0xFFFFL << 26)) | (Integer.toUnsignedLong(fluidId) << 26);
            metadata = this.modelMan.getModelMetadataFromClientId(fluidId);
            quad = (quad & ~0b110L) | getQuadTyping(metadata);
        }
        quad = encodeFluidShape(quad, shapePayload, fluidIndex);
        long neighborLight = (neighborRaw & (0xFFL << 56)) >>> 1;
        return applyQuadLight(1L | (quad & ~LM) | maxQuadLight(quad, neighborLight), metadata);
    }

    private long makeDirectOuterFluidFace(int fluidIndex, long neighborRaw, int facingForward, int shapePayload) {
        long quad = this.sectionData[fluidIndex * 2];
        long metadata = this.sectionData[fluidIndex * 2 + 1];
        if (ModelQueries.containsFluid(metadata)) {
            int fluidId = this.modelMan.getFluidClientStateId((int) ((quad >>> 26) & 0xFFFF));
            quad = (quad & ~(0xFFFFL << 26)) | (Integer.toUnsignedLong(fluidId) << 26);
            metadata = this.modelMan.getModelMetadataFromClientId(fluidId);
            quad = (quad & ~0b110L) | getQuadTyping(metadata);
        }
        quad = encodeFluidShape(quad, shapePayload, fluidIndex);
        long neighborLight = (neighborRaw & (0xFFL << 56)) >>> 1;
        return applyQuadLight(facingForward | (quad & ~LM) | maxQuadLight(quad, neighborLight), metadata);
    }

    private int fluidSidePayload(int fluidIndex, int neighborIndex, int axis, int facingForward) {
        int kind = fluidKind(fluidIndex);
        if (kind == 0) return -1;

        float top0;
        float top1;
        float bottom0 = 0.0f;
        float bottom1 = 0.0f;
        if (axis == 2) {
            int edge = facingForward == 1 ? 1 : 0;
            top0 = fluidCornerHeight(fluidIndex, edge, 0);
            top1 = fluidCornerHeight(fluidIndex, edge, 1);
            if (fluidKind(neighborIndex) == kind) {
                int neighborEdge = 1 - edge;
                bottom0 = fluidCornerHeight(neighborIndex, neighborEdge, 0);
                bottom1 = fluidCornerHeight(neighborIndex, neighborEdge, 1);
            }
            return packFluidCorners(bottom0, bottom1, top0, top1);
        }

        int edge = facingForward == 1 ? 1 : 0;
        top0 = fluidCornerHeight(fluidIndex, 0, edge);
        top1 = fluidCornerHeight(fluidIndex, 1, edge);
        if (fluidKind(neighborIndex) == kind) {
            int neighborEdge = 1 - edge;
            bottom0 = fluidCornerHeight(neighborIndex, 0, neighborEdge);
            bottom1 = fluidCornerHeight(neighborIndex, 1, neighborEdge);
        }
        return packFluidCorners(bottom0, top0, bottom1, top1);
    }

    private int outerFluidSidePayload(int fluidIndex, int axis, int facingForward) {
        if (axis == 2) {
            int edge = facingForward == 1 ? 1 : 0;
            return packFluidCorners(0.0f, 0.0f,
                    fluidCornerHeight(fluidIndex, edge, 0),
                    fluidCornerHeight(fluidIndex, edge, 1));
        }
        int edge = facingForward == 1 ? 1 : 0;
        return packFluidCorners(0.0f,
                fluidCornerHeight(fluidIndex, 0, edge),
                0.0f,
                fluidCornerHeight(fluidIndex, 1, edge));
    }

    private void emitDirectOuterFluidBoundary(int fluidIndex, long neighborRaw, int axis,
                                               int plane, int firstCoord, int secondCoord,
                                               int facingForward) {
        int kind = fluidKind(fluidIndex);
        if (kind == 0 || rawFluidKind(neighborRaw) == kind) return;
        if (CHECK_NEIGHBOR_FACE_OCCLUSION
                && ModelQueries.faceOccludes(rawModelMetadata(neighborRaw),
                (axis << 1) | (1 - facingForward))) {
            return;
        }

        int payload = outerFluidSidePayload(fluidIndex, axis, facingForward);
        long quad = makeDirectOuterFluidFace(fluidIndex, neighborRaw, facingForward, payload);
        this.blockMesher.axis = axis;
        this.blockMesher.auxiliaryPosition = plane;
        this.blockMesher.doAuxiliaryFaceOffset = true;
        this.blockMesher.emitShapedFluidQuad(firstCoord, secondCoord, payload, quad);
    }

    private void emitDirectFluidBoundary(int firstIndex, int secondIndex, int axis, int plane, int firstCoord, int secondCoord) {
        boolean firstFluid = hasFluid(firstIndex);
        boolean secondFluid = hasFluid(secondIndex);
        if (!firstFluid && !secondFluid) return;

        int fluidIndex;
        int neighborIndex;
        int facingForward;
        int lowerHeight = 0;
        int shapePayload;

        if (firstFluid && secondFluid) {
            int firstDescriptor = fluidDescriptor(firstIndex);
            int secondDescriptor = fluidDescriptor(secondIndex);
            int firstKind = fluidKind(firstIndex);
            int secondKind = fluidKind(secondIndex);
            int firstHeight = firstKind != 0 ? effectiveFluidHeight(firstIndex) : firstDescriptor & 31;
            int secondHeight = secondKind != 0 ? effectiveFluidHeight(secondIndex) : secondDescriptor & 31;
            if (firstKind != 0 && firstKind == secondKind) return;
            if (firstKind == 0 && firstDescriptor == secondDescriptor) return;

            if ((firstKind != 0 && firstKind == secondKind)
                    || (firstKind == 0 && secondKind == 0
                    && (firstDescriptor & (1 << 5)) == (secondDescriptor & (1 << 5)))) {
                if (firstHeight == secondHeight) return;
                boolean firstHigher = firstHeight > secondHeight;
                fluidIndex = firstHigher ? firstIndex : secondIndex;
                neighborIndex = firstHigher ? secondIndex : firstIndex;
                facingForward = firstHigher ? 1 : 0;
                lowerHeight = Math.min(firstHeight, secondHeight);
            } else {
                fluidIndex = firstIndex;
                neighborIndex = secondIndex;
                facingForward = 1;
            }
        } else {
            boolean firstIsFluid = firstFluid;
            fluidIndex = firstIsFluid ? firstIndex : secondIndex;
            neighborIndex = firstIsFluid ? secondIndex : firstIndex;
            facingForward = firstIsFluid ? 1 : 0;

            long neighborMetadata = this.sectionData[neighborIndex * 2 + 1];
            if (CHECK_NEIGHBOR_FACE_OCCLUSION
                    && ModelQueries.faceOccludes(neighborMetadata, (axis << 1) | (1 - facingForward))) {
                return;
            }
        }

        this.blockMesher.axis = axis;
        this.blockMesher.auxiliaryPosition = plane;
        this.blockMesher.doAuxiliaryFaceOffset = true;
        shapePayload = fluidSidePayload(fluidIndex, neighborIndex, axis, facingForward);
        long quad = makeDirectFluidFace(fluidIndex, neighborIndex, facingForward, lowerHeight, shapePayload);
        if (shapePayload >= 0) {
            this.blockMesher.emitShapedFluidQuad(firstCoord, secondCoord, shapePayload, quad);
        } else {
            this.blockMesher.emitQuad(firstCoord, secondCoord, 1, 1, quad);
        }
    }

    private void generateDirectHorizontalFluidGeometry() {
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int index = x | (z << 5) | (y << 10);
                    if (x < 31) {
                        emitDirectFluidBoundary(index, index + 1, 2, x, z, y);
                    }
                    if (z < 31) {
                        emitDirectFluidBoundary(index, index + 32, 1, z, x, y);
                    }
                }
            }
        }
    }

    private void generateDirectOuterHorizontalFluidGeometry(int unavailableNeighborMsk) {
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                int sliceIndex = z + y * 32;
                if ((unavailableNeighborMsk & 1) == 0) {
                    int index = (z << 5) | (y << 10);
                    if (fluidKind(index) != 0) {
                        emitDirectOuterFluidBoundary(index, this.neighboringFaces[sliceIndex],
                                2, -1, z, y, 0);
                    }
                }
                if ((unavailableNeighborMsk & 2) == 0) {
                    int index = 31 | (z << 5) | (y << 10);
                    if (fluidKind(index) != 0) {
                        emitDirectOuterFluidBoundary(index, this.neighboringFaces[32 * 32 + sliceIndex],
                                2, 31, z, y, 1);
                    }
                }
            }
            for (int x = 0; x < 32; x++) {
                int sliceIndex = x + y * 32;
                if ((unavailableNeighborMsk & 16) == 0) {
                    int index = x | (y << 10);
                    if (fluidKind(index) != 0) {
                        emitDirectOuterFluidBoundary(index, this.neighboringFaces[4 * 32 * 32 + sliceIndex],
                                1, -1, x, y, 0);
                    }
                }
                if ((unavailableNeighborMsk & 32) == 0) {
                    int index = x | (31 << 5) | (y << 10);
                    if (fluidKind(index) != 0) {
                        emitDirectOuterFluidBoundary(index, this.neighboringFaces[5 * 32 * 32 + sliceIndex],
                                1, 31, x, y, 1);
                    }
                }
            }
        }
    }

    private void generateDirectFluidTops() {
        this.blockMesher.axis = 0;
        this.blockMesher.doAuxiliaryFaceOffset = true;
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int index = x | (z << 5) | (y << 10);
                    int kind = fluidKind(index);
                    if (kind == 0) continue;

                    int above = y < 31 ? index + 1024 : -1;
                    long aboveRaw = y == 31
                            ? this.neighboringFaces[3 * 32 * 32 + x + z * 32]
                            : 0;
                    int aboveKind = above >= 0 ? fluidKind(above) : rawFluidKind(aboveRaw);
                    if (aboveKind == kind) continue;

                    long aboveMetadata = above >= 0
                            ? this.sectionData[above * 2 + 1]
                            : rawModelMetadata(aboveRaw);
                    if (CHECK_NEIGHBOR_FACE_OCCLUSION && ModelQueries.faceOccludes(aboveMetadata, 0)) continue;

                    int payload = packFluidCorners(
                            fluidCornerHeight(index, 0, 0),
                            fluidCornerHeight(index, 0, 1),
                            fluidCornerHeight(index, 1, 0),
                            fluidCornerHeight(index, 1, 1));
                    long quad = above >= 0
                            ? makeDirectFluidFace(index, above, 1, 0, payload)
                            : makeDirectFluidTop(index, aboveRaw, payload);
                    this.blockMesher.auxiliaryPosition = y;
                    this.blockMesher.emitShapedFluidQuad(x, z, payload, quad);
                }
            }
        }
    }

   private void generateXNonOpaqueInnerGeometry() {
      for (int y = 0; y < 32; y++) {
         long sumA = 0L;
         long sumB = 0L;
         long sumC = 0L;
         int partialHasCount = -1;
         int msk = 0;

         for (int z = 0; z < 32; z++) {
            msk = this.nonOpaqueMasks[y * 32 + z] & 2147483646;
            sumA += 1162219258676257L;
            sumB += 1162219258676257L;
            sumC += 1162219258676257L;
            partialHasCount &= ~msk;
            if (z == 30 && partialHasCount != 0) {
               int cmsk = partialHasCount;

               while (cmsk != 0) {
                  int index = Integer.numberOfTrailingZeros(cmsk);
                  cmsk &= ~Integer.lowestOneBit(cmsk);
                  this.xAxisMeshers[index].skip(31);
                  this.secondaryXAxisMeshers[index].skip(31);
               }

               sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), 1162219258676257L) * 31L);
               sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 11, 1162219258676257L) * 31L);
               sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 22, 1162219258676257L) * 31L);
            }

            if (msk != 0) {
               int iter = msk;

               while (iter != 0) {
                  int index = Integer.numberOfTrailingZeros(iter);
                  iter &= ~Integer.lowestOneBit(iter);
                  int skipCount;
                  if (index < 11) {
                     skipCount = (int)(sumA >> index * 5);
                     sumA &= ~(31L << index * 5);
                  } else if (index < 22) {
                     skipCount = (int)(sumB >> (index - 11) * 5);
                     sumB &= ~(31L << (index - 11) * 5);
                  } else {
                     skipCount = (int)(sumC >> (index - 22) * 5);
                     sumC &= ~(31L << (index - 22) * 5);
                  }

                  skipCount &= 31;
                  skipCount--;
                  RenderDataFactory.Mesher mesherA = this.xAxisMeshers[index];
                  RenderDataFactory.Mesher mesherB = this.secondaryXAxisMeshers[index];
                  if (skipCount != 0) {
                     mesherA.skip(skipCount);
                     mesherB.skip(skipCount);
                  }

                  int idx = index + z * 32 + y * 32 * 32;
                  long A = this.sectionData[idx * 2];
                  long Am = this.sectionData[idx * 2 + 1];
                  meshNonOpaqueFace(4, A, Am, this.sectionData[(idx - 1) * 2], this.sectionData[(idx - 1) * 2 + 1], mesherB);
                  meshNonOpaqueFace(5, A, Am, this.sectionData[(idx + 1) * 2], this.sectionData[(idx + 1) * 2 + 1], mesherA);
               }
            }
         }

         msk = ~msk;

         while (msk != 0) {
            int indexx = Integer.numberOfTrailingZeros(msk);
            msk &= ~Integer.lowestOneBit(msk);
            int skipCountx;
            if (indexx < 11) {
               skipCountx = (int)(sumA >> indexx * 5);
            } else if (indexx < 22) {
               skipCountx = (int)(sumB >> (indexx - 11) * 5);
            } else {
               skipCountx = (int)(sumC >> (indexx - 22) * 5);
            }

            skipCountx &= 31;
            if (skipCountx != 0) {
               this.xAxisMeshers[indexx].skip(skipCountx);
               this.secondaryXAxisMeshers[indexx].skip(skipCountx);
            }
         }
      }
   }

   private static void dualMeshNonOpaqueOuterX(
      int side,
      long quad,
      long meta,
      int neighborAId,
      int neighborLight,
      long neighborAMeta,
      long neighborBQuad,
      long neighborBMeta,
      RenderDataFactory.Mesher ma,
      RenderDataFactory.Mesher mb
   ) {
      if (neighborAId == 0 && ModelQueries.faceExists(meta, 4 ^ side)
         || neighborAId != 0 && shouldMeshNonOpaqueBlockFace(4 ^ side, quad, meta, (long)neighborAId << 26, neighborAMeta)) {
         ma.putNext(
            applyQuadLight(side | quad & -9187343239835811841L | (ModelQueries.faceUsesSelfLighting(meta, 4 ^ side) ? quad : (long)neighborLight << 55), meta)
         );
      } else {
         ma.skip(1);
      }

      if (shouldMeshNonOpaqueBlockFace(5 ^ side, quad, meta, neighborBQuad, neighborBMeta)) {
         mb.putNext(
            applyQuadLight(
               side ^ 1 | quad & -9187343239835811841L | (ModelQueries.faceUsesSelfLighting(meta, 5 ^ side) ? quad : neighborBQuad) & 9187343239835811840L,
               meta
            )
         );
      } else {
         mb.skip(1);
      }
   }

   private void generateXNonOpaqueOuterGeometry() {
      RenderDataFactory.Mesher npx = this.xAxisMeshers[0];
      npx.finish();
      RenderDataFactory.Mesher nnx = this.secondaryXAxisMeshers[0];
      nnx.finish();
      RenderDataFactory.Mesher ppx = this.xAxisMeshers[31];
      ppx.finish();
      RenderDataFactory.Mesher pnx = this.secondaryXAxisMeshers[31];
      pnx.finish();

      for (int y = 0; y < 32; y++) {
         int skipA = 0;
         int skipB = 0;

         for (int z = 0; z < 32; z++) {
            int i = y * 32 + z;
            int msk = this.nonOpaqueMasks[i];
            if ((msk & 1) != 0) {
               long neighborId = this.neighboringFaces[i];
               int sidx = (i << 5) * 2;
               long A = this.sectionData[sidx];
               long Am = this.sectionData[sidx + 1];
               int modelId = 0;
               long nM = 0L;
               if (Mapper.getBlockId(neighborId) != 0) {
                  modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                  nM = this.modelMan.getModelMetadataFromClientId(modelId);
               }

               nnx.skip(skipA);
               npx.skip(skipA);
               skipA = 0;
               dualMeshNonOpaqueOuterX(0, A, Am, modelId, Mapper.getLightId(neighborId), nM, this.sectionData[sidx + 2], this.sectionData[sidx + 3], nnx, npx);
            } else {
               skipA++;
            }

            if ((msk & -2147483648) != 0) {
               long neighborId = this.neighboringFaces[i + 1024];
               int sidx = (i * 32 + 31) * 2;
               long A = this.sectionData[sidx];
               long Am = this.sectionData[sidx + 1];
               int modelId = 0;
               long nM = 0L;
               if (Mapper.getBlockId(neighborId) != 0) {
                  modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                  nM = this.modelMan.getModelMetadataFromClientId(modelId);
               }

               pnx.skip(skipB);
               ppx.skip(skipB);
               skipB = 0;
               dualMeshNonOpaqueOuterX(1, A, Am, modelId, Mapper.getLightId(neighborId), nM, this.sectionData[sidx - 2], this.sectionData[sidx - 1], ppx, pnx);
            } else {
               skipB++;
            }
         }

         nnx.skip(skipA);
         npx.skip(skipA);
         pnx.skip(skipB);
         ppx.skip(skipB);
      }
   }

   private void generateXFaces() {
      this.generateXOpaqueInnerGeometry();
      this.generateXOuterOpaqueGeometry();

      for (RenderDataFactory.Mesher mesher : this.xAxisMeshers) {
         mesher.finish();
      }

      this.generateXNonOpaqueInnerGeometry();
      this.generateXNonOpaqueOuterGeometry();

      for (RenderDataFactory.Mesher mesher : this.xAxisMeshers) {
         mesher.finish();
      }

      for (RenderDataFactory.Mesher mesher : this.secondaryXAxisMeshers) {
         mesher.finish();
      }
   }

   private void generateFluidFaces() {
      this.generateDirectHorizontalFluidGeometry();
      this.blockMesher.axis = 1;
      this.generateYZFluidOuterGeometry(1);

      for (RenderDataFactory.Mesher mesher : this.xAxisMeshers) mesher.finish();
      this.generateXOuterFluidGeometry();
      for (RenderDataFactory.Mesher mesher : this.xAxisMeshers) mesher.finish();
      this.generateDirectOuterHorizontalFluidGeometry(0);

      this.blockMesher.axis = 0;
      this.generateYZFluidInnerGeometry(0);
      this.generateYZFluidOuterGeometry(0);
      this.generateDirectFluidTops();
   }

   private final int occupancyBarrier(int index) {
      int occ = 0;
      int msk = this.opaqueMasks[index];
      occ |= msk ^ msk >> 1;
      occ |= msk ^ msk << 1;
      occ |= index < 992 ? msk ^ this.opaqueMasks[index + 32] : 0;
      occ |= 31 < index ? msk ^ this.opaqueMasks[index - 32] : 0;
      occ |= (index & 31) < 31 ? msk ^ this.opaqueMasks[index + 1] : 0;
      return occ | (0 < (index & 31) ? msk ^ this.opaqueMasks[index - 1] : 0);
   }

   private final void buildOccupancy() {
      for (int i = 0; i < 1024; i++) {
         for (int occ = this.occupancyBarrier(i); occ != 0; occ &= ~Integer.lowestOneBit(occ)) {
            this.occupancy.set(i * 32 + Integer.numberOfTrailingZeros(occ));
         }
      }
   }

   private final void buildOccupancy16() {
      for (int i = 0; i < 256; i++) {
         int x = (i & 15) * 2;
         int y = (i >> 4) * 2;
         int A = this.occupancyBarrier(y * 32 + x);
         A = (A | A >> 16) & 65535;
         int B = this.occupancyBarrier(y * 32 + x + 1);
         B = (B | B >> 16) & 65535;
         int C = this.occupancyBarrier((y + 1) * 32 + x);
         C = (C | C >> 16) & 65535;
         int D = this.occupancyBarrier((y + 1) * 32 + x + 1);
         D = (D | D >> 16) & 65535;

         for (int occ = A | B | C | D; occ != 0; occ &= ~Integer.lowestOneBit(occ)) {
            this.occupancy.set(i * 16 + Integer.numberOfTrailingZeros(occ));
         }
      }
   }

   /** 将区段快照转换为该版本渲染器使用的固定网格布局。 */
   public BuiltSection generateMesh(WorldSection section) {
        try (var view = this.surfaceView.open(this.world, section)) {
            return this.generateMeshWithView(section);
        }
    }

    private BuiltSection generateMeshWithView(WorldSection section) {
      this.quadCount = 0;
      this.droppedQuads = 0;
      this.blockMesher.reset();
      this.blockMesher.doAuxiliaryFaceOffset = true;
      this.secondaryBlockMesher.reset();
      this.secondaryBlockMesher.doAuxiliaryFaceOffset = true;

      for (RenderDataFactory.Mesher mesher : this.xAxisMeshers) {
         mesher.reset();
         mesher.doAuxiliaryFaceOffset = true;
      }

      for (RenderDataFactory.Mesher mesher : this.secondaryXAxisMeshers) {
         mesher.reset();
         mesher.doAuxiliaryFaceOffset = false;
      }

      if (this.occupancy != null) {
         this.occupancy.reset();
      }

      this.minX = Integer.MAX_VALUE;
      this.minY = Integer.MAX_VALUE;
      this.minZ = Integer.MAX_VALUE;
      this.maxX = Integer.MIN_VALUE;
      this.maxY = Integer.MIN_VALUE;
      this.maxZ = Integer.MIN_VALUE;
      Arrays.fill(this.quadCounters, 0);
      Arrays.fill(this.opaqueMasks, 0);
      Arrays.fill(this.nonOpaqueMasks, 0);
      Arrays.fill(this.fluidMasks, 0);
      long[] rawSection = this.surfaceView.sectionData();
        int neighborMskAndFlags = this.prepareSectionData(rawSection);
      if ((neighborMskAndFlags & -2147483648) != 0) {
         throw new IdNotYetComputedException(neighborMskAndFlags & 1048575, true);
      } else {
         int neighborMsk = neighborMskAndFlags & 63;
         int flags = neighborMskAndFlags >>> 6;
         this.acquireNeighborData(section, neighborMsk);

         try {
            boolean fluidHalo = false;
            for (int mask : this.fluidMasks) if (mask != 0) { fluidHalo = true; break; }
            int blendRadius = Math.min(7, Math.max(0, me.cortex.voxy.client.config.VoxyConfig.CONFIG.biomeBlendRadius));
            if (blendRadius > 0) {
                boolean waterOnly = !"water_grass".equals(me.cortex.voxy.client.config.VoxyConfig.CONFIG.biomeBlendScope);
                boolean blend = false;
                if (this.modelMan.blendPalette().snapshot().rowBaseByModelId().length != 0) {
                    for (int i = 0; i < 32768; i++) if (blendModel(i, waterOnly) >= 0) { blend = true; break; }
                }
                if (!blend) blendRadius = 0;
            }
            if (fluidHalo || blendRadius != 0) prepareHorizontalHalo(section, fluidHalo, Math.max(fluidHalo ? 1 : 0, blendRadius));
            this.applyBiomeBlend(rawSection);
            this.generateYZFaces();
            this.generateXFaces();
            this.generateFluidFaces();
         } catch (IdNotYetComputedException var12) {
            var12.auxBitMsk = neighborMsk;
            var12.auxData = this.neighboringFaces;
            throw var12;
         }

         if (this.occupancy != null && section.lvl == 0 && this.quadCount != 0 && (flags & 1) != 0) {
            this.buildOccupancy();
         }

         if (this.droppedQuads != 0) {
             Logger.warn("Quad bucket limit for section " + WorldEngine.pprintPos(section.key)
                     + ": dropped " + this.droppedQuads + " quads");
         }

         if (this.quadCount == 0) {
            return BuiltSection.emptyWithChildren(section.key, section.getNonEmptyChildren());
         } else {
            if (this.quadCount >= 65536) {
               Logger.warn("Large quad count for section " + WorldEngine.pprintPos(section.key) + " is " + this.quadCount);
            }

            if (this.minX >= 0 && this.minY >= 0 && this.minZ >= 0 && 32 >= this.maxX && 32 >= this.maxY && 32 >= this.maxZ) {
               int[] offsets = new int[8];
               MemoryBuffer buff = new MemoryBuffer(this.quadCount * 8L);
               long ptr = buff.address;
               int coff = 0;

               for (int buffer = 0; buffer < 8; buffer++) {
                  offsets[buffer] = coff;
                  int size = this.quadCounters[buffer];
                  UnsafeUtil.memcpy(this.quadBufferPtr + buffer * 524288, ptr + coff * 8L, size * 8L);
                  coff += size;
               }

               int aabb = 0;
               aabb |= this.minX;
               aabb |= this.minY << 5;
               aabb |= this.minZ << 10;
               aabb |= Math.max(0, this.maxX - this.minX - 1) << 15;
               aabb |= Math.max(0, this.maxY - this.minY - 1) << 20;
               aabb |= Math.max(0, this.maxZ - this.minZ - 1) << 25;
               MemoryBuffer occupancy = null;
               if (this.occupancy != null && !this.occupancy.isEmpty()) {
                  occupancy = new MemoryBuffer(this.occupancy.writeSize());
                  this.occupancy.write(occupancy.address, false);
               }

               return new BuiltSection(section.key, section.getNonEmptyChildren(), aabb, buff, offsets, occupancy);
            } else {
               throw new IllegalStateException();
            }
         }
      }
   }

   /** 释放四边形缓冲，实例生命周期结束时调用。 */
   public void free() {
      this.quadBuffer.free();
   }

   private final class Mesher extends ScanMesher2D {
      public int auxiliaryPosition;
      public boolean doAuxiliaryFaceOffset;
      public int axis;

      private Mesher() {
         Objects.requireNonNull(RenderDataFactory.this);
         super();
         this.auxiliaryPosition = 0;
         this.doAuxiliaryFaceOffset = true;
         this.axis = 0;
      }

      @Override
      protected void emitQuad(int x, int z, int length, int width, long data) {
         this.emitQuad(x, z, length, width, data, -1);
      }

      private void emitShapedFluidQuad(int x, int z, int payload, long data) {
         this.emitQuad(x, z, 1, 1, data, payload & 255);
      }

      private void emitQuad(int x, int z, int length, int width, long data, int encodedSizeBits) {
         if (RenderDataFactory.VERIFY_MESHING) {
            if (length < 1 || length > 16) {
               throw new IllegalStateException("length out of bounds: " + length);
            }

            if (width < 1 || width > 16) {
               throw new IllegalStateException("width out of bounds: " + width);
            }

            if (x < 0 || x > 31) {
               throw new IllegalStateException("x out of bounds: " + x);
            }

            if (z < 0 || z > 31) {
               throw new IllegalStateException("z out of bounds: " + z);
            }

            if (x - (length - 1) < 0 || z - (width - 1) < 0) {
               throw new IllegalStateException("dim out of bounds: " + (x - (length - 1)) + ", " + (z - (width - 1)));
            }
         }

         RenderDataFactory.this.quadCount++;
         x -= length - 1;
         z -= width - 1;
         if (this.axis == 2) {
            int tmp = x;
            x = z;
            z = tmp;
            tmp = length;
            length = width;
            width = tmp;
         }

         int auxData = (int)(data & 67108863L);
         data &= -67108864L;
         int axisSide = auxData & 1;
         int type = auxData >> 1 & 3;
         if (RenderDataFactory.VERIFY_MESHING && type == 3) {
            throw new IllegalStateException();
         } else {
            int auxPos = this.auxiliaryPosition;
            auxPos += 1 - (this.doAuxiliaryFaceOffset ? axisSide : 1);
            if (RenderDataFactory.VERIFY_MESHING && auxPos > 31) {
               throw new IllegalStateException("OOB face: " + auxPos + ", " + axisSide);
            } else {
               int axis = this.axis;
               int face = axis << 1 | axisSide;
               int encodedPosition = face | (encodedSizeBits >= 0
                  ? encodedSizeBits << 3
                  : width - 1 << 7 | length - 1 << 3);
               encodedPosition |= x << (axis == 2 ? 16 : 21);
               encodedPosition |= z << (axis == 1 ? 16 : 11);
               int shiftAmount = axis == 0 ? 16 : (axis == 1 ? 11 : 21);
               encodedPosition |= auxPos << shiftAmount;
               long quad = data | Integer.toUnsignedLong(encodedPosition);
               int bufferIdx = type + (type == 2 ? face : 0);
               if (RenderDataFactory.this.quadCounters[bufferIdx] >= MAX_BUCKET_QUADS) {
                   RenderDataFactory.this.quadCount--;
                   RenderDataFactory.this.droppedQuads++;
                   return;
               }
               long bufferOffset = RenderDataFactory.this.quadCounters[bufferIdx]++ * 8L + bufferIdx * 8L * 65536L;
               MemoryUtil.memPutLong(RenderDataFactory.this.quadBufferPtr + bufferOffset, quad);
               if (axis == 0) {
                  RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, auxPos);
                  RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, auxPos);
                  RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
                  RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);
                  RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
                  RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
               } else if (axis == 1) {
                  RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, auxPos);
                  RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, auxPos);
                  RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
                  RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);
                  RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, z);
                  RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, z + width);
               } else {
                  RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, auxPos);
                  RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, auxPos);
                  RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, x);
                  RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, x + length);
                  RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
                  RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
               }
            }
         }
      }
   }
}
