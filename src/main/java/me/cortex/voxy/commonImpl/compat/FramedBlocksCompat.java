package me.cortex.voxy.commonImpl.compat;

import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Preserves FramedBlocks' block-entity-backed appearance in Voxy's state-only voxel stream.
 *
 * <p>FramedBlocks wrapper models require their block entity {@link ModelData}; the ordinary block
 * state does not contain the camo(s), collapsible offsets, flower-pot contents, or reinforcement.
 * Capture the block entity's update tag as a persistent Mapper variant and recreate a temporary
 * block entity when that variant is baked. No FramedBlocks classes are linked directly, keeping
 * the integration genuinely optional.</p>
 */
public final class FramedBlocksCompat {
    public static final String DISGUISE_TABLE = "disguise_framedblocks";
    public static final String VARIANT_TYPE = "framedblocks";

    private static final boolean LOADED = ModList.get().isLoaded(VARIANT_TYPE);
    private static final String PACKAGE_PREFIX = "xfacthd.framedblocks.";
    private static final Predicate<BlockState> FRAMED_STATE_PREDICATE = FramedBlocksCompat::isFramedState;
    private static final ThreadLocal<SectionMappings> SECTION_MAPPINGS =
            ThreadLocal.withInitial(SectionMappings::new);
    private static final Map<Mapper, Map<Integer, CompoundTag>> DESCRIPTORS = new ConcurrentHashMap<>();
    private static final Map<Mapper, Map<Integer, DomumOrnamentumCompat.BakePlan>> BAKE_PLANS =
            new ConcurrentHashMap<>();

    private static final ClassValue<Optional<Method>> MODEL_DATA_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getModelData", boolean.class));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static volatile ModelProperty<Object> framedDataProperty;
    private static volatile Method getCamoContent;
    private static volatile Method getAppearanceState;
    private static volatile boolean colourAccessResolved;

    private FramedBlocksCompat() {
    }

    public static boolean isFramedState(BlockState state) {
        if (!LOADED || state == null) return false;
        var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && VARIANT_TYPE.equals(id.getNamespace());
    }

    public static void beginSection(Mapper mapper, SectionStorage storage, LevelChunk chunk,
                                    LevelChunkSection section, int sectionX, int sectionY, int sectionZ) {
        if (!LOADED) return;
        SectionMappings mappings = SECTION_MAPPINGS.get();
        mappings.reset();
        if (mapper == null || section == null || !section.maybeHas(FRAMED_STATE_PREDICATE)) return;

        if (chunk == null || chunk.getBlockEntities().isEmpty()) {
            int restored = DisguiseStore.load(storage, DISGUISE_TABLE, sectionX, sectionY, sectionZ,
                    mappings::put);
            mappings.active = restored != 0;
            return;
        }

        int minY = sectionY << 4;
        int maxY = minY + 15;
        try {
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (!blockEntity.getClass().getName().startsWith(PACKAGE_PREFIX)) continue;
                BlockPos pos = blockEntity.getBlockPos();
                if (pos.getY() < minY || pos.getY() > maxY) continue;

                try {
                    BlockState state = section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                    if (!isFramedState(state)) continue;
                    var level = blockEntity.getLevel();
                    if (level == null) continue;

                    CompoundTag updateTag = blockEntity.getUpdateTag(level.registryAccess()).copy();
                    // Position and type are supplied by the variant's BlockState when reconstructed.
                    // Keeping them in the key would create one model variant per world coordinate.
                    updateTag.remove("x");
                    updateTag.remove("y");
                    updateTag.remove("z");
                    updateTag.remove("id");
                    CompoundTag descriptor = new CompoundTag();
                    descriptor.put("block_entity", updateTag);
                    String key = NbtVariantKey.of(updateTag);

                    int mappedId = mapper.getIdForBlockStateVariant(state, VARIANT_TYPE, key, descriptor);
                    descriptorsFor(mapper).putIfAbsent(mappedId, descriptor.copy());
                    mappings.put((pos.getX() & 15) | ((pos.getZ() & 15) << 4) | ((pos.getY() & 15) << 8), mappedId);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        mappings.active = mappings.touchedCount != 0;
        if (storage != null) {
            if (!mappings.active) {
                DisguiseStore.clear(storage, DISGUISE_TABLE, sectionX, sectionY, sectionZ);
            } else {
                int[] packed = new int[mappings.touchedCount * 2];
                for (int i = 0; i < mappings.touchedCount; i++) {
                    int index = mappings.touched[i];
                    packed[i * 2] = index;
                    packed[i * 2 + 1] = mappings.ids[index];
                }
                DisguiseStore.save(storage, DISGUISE_TABLE, sectionX, sectionY, sectionZ,
                        packed, mappings.touchedCount);
            }
        }
    }

    public static void endSection() {
        if (LOADED) SECTION_MAPPINGS.get().active = false;
    }

    public static int[] activeSectionIds() {
        if (!LOADED) return null;
        SectionMappings mappings = SECTION_MAPPINGS.get();
        return mappings.active ? mappings.ids : null;
    }

    public static void restoreVariant(Mapper mapper, int blockId, BlockState state,
                                      String variantType, CompoundTag data) {
        if (!LOADED || mapper == null || !VARIANT_TYPE.equals(variantType)
                || !isFramedState(state) || data == null || !data.contains("block_entity")) return;
        descriptorsFor(mapper).putIfAbsent(blockId, data.copy());
    }

    public static DomumOrnamentumCompat.BakePlan getBakePlan(Mapper mapper, int blockId, BlockState state) {
        CompoundTag descriptor = descriptorFor(mapper, blockId);
        if (descriptor == null || !isFramedState(state)) return DomumOrnamentumCompat.BakePlan.empty();
        Map<Integer, DomumOrnamentumCompat.BakePlan> plans =
                BAKE_PLANS.computeIfAbsent(mapper, ignored -> new ConcurrentHashMap<>());
        DomumOrnamentumCompat.BakePlan cached = plans.get(blockId);
        if (cached != null) return cached;
        ModelData data = recreateModelData(state, descriptor);
        if (data == null || data == ModelData.EMPTY) return DomumOrnamentumCompat.BakePlan.empty();
        BlockState colourState = appearanceState(data);
        // The wrapper's render types are camo-dependent, so ask it for its complete declared set.
        DomumOrnamentumCompat.BakePlan plan = new DomumOrnamentumCompat.BakePlan(data, null, colourState,
                -1, false, true, false);
        DomumOrnamentumCompat.BakePlan prior = plans.putIfAbsent(blockId, plan);
        return prior == null ? plan : prior;
    }

    public static BlockState getColourState(Mapper mapper, int blockId, BlockState fallback) {
        DomumOrnamentumCompat.BakePlan plan = getBakePlan(mapper, blockId, fallback);
        BlockState appearance = plan.colourState();
        return appearance == null || appearance.isAir() ? fallback : appearance;
    }

    public static void closeMapper(Mapper mapper) {
        if (LOADED && mapper != null) {
            DESCRIPTORS.remove(mapper);
            BAKE_PLANS.remove(mapper);
        }
    }

    private static ModelData recreateModelData(BlockState state, CompoundTag descriptor) {
        try {
            var level = Minecraft.getInstance().level;
            if (level == null || !(state.getBlock() instanceof EntityBlock entityBlock)) return null;
            BlockEntity blockEntity = entityBlock.newBlockEntity(BlockPos.ZERO, state);
            if (blockEntity == null || !blockEntity.getClass().getName().startsWith(PACKAGE_PREFIX)) return null;
            blockEntity.handleUpdateTag(descriptor.getCompound("block_entity").copy(), level.registryAccess());
            blockEntity.setLevel(level);
            Method method = MODEL_DATA_METHODS.get(blockEntity.getClass()).orElse(null);
            Object result = method == null ? blockEntity.getModelData() : method.invoke(blockEntity, false);
            return result instanceof ModelData modelData ? modelData : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static BlockState appearanceState(ModelData data) {
        if (data == null) return null;
        resolveColourAccess();
        if (framedDataProperty == null || getCamoContent == null || getAppearanceState == null) return null;
        try {
            Object framedData = data.get(framedDataProperty);
            if (framedData == null) return null;
            Object camo = getCamoContent.invoke(framedData);
            Object state = getAppearanceState.invoke(camo);
            return state instanceof BlockState blockState ? blockState : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static synchronized void resolveColourAccess() {
        if (colourAccessResolved) return;
        try {
            Class<?> dataClass = Class.forName("xfacthd.framedblocks.api.model.data.FramedBlockData");
            framedDataProperty = (ModelProperty<Object>) dataClass.getField("PROPERTY").get(null);
            getCamoContent = dataClass.getMethod("getCamoContent");
            Class<?> camoClass = Class.forName("xfacthd.framedblocks.api.camo.CamoContent");
            getAppearanceState = camoClass.getMethod("getAppearanceState");
        } catch (Throwable ignored) {
        }
        colourAccessResolved = true;
    }



    private static CompoundTag descriptorFor(Mapper mapper, int blockId) {
        if (!LOADED || mapper == null) return null;
        Map<Integer, CompoundTag> descriptors = DESCRIPTORS.get(mapper);
        return descriptors == null ? null : descriptors.get(blockId);
    }

    private static Map<Integer, CompoundTag> descriptorsFor(Mapper mapper) {
        return DESCRIPTORS.computeIfAbsent(mapper, ignored -> new ConcurrentHashMap<>());
    }

    private static final class SectionMappings {
        private final int[] ids = new int[4096];
        private final int[] touched = new int[4096];
        private int touchedCount;
        private boolean active;

        void reset() {
            for (int i = 0; i < touchedCount; i++) ids[touched[i]] = 0;
            touchedCount = 0;
            active = false;
        }

        void put(int index, int id) {
            if (ids[index] == 0) touched[touchedCount++] = index;
            ids[index] = id;
        }
    }
}
