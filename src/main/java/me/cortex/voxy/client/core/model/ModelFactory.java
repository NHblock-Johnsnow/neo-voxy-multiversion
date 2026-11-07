package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.compat.DomumOrnamentumCompat;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;

import static me.cortex.voxy.client.core.model.ModelStore.MODEL_SIZE;
import static org.lwjgl.opengl.ARBDirectStateAccess.nglTextureSubImage2D;
import static org.lwjgl.opengl.GL11.*;

/**
 * 方块模型、纹理图集和渲染元数据的 CPU/GPU 侧协调器。
 *
 * 模型烘焙是异步的：先登记待处理状态，再由上传队列把结果提交到 GPU。metadataCache 和
 * idMappings 是地形网格热路径使用的定长查找表，布局不能与 shader 或缓存格式脱节。
 */
public class ModelFactory {
    public static final int MODEL_TEXTURE_SIZE = 16;
    public static final int LAYERS = Integer.numberOfTrailingZeros(MODEL_TEXTURE_SIZE);

    /** 相同模型和材质的去重键；字段顺序与 GPU 元数据生成保持一致。 */
    private record ModelEntry(
            ColourDepthTextureData down,
            ColourDepthTextureData up,
            ColourDepthTextureData north,
            ColourDepthTextureData south,
            ColourDepthTextureData west,
            ColourDepthTextureData east,
            int fluidBlockStateId,
            int fluidKind,
            int tintingColour,
            boolean framedBlocks,
            boolean createTrack,
            boolean conservativeComplexModel,
            boolean completeComplexBlock) {
        public ModelEntry(ColourDepthTextureData[] textures, int fluidBlockStateId, int fluidKind, int tintingColour, boolean framedBlocks, boolean createTrack, boolean conservativeComplexModel, boolean completeComplexBlock) {
            this(textures[0], textures[1], textures[2], textures[3], textures[4], textures[5],
                    fluidBlockStateId, fluidKind, tintingColour,
                    framedBlocks, createTrack, conservativeComplexModel, completeComplexBlock);
        }
    }

    private final Biome DEFAULT_BIOME = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value();

    public final SoftwareModelTextureBakery bakery2;
    private final long bakeScratchBuffer = MemoryUtil.nmemAlloc(MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE * 8 * 6);

    // 模型元数据和流体查找表：渲染线程通过定长数组读取，避免热路径 Map 查找。
    private final long[] metadataCache;
    private final int[] fluidStateLUT;
    private final int[] fluidKinds;
    private final IdentityHashMap<Fluid, Integer> fluidKindByType = new IdentityHashMap<>();
    private final List<Fluid> fluidKindRepresentatives = new ArrayList<>();

    // 多个方块状态可以共享同一个内部模型 ID。
    private final int[] idMappings;
    private final Object2IntOpenHashMap<ModelEntry> modelTexture2id = new Object2IntOpenHashMap<>();

    // GPU 异步反馈期间的进行中集合，防止递归登记同一个状态。
    private final IntOpenHashSet blockStatesInFlight = new IntOpenHashSet();
    private final ReentrantLock blockStatesInFlightLock = new ReentrantLock();

    private final List<Biome> biomes = new ArrayList<>();
    private final List<Pair<Integer, BlockState>> modelsRequiringBiomeColours = new ArrayList<>();

    private static final ObjectSet<BlockState> LOGGED_SELF_CULLING_WARNING = new ObjectOpenHashSet<>();

    private final Mapper mapper;
    private final ModelStore storage;
    private final BiomeBlendPalette blendPalette = new BiomeBlendPalette();

    public BiomeBlendPalette blendPalette() {
        return this.blendPalette;
    }

    private final ConcurrentLinkedDeque<BlockBake> blockBakeQueue = new ConcurrentLinkedDeque<>();

    private final ConcurrentLinkedDeque<ResultUploader> uploadResults = new ConcurrentLinkedDeque<>();

    private Object2IntMap<BlockState> customBlockStateIdMapping;

    // 初始化空模型后，后续模型按需登记和烘焙。
    public ModelFactory(Mapper mapper, ModelStore storage) {
        this.mapper = mapper;
        this.storage = storage;
        this.bakery2 = new SoftwareModelTextureBakery(mapper);
        this.bakery2.setupTexture();

        this.metadataCache = new long[1 << 16];
        this.fluidStateLUT = new int[1 << 16];
        this.fluidKinds = new int[1 << 16];
        this.idMappings = new int[1 << 20];
        Arrays.fill(this.idMappings, -1);
        Arrays.fill(this.fluidStateLUT, -1);

        this.modelTexture2id.defaultReturnValue(-1);
        this.addEntry(0);
    }

    public void setCustomBlockStateMapping(Object2IntMap<BlockState> mapping) {
        this.customBlockStateIdMapping = mapping;
    }

    private static final record BlockBake(int blockId, BlockState state) {
    }

    /** 登记一个方块状态并把烘焙任务放入异步队列。 */
    public boolean addEntry(int blockId) {
        if (this.idMappings[blockId] != -1) {
            return false;
        }

        // 先占用状态再解析流体依赖，避免自引用或 A→B→A 的循环递归。
        this.blockStatesInFlightLock.lock();
        try {
            if (this.idMappings[blockId] != -1 || !this.blockStatesInFlight.add(blockId)) {
                return false;
            }
        } finally {
            this.blockStatesInFlightLock.unlock();
        }

        BlockState blockState = null;
        try {
            blockState = this.mapper.getBlockStateFromBlockId(blockId);
            if (blockState.getBlock() instanceof StairBlock sb) {
                blockState = sb.baseState.getBlock().withPropertiesOf(blockState);
            }

            // 水logged 模型先登记流体，保证材质表中的流体 ID 已经可用。
            if (!isFluidBlockState(blockState) && !blockState.getFluidState().isEmpty()) {
                int fluidStateId = this.mapper.getIdForBlockState(
                        blockState.getFluidState().createLegacyBlock());
                if (this.idMappings[fluidStateId] == -1) {
                    this.addEntry(fluidStateId);
                }
            }

            this.blockBakeQueue.add(new BlockBake(blockId, blockState));
            return true;
        } catch (Throwable t) {
            rethrowFatal(t);
            if (blockState != null) {
                this.reportBakeFailure(blockState, t);
            } else {
                Logger.error("Resolving Voxy model state " + blockId
                        + "; this block renders as air at LOD range", t);
            }
            this.retireFailedBake(blockId);
            return false;
        }
    }

    private void retireFailedBake(int blockId) {
        // 某些异常发生在映射写入之后，不能把已成功烘焙的状态错误回退为空气。
        if (this.idMappings[blockId] == -1) {
            this.idMappings[blockId] = 0;
        }
        this.blockStatesInFlightLock.lock();
        try {
            this.blockStatesInFlight.remove(blockId);
        } finally {
            this.blockStatesInFlightLock.unlock();
        }
    }

    private static final ObjectSet<Block> LOGGED_BAKE_FAILURE = new ObjectOpenHashSet<>();

    private ModelEntry pendingEntry;
    private int pendingModelId = -1;
    private int pendingBiomeColourEntries = -1;
    private ModelBakeResultUpload pendingUpload;

    private void rollbackPendingBake() {
        if (this.pendingEntry != null) {
            this.modelTexture2id.removeInt(this.pendingEntry);
            this.fluidStateLUT[this.pendingModelId] = -1;
            // 清理 (modelId, state) 对，避免后续群系上传把颜色写到复用的模型 ID。
            while (this.modelsRequiringBiomeColours.size() > this.pendingBiomeColourEntries) {
                this.modelsRequiringBiomeColours.remove(this.modelsRequiringBiomeColours.size() - 1);
            }
            this.pendingEntry = null;
            this.pendingModelId = -1;
            this.pendingBiomeColourEntries = -1;
        }
        if (this.pendingUpload != null) {
            this.pendingUpload.free();
            this.pendingUpload = null;
        }
    }

    private void reportBakeFailure(BlockState state, Throwable t) {
        boolean first;
        synchronized (LOGGED_BAKE_FAILURE) {
            first = LOGGED_BAKE_FAILURE.add(state.getBlock());
        }
        if (first) {
            Logger.error("Model bake failed for " + state + "; this block renders as air at LOD range", t);
        }
    }

    private static void rethrowFatal(Throwable t) {
        if (t instanceof ThreadDeath death) {
            throw death;
        }
        // 恶意模型导致的栈溢出可以隔离到单个状态；OOM 等 VM 错误必须继续抛出。
        if (t instanceof VirtualMachineError fatal && !(fatal instanceof StackOverflowError)) {
            throw fatal;
        }
    }

    private boolean processModelResult() {
        var bake = this.blockBakeQueue.poll();
        if (bake == null) {
            return false;
        }
        // render-only 标志必须在 finally 清除，避免一次失败污染下一个模型。
        this.bakery2.beginRenderOnlyBake(bake.blockId);
        try {
            return this.processModelResult0(bake);
        } finally {
            this.bakery2.endRenderOnlyBake();
        }
    }

    private boolean processModelResult0(BlockBake bake) {
        ColourDepthTextureData[] textureData = new ColourDepthTextureData[6];

        int flags;
        try {
            flags = this.bakery2.renderToOutput(bake.blockId, bake.state, this.bakeScratchBuffer);
        } catch (Throwable t) {
            rethrowFatal(t);
            this.reportBakeFailure(bake.state, t);
            this.retireFailedBake(bake.blockId);
            return true;
        }


        // 从 native scratch buffer 拆出六个面的颜色和深度纹理。
        {
            long ptr = this.bakeScratchBuffer;
            final int FACE_SIZE = MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE;
            for (int face = 0; face < 6; face++) {
                long faceDataPtr = ptr + (FACE_SIZE * 4) * face * 2;
                int[] colour = new int[FACE_SIZE];
                int[] depth = new int[FACE_SIZE];

                for (int i = 0; i < FACE_SIZE; i++) {
                    long value = MemoryUtil.memGetLong(faceDataPtr + i * 8);
                    colour[i] = (int) value;
                    depth[i] = (int) (value >>> 32);
                }
                textureData[face] = new ColourDepthTextureData(colour, depth, MODEL_TEXTURE_SIZE, MODEL_TEXTURE_SIZE);
            }
        }


        boolean hasDarkenedTextures = (flags & 2) != 0;
        boolean isShaded = (flags & 1) != 0;
        RenderType layer = null;
        if ((flags & 4) != 0) {
            boolean anyTranslucent = false;
            for (var face : textureData) {
                anyTranslucent |= TextureUtils.hasTranslucentPixel(face);
                if (anyTranslucent) break;
            }
            if (anyTranslucent) {
                layer = RenderType.translucent();
            } else {
                boolean solid = true;
                for (var face : textureData) {
                    solid &= TextureUtils.isSolidWhereDrawn(face);
                    if (!solid) break;
                }
                layer = solid ? RenderType.solid() : RenderType.cutout();
            }
        }
        if (layer == null && (flags & 8) != 0) {
            layer = RenderType.cutout();
        }
        if (layer == null) {
            layer = RenderType.solid();
        }
        boolean centeredGroundCross = (flags & SoftwareModelTextureBakery.FLAG_CENTERED_GROUND_CROSS) != 0;
        boolean conservativeCulling = (flags & SoftwareModelTextureBakery.FLAG_CONSERVATIVE_CULLING) != 0;
        ModelBakeResultUpload bakeResult;
        try {
            bakeResult = this.processTextureBakeResult(
                    bake.blockId, bake.state, textureData, isShaded, hasDarkenedTextures, layer,
                    centeredGroundCross, conservativeCulling);
        } catch (Throwable t) {
            rethrowFatal(t);
            this.reportBakeFailure(bake.state, t);
            this.rollbackPendingBake();
            this.retireFailedBake(bake.blockId);
            return true;
        }
        if (bakeResult != null) {
            this.uploadResults.add(bakeResult);
        }
        return !this.blockBakeQueue.isEmpty();
    }

    private final ConcurrentLinkedDeque<Mapper.BiomeEntry> biomeQueue = new ConcurrentLinkedDeque<>();

    public void addBiome(Mapper.BiomeEntry biome) {
        this.biomeQueue.add(biome);
    }

    /** 在后台线程处理群系和模型烘焙队列，返回是否仍有待处理任务。 */
    public boolean processAllThings() {
        var biomeEntry = this.biomeQueue.poll();
        while (biomeEntry != null) {
            var biomeRegistry = Minecraft.getInstance().level.registryAccess().registryOrThrow(Registries.BIOME);
            var mcbiomeEntry = biomeRegistry.getOptional(ResourceLocation.parse(biomeEntry.biome));
            if (mcbiomeEntry.isEmpty()) {
                Logger.error("Could not find biome: " + biomeEntry.biome + " using default");
            }
            var res = this.addBiome0(biomeEntry.id, mcbiomeEntry.orElse(DEFAULT_BIOME));
            if (res != null) {
                this.uploadResults.add(res);
            }
            biomeEntry = this.biomeQueue.poll();
        }

        while (!Thread.currentThread().isInterrupted() && this.processModelResult());
        return !Thread.currentThread().isInterrupted()
                && (this.blockStatesInFlight.size() != 0
                || !this.blockBakeQueue.isEmpty()
                || !this.biomeQueue.isEmpty());
    }

    public void drainBlendPalette() {
        if (this.blendPalette.drainUploads(this.storage.modelColourBuffer)) {
            UploadStream.INSTANCE.commit();
        }
    }

    public void processUploads(long totalBudgetNanos) {
        var upload = this.uploadResults.poll();
        if (upload == null) {
            return;
        }
        long started = System.nanoTime();
        long budget = Math.max(0L, totalBudgetNanos);

        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        try {
            do {
                try {
                    upload.upload(this.storage);
                } finally {
                    upload.free();
                }
                // 预算耗尽后不再出队，避免丢任务和多余的回队操作。
            } while (System.nanoTime() - started < budget
                    && (upload = this.uploadResults.poll()) != null);
        } finally {
            UploadStream.INSTANCE.commit();
        }
    }

    private interface ResultUploader {
        void upload(ModelStore store);

        void free();
    }

    private static final class ModelBakeResultUpload implements ResultUploader {
        private final MemoryBuffer model = new MemoryBuffer(MODEL_SIZE).zero();
        private final MemoryBuffer texture = new MemoryBuffer(
                (2L * 3 * computeSizeWithMips(MODEL_TEXTURE_SIZE)) * 4);

        public int modelId = -1;

        public int biomeUploadIndex = -1;
        public @Nullable MemoryBuffer biomeUpload;

        public void upload(ModelStore store) {
            if (this.biomeUploadIndex != -1) {
                store.ensureBiomeColourCapacity(this.biomeUploadIndex + this.biomeUpload.size / 4);
            }
            this.upload(store.modelBuffer, store.modelColourBuffer, store.textures);
        }

        public void upload(GlBuffer modelBuffer, GlBuffer colourBuffer, GlTexture atlas) {
            this.model.cpyTo(UploadStream.INSTANCE.upload(modelBuffer, (long) this.modelId * MODEL_SIZE, MODEL_SIZE));
            if (this.biomeUploadIndex != -1) {
                this.biomeUpload.cpyTo(UploadStream.INSTANCE.upload(colourBuffer, (BiomeBlendPalette.NORMAL_COLOUR_BASE + this.biomeUploadIndex) * 4L, this.biomeUpload.size));
                this.biomeUploadIndex = -1;
                this.biomeUpload.free();
                this.biomeUpload = null;
            }

            int x = (this.modelId & 0xFF) * MODEL_TEXTURE_SIZE * 3;
            int y = ((this.modelId >> 8) & 0xFF) * MODEL_TEXTURE_SIZE * 2;

            long cAddr = this.texture.address;
            for (int lvl = 0; lvl < LAYERS; lvl++) {
                nglTextureSubImage2D(
                        atlas.id,
                        lvl,
                        x >> lvl,
                        y >> lvl,
                        (MODEL_TEXTURE_SIZE * 3) >> lvl,
                        (MODEL_TEXTURE_SIZE * 2) >> lvl,
                        GL_RGBA,
                        GL_UNSIGNED_BYTE,
                        cAddr);
                cAddr += (MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE * 3 * 2 * 4) >> (lvl << 1);
            }

            this.modelId = -1;
        }

        public void free() {
            this.model.free();
            this.texture.free();
            if (this.biomeUpload != null) {
                this.biomeUpload.free();
            }
        }
    }

    private static final java.util.Set<Object> LOGGED_SELF_CULL_PROBE_FAILURE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static boolean hasFullOcclusionShape(BlockState state) {
        try {
            return Block.isShapeFullBlock(state.getOcclusionShape(
                    net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isCreateTrack(BlockState state) {
        for (Class<?> type = state.getBlock().getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("com.simibubi.create.content.trains.track.TrackBlock")) {
                return true;
            }
        }
        return false;
    }

    private ModelBakeResultUpload processTextureBakeResult(int blockId, BlockState blockState,
                                                           ColourDepthTextureData[] textureData,
                                                           boolean isShaded, boolean darkenedTinting,
                                                           RenderType layer, boolean crossPlant,
                                                           boolean conservativeComplexModel) {
        if (this.idMappings[blockId] != -1) {
            throw new IllegalStateException("Block id already added: " + blockId + " for state: " + blockState);
        }

        this.blockStatesInFlightLock.lock();
        if (!this.blockStatesInFlight.contains(blockId)) {
            this.blockStatesInFlightLock.unlock();
            throw new IllegalStateException("processing a texture bake result but the block state was not in flight!!");
        }
        this.blockStatesInFlightLock.unlock();


        boolean isFluid = isFluidBlockState(blockState);
        int fluidKind = isFluid ? this.getOrCreateFluidKind(blockState.getFluidState()) : 0;
        boolean leafModel = isLeafBlockState(blockState);
        boolean balancedLeaf = leafModel
                && VoxyConfig.CONFIG.getLeafLodMode() == VoxyConfig.LeafLodMode.BALANCED;
        boolean completeComplexBlock = !conservativeComplexModel || hasFullOcclusionShape(blockState);

        int modelId = -1;


        int clientFluidStateId = -1;

        if ((!isFluid) && (!blockState.getFluidState().isEmpty())) {
            //Insert into the fluid LUT
            var fluidState = blockState.getFluidState().createLegacyBlock();

            int fluidStateId = this.mapper.getIdForBlockState(fluidState);

            clientFluidStateId = this.idMappings[fluidStateId];
            if (clientFluidStateId == -1) {
                throw new IllegalStateException("Block has a fluid state but fluid state is not already baked!!!");
            }
        }

        BlockState colourState = DomumOrnamentumCompat.getColourState(this.mapper, blockId, blockState);
        colourState = me.cortex.voxy.commonImpl.compat.CreateCopycatCompat.getColourState(this.mapper, blockId, colourState);
        colourState = me.cortex.voxy.commonImpl.compat.FramedBlocksCompat.getColourState(this.mapper, blockId, colourState);
        var colourProvider = colourState == null ? null : getColourProvider(colourState);

        boolean isBiomeColourDependent = false;
        if (colourProvider != null) {
            isBiomeColourDependent = isBiomeDependentColour(colourProvider, colourState);
            if (!isBiomeColourDependent) {
                var seasonalView = me.cortex.voxy.client.core.compat.eclipticseasons.SeasonalLod.view;
                isBiomeColourDependent = seasonalView != null && seasonalView.isSeasonalConstantTint(colourState, colourProvider);
            }
        }

        ModelEntry entry;
        {//Deduplicate same entries
            entry = new ModelEntry(textureData, clientFluidStateId, fluidKind, isBiomeColourDependent||colourProvider==null?-1:captureColourConstant(colourProvider, colourState, DEFAULT_BIOME)|0xFF000000,
                    me.cortex.voxy.commonImpl.compat.FramedBlocksCompat.isFramedState(blockState),
                    isCreateTrack(blockState),
                    conservativeComplexModel, completeComplexBlock);
            int possibleDuplicate = this.modelTexture2id.getInt(entry);
            if (possibleDuplicate != -1) {//Duplicate found
                this.idMappings[blockId] = possibleDuplicate;
                modelId = possibleDuplicate;
                if (fluidKind != 0) {
                    this.fluidKinds[modelId] = fluidKind;
                }
                //Remove from flight
                this.blockStatesInFlightLock.lock();
                if (!this.blockStatesInFlight.remove(blockId)) {
                    this.blockStatesInFlightLock.unlock();
                    throw new IllegalStateException();
                }
                this.blockStatesInFlightLock.unlock();
                return null;
            } else {//Not a duplicate so create a new entry
                modelId = this.modelTexture2id.size();
                this.modelTexture2id.put(entry, modelId);
                this.pendingEntry = entry;
                this.pendingModelId = modelId;
                this.pendingBiomeColourEntries = this.modelsRequiringBiomeColours.size();
            }
        }

        if (isFluid) {
            this.fluidStateLUT[modelId] = modelId;
            this.fluidKinds[modelId] = fluidKind;
        } else if (clientFluidStateId != -1) {
            this.fluidStateLUT[modelId] = clientFluidStateId;
        }


        int checkMode = layer==RenderType.solid()?TextureUtils.WRITE_CHECK_STENCIL:TextureUtils.WRITE_CHECK_ALPHA;




        ModelBakeResultUpload uploadResult = new ModelBakeResultUpload();
        this.pendingUpload = uploadResult;
        uploadResult.modelId = modelId;
        long uploadPtr = uploadResult.model.address;


        //If it contains fluid but isnt a fluid
        if ((!isFluid) && (!blockState.getFluidState().isEmpty()) && clientFluidStateId != -1) {

            //Or it with the fluid state biome dependency
            isBiomeColourDependent |= ModelQueries.isBiomeColoured(this.getModelMetadataFromClientId(clientFluidStateId));
        }




        var depths = computeModelDepth(textureData, checkMode, layer!=RenderType.solid()?TextureUtils.DEPTH_MODE_MIN:TextureUtils.DEPTH_MODE_AVG);

        if (crossPlant) {
            //Cross plants project onto all four side views and bake into a boxy shell; pull the side faces
            //to the cell centre so they render as two crossed mid planes. The 0.5 offset also clears both
            //occlusion flags via the thresholds below.
            for (int f = 2; f < 6; f++) {
                if (depths[f] > -0.1f) depths[f] = 0.5f;
            }
        }

        // is the same, (if yes then needs double sided quads)
        // another way to test it is if e.g. up and down havent got anything rendered but the sides do (e.g. all plants etc)
        boolean needsDoubleSidedQuads = (depths[0] < -0.1 && depths[1] < -0.1) || (depths[2] < -0.1 && depths[3] < -0.1) || (depths[4] < -0.1 && depths[5] < -0.1);


        boolean cullsSame = false;

        try {
            boolean allTrue = true;
            boolean allFalse = true;
            //Guestimation test for if the block culls itself
            for (var dir : Direction.values()) {
                if (blockState.skipRendering(blockState, dir)) {
                    allFalse = false;
                } else {
                    allTrue = false;
                }
            }

            if (allFalse == allTrue) {//If only some sides where self culled then abort
                cullsSame = false;
            }

            if (allTrue) {
                cullsSame = true;
            }
        } catch (Throwable e) {
            //skipRendering is a popular mixin target (e.g. culling mods poking sodium internals) and
            //a broken third party there must not kill the bake thread; worst case is redundant faces
            //between identical neighbors.
            cullsSame = false;
            if (LOGGED_SELF_CULL_PROBE_FAILURE.add(blockState.getBlock())) {
                Logger.error("skipRendering probe threw for " + blockState + ", assuming no self culling", e);
            }
        }
        if (balancedLeaf) {
            cullsSame = true;
        }
        if (conservativeComplexModel && !completeComplexBlock) {
            cullsSame = false;
        }


        //Each face gets 1 byte, with the top 2 bytes being for whatever
        long metadata = 0;
        metadata |= isBiomeColourDependent?1:0;
        metadata |= layer == RenderType.translucent()?2:0;
        metadata |= needsDoubleSidedQuads?4:0;
        metadata |= ((!isFluid) && !blockState.getFluidState().isEmpty())?8:0;//Has a fluid state accosiacted with it and is not itself a fluid
        metadata |= isFluid?16:0;//Is a fluid

        metadata |= cullsSame?32:0;

        boolean fullyOpaque = true;


        for (int face = 5; face != -1; face--) {//In reverse order to make indexing into the metadata long easier
            long faceUploadPtr = uploadPtr + 4L * face;//Each face gets 4 bytes worth of data
            metadata <<= 8;
            float offset = depths[face];
            if (offset < -0.1) {//Face is empty, so ignore
                metadata |= 0xFF;//Mark the face as non-existent
                //Set to -1 as safepoint
                MemoryUtil.memPutInt(faceUploadPtr, -1);

                fullyOpaque = false;
                continue;
            }
            var faceSize = TextureUtils.computeBounds(textureData[face], checkMode);
            int writeCount = TextureUtils.getWrittenPixelCount(textureData[face], checkMode);

            boolean faceCoversFullBlock = faceSize[0] == 0 && faceSize[2] == 0 &&
                    faceSize[1] == (MODEL_TEXTURE_SIZE-1) && faceSize[3] == (MODEL_TEXTURE_SIZE-1);
            if (conservativeComplexModel) {
                faceCoversFullBlock &= completeComplexBlock && offset <= (1.0f / 64.0f)
                        && writeCount == MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE;
            }


            metadata |= faceCoversFullBlock?2:0;

            boolean occludesFace = true;
            occludesFace &= layer != RenderType.translucent();//If its translucent, it doesnt occlude
            occludesFace &= !isFluid;

            occludesFace &= offset < 0.1;//If the face is rendered far away from the other face, then it doesnt occlude

            if (occludesFace) {
                occludesFace &= ((float)writeCount)/(MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE) > 0.9;// only occlude if the face covers more than 90% of the face
            }
            occludesFace &= faceCoversFullBlock;
            if (conservativeComplexModel) {
                occludesFace &= faceCoversFullBlock;
            }
            metadata |= occludesFace?1:0;
            fullyOpaque &= occludesFace;



            boolean canBeOccluded = true;
            canBeOccluded &= offset < 0.3;//If the face is rendered far away from the other face, then it cant be occluded
            if (conservativeComplexModel) {
                canBeOccluded &= faceCoversFullBlock;
            }

            metadata |= canBeOccluded?4:0;

            //Face uses its own lighting if its not flat against the adjacent block & isnt traslucent
            metadata |= (offset > 0.01 || layer == RenderType.translucent())?0b1000:0;



            if (MODEL_TEXTURE_SIZE-1 != 15) {
                //Scale face size from 0->this.modelTextureSize-1 to 0->15
                for (int i = 0; i < 4; i++) {
                    faceSize[i] = Math.round((((float) faceSize[i]) / (MODEL_TEXTURE_SIZE - 1)) * 15);
                }
            }

            int faceModelData = 0;
            faceModelData |= faceSize[0] | (faceSize[1]<<4) | (faceSize[2]<<8) | (faceSize[3]<<12);
            //Change the scale from 0->1 (ends inclusive)
            // this is cursed also warning stuff at 63 (i.e half a pixel from the end will be clamped to the end)
            int enc = Math.round(offset*64);
            faceModelData |= Math.min(enc,62)<<16;
            //Still have 11 bits free

            //Stuff like fences are solid, however they have extra side piece that mean it needs to have discard on
            int area = (faceSize[1]-faceSize[0]+1) * (faceSize[3]-faceSize[2]+1);
            boolean needsAlphaDiscard = ((float)writeCount)/area<0.9;//If the amount of area covered by written pixels is less than a threashold, disable discard as its not needed

            needsAlphaDiscard |= layer != RenderType.solid();
            needsAlphaDiscard &= layer != RenderType.translucent();//Translucent doesnt have alpha discard
            faceModelData |= needsAlphaDiscard?1<<22:0;

            faceModelData |= ((!faceCoversFullBlock)&&layer != RenderType.translucent())?1<<23:0;//Alpha discard override, translucency doesnt have alpha discard

            //Bits 24,25 are tint metadata
            if (colourProvider!=null) {//We have a colour provider
                int tintState = TextureUtils.computeFaceTint(textureData[face], checkMode);
                if (tintState == 2) {//Partial tint
                    faceModelData |= 1<<24;
                } else if (tintState == 3) {//Full tint
                    faceModelData |= 2<<24;
                }
            }

            MemoryUtil.memPutInt(faceUploadPtr, faceModelData);
        }

        metadata |= fullyOpaque?(1L<<(48+6)):0;

        boolean canBeCorrectlyRendered = true;//This represents if a model can be correctly (perfectly) represented
        // i.e. no gaps

        //block emission
        metadata |= ((long)getBlockLightEmission(blockState))<<(48+7);
        FluidState fluidState = blockState.getFluidState();
        int fluidHeight = isFluid
                ? Math.clamp(Math.round(fluidState.getOwnHeight() * 9.0f), 1, 9)
                : 0;
        metadata |= ((long) fluidHeight) << 59;

        this.metadataCache[modelId] = metadata;

        uploadPtr += 4*6;
        //Have 40 bytes free for remaining model data
        int modelFlags = 0;
        modelFlags |= colourProvider != null?1:0;
        modelFlags |= isBiomeColourDependent?2:0;//Basicly whether to use the next int as a colour or as a base index/id into a colour buffer for biome dependent colours
        modelFlags |= layer == RenderType.translucent()?4:0;//Is translucent


        modelFlags |= isShaded?8:0;//model has AO and shade
        // The dimension-wide fluid datum represents sea level and is only valid for water.
        // Applying it to lava raises coarse lava caps until the player reaches LOD 0.
        boolean usesFluidDatum = isFluid && blockState.getFluidState().is(FluidTags.WATER);
        modelFlags |= usesFluidDatum ? 16 : 0;
        modelFlags |= balancedLeaf ? 32 : 0;
        // Lava keeps a conservative handoff inside the circular boundary. Its vanilla
        // translucent surface does not provide a reliable depth owner for a dithered overlap.
        boolean lava = isFluid && blockState.getFluidState().is(FluidTags.LAVA);
        modelFlags |= lava ? 64 : 0;
        // All leaf quality modes retain per-pixel depth/stencil ownership instead of the circular
        // geometry clip. This avoids dropping the LOD canopy before vanilla cutout pixels exist.
        modelFlags |= leafModel ? 128 : 0;
        modelFlags |= fluidHeight << 8;
        modelFlags |= isFluid ? 1 << 12 : 0;
        modelFlags |= entry.framedBlocks ? 1 << 13 : 0;
        modelFlags |= entry.createTrack ? 1 << 14 : 0;

        //modelFlags |= blockRenderLayer == RenderLayer.getSolid()?0:1;// should discard alpha
        MemoryUtil.memPutInt(uploadPtr, modelFlags); uploadPtr += 4;


        //Temporary override to always be non biome specific
        if (colourProvider == null) {
            MemoryUtil.memPutInt(uploadPtr, -1);//Set the default to nothing so that its faster on the gpu
        } else if (!isBiomeColourDependent) {
            MemoryUtil.memPutInt(uploadPtr, entry.tintingColour);
        } else {
            //Populate the list of biomes for the model state
            int biomeIndex = this.modelsRequiringBiomeColours.size() * this.biomes.size();
            MemoryUtil.memPutInt(uploadPtr, biomeIndex);
            this.modelsRequiringBiomeColours.add(new Pair<>(modelId, colourState));
            if (!this.biomes.isEmpty()) {
                uploadResult.biomeUploadIndex = biomeIndex;
                long clrUploadPtr = (uploadResult.biomeUpload = new MemoryBuffer(4L * this.biomes.size())).address;
                for (var biome : this.biomes) {
                    MemoryUtil.memPutInt(clrUploadPtr, captureColourConstant(colourProvider, colourState, biome) | 0xFF000000); clrUploadPtr += 4;
                }
                this.blendPalette.mirrorRow(modelId, biomeIndex,
                        uploadResult.biomeUpload.address, this.biomes.size());
            }
        }
        uploadPtr += 4;

        //have 32 bytes of free space after here

        //install the custom mapping id if it exists
        if (this.customBlockStateIdMapping != null && this.customBlockStateIdMapping.containsKey(blockState)) {
            MemoryUtil.memPutInt(uploadPtr, this.customBlockStateIdMapping.getInt(blockState));
        } else {
            MemoryUtil.memPutInt(uploadPtr, 0);
        } uploadPtr += 4;


        //Note: if the layer isSolid then need to fill all the points in the texture where alpha == 0 with the average colour
        // of the surrounding blocks but only within the computed face size bounds



        MipGen.putTextures(darkenedTinting, textureData, uploadResult.texture);


        //Set the mapping at the very end
        this.idMappings[blockId] = modelId;

        this.blockStatesInFlightLock.lock();
        if (!this.blockStatesInFlight.remove(blockId)) {
            this.blockStatesInFlightLock.unlock();
            throw new IllegalStateException("processing a texture bake result but the block state was not in flight!!");
        }
        this.blockStatesInFlightLock.unlock();

        this.pendingEntry = null;
        this.pendingModelId = -1;
        this.pendingBiomeColourEntries = -1;
        this.pendingUpload = null;
        return uploadResult;
    }

    private static int getBlockLightEmission(BlockState state) {
        BlockGetter blockGetter = new BlockGetter() {
            @Override
            public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }
        };
        boolean isEmissive = state.emissiveRendering(blockGetter, BlockPos.ZERO);
        if (isEmissive) {
            return 15;//full bright
        }
        return Math.clamp(state.getLightEmission(blockGetter, BlockPos.ZERO),0,15);
    }

    private static final class BiomeUploadResult implements ResultUploader {
        private final MemoryBuffer biomeColourBuffer;
        private final MemoryBuffer modelBiomeIndexPairs;
        private BiomeUploadResult(int biomes, int models) {
            this.biomeColourBuffer = new MemoryBuffer(biomes*models*4);
            this.modelBiomeIndexPairs = new MemoryBuffer(models*8);
        }

        public void upload(ModelStore store) {
            store.ensureBiomeColourCapacity(this.biomeColourBuffer.size / 4);
            this.upload(store.modelBuffer, store.modelColourBuffer);
        }

        public void upload(GlBuffer modelBuffer, GlBuffer modelColourBuffer) {
            this.biomeColourBuffer.cpyTo(UploadStream.INSTANCE.upload(modelColourBuffer, BiomeBlendPalette.NORMAL_COLOUR_BASE * 4L, this.biomeColourBuffer.size));

            long ptr = this.modelBiomeIndexPairs.address;
            for (long offset = 0; offset < this.modelBiomeIndexPairs.size; offset += 8) {
                long v = MemoryUtil.memGetLong(ptr);ptr += 8;
                MemoryUtil.memPutInt(UploadStream.INSTANCE.upload(modelBuffer, (MODEL_SIZE*(v&((1L<<32)-1)))+ 4*6 + 4, 4), (int) (v>>>32));
            }

            this.biomeColourBuffer.free();
            this.modelBiomeIndexPairs.free();
        }

        public void free() {
            if (!this.biomeColourBuffer.isFreed()) {
                this.biomeColourBuffer.free();
                this.modelBiomeIndexPairs.free();
            }
        }
    }

    private BiomeUploadResult addBiome0(int id, Biome biome) {
        if (biome == null) {
            throw new IllegalStateException("Null biome");
        }
        for (int i = this.biomes.size(); i <= id; i++) {
            this.biomes.add(null);
        }
        var oldBiome = this.biomes.set(id, biome);

        if (oldBiome != null && oldBiome != biome) {
            throw new IllegalStateException("Biome was put in an id that was not null");
        }
        if (oldBiome == biome) {
            Logger.error("Biome added was a duplicate: " + id);
            return null;
        }

        if (this.modelsRequiringBiomeColours.isEmpty()) return null;

        var result = new BiomeUploadResult(this.biomes.size(), this.modelsRequiringBiomeColours.size());

        int i = 0;
        long modelUpPtr = result.modelBiomeIndexPairs.address;
        for (var entry : this.modelsRequiringBiomeColours) {
            var colourProvider = getColourProvider(entry.right());
            if (colourProvider == null) {
                throw new IllegalStateException();
            }
            //Populate the list of biomes for the model state
            int biomeIndex = (i++) * this.biomes.size();
            MemoryUtil.memPutLong(modelUpPtr, Integer.toUnsignedLong(entry.left())|(Integer.toUnsignedLong(biomeIndex)<<32));modelUpPtr+=8;
            long clrUploadPtr = result.biomeColourBuffer.address + biomeIndex * 4L;
            for (var biomeE : this.biomes) {
                if (biomeE == null) {
                    continue;//If null, ignore
                }
                MemoryUtil.memPutInt(clrUploadPtr, captureColourConstant(colourProvider, entry.right(), biomeE)|0xFF000000); clrUploadPtr += 4;
            }
        }

        this.blendPalette.mirrorRebuild(result.modelBiomeIndexPairs.address,
                this.modelsRequiringBiomeColours.size(), result.biomeColourBuffer.address,
                (int) (result.biomeColourBuffer.size / 4), this.biomes.size());

        return result;
    }

    //Instance-scoped so the probe can reuse DEFAULT_BIOME (both call sites are instance methods)
    private BlockColor getColourProvider(BlockState blockState) {
        if (isLumiseneFluidBlockState(blockState)) {
            return null;
        }
        Block block = blockState.getBlock();
        BlockState defaultState = block.defaultBlockState();
        var blockColors = Minecraft.getInstance().getBlockColors();
        if (isFluidBlockState(blockState) || isFluidBlockState(defaultState)) {
            return (state, world, pos, tintIndex) -> blockColors.getColor(state, world, pos, tintIndex);
        }
        BlockColor provider = (state, world, pos, tintIndex) -> blockColors.getColor(state, world, pos, tintIndex);
        int color;
        try {
            color = captureColourConstant(provider, defaultState, DEFAULT_BIOME);
        } catch (Exception e) {
            return null;
        }
        if (color != 0 && color != -1) {
            return provider;
        }
        return null;
    }

    public static boolean isLeafBlockState(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock;
    }

    public static boolean isFluidBlockState(BlockState state) {
        if (state.getBlock() instanceof LiquidBlock) {
            return true;
        }

        FluidState fluidState = state.getFluidState();
        return !fluidState.isEmpty() && fluidState.createLegacyBlock().getBlock() == state.getBlock();
    }

    public static boolean isLumiseneFluidBlockState(BlockState state) {
        FluidState fluidState = state.getFluidState();
        if (fluidState.isEmpty()) {
            return false;
        }
        var id = BuiltInRegistries.FLUID.getKey(fluidState.getType());
        return id != null && id.getNamespace().equals("supplementaries") && id.getPath().equals("lumisene");
    }

    // if it is, need to add it to a list and mark it as biome colour dependent or something then the shader
    // will either use the uint as an index or a direct colour multiplier
    private static int captureColourConstant(BlockColor colorProvider, BlockState state, Biome biome) {
        var getter = new BlockAndTintGetter() {

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                return colorResolver.getColor(biome, 0, 0);
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }

            @Override
            public float getShade(Direction direction, boolean bl) {
                return Minecraft.getInstance().level.getShade(direction, bl);
            }
        };
        int c = colorProvider.getColor(state, getter, BlockPos.ZERO, 0);
        if (c!=-1) return c;
        return colorProvider.getColor(state, getter, BlockPos.ZERO, 1);
    }

    private static boolean isBiomeDependentColour(BlockColor colorProvider, BlockState state) {
        boolean[] biomeDependent = new boolean[1];
        var getter = new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                biomeDependent[0] = true;
                return 0;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }
        };
        colorProvider.getColor(state, getter, BlockPos.ZERO, 0);
        colorProvider.getColor(state, getter, BlockPos.ZERO, 1);
        return biomeDependent[0];
    }

    private static float[] computeModelDepth(ColourDepthTextureData[] textures, int checkMode) {
        return computeModelDepth(textures, checkMode, TextureUtils.DEPTH_MODE_AVG);
    }

    private static float[] computeModelDepth(ColourDepthTextureData[] textures, int checkMode, int computeMode) {
        float[] res = new float[6];
        for (var dir : Direction.values()) {
            var data = textures[dir.get3DDataValue()];
            float fd = TextureUtils.computeDepth(data, computeMode, checkMode);//Compute the min float depth, smaller means closer to the camera, range 0-1
            //If fd is -1, it means that there was nothing rendered on that face and it should be discarded
            if (fd < -0.1) {
                res[dir.ordinal()] = -1;
            } else {
                res[dir.ordinal()] = fd;//((float) depth)/MODEL_TEXTURE_SIZE;
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
        }
        return map;
    }

    public boolean hasModelForBlockId(int blockId) {
        return this.idMappings[blockId] != -1;
    }

    public int getFluidClientStateId(int clientBlockStateId) {
        int map = this.fluidStateLUT[clientBlockStateId];
        if (map == -1) {
            throw new IdNotYetComputedException(clientBlockStateId, false);
        }
        return map;
    }

    private synchronized int getOrCreateFluidKind(FluidState state) {
        Fluid type = state.getType();
        Integer cached = this.fluidKindByType.get(type);
        if (cached != null) {
            return cached;
        }

        for (int i = 0; i < this.fluidKindRepresentatives.size(); i++) {
            Fluid representative = this.fluidKindRepresentatives.get(i);
            if (type.isSame(representative) || representative.isSame(type)) {
                int kind = i + 1;
                this.fluidKindByType.put(type, kind);
                return kind;
            }
        }

        this.fluidKindRepresentatives.add(type);
        int kind = this.fluidKindRepresentatives.size();
        this.fluidKindByType.put(type, kind);
        return kind;
    }

    public int getFluidKind(int clientId) {
        return this.fluidKinds[clientId];
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
        size += this.blockBakeQueue.size();
        return size;
    }


    private static int computeSizeWithMips(int size) {
        int total = 0;
        for (;size!=0;size>>=1) total += size*size;
        return total;
    }
}
