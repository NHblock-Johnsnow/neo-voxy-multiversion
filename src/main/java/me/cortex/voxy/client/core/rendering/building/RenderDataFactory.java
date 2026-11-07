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

/** 将 32³ 世界区段转换为固定布局的 LOD 四边形缓冲。 */
public class RenderDataFactory {
    // 这些开关只用于验证或保留旧版实验路径，不能在热路径中动态改变。
    private static final boolean BUILD_OCCUPANCY_SET = false;

    private static final boolean CHECK_NEIGHBOR_FACE_OCCLUSION = true;
    private static final boolean DISABLE_CULL_SAME_OCCLUDES = false;

    private static final boolean VERIFY_MESHING = VoxyCommon.isVerificationFlagOn("verifyMeshing");

    private final WorldEngine world;
    private final ModelFactory modelMan;

    private final long[] sectionData = new long[32*32*32*2];
    private final long[] neighboringFaces = new long[32*32*6];

    private final int[] opaqueMasks = new int[32*32];
    private final int[] nonOpaqueMasks = new int[32*32];
    // 流体单独建面，允许同一体素同时保留方块和流体覆盖层。
    private final int[] fluidMasks = new int[32 * 32];
    private final short[] biomeIds = new short[32*32*32];

    // 缓冲步长保留 65536；元数据的 u16 数量最多表示 65535。
    private static final int MAX_BUCKET_QUADS = (1 << 16) - 1;
    private final MemoryBuffer quadBuffer = new MemoryBuffer(8 * (8 * (1 << 16)));
    private final long quadBufferPtr = this.quadBuffer.address;
    private final int[] quadCounters = new int[8];
    private int droppedQuads;

    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    private int quadCount = 0;

    private final OccupancySet occupancy;

    // 扫描合并器只接收单面几何；双面/非不透明面走 secondaryBlockMesher。
    private final class Mesher extends ScanMesher2D {
        public int auxiliaryPosition = 0;
        public boolean doAuxiliaryFaceOffset = true;
        public int axis = 0;//Y,Z,X

        //Note x, z are in top right
        @Override
        protected void emitQuad(int x, int z, int length, int width, long data) {
            this.emitQuad(x, z, length, width, data, -1);
        }

        private void emitShapedFluidQuad(int x, int z, int payload, long data) {
            this.emitQuad(x, z, 1, 1, data, payload & 0xFF);
        }

        private void emitQuad(int x, int z, int length, int width, long data, int encodedSizeBits) {
            if (VERIFY_MESHING) {
                if (length<1||length>16) {
                    throw new IllegalStateException("length out of bounds: " + length);
                }
                if (width<1||width>16) {
                    throw new IllegalStateException("width out of bounds: " + width);
                }
                if (x<0||x>31) {
                    throw new IllegalStateException("x out of bounds: " + x);
                }
                if (z<0||z>31) {
                    throw new IllegalStateException("z out of bounds: " + z);
                }
                if (x-(length-1)<0 || z-(width-1)<0) {
                    throw new IllegalStateException("dim out of bounds: " + (x-(length-1))+", " + (z-(width-1)));
                }
            }

            RenderDataFactory.this.quadCount++;

            x -= length-1;
            z -= width-1;

            if (this.axis == 2) {
                //Need to swizzle the data if on x axis
                int tmp = x;
                x = z;
                z = tmp;

                tmp = length;
                length = width;
                width = tmp;
            }

            //Lower 26 bits can be auxiliary data since that is where quad position information goes;
            int auxData = (int) (data&((1<<26)-1));
            data &= ~((1L<<26)-1);

            int axisSide = auxData&1;
            int type = (auxData>>1)&3;//Translucent, double side, directional

            if (VERIFY_MESHING) {
                if (type == 3) {
                    throw new IllegalStateException();
                }
            }

            //Shift up if is negative axis
            int auxPos = this.auxiliaryPosition;
            auxPos += 1-(this.doAuxiliaryFaceOffset?axisSide:1);//Shift

            if (VERIFY_MESHING) {
                if (auxPos > 31) {
                    throw new IllegalStateException("OOB face: " + auxPos + ", " + axisSide);
                }
            }

            final int axis = this.axis;
            int face = (axis<<1)|axisSide;

            int encodedPosition = face;
            encodedPosition |= encodedSizeBits >= 0
                    ? encodedSizeBits << 3
                    : ((width - 1) << 7) | ((length - 1) << 3);
            encodedPosition |= x << (axis==2?16:21);
            encodedPosition |= z << (axis==1?16:11);
            int shiftAmount = axis==0?16:(axis==1?11:21);
            //shiftAmount += ;
            encodedPosition |= auxPos << (shiftAmount);

            long quad = data | Integer.toUnsignedLong(encodedPosition);

            int bufferIdx = type+(type==2?face:0);//Translucent, double side, directional
            if (RenderDataFactory.this.quadCounters[bufferIdx] >= MAX_BUCKET_QUADS) {
                RenderDataFactory.this.quadCount--;
                RenderDataFactory.this.droppedQuads++;
                return;
            }
            long bufferOffset = (RenderDataFactory.this.quadCounters[bufferIdx]++)*8L + bufferIdx*8L*(1<<16);
            MemoryUtil.memPutLong(RenderDataFactory.this.quadBufferPtr + bufferOffset, quad);

            //Update AABB bounds
            if (axis == 0) {//Y
                RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, auxPos);
                RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, auxPos);

                RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
                RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);

                RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
                RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
            } else if (axis == 1) {//Z
                RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, auxPos);
                RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, auxPos);

                RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
                RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);

                RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, z);
                RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, z + width);
            } else {//X
                RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, auxPos);
                RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, auxPos);

                RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, x);
                RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, x + length);

                RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
                RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
            }
        }
    }

    private final Mesher blockMesher = new Mesher();
    private final Mesher secondaryBlockMesher = new Mesher();

    public RenderDataFactory(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets) {
        this(world, modelManager, emitMeshlets, false);
    }
    /** 创建一次性复用的建面工作区；工作线程只使用所属实例的数组。 */
    public RenderDataFactory(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets, boolean generateOccupancy) {
        this.world = world;
        this.modelMan = modelManager;
        if (generateOccupancy && BUILD_OCCUPANCY_SET) {
            this.occupancy = new OccupancySet();
        } else {
            this.occupancy = null;
        }
    }

    // ---- 元数据编码 -----------------------------------------------------

    private static long getQuadTyping(long metadata) {
        return 0b111L&(0b000_000_010_100L>>(ModelQueries._isTranslucent(metadata)*6+ModelQueries._isDoubleSided(metadata)*3));
    }

    private static long packPartialQuadData(int modelId, long state, long metadata) {
        //This uses hardcoded data to shuffle things
        long lightAndBiome =  (state&((0x1FFL<<47)|(0xFFL<<56)))>>>1;
        lightAndBiome &= ~(ModelQueries._notIsBiomeColoured(metadata) * (0x1FFL << 46));//46 not 47 because is already shifted by 1 THIS WASTED 4 HOURS ;-; aaaaaAAAAAA
        lightAndBiome &= ~(ModelQueries._isFullyOpaque(metadata)*(0xFFL << 55));//If its fully opaque it always uses neighbor light?

        long quadData = lightAndBiome;
        quadData |= Integer.toUnsignedLong(modelId)<<26;
        quadData |= getQuadTyping(metadata);//Returns the typing already shifted by 1
        return quadData;
    }

    // ---- 区段准备和邻接数据 -------------------------------------------

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
                    if (fluid) {
                        var seasonal = me.cortex.voxy.client.core.compat.eclipticseasons.SeasonalLod.view;
                        if (seasonal != null) {
                            try {
                                seasonal.substituteRegion(this.world, this.surfaceView, this.horizontalHalo,
                                        haloIndex(minX, dy * 32, minZ), maxX - minX, dy == 0 ? 32 : 1,
                                        maxZ - minZ, HALO_STRIDE, HALO_LAYER, minX, dy * 32, minZ);
                            } catch (LinkageError error) {
                                me.cortex.voxy.client.core.compat.eclipticseasons.SeasonalLod.disarm(error);
                            }
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

    private int prepareSectionData(final long[] rawSectionData) {
        final var sectionData = this.sectionData;
        if (sectionData.length != 32*32*32*2 || rawSectionData.length != 32*32*32) {
            throw new IllegalStateException("Unexpected section buffer size");
        }
        final var rawModelIds = this.modelMan._unsafeRawAccess();
        long opaque = 0;
        long notEmpty = 0;
        long pureFluid = 0;
        long partialFluid = 0;

        int neighborAcquireMskAndFlags = 0;//-+x, -+z, -+y
        int i = 0;
        for (int q = 0; q < 512; q++) {
            for (int j = 0; j < 64; i++, j++) {
                i &= 32*32*32-1;
                long block = rawSectionData[i];//Get the block mapping
                int i2 = (i * 2) & ((32*32*32*2-1)^1);
                this.biomeIds[i] = (short) Mapper.getBiomeId(block);
                if (Mapper.isAir(block)) {//If it is air, just emit lighting
                    sectionData[i2] = (block & (0xFFL << 56)) >>> 1;
                    sectionData[i2 + 1] = 0;
                } else {
                    int modelId = rawModelIds[Mapper.getBlockId(block)];
                    if (modelId == -1) {//Failed, so just return error
                        return Mapper.getBlockId(block) | (1 << 31);
                    }
                    if (modelId == 0) {//modelId == 0, its basicly air so set it as air
                        sectionData[i2] = (block & (0xFFL << 56)) >>> 1;
                        sectionData[i2 + 1] = 0;
                    } else {

                        long modelMetadata = this.modelMan.getModelMetadataFromClientId(modelId);

                        sectionData[i2] = packPartialQuadData(modelId, block, modelMetadata);
                        sectionData[i2 + 1] = modelMetadata;

                        notEmpty |= 1L << j;
                        opaque |= ModelQueries._isFullyOpaque(modelMetadata)<<j;
                        pureFluid |= ModelQueries._isFluid(modelMetadata)<<j;
                        partialFluid |= ModelQueries._containsFluid(modelMetadata)<<j;
                    }
                }
            }
            if (notEmpty != 0) {
                neighborAcquireMskAndFlags |= getNeighborMsk(notEmpty, i);
                neighborAcquireMskAndFlags |= opaque!=0?(1<<6):0;
                this.opaqueMasks[(i >> 5) - 2] = (int) opaque;
                this.opaqueMasks[(i >> 5) - 1] = (int) (opaque>>>32);
                long nonOpaque = (notEmpty^opaque)&~pureFluid;
                notEmpty = 0;
                opaque = 0;
                this.nonOpaqueMasks[(i >> 5) - 2] = (int) nonOpaque;
                this.nonOpaqueMasks[(i >> 5) - 1] = (int) (nonOpaque>>>32);
                long fluid = pureFluid|partialFluid;
                pureFluid = 0;
                partialFluid = 0;
                this.fluidMasks[(i >> 5) - 2] = (int) fluid;
                this.fluidMasks[(i >> 5) - 1] = (int) (fluid>>>32);
            }
        }
        return neighborAcquireMskAndFlags;
    }

    private static int getNeighborMsk(long notEmpty, int i) {
        int packedEmpty = (int) ((notEmpty >>>32)| notEmpty);

        int neighborMsk = 0;
        //-+x
        neighborMsk += packedEmpty&1;//-x
        neighborMsk += (packedEmpty>>>30)&0b10;//+x

        //notEmpty = (notEmpty != 0)?1:0;
        neighborMsk += ((((i - 1) >> 10) == 0) ? 0b100 : 0)*(packedEmpty!=0?1:0);//-y
        neighborMsk += ((((i - 1) >> 10) == 31) ? 0b1000 : 0)*(packedEmpty!=0?1:0);//+y
        neighborMsk += (((((i - 33) >> 5) & 0x1F) == 0) ? 0b10000 : 0)*(((int) notEmpty)!=0?1:0);//-z
        neighborMsk += (((((i - 1) >> 5) & 0x1F) == 31) ? 0b100000 : 0)*((notEmpty >>>32)!=0?1:0);//+z
        return neighborMsk;
    }

    private int acquireNeighborData(WorldSection section, int msk) {
        int unavailable = 0;
        for (int face = 0; face < 6; face++) {
            if ((msk & (1 << face)) == 0) continue;
            long missing = face == 3 ? Mapper.airWithLight(0x0F) : 0L;
            if (!this.surfaceView.copyFace(face, this.neighboringFaces, face * 1024, missing)) {
                unavailable |= 1 << face;
            }
        }
        return unavailable;
    }

    private static final long LM = (0xFFL << 55);

    // ---- 流体描述和形状编码 -------------------------------------------

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
        return this.modelMan.getFluidKind(fluidModelId(voxelIndex));
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
        return modelId == 0 ? 0 : this.modelMan.getFluidKind(modelId);
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
        long above = horizontalNeighborRaw(x, y + 1, z);
        return rawFluidKind(above) == kind ? 9 : height;
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
        if (kind != 0 && y < 31 && fluidKind(voxelIndex + 1024) == kind) {
            return 9;
        }
        if (kind != 0 && y == 31) {
            int x = voxelIndex & 31;
            int z = (voxelIndex >>> 5) & 31;
            if (rawFluidKind(this.neighboringFaces[3 * 32 * 32 + x + z * 32]) == kind) {
                return 9;
            }
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
                        long metadata = this.sectionData[sample * 2 + 1];
                        sampleHeight = ModelQueries.isFullyOpaque(metadata) ? -1.0f : 0.0f;
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

    // ---- 面剔除与四边形编码 -------------------------------------------

    private static boolean shouldMeshNonOpaqueBlockFace(int face, long quad, long meta, long neighborQuad, long neighborMeta) {
        if (((quad^neighborQuad)&(0xFFFFL<<26))==0 && (DISABLE_CULL_SAME_OCCLUDES || ModelQueries.cullsSame(meta))) return false;
        if (!ModelQueries.faceExists(meta, face)) return false;//Dont mesh if no face
        if (ModelQueries.faceCanBeOccluded(meta, face))
          if (ModelQueries.faceOccludes(neighborMeta, face^1)) return false;
        return true;
    }

    private static void meshNonOpaqueFace(int face, long quad, long meta, long neighborQuad, long neighborMeta, Mesher mesher) {
        if (shouldMeshNonOpaqueBlockFace(face, quad, meta, neighborQuad, neighborMeta)) {
            mesher.putNext(applyQuadLight(
                    (long) (face&1) |
                    (quad&~LM) |
                    ((ModelQueries.faceUsesSelfLighting(meta, face)?quad:neighborQuad) & LM),
                    meta));
        } else {
            mesher.skip(1);
        }
    }

    private static long applyQuadLight(long quad, long selfmeta) {
        final long BLMSK = 0xFL<<(55+4);//block light mask
        long bl = quad&BLMSK;
        bl = Math.max(bl, ModelQueries.lightEmission(selfmeta)<<(55+4));
        quad &= ~(BLMSK);
        quad |= bl;
        return quad;
    }

    private static long maxQuadLight(long a, long b) {
        final long SKYMSK = 0xFL<<55;
        final long BLKMSK = 0xFL<<59;
        return Math.max(a&SKYMSK, b&SKYMSK) | Math.max(a&BLKMSK, b&BLKMSK);
    }

    // ---- Y/Z 方向几何 --------------------------------------------------

    private void generateYZOpaqueInnerGeometry(int axis) {
        for (int layer = 0; layer < 31; layer++) {
            this.blockMesher.auxiliaryPosition = layer;
            int cSkip = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis==0 ?(layer*32+other):(other*32+layer);
                int skipAmount = axis==0?32:1;

                int current = this.opaqueMasks[pidx];
                int next = this.opaqueMasks[pidx + skipAmount];

                int msk = current ^ next;
                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                cSkip = 0;

                int faceForwardMsk = msk & current;
                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    int facingForward = ((faceForwardMsk >> index) & 1);

                    {
                        int idx = index + (pidx*32);
                        int shift = skipAmount * 32 * 2;

                        //Flip data with respect to facing direction
                        int iA = idx * 2 + (facingForward == 1 ? 0 : shift);
                        int iB = idx * 2 + (facingForward == 1 ? shift : 0);

                        long selfModel = this.sectionData[iA];
                        long selfMeta  = this.sectionData[iA+1];
                        long nextModel = this.sectionData[iB];

                        //Check if next culls this face
                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            long neighbor = this.sectionData[iB + 1];
                            boolean culls = false;
                            culls |= ((selfModel^nextModel)&(0xFFFFL<<26))==0&&ModelQueries.cullsSame(neighbor);
                            culls |= ModelQueries.faceOccludes(neighbor, (axis << 1) | (1 - facingForward));
                            if (culls) {
                                this.blockMesher.skip(1);
                                continue;
                            }
                        }

                        this.blockMesher.putNext(
                            applyQuadLight(
                                ((long) facingForward) |//Facing
                                (selfModel&~LM) |
                                (nextModel&LM),//Apply lighting
                                selfMeta
                            ));
                    }
                }

                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
    }

    private void generateYZOpaqueOuterGeometry(int axis) {
        this.blockMesher.doAuxiliaryFaceOffset = false;
        for (int side = 0; side < 2; side++) {//-, +
            int layer = side == 0 ? 0 : 31;
            this.blockMesher.auxiliaryPosition = layer;
            int cSkips = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int msk = this.opaqueMasks[pidx];
                if (msk == 0) {
                    cSkips += 32;
                    continue;
                }

                this.blockMesher.skip(cSkips);
                cSkips = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);

                        int neighborIdx = ((axis+1)*32*32 * 2)+(side)*32*32;
                        long neighborId = this.neighboringFaces[neighborIdx + (other*32) + index];
                        long A = this.sectionData[idx * 2];
                        long selfMeta = this.sectionData[idx * 2 +1];

                        int nib = Mapper.getBlockId(neighborId);
                        if (nib != 0) {//Not air

                            int cid = this.modelMan.getModelId(nib);
                            long meta = this.modelMan.getModelMetadataFromClientId(cid);
                            if (ModelQueries.isFullyOpaque(meta)) {//Dont mesh this face
                                this.blockMesher.skip(1);
                                continue;
                            }

                            //This very funnily causes issues when not combined with meshing non full opaque geometry
                            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                                boolean culls = false;
                                culls |= cid==((A>>26)&0xFFFF)&&ModelQueries.cullsSame(meta);
                                culls |= ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side));
                                if (culls) {
                                    this.blockMesher.skip(1);
                                    continue;
                                }
                            }
                        }

                        this.blockMesher.putNext(applyQuadLight(
                                ((side == 0) ? 0L : 1L) |
                                (A&~LM) |
                                ((neighborId & (0xFFL << 56)) >>> 1),
                                selfMeta
                                )
                        );
                    }
                }
                this.blockMesher.endRow();
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
                int pidx = axis==0 ?(layer*32+other):(other*32+layer);
                int skipAmount = axis==0?32:1;

                int current = this.fluidMasks[pidx];
                int next = this.fluidMasks[pidx + skipAmount];

                int msk = (current | this.opaqueMasks[pidx]) ^ (next | this.opaqueMasks[pidx + skipAmount]);
                msk &= current|next;
                int differentFluidMsk = 0;
                int higherCurrentMsk = 0;
                int shared = current & next;
                while (shared != 0) {
                    int index = Integer.numberOfTrailingZeros(shared);
                    shared &= ~Integer.lowestOneBit(shared);
                    int aIndex = index + pidx * 32;
                    int bIndex = aIndex + skipAmount * 32;
                    int aFluid = fluidDescriptor(aIndex);
                    int bFluid = fluidDescriptor(bIndex);
                    if (axis != 0 && aFluid != bFluid) {
                        differentFluidMsk |= 1 << index;
                        int aHeight = aFluid & 31;
                        int bHeight = bFluid & 31;
                        if (aHeight >= bHeight) higherCurrentMsk |= 1 << index;
                    }
                }
                msk |= differentFluidMsk;
                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                cSkip = 0;

                int faceForwardMsk = (msk & current & ~differentFluidMsk) | higherCurrentMsk;
                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    int facingForward = ((faceForwardMsk >> index) & 1);

                    {
                        int idx = index + (pidx*32);

                        int a = idx*2;
                        int b = (idx + skipAmount * 32) * 2;

                        //Flip data with respect to facing direction
                        int ai = facingForward == 1 ? a : b;
                        int bi = facingForward == 1 ? b : a;

                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludes(this.sectionData[bi + 1], (axis << 1) | (1 - facingForward))) {
                                this.blockMesher.skip(1);
                                continue;
                            }
                        }

                        long A = this.sectionData[ai];
                        long Am = this.sectionData[ai+1];
                        //If it isnt a fluid but contains one,
                        if (ModelQueries.containsFluid(Am)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                            //Update quad typing info
                            A &= ~0b110L; A |= getQuadTyping(Am);
                        }

                        if (axis == 0 && facingForward == 1 && this.modelMan.getFluidKind(
                                (int) ((A >>> 26) & 0xFFFF)) != 0) {
                            this.blockMesher.skip(1);
                            continue;
                        }

                        if ((differentFluidMsk & (1 << index)) != 0) {
                            int selfFluid = fluidDescriptor(ai / 2);
                            int neighborFluid = fluidDescriptor(bi / 2);
                            if ((selfFluid & (1 << 5)) == (neighborFluid & (1 << 5))) {
                                A = encodeFluidStep(A, neighborFluid & 31, ai / 2);
                            }
                        }

                        long lighter = this.sectionData[bi];

                        this.blockMesher.putNext(applyQuadLight(
                                ((long) facingForward) |//Facing
                                (A&~LM) |
                                maxQuadLight(A, lighter),//Apply lighting
                                Am)
                        );
                    }
                }

                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
    }

    private void generateYZFluidOuterGeometry(int axis, int unavailableNeighborMsk) {
        this.blockMesher.doAuxiliaryFaceOffset = false;
        for (int side = 0; side < 2; side++) {//-, +
            int layer = side == 0 ? 0 : 31;
            boolean suppressUnknownEdge = axis == 1 && (unavailableNeighborMsk & (16 << side)) != 0;
            this.blockMesher.auxiliaryPosition = layer;
            int cSkips = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int msk = suppressUnknownEdge ? 0 : this.fluidMasks[pidx];
                if (msk == 0) {
                    cSkips += 32;
                    continue;
                }

                this.blockMesher.skip(cSkips);
                cSkips = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);

                        int neighborIdx = ((axis+1)*32*32 * 2)+(side)*32*32;
                        long neighborId = this.neighboringFaces[neighborIdx + (other*32) + index];

                        long A = this.sectionData[idx * 2];
                        long Am = this.sectionData[idx * 2 + 1];

                        if (ModelQueries.containsFluid(Am)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                            //We need to update the typing info for A
                            A &= ~0b110L; A |= getQuadTyping(Am);
                        }

                        if (((axis == 0 && side == 1) || axis == 1)
                                && this.modelMan.getFluidKind((int) ((A >>> 26) & 0xFFFF)) != 0) {
                            this.blockMesher.skip(1);
                            continue;
                        }

                        //Check and test if can cull W.R.T neighbor
                        if (Mapper.getBlockId(neighborId) != 0) {//Not air
                            int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                            long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                            if (ModelQueries.containsFluid(meta)) {
                                modelId = this.modelMan.getFluidClientStateId(modelId);
                            }
                            if (ModelQueries.cullsSame(Am)) {
                                if (modelId == ((A>>26)&0xFFFF)) {
                                    this.blockMesher.skip(1);
                                    continue;
                                }
                            }

                            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                                if (ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side))) {
                                    this.blockMesher.skip(1);
                                    continue;
                                }
                            }
                        }

                        this.blockMesher.putNext(applyQuadLight(
                                (side == 0 ? 0L : 1L) |
                                (A&~LM) |
                                maxQuadLight(A, (neighborId&(0xFFL<<56))>>>1),
                                Am)
                        );
                    }
                }
                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
        this.blockMesher.doAuxiliaryFaceOffset = true;
    }

    private void generateYZNonOpaqueInnerGeometry(int axis) {
        //Note: think is ok to just reuse.. blockMesher
        this.secondaryBlockMesher.doAuxiliaryFaceOffset = false;
        this.blockMesher.axis = axis;
        this.secondaryBlockMesher.axis = axis;
        for (int layer = 1; layer < 31; layer++) {//(should be 1->31, then have outer face mesher)
            this.blockMesher.auxiliaryPosition = layer;
            this.secondaryBlockMesher.auxiliaryPosition = layer;
            int cSkip = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int skipAmount = axis==0?32*32:32;

                int msk = this.nonOpaqueMasks[pidx];

                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                this.secondaryBlockMesher.skip(cSkip);
                cSkip = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) {
                        this.blockMesher.skip(delta);
                        this.secondaryBlockMesher.skip(delta);
                    }
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);

                        long A = this.sectionData[idx * 2];
                        long B = this.sectionData[idx * 2+1];

                        meshNonOpaqueFace((axis<<1)|0, A, B, this.sectionData[(idx-skipAmount)*2], this.sectionData[(idx-skipAmount)*2+1], this.secondaryBlockMesher);//-
                        meshNonOpaqueFace((axis<<1)|1, A, B, this.sectionData[(idx+skipAmount)*2], this.sectionData[(idx+skipAmount)*2+1], this.blockMesher);//+
                    }
                }
                this.blockMesher.endRow();
                this.secondaryBlockMesher.endRow();
            }
            this.blockMesher.finish();
            this.secondaryBlockMesher.finish();
        }
    }

    private void generateYZNonOpaqueOuterGeometry(int axis) {
        //Note: think is ok to just reuse.. blockMesher
        this.secondaryBlockMesher.doAuxiliaryFaceOffset = false;
        this.blockMesher.axis = axis;
        this.secondaryBlockMesher.axis = axis;
        for (int side = 0; side < 2; side++) {//-, +
            int layer = side == 0 ? 0 : 31;
            int skipAmount = (axis==0?32*32:32) * (1-(side*2));
            this.blockMesher.auxiliaryPosition = layer;
            this.secondaryBlockMesher.auxiliaryPosition = layer;
            int cSkips = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int msk = this.nonOpaqueMasks[pidx];
                if (msk == 0) {
                    cSkips += 32;
                    continue;
                }

                this.blockMesher.skip(cSkips);
                this.secondaryBlockMesher.skip(cSkips);
                cSkips = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) {
                        this.blockMesher.skip(delta);
                        this.secondaryBlockMesher.skip(delta);
                    }
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);

                        int neighborIdx = ((axis+1)*32*32 * 2)+(side)*32*32;
                        long neighborId = this.neighboringFaces[neighborIdx + (other*32) + index];

                        long A = this.sectionData[idx * 2];
                        long Am = this.sectionData[idx * 2 + 1];

                        boolean fail = false;
                        //Check and test if can cull W.R.T neighbor
                        if (Mapper.getBlockId(neighborId) != 0) {//Not air
                            int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));

                            if (ModelQueries.cullsSame(Am) && modelId == ((A>>26)&0xFFFF)) {
                                fail = true;
                            } else {
                                long meta = this.modelMan.getModelMetadataFromClientId(modelId);

                                if (ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side))) {
                                    fail = true;
                                }
                            }
                        }

                        long nA = this.sectionData[(idx+skipAmount) * 2];
                        long nB = this.sectionData[(idx+skipAmount) * 2 + 1];
                        boolean failB = false;

                        if (ModelQueries.cullsSame(nB) && (nA&(0xFFFFL<<26)) == (A&(0xFFFFL<<26))) {
                            failB = true;
                        } else {
                            if (ModelQueries.faceOccludes(nB, (axis << 1) | (side))) {
                                failB = true;
                            }
                        }

                        if (ModelQueries.faceExists(Am, (axis<<1)|1) && ((side==1&&!fail) || (side==0&&!failB))) {
                            this.blockMesher.putNext(applyQuadLight(
                                    (long) (false ? 0L : 1L) |
                                    A |
                                    0,//((ModelQueries.faceUsesSelfLighting(B, (axis<<1)|1)?A:) & (0xFFL << 55))
                                    Am)
                            );
                        } else {
                            this.blockMesher.skip(1);
                        }

                        if (ModelQueries.faceExists(Am, (axis<<1)|0) && ((side==0&&!fail) || (side==1&&!failB))) {
                            this.secondaryBlockMesher.putNext(applyQuadLight(
                                    (long) (true ? 0L : 1L) |
                                    A |
                                    0,//(((0xFFL) & 0xFF) << 55)
                                    Am)
                            );
                        } else {
                            this.secondaryBlockMesher.skip(1);
                        }
                    }
                }
                this.blockMesher.endRow();
                this.secondaryBlockMesher.endRow();
            }
            this.blockMesher.finish();
            this.secondaryBlockMesher.finish();
        }
    }

    private void generateYZFaces() {
        for (int axis = 0; axis < 2; axis++) {//Y then Z
            this.blockMesher.axis = axis;

            this.generateYZOpaqueInnerGeometry(axis);
            this.generateYZOpaqueOuterGeometry(axis);

            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                this.generateYZNonOpaqueInnerGeometry(axis);
                this.generateYZNonOpaqueOuterGeometry(axis);
            }
        }
    }

    private final Mesher[] xAxisMeshers = new Mesher[32];
    private final Mesher[] secondaryXAxisMeshers = new Mesher[32];
    {
        for (int i = 0; i < 32; i++) {
            var mesher = new Mesher();
            mesher.auxiliaryPosition = i;
            mesher.axis = 2;//X axis
            this.xAxisMeshers[i] = mesher;
        }
        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
            for (int i = 0; i < 32; i++) {
                var mesher = new Mesher();
                mesher.auxiliaryPosition = i;
                mesher.axis = 2;//X axis
                mesher.doAuxiliaryFaceOffset = false;
                this.secondaryXAxisMeshers[i] = mesher;
            }
        }
    }

    // ---- X 方向几何 ----------------------------------------------------

    private static final long X_I_MSK = 0x4210842108421L;

    private void generateXOpaqueInnerGeometry() {
        for (int y = 0; y < 32; y++) {
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                int lMsk = this.opaqueMasks[y*32+z];
                msk = (lMsk^(lMsk>>>1));
                msk &= -1>>>1;//Remove top bit as we dont actually know/have the data for that slice

                //Always increment cause can do funny trick (i.e. -1 on skip amount)
                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);
                        this.xAxisMeshers[index].skip(31);
                    }
                    //Clear the sum
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                int faceForwardMsk = msk&lMsk;
                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);

                    var mesher = this.xAxisMeshers[index];

                    int skipCount;//Compute the skip count
                    {
                        //Compute skip and clear
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    if (skipCount != 0) {
                        mesher.skip(skipCount);
                    }

                    int facingForward = ((faceForwardMsk>>index)&1);
                    {
                        int idx = index + (z * 32) + (y * 32 * 32);

                        //Flip data with respect to facing direction
                        int iA = idx * 2 + (facingForward == 1 ? 0 : 2);
                        int iB = idx * 2 + (facingForward == 1 ? 2 : 0);

                        //Check if next culls this face
                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludes(this.sectionData[iB + 1], (2 << 1) | (1 - facingForward))) {
                                mesher.skip(1);
                                continue;
                            }
                        }

                        long selfModel = this.sectionData[iA];
                        long selfMeta  = this.sectionData[iA+1];
                        long nextModel = this.sectionData[iB];

                        //Example thing thats just wrong but as example
                        mesher.putNext(applyQuadLight(
                                ((long) facingForward) |//Facing
                                (selfModel&~LM) |
                                (nextModel&LM),
                                selfMeta
                                )
                        );
                    }
                }
            }

            //Need to skip the remaining entries in the skip array
            {
                msk = ~msk;//Invert the mask as we only need to set stuff that isnt 0
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }
    }

    private void generateXOuterOpaqueGeometry() {

        var ma = this.xAxisMeshers[0];
        var mb = this.xAxisMeshers[31];
        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = false;
        mb.doAuxiliaryFaceOffset = false;

        for (int y = 0; y < 32; y++) {
            int skipA = 0;
            int skipB = 0;
            for (int z = 0; z < 32; z++) {
                int i = y*32+z;
                int msk = this.opaqueMasks[i];
                if ((msk & 1) != 0) {//-x
                    long neighborId = this.neighboringFaces[i];
                    boolean oki = true;
                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        } else if (CHECK_NEIGHBOR_FACE_OCCLUSION && ModelQueries.faceOccludes(meta, (2 << 1) | (1 - 0))) {
                            oki = false;
                        }
                    }
                    if (oki) {
                        ma.skip(skipA); skipA = 0;
                        long A = this.sectionData[(i<<5) * 2];
                        long Am = this.sectionData[(i<<5) * 2+1];
                        ma.putNext(applyQuadLight(
                                0L |
                                (A&~LM) |
                                ((neighborId&(0xFFL<<56))>>>1),
                                Am
                                )
                        );
                    } else {skipA++;}
                } else {skipA++;}

                if ((msk & (1<<31)) != 0) {//+x
                    long neighborId = this.neighboringFaces[i+32*32];
                    boolean oki = true;
                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        } else if (CHECK_NEIGHBOR_FACE_OCCLUSION && ModelQueries.faceOccludes(meta, (2 << 1) | (1 - 1))) {
                            oki = false;
                        }
                    }
                    if (oki) {
                        mb.skip(skipB); skipB = 0;
                        long A = this.sectionData[(i*32+31) * 2];
                        long Am = this.sectionData[(i*32+31) * 2+1];
                        mb.putNext(applyQuadLight(
                                1L |
                                (A&~LM) |
                                ((neighborId&(0xFFL<<56))>>>1),
                                Am
                                )
                        );
                    } else {skipB++;}
                } else {skipB++;}
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
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                int oMsk = this.opaqueMasks[y*32+z];
                int fMsk = this.fluidMasks[y*32+z];
                int lMsk = oMsk|fMsk;
                msk = (lMsk^(lMsk>>>1));
                msk &= -1>>>1;//Remove top bit as we dont actually know/have the data for that slice

                //Dont generate geometry for opaque faces
                msk &= fMsk|(fMsk>>1);

                int differentFluidMsk = 0;
                int higherCurrentMsk = 0;
                int shared = fMsk & (fMsk >>> 1);
                while (shared != 0) {
                    int index = Integer.numberOfTrailingZeros(shared);
                    shared &= ~Integer.lowestOneBit(shared);
                    int aIndex = index + z * 32 + y * 32 * 32;
                    int aFluid = fluidDescriptor(aIndex);
                    int bFluid = fluidDescriptor(aIndex + 1);
                    if (aFluid != bFluid) {
                        differentFluidMsk |= 1 << index;
                        int aHeight = aFluid & 31;
                        int bHeight = bFluid & 31;
                        if (aHeight >= bHeight) higherCurrentMsk |= 1 << index;
                    }
                }
                msk |= differentFluidMsk;

                //Always increment cause can do funny trick (i.e. -1 on skip amount)
                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);
                        this.xAxisMeshers[index].skip(31);
                    }
                    //Clear the sum
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                int faceForwardMsk = (msk & lMsk & ~differentFluidMsk) | higherCurrentMsk;
                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);

                    var mesher = this.xAxisMeshers[index];

                    int skipCount;//Compute the skip count
                    {
                        //Compute skip and clear
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    if (skipCount != 0) {
                        mesher.skip(skipCount);
                    }

                    int facingForward = ((faceForwardMsk>>index)&1);
                    {
                        int idx = index + (z * 32) + (y * 32 * 32);

                        //The facingForward thing is to get next entry automajicly
                        int ai = (idx+(1-facingForward))*2;
                        int bi = (idx+facingForward)*2;

                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludes(this.sectionData[bi + 1], (2 << 1) | (1 - facingForward))) {
                                mesher.skip(1);
                                continue;
                            }
                        }

                        long A = this.sectionData[ai];
                        long Am = this.sectionData[ai+1];

                        if (ModelQueries.containsFluid(Am)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                            //Update quad typing info to be the fluid type
                            A &= ~0b110L; A |= getQuadTyping(Am);
                        }

                        if ((differentFluidMsk & (1 << index)) != 0) {
                            int selfFluid = fluidDescriptor(ai / 2);
                            int neighborFluid = fluidDescriptor(bi / 2);
                            if ((selfFluid & (1 << 5)) == (neighborFluid & (1 << 5))) {
                                A = encodeFluidStep(A, neighborFluid & 31, ai / 2);
                            }
                        }

                        long lighter = this.sectionData[bi];

                        //Example thing thats just wrong but as example
                        mesher.putNext(applyQuadLight(
                                ((long) facingForward) |//Facing
                                (A&~LM) |
                                maxQuadLight(A, lighter),//Lighting
                                Am
                                )
                        );
                    }
                }
            }

            //Need to skip the remaining entries in the skip array
            {
                msk = ~msk;//Invert the mask as we only need to set stuff that isnt 0
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }
    }

    private void generateXOuterFluidGeometry(int unavailableNeighborMsk) {

        var ma = this.xAxisMeshers[0];
        var mb = this.xAxisMeshers[31];
        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = false;
        mb.doAuxiliaryFaceOffset = false;

        for (int y = 0; y < 32; y++) {
            int skipA = 0;
            int skipB = 0;
            for (int z = 0; z < 32; z++) {
                int i = y*32+z;
                int msk = this.fluidMasks[i];
                if ((unavailableNeighborMsk & 1) == 0 && (msk & 1) != 0) {//-x
                    long neighborId = this.neighboringFaces[i];
                    boolean oki = true;

                    int sidx = (i<<5) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    if (ModelQueries.containsFluid(Am)) {
                        int modelId = (int) ((A>>26)&0xFFFF);
                        A &= ~(0xFFFFL<<26);
                        int fluidId = this.modelMan.getFluidClientStateId(modelId);
                        A |= Integer.toUnsignedLong(fluidId)<<26;
                        Am = this.modelMan.getModelMetadataFromClientId(fluidId);

                        //Update quad typing info to be the fluid type
                        A &= ~0b110L; A |= getQuadTyping(Am);
                    }
                    if (this.modelMan.getFluidKind((int) ((A >>> 26) & 0xFFFF)) != 0) {
                        oki = false;
                    }

                    if (Mapper.getBlockId(neighborId) != 0) {//Not air

                        int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        }

                        //Check neighbor face
                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludes(meta, (2 << 1) | (1-0))) {
                                oki = false;
                            }
                        }

                        if (ModelQueries.containsFluid(meta)) {
                            modelId = this.modelMan.getFluidClientStateId(modelId);
                        }

                        if (ModelQueries.cullsSame(Am)) {
                            if (modelId == ((A>>26)&0xFFFF)) {
                                oki = false;
                            }
                        }
                    }

                    if (oki) {
                        ma.skip(skipA); skipA = 0;

                        long lightData = maxQuadLight(A, (neighborId&(0xFFL<<56))>>>1);

                        ma.putNext(applyQuadLight(
                                0L |
                                (A&~LM) |
                                lightData,
                                Am
                                )
                        );
                    } else {skipA++;}
                } else {skipA++;}

                if ((unavailableNeighborMsk & 2) == 0 && (msk & (1<<31)) != 0) {//+x
                    long neighborId = this.neighboringFaces[i+32*32];
                    boolean oki = true;

                    int sidx = (i*32+31) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    if (ModelQueries.containsFluid(Am)) {
                        int modelId = (int) ((A>>26)&0xFFFF);
                        A &= ~(0xFFFFL<<26);
                        int fluidId = this.modelMan.getFluidClientStateId(modelId);
                        A |= Integer.toUnsignedLong(fluidId)<<26;
                        Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                    }
                    if (this.modelMan.getFluidKind((int) ((A >>> 26) & 0xFFFF)) != 0) {
                        oki = false;
                    }

                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        }

                        //Check neighbor face
                        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                            if (ModelQueries.faceOccludes(meta, (2 << 1) | (1-1))) {
                                oki = false;
                            }
                        }

                        if (ModelQueries.containsFluid(meta)) {
                            modelId = this.modelMan.getFluidClientStateId(modelId);
                        }

                        if (ModelQueries.cullsSame(Am)) {
                            if (modelId == ((A>>26)&0xFFFF)) {
                                oki = false;
                            }
                        }
                    }

                    if (oki) {
                        mb.skip(skipB); skipB = 0;

                        long lightData = maxQuadLight(A, (neighborId&(0xFFL<<56))>>>1);

                        mb.putNext(applyQuadLight(
                                1L |
                                (A&~LM) |
                                lightData,
                                Am
                                )
                        );
                    } else {skipB++;}
                } else {skipB++;}
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
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                msk = this.nonOpaqueMasks[y*32+z]&(~0x80000001);//Dont mesh the outer layer

                //Always increment cause can do funny trick (i.e. -1 on skip amount)
                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);

                        this.xAxisMeshers[index].skip(31);
                        this.secondaryXAxisMeshers[index].skip(31);
                    }
                    //Clear the sum
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);

                    int skipCount;//Compute the skip count
                    {
                        //Compute skip and clear
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    var mesherA = this.xAxisMeshers[index];
                    var mesherB = this.secondaryXAxisMeshers[index];
                    if (skipCount != 0) {
                        mesherA.skip(skipCount);
                        mesherB.skip(skipCount);
                    }

                    {
                        int idx = index + (z * 32) + (y * 32 * 32);

                        long A = this.sectionData[idx*2];
                        long Am = this.sectionData[idx*2+1];

                        //Check and generate the mesh for both + and - faces
                        meshNonOpaqueFace(2<<1, A, Am, this.sectionData[(idx-1)*2], this.sectionData[(idx-1)*2+1], mesherB);//-
                        meshNonOpaqueFace((2<<1)|1, A, Am, this.sectionData[(idx+1)*2], this.sectionData[(idx+1)*2+1], mesherA);//+
                    }
                }
            }

            //Need to skip the remaining entries in the skip array
            {
                msk = ~msk;//Invert the mask as we only need to set stuff that isnt 0
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                        this.secondaryXAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }
    }

    private static void dualMeshNonOpaqueOuterX(int side, long quad, long meta, int neighborAId, int neighborLight, long neighborAMeta, long neighborBQuad, long neighborBMeta, Mesher ma, Mesher mb) {
        //side == 0 if is on 0 side and 1 if on 31 side

        if ((neighborAId==0 && ModelQueries.faceExists(meta, ((2<<1)|0)^side))||(neighborAId!=0&&shouldMeshNonOpaqueBlockFace(((2<<1)|0)^side, quad, meta, ((long)neighborAId)<<26, neighborAMeta))) {
            ma.putNext(applyQuadLight(
                    ((long)side)|
                    (quad&~LM) |
                    (ModelQueries.faceUsesSelfLighting(meta, ((2<<1)|0)^side)?quad:(((long)neighborLight)<<55)),
                    meta
                    )
            );
        } else {
            ma.skip(1);
        }

        if (shouldMeshNonOpaqueBlockFace(((2<<1)|1)^side, quad, meta, neighborBQuad, neighborBMeta)) {
            mb.putNext(applyQuadLight(
                    ((long)(side^1))|
                    (quad&~LM) |
                    ((ModelQueries.faceUsesSelfLighting(meta, ((2<<1)|1)^side)?quad:neighborBQuad)&(0xFFL<<55)),
                    meta
                    )
            );
        } else {
            mb.skip(1);
        }
    }

    private void generateXNonOpaqueOuterGeometry() {
        var npx = this.xAxisMeshers[0]; npx.finish();
        var nnx = this.secondaryXAxisMeshers[0]; nnx.finish();
        var ppx = this.xAxisMeshers[31]; ppx.finish();
        var pnx = this.secondaryXAxisMeshers[31]; pnx.finish();

        for (int y = 0; y < 32; y++) {
            int skipA = 0;
            int skipB = 0;
            for (int z = 0; z < 32; z++) {
                int i = y*32+z;
                int msk = this.nonOpaqueMasks[i];
                if ((msk & 1) != 0) {//-x
                    long neighborId = this.neighboringFaces[i];

                    int sidx = (i<<5) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    int modelId = 0;
                    long nM = 0;
                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        nM = this.modelMan.getModelMetadataFromClientId(modelId);
                    }

                    nnx.skip(skipA);
                    npx.skip(skipA); skipA = 0;

                    dualMeshNonOpaqueOuterX(0, A, Am, modelId, Mapper.getLightId(neighborId), nM, this.sectionData[sidx+2], this.sectionData[sidx+3], nnx, npx);
                } else {skipA++;}

                if ((msk & (1<<31)) != 0) {//+x
                    long neighborId = this.neighboringFaces[i+32*32];

                    int sidx = (i*32+31) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    int modelId = 0;
                    long nM = 0;
                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        nM = this.modelMan.getModelMetadataFromClientId(modelId);
                    }

                    pnx.skip(skipB);
                    ppx.skip(skipB); skipB = 0;

                    dualMeshNonOpaqueOuterX(1, A, Am, modelId, Mapper.getLightId(neighborId), nM, this.sectionData[sidx-2], this.sectionData[sidx-1], ppx, pnx);
                } else {skipB++;}
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

        for (var mesher : this.xAxisMeshers) {
            mesher.finish();
        }

        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
            this.generateXNonOpaqueInnerGeometry();
            this.generateXNonOpaqueOuterGeometry();

            for (var mesher : this.xAxisMeshers) {
                mesher.finish();
            }
            for (var mesher : this.secondaryXAxisMeshers) {
                mesher.finish();
            }
        }
    }

    private void generateFluidFaces(int unavailableNeighborMsk) {
        // The translucent bucket is order-sensitive. Submit side walls first and
        // horizontal surfaces last so water tops do not hide shoreline geometry.
        this.generateDirectHorizontalFluidGeometry();

        this.blockMesher.axis = 1;
        this.generateYZFluidOuterGeometry(1, unavailableNeighborMsk);

        for (var mesher : this.xAxisMeshers) {
            mesher.finish();
        }
        this.generateXOuterFluidGeometry(unavailableNeighborMsk);
        for (var mesher : this.xAxisMeshers) {
            mesher.finish();
        }
        this.generateDirectOuterHorizontalFluidGeometry(unavailableNeighborMsk);

        this.blockMesher.axis = 0;
        this.generateYZFluidInnerGeometry(0);
        this.generateYZFluidOuterGeometry(0, unavailableNeighborMsk);
        this.generateDirectFluidTops();
    }

    // ---- 可选占用栅格与结果输出 ---------------------------------------

    private final int occupancyBarrier(int index) {
        int occ = 0;
        int msk = this.opaqueMasks[index];
        //x
        occ |= msk^(msk>>1);
        occ |= msk^(msk<<1);
        //y
        occ |= index<32*31?msk^this.opaqueMasks[index+32]:0;
        occ |= 31<index   ?msk^this.opaqueMasks[index-32]:0;
        //z
        occ |= (index&31)<31?msk^this.opaqueMasks[index+1]:0;
        occ |= 0< (index&31)?msk^this.opaqueMasks[index-1]:0;
        return occ;
    }

    private final void buildOccupancy() {
        for (int i = 0; i < 32*32; i++) {
            int occ = this.occupancyBarrier(i);
            //We now have our occlusion mask, fill in our occupancy set
            for (;occ!=0;occ&=~Integer.lowestOneBit(occ)) {
                this.occupancy.set(i*32+Integer.numberOfTrailingZeros(occ));
            }
        }
    }

    private final void buildOccupancy16() {
        for (int i = 0; i < 16*16; i++) {
            int x = (i&15)*2;
            int y = (i>>4)*2;
            int A = this.occupancyBarrier(y*32+x); A = (A|(A>>16))&0xFFFF;
            int B = this.occupancyBarrier(y*32+x+1); B = (B|(B>>16))&0xFFFF;
            int C = this.occupancyBarrier((y+1)*32+x); C = (C|(C>>16))&0xFFFF;
            int D = this.occupancyBarrier((y+1)*32+x+1); D = (D|(D>>16))&0xFFFF;
            int occ = A|B|C|D;

            //Shink to 16 bit
            //We now have our occlusion mask, fill in our occupancy set
            for (;occ!=0;occ&=~Integer.lowestOneBit(occ)) {
                this.occupancy.set(i*16+Integer.numberOfTrailingZeros(occ));
            }
        }
    }

    //section is already acquired and gets released by the parent
    /** 读取区段快照、生成所有方向面，并返回 GPU 上传所需的不可变结果。 */
    public BuiltSection generateMesh(WorldSection section) {
        try (var view = this.surfaceView.open(this.world, section)) {
            return this.generateMeshWithView(section);
        }
    }

    private BuiltSection generateMeshWithView(WorldSection section) {
        if (section.isUniform() && Mapper.isAir(section.getUniformValue())) {
            return BuiltSection.emptyWithChildren(section.key, section.getNonEmptyChildren());
        }
        // this can result in the state of all block meshes and well _everything_ from being incorrect
        //THE EXCEPTION THAT THIS THROWS CAUSES MAJOR ISSUES

        //Copy section data to end of array so that can mutate array while reading safely

        //We must reset _everything_ that could have changed as we dont exactly know the state due to how the model id exception
        // throwing system works
        this.droppedQuads = 0;
        this.quadCount = 0;

        {//Reset all the block meshes
            this.blockMesher.reset();
            this.blockMesher.doAuxiliaryFaceOffset = true;
            this.secondaryBlockMesher.reset();
            this.secondaryBlockMesher.doAuxiliaryFaceOffset = true;
            for (var mesher : this.xAxisMeshers) {
                mesher.reset();
                mesher.doAuxiliaryFaceOffset = true;
            }
            if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
                for (var mesher : this.secondaryXAxisMeshers) {
                    mesher.reset();
                    mesher.doAuxiliaryFaceOffset = false;
                }
            }
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

        Arrays.fill(this.quadCounters,0);
        Arrays.fill(this.opaqueMasks, 0);
        Arrays.fill(this.nonOpaqueMasks, 0);
        Arrays.fill(this.fluidMasks, 0);

        //Prepare everything
        long[] rawSection = this.surfaceView.sectionData();
        var seasonalView = me.cortex.voxy.client.core.compat.eclipticseasons.SeasonalLod.view;
        if (seasonalView != null) {
            try {
                // 季节只读几何视图，发生替换时才复制，不能改写存储或采样缓冲。
                rawSection = seasonalView.substituteSection(this.world, section, rawSection, this.surfaceView);
            } catch (LinkageError e) {
                //An ES build the version gate lets through can still lack a symbol the view only
                //touches mid-mesh; the throw leaves rawSection at the season-neutral original
                me.cortex.voxy.client.core.compat.eclipticseasons.SeasonalLod.disarm(e);
                seasonalView = null;
            }
        }
        int neighborMskAndFlags = this.prepareSectionData(rawSection);
        if ((neighborMskAndFlags&(1<<31))!=0) {//We failed to get everything so throw exception
            throw new IdNotYetComputedException(neighborMskAndFlags&((1<<20)-1), true);
        }
        int neighborMsk = neighborMskAndFlags&0b11_11_11;
        int flags = neighborMskAndFlags>>>6;
        int unavailableNeighborMsk = CHECK_NEIGHBOR_FACE_OCCLUSION
                ? this.acquireNeighborData(section, neighborMsk)
                : 0;
        if (CHECK_NEIGHBOR_FACE_OCCLUSION) {
            if (seasonalView != null) {
                try {
                    //Same substitution on the pulled lateral slices, so the same-id translucent cull
                    //(shouldMeshNonOpaqueBlockFace) does not leave an ice/water wall on the section
                    //border of a frozen lake
                    seasonalView.substituteLateralSlices(this.world, section, this.neighboringFaces, neighborMsk, this.surfaceView);
                } catch (LinkageError e) {
                    //Partially substituted slices only carry render-only ids, which degrade
                    //per block at bake; the next remesh runs season neutral after the disarm
                    me.cortex.voxy.client.core.compat.eclipticseasons.SeasonalLod.disarm(e);
                }
            }
        }

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
            this.generateFluidFaces(unavailableNeighborMsk);
        } catch (IdNotYetComputedException e) {
            e.auxBitMsk = neighborMsk;
            e.auxData = this.neighboringFaces;
            throw e;
        }

        if (this.droppedQuads != 0) {
            me.cortex.voxy.commonImpl.PerfStats.quadBucketOverflow.increment();
            me.cortex.voxy.commonImpl.PerfStats.quadBucketDropped.add(this.droppedQuads);
        }

        //We only care if we have quads
        if (this.occupancy != null && section.lvl == 0 /*only generate occupancy for lowest lod level*/ && this.quadCount != 0 && (flags&1) != 0) {
            this.buildOccupancy();
        }

        // if the connecting type of the translucent block is the same AND the face is full, discard it
        // this stops e.g. multiple layers of glass (and ocean) from having 3000 layers of quads etc
        if (this.quadCount == 0) {
            return BuiltSection.emptyWithChildren(section.key, section.getNonEmptyChildren());
        }

        if (this.quadCount >= 1<<16) {
            Logger.warn("Large quad count for section " + WorldEngine.pprintPos(section.key) + " is " + this.quadCount);
        }

        if (this.minX<0 || this.minY<0 || this.minZ<0 || 32<this.maxX || 32<this.maxY || 32<this.maxZ) {
            throw new IllegalStateException();
        }

        int[] offsets = new int[8];
        var buff = new MemoryBuffer(this.quadCount * 8L);
        long ptr = buff.address;
        int coff = 0;
        for (int buffer = 0; buffer < 8; buffer++) {// translucent, double sided quads, 6 faces
            offsets[buffer] = coff;
            int size = this.quadCounters[buffer];
            UnsafeUtil.memcpy(this.quadBufferPtr + (buffer*(8*(1<<16))), ptr + coff*8L, (size* 8L));
            coff += size;
        }

        int aabb = 0;
        aabb |= this.minX;
        aabb |= this.minY<<5;
        aabb |= this.minZ<<10;
        //Feel like a clown for missing the Math.max
        aabb |= Math.max(0,this.maxX-this.minX-1)<<15;
        aabb |= Math.max(0,this.maxY-this.minY-1)<<20;
        aabb |= Math.max(0,this.maxZ-this.minZ-1)<<25;

        MemoryBuffer occupancy = null;
        if (this.occupancy != null && !this.occupancy.isEmpty()) {
            occupancy = new MemoryBuffer(this.occupancy.writeSize());
            this.occupancy.write(occupancy.address, false);
        }

        return new BuiltSection(section.key, section.getNonEmptyChildren(), aabb, buff, offsets, occupancy);
    }

    /** 释放固定四边形缓冲；调用后实例不可再次生成网格。 */
    public void free() {
        this.quadBuffer.free();
    }
}
