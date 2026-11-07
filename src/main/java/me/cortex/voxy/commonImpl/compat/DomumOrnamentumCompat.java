package me.cortex.voxy.commonImpl.compat;

import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.config.section.SectionStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class DomumOrnamentumCompat {
    public static final String DISGUISE_TABLE = "disguise_domum";
    public static final String VARIANT_TYPE = "domum_ornamentum";
    private static final CapturedBlock[] NO_BLOCK_ENTITIES = new CapturedBlock[0];

    private static final boolean LOADED = ModList.get().isLoaded(VARIANT_TYPE);
    private static final String PACKAGE_PREFIX = "com.ldtteam.domumornamentum";
    private static final String MODEL_PROPERTIES = PACKAGE_PREFIX + ".client.model.properties.ModProperties";
    private static final String TEXTURE_DATA_CLASS = PACKAGE_PREFIX + ".client.model.data.MaterialTextureData";

    private static final Predicate<BlockState> DOMUM_STATE_PREDICATE = DomumOrnamentumCompat::isDomumState;
    private static final ThreadLocal<SectionMappings> SECTION_MAPPINGS =
            ThreadLocal.withInitial(SectionMappings::new);
    private static final Map<Mapper, Map<Integer, BakePlan>> BAKE_PLANS = new ConcurrentHashMap<>();
    private static final Map<String, VariantDescriptor> DESCRIPTORS = new ConcurrentHashMap<>();

    //Walk the superclass chain rather than testing the concrete class alone: an addon subclassing a Domum
    //block entity lands outside the package but still carries the material data we are after.
    private static final ClassValue<Boolean> DOMUM_BLOCK_ENTITIES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                if (current.getName().startsWith(PACKAGE_PREFIX)) {
                    return true;
                }
            }
            return false;
        }
    };

    private static final ClassValue<Optional<Method>> TEXTURE_DATA_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getTextureData"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static final ClassValue<Optional<Method>> SERIALIZE_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("serializeNBT"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static final ClassValue<Optional<Method>> TEXTURED_COMPONENT_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getTexturedComponents"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static final ClassValue<Optional<Method>> MAIN_COMPONENT_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getMainComponent"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static final ClassValue<Optional<Method>> COMPONENT_ID_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getId"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static volatile ModelProperty<?> materialTextureProperty;
    private static volatile boolean materialTexturePropertyMissing;
    private static volatile Method textureDataDeserializer;

    private DomumOrnamentumCompat() {
    }

    public interface SectionListener {
        void changed(SectionStorage storage, int sectionX, int sectionY, int sectionZ);
    }

    public static volatile SectionListener sectionListener;

    public record BakePlan(ModelData modelData, BlockState modelState, BlockState colourState,
                           int fallbackTintAbgr, boolean forceTint, boolean independentModel,
                           boolean detailedMesh) {
        private static final BakePlan EMPTY =
                new BakePlan(ModelData.EMPTY, null, null, -1, false, false, false);

        public static BakePlan empty() {
            return EMPTY;
        }

        public boolean isEmpty() {
            return this == EMPTY;
        }
    }

    private record VariantDescriptor(String key, CompoundTag data) {
    }

    public record CapturedBlock(BlockPos pos, String materialKey, CompoundTag materialData) {}

    public static boolean isLoaded() {
        return LOADED;
    }

    public static boolean isDomumState(BlockState state) {
        if (!LOADED || state == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && VARIANT_TYPE.equals(id.getNamespace());
    }

    public static final class SectionBlockEntities {
        private static final SectionBlockEntities EMPTY = new SectionBlockEntities(Map.of());
        private final Map<Integer, CapturedBlock[]> sections;

        private SectionBlockEntities(Map<Integer, CapturedBlock[]> sections) {
            this.sections = sections;
        }

        public CapturedBlock[] forSection(int sectionY) {
            return this.sections.getOrDefault(sectionY, NO_BLOCK_ENTITIES);
        }
    }

    /** 在调用线程冻结材质；后台只读取私有 NBT 和位置。 */
    public static SectionBlockEntities captureBlockEntitiesBySection(LevelChunk chunk) {
        if (!LOADED || chunk == null || chunk.getBlockEntities().isEmpty()) {
            return SectionBlockEntities.EMPTY;
        }

        Map<Integer, ArrayList<CapturedBlock>> buckets = null;
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity == null || !DOMUM_BLOCK_ENTITIES.get(blockEntity.getClass())) {
                continue;
            }
            CapturedBlock captured = capture(blockEntity);
            if (captured == null) continue;
            if (buckets == null) {
                buckets = new HashMap<>();
            }
            buckets.computeIfAbsent(captured.pos().getY() >> 4, ignored -> new ArrayList<>())
                    .add(captured);
        }
        if (buckets == null) {
            return SectionBlockEntities.EMPTY;
        }

        Map<Integer, CapturedBlock[]> sections = new HashMap<>(buckets.size());
        buckets.forEach((sectionY, entities) ->
                sections.put(sectionY, entities.toArray(CapturedBlock[]::new)));
        return new SectionBlockEntities(Map.copyOf(sections));
    }

    public static CapturedBlock[] captureBlockEntities(LevelChunk chunk, LevelChunkSection section) {
        if (!LOADED || chunk == null || section == null
                || !section.maybeHas(DOMUM_STATE_PREDICATE)
                || chunk.getBlockEntities().isEmpty()) {
            return NO_BLOCK_ENTITIES;
        }

        ArrayList<CapturedBlock> result = null;
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity != null && DOMUM_BLOCK_ENTITIES.get(blockEntity.getClass())) {
                CapturedBlock captured = capture(blockEntity);
                if (captured == null) continue;
                if (result == null) {
                    result = new ArrayList<>();
                }
                result.add(captured);
            }
        }
        return result == null ? NO_BLOCK_ENTITIES : result.toArray(CapturedBlock[]::new);
    }

    private static CapturedBlock capture(BlockEntity blockEntity) {
        try {
            Object textureData = extractTextureData(blockEntity);
            if (textureData == null) textureData = extractTextureData(blockEntity.getModelData());
            VariantDescriptor descriptor = textureData == null ? null : createDescriptor(textureData);
            return descriptor == null ? null : new CapturedBlock(blockEntity.getBlockPos().immutable(),
                    descriptor.key(), descriptor.data().copy());
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void beginSection(Mapper mapper, SectionStorage storage, CapturedBlock[] blockEntities,
                                    LevelChunkSection section, int sectionX, int sectionY, int sectionZ) {
        if (!LOADED) {
            return;
        }
        SectionMappings mappings = SECTION_MAPPINGS.get();
        mappings.reset();
        if (mapper == null || section == null) {
            return;
        }
        if (!section.maybeHas(DOMUM_STATE_PREDICATE)) {
            if (DisguiseStore.clearIfPresent(storage, DISGUISE_TABLE, sectionX, sectionY, sectionZ)) {
                SectionListener listener = sectionListener;
                if (listener != null) listener.changed(storage, sectionX, sectionY, sectionZ);
            }
            return;
        }
        if (blockEntities == null || blockEntities.length == 0) {
            // 调色板可能保留已删除状态；只核对历史记录中的坐标。
            int restored = DisguiseStore.load(storage, DISGUISE_TABLE, sectionX, sectionY, sectionZ,
                    (index, id) -> {
                        if (isDomumState(section.getBlockState(index & 15, index >>> 8, (index >>> 4) & 15))) {
                            mappings.put(index, id);
                        }
                    });
            mappings.active = mappings.touchedCount != 0;
            if (restored != mappings.touchedCount) {
                storeMappings(storage, mappings, sectionX, sectionY, sectionZ);
            }
            return;
        }

        int minY = sectionY << 4;
        int maxY = minY + 15;
        Map<Integer, BakePlan> plans = null;

        for (CapturedBlock captured : blockEntities) {
            BlockPos pos = captured.pos();
            if (pos.getY() < minY || pos.getY() > maxY) {
                continue;
            }

            try {
                VariantDescriptor descriptor = new VariantDescriptor(captured.materialKey(), captured.materialData());

                int lx = pos.getX() & 15;
                int ly = pos.getY() & 15;
                int lz = pos.getZ() & 15;
                BlockState state = section.getBlockState(lx, ly, lz);
                if (!isDomumState(state)) {
                    continue;
                }

                int mappedId = mapper.getIdForBlockStateVariant(
                        state, VARIANT_TYPE, descriptor.key(), descriptor.data());
                if (plans == null) {
                    plans = plansFor(mapper);
                }
                if (plans.get(mappedId) == null) {
                    Object textureData = deserializeTextureData(descriptor.data());
                    if (textureData == null) continue;
                    ModelData resolvedModelData = createModelData(textureData);
                    plans.putIfAbsent(mappedId,
                            createBakePlan(state, resolvedModelData, descriptor, textureData));
                }

                mappings.put(lx | (lz << 4) | (ly << 8), mappedId);
            } catch (Throwable ignored) {
            }
        }
        mappings.active = mappings.touchedCount != 0;
        storeMappings(storage, mappings, sectionX, sectionY, sectionZ);
    }

    private static void storeMappings(SectionStorage storage, SectionMappings mappings,
                                      int sectionX, int sectionY, int sectionZ) {
        if (storage != null) {
            if (mappings.touchedCount == 0) {
                DisguiseStore.clear(storage, DISGUISE_TABLE, sectionX, sectionY, sectionZ);
            } else {
                int[] packed = new int[mappings.touchedCount * 2];
                for (int i = 0; i < mappings.touchedCount; i++) {
                    int index = Short.toUnsignedInt(mappings.touched[i]);
                    packed[i * 2] = index;
                    packed[i * 2 + 1] = mappings.ids[index];
                }
                DisguiseStore.save(storage, DISGUISE_TABLE, sectionX, sectionY, sectionZ,
                        packed, mappings.touchedCount);
            }
            SectionListener listener = sectionListener;
            if (listener != null) listener.changed(storage, sectionX, sectionY, sectionZ);
        }
    }

    public static void endSection() {
        if (LOADED) SECTION_MAPPINGS.get().active = false;
    }

    public static boolean hasSectionMappings() {
        return LOADED && SECTION_MAPPINGS.get().active;
    }

    public static int mapBlockId(Mapper mapper, BlockState state, int baseBlockId, int localIndex) {
        if (!LOADED || localIndex < 0 || localIndex >= 4096) {
            return baseBlockId;
        }
        SectionMappings mappings = SECTION_MAPPINGS.get();
        if (!mappings.active) {
            return baseBlockId;
        }
        int mappedId = mappings.ids[localIndex];
        return mappedId == 0 ? baseBlockId : mappedId;
    }

    public static BakePlan getBakePlan(Mapper mapper, int blockId) {
        if (!LOADED || mapper == null) {
            return BakePlan.EMPTY;
        }
        Map<Integer, BakePlan> plans = BAKE_PLANS.get(mapper);
        return plans == null ? BakePlan.EMPTY : plans.getOrDefault(blockId, BakePlan.EMPTY);
    }

    public static BlockState getColourState(Mapper mapper, int blockId, BlockState fallback) {
        BakePlan plan = getBakePlan(mapper, blockId);
        return plan.forceTint() ? null : plan.colourState() == null ? fallback : plan.colourState();
    }

    public static void restoreVariant(Mapper mapper, int blockId, BlockState state, String variantType, CompoundTag data) {
        if (!LOADED || mapper == null || !VARIANT_TYPE.equals(variantType) || data == null || data.isEmpty()) {
            return;
        }
        try {
            Object textureData = deserializeTextureData(data);
            if (textureData == null) {
                return;
            }
            VariantDescriptor descriptor = createDescriptor(textureData);
            if (descriptor == null) {
                return;
            }
            ModelData modelData = createModelData(textureData);
            plansFor(mapper).putIfAbsent(blockId,
                    createBakePlan(state, modelData, descriptor, textureData));
        } catch (Throwable ignored) {
        }
    }

    private static BakePlan createBakePlan(BlockState state, ModelData modelData,
                                           VariantDescriptor descriptor, Object textureData) {
        ModelData resolved = modelData == null || modelData == ModelData.EMPTY
                ? createModelDataFromDescriptor(descriptor)
                : modelData;
        BlockState colourState = selectMaterialState(state, textureData);
        int tintAbgr = resolveTint(colourState);

        if (isDetailedShingle(state)) {
            return new BakePlan(resolved, null, colourState, tintAbgr, false, true, true);
        }
        return new BakePlan(resolved, null, colourState, tintAbgr, false, true, false);
    }

    private static ModelData createModelDataFromDescriptor(VariantDescriptor descriptor) {
        try {
            Object textureData = deserializeTextureData(descriptor.data());
            return textureData == null ? ModelData.EMPTY : createModelData(textureData);
        } catch (Throwable ignored) {
            return ModelData.EMPTY;
        }
    }

    private static VariantDescriptor createDescriptor(Object textureData) {
        try {
            CompoundTag data = serializeTextureData(textureData);
            if (data == null || data.isEmpty()) {
                return null;
            }
            CompoundTag snapshot = data.copy();
            String key = NbtVariantKey.of(snapshot);
            return DESCRIPTORS.computeIfAbsent(key, ignored -> new VariantDescriptor(key, snapshot));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object extractTextureData(BlockEntity blockEntity) {
        Optional<Method> method = TEXTURE_DATA_METHODS.get(blockEntity.getClass());
        if (method.isEmpty()) {
            return null;
        }
        try {
            return method.get().invoke(blockEntity);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object extractTextureData(ModelData modelData) {
        if (modelData == null || modelData == ModelData.EMPTY) {
            return null;
        }
        ModelProperty property = getMaterialTextureProperty();
        if (property == null) {
            return null;
        }
        try {
            return modelData.has(property) ? modelData.get(property) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static CompoundTag serializeTextureData(Object textureData) throws ReflectiveOperationException {
        Optional<Method> method = SERIALIZE_METHODS.get(textureData.getClass());
        if (method.isEmpty()) return null;
        Object value = method.get().invoke(textureData);
        return value instanceof CompoundTag tag ? tag : null;
    }

    private static Object deserializeTextureData(CompoundTag data) throws ReflectiveOperationException {
        Method method = textureDataDeserializer;
        if (method == null) {
            synchronized (DomumOrnamentumCompat.class) {
                method = textureDataDeserializer;
                if (method == null) {
                    Class<?> type = Class.forName(TEXTURE_DATA_CLASS);
                    method = type.getMethod("deserializeFromNBT", CompoundTag.class);
                    textureDataDeserializer = method;
                }
            }
        }
        return method.invoke(null, data.copy());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ModelData createModelData(Object textureData) {
        ModelProperty property = getMaterialTextureProperty();
        if (property == null || textureData == null) {
            return ModelData.EMPTY;
        }
        try {
            return ModelData.builder().with((ModelProperty) property, textureData).build();
        } catch (Throwable ignored) {
            return ModelData.EMPTY;
        }
    }

    private static BlockState selectMaterialState(BlockState ownerState, Object textureData) {
        try {
            Optional<Method> method = TEXTURED_COMPONENT_METHODS.get(textureData.getClass());
            if (method.isEmpty()) return null;
            Object value = method.get().invoke(textureData);
            if (!(value instanceof Map<?, ?> components) || components.isEmpty()) {
                return null;
            }

            // Domum exposes the semantic main component on its block. Prefer it over name
            // heuristics so the independent Voxy model uses the same dominant material tint.
            if (ownerState != null) {
                Optional<Method> mainMethod =
                        MAIN_COMPONENT_METHODS.get(ownerState.getBlock().getClass());
                if (mainMethod.isPresent()) {
                    Object mainComponent = mainMethod.get().invoke(ownerState.getBlock());
                    if (mainComponent != null) {
                        Optional<Method> idMethod =
                                COMPONENT_ID_METHODS.get(mainComponent.getClass());
                        if (idMethod.isPresent()) {
                            Object mainId = idMethod.get().invoke(mainComponent);
                            Object mainMaterial = components.get(mainId);
                            if (mainMaterial instanceof Block block && block != Blocks.AIR) {
                                return block.defaultBlockState();
                            }
                        }
                    }
                }
            }

            var entries = new ArrayList<>(components.entrySet());
            entries.sort(Comparator
                    .comparingInt((Map.Entry<?, ?> entry) -> materialPriority(String.valueOf(entry.getKey())))
                    .thenComparing(entry -> String.valueOf(entry.getKey())));

            for (Map.Entry<?, ?> entry : entries) {
                if (entry.getValue() instanceof Block block && block != Blocks.AIR) {
                    return block.defaultBlockState();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int materialPriority(String key) {
        String normalized = key.toLowerCase();
        if (normalized.contains("main") || normalized.contains("body") || normalized.contains("brick")
                || normalized.contains("shingle") || normalized.contains("material")) {
            return 0;
        }
        if (normalized.contains("frame") || normalized.contains("pillar") || normalized.contains("support")) {
            return 2;
        }
        return 1;
    }

    private static int resolveTint(BlockState materialState) {
        if (materialState == null) {
            return -1;
        }
        try {
            int colour = net.minecraft.client.Minecraft.getInstance().getBlockColors()
                    .getColor(materialState, null, BlockPos.ZERO, 0);
            if (colour != -1) {
                return rgbToAbgr(colour);
            }
        } catch (Throwable ignored) {
        }
        try {
            int colour = materialState.getMapColor(null, BlockPos.ZERO).col;
            return rgbToAbgr(colour);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean isDetailedShingle(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null || !VARIANT_TYPE.equals(id.getNamespace())) {
            return false;
        }
        String path = id.getPath();
        return path.contains("shingle") && !path.contains("slab");
    }

    private static Map<Integer, BakePlan> plansFor(Mapper mapper) {
        return BAKE_PLANS.computeIfAbsent(mapper, ignored -> new ConcurrentHashMap<>());
    }

    private static final class SectionMappings {
        private final int[] ids = new int[4096];
        private final short[] touched = new short[4096];
        private int touchedCount;
        private boolean active;

        void reset() {
            for (int index = 0; index < this.touchedCount; index++) {
                this.ids[Short.toUnsignedInt(this.touched[index])] = 0;
            }
            this.touchedCount = 0;
            this.active = false;
        }

        private void put(int localIndex, int mappedId) {
            if (this.ids[localIndex] == 0) {
                this.touched[this.touchedCount++] = (short) localIndex;
            }
            this.ids[localIndex] = mappedId;
        }
    }

    public static void closeMapper(Mapper mapper) {
        if (LOADED && mapper != null) {
            BAKE_PLANS.remove(mapper);
            if (BAKE_PLANS.isEmpty()) {
                DESCRIPTORS.clear();
            }
        }
    }

    private static int rgbToAbgr(int rgb) {
        return 0xFF000000 | ((rgb & 0x0000FF) << 16) | (rgb & 0x00FF00) | ((rgb >>> 16) & 0xFF);
    }

    private static ModelProperty<?> getMaterialTextureProperty() {
        ModelProperty<?> property = materialTextureProperty;
        if (property != null || materialTexturePropertyMissing) {
            return property;
        }
        try {
            Class<?> propertiesClass = Class.forName(MODEL_PROPERTIES);
            Field field = propertiesClass.getField("MATERIAL_TEXTURE_PROPERTY");
            Object value = field.get(null);
            if (value instanceof ModelProperty<?> modelProperty) {
                materialTextureProperty = modelProperty;
                return modelProperty;
            }
        } catch (Throwable ignored) {
        }
        materialTexturePropertyMissing = true;
        return null;
    }
}
