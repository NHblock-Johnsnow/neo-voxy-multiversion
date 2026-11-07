package me.cortex.voxy.commonImpl.compat;

import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Copycats/Create 兼容层：把伪装材料映射为稳定的 Voxy 变体 ID。 */
public final class CreateCopycatCompat {
    public static final String DISGUISE_TABLE = "disguise_copycat";
    public static final String VARIANT_TYPE = "create_copycat";

    private static final boolean LOADED = CopycatCommon.isLoaded();
    private static final String CREATE_PREFIX = CopycatCommon.CREATE_PREFIX;
    private static final String ADDON_PREFIX = CopycatCommon.ADDON_PREFIX;

    private static final ThreadLocal<SectionMappings> SECTION_MAPPINGS =
            ThreadLocal.withInitial(SectionMappings::new);
    private static final Map<Mapper, Map<Integer, MaterialSet>> MATERIALS_BY_MAPPER = new ConcurrentHashMap<>();
    private static final Map<MaterialSet, MaterialKey> MATERIAL_KEYS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final String MATERIALS_KEY = "materials";

    private record MaterialSet(Map<String, BlockState> parts) {
        MaterialSet {
            parts = Map.copyOf(parts);
        }

        BlockState primary() {
            BlockState material = this.parts.get("material");
            return material != null ? material : this.parts.entrySet().stream()
                    .min(Map.Entry.comparingByKey()).map(Map.Entry::getValue).orElse(null);
        }

    }

    private record MaterialKey(CompoundTag data, String key) {}

    private static final Predicate<BlockState> COPYCAT_STATE_PREDICATE = CreateCopycatCompat::isCopycatState;
    private static final ClassValue<Boolean> COPYCATS_PLUS_MODELS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getName().startsWith(ADDON_PREFIX + ".");
        }
    };

    private static final ClassValue<Optional<Method>> GET_MATERIAL_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getMaterial"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static final ClassValue<Optional<Method>> GET_STORAGE_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getMaterialItemStorage"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static final ClassValue<Optional<Method>> GET_MATERIAL_MAP_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getMaterialMap"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static final ClassValue<Optional<Method>> GET_STORAGE_PROPERTIES_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("storageProperties"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static final ClassValue<Optional<Method>> GET_SPRITE_PROPERTY_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("getProperty"));
            } catch (ReflectiveOperationException ignored) {
                return Optional.empty();
            }
        }
    };

    private static volatile ModelProperty<BlockState> createMaterialProperty;
    private static volatile ModelProperty<BlockState> addonMaterialProperty;
    private static volatile ModelProperty<Map<String, BlockState>> addonMaterialsProperty;
    private static volatile ModelProperty<Boolean> virtualProperty;
    private static volatile boolean propertiesResolved;

    public interface SectionListener {
        void changed(SectionStorage storage, int sectionX, int sectionY, int sectionZ);
    }

    public static volatile SectionListener sectionListener;

    private CreateCopycatCompat() {
    }

    // ---- 状态与区段映射 -----------------------------------------------

    public static boolean isLoaded() {
        return LOADED;
    }

    public static boolean isCopycatState(BlockState state) {
        return CopycatCommon.isCopycatState(state);
    }

    public static boolean isCopycatsPlusModel(BakedModel model) {
        return LOADED && model != null && COPYCATS_PLUS_MODELS.get(model.getClass());
    }

    /** 在区段进入摄取流程时读取方块实体材料并写入持久化伪装表。 */
    public static void beginSection(Mapper mapper, SectionStorage storage, LevelChunk chunk, LevelChunkSection section, int sectionX, int sectionY, int sectionZ) {
        if (!LOADED) {
            return;
        }
        SectionMappings mappings = SECTION_MAPPINGS.get();
        mappings.reset();
        if (mapper == null || section == null) return;
        if (!section.maybeHas(COPYCAT_STATE_PREDICATE)) {
            if (DisguiseStore.clearIfPresent(storage, DISGUISE_TABLE, sectionX, sectionY, sectionZ)) {
                SectionListener listener = sectionListener;
                if (listener != null) listener.changed(storage, sectionX, sectionY, sectionZ);
            }
            return;
        }
        if (chunk == null || chunk.getBlockEntities().isEmpty()) {
            // 调色板可能保留已删除状态；只核对历史记录中的坐标。
            int restored = DisguiseStore.load(storage, DISGUISE_TABLE, sectionX, sectionY, sectionZ,
                    (index, id) -> {
                        if (isCopycatState(section.getBlockState(index & 15, index >>> 8, (index >>> 4) & 15))) {
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

        try {
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (!CopycatCommon.isCopycatClass(blockEntity)) {
                    continue;
                }
                BlockPos pos = blockEntity.getBlockPos();
                if (pos.getY() < minY || pos.getY() > maxY) {
                    continue;
                }

                try {
                    int lx = pos.getX() & 15;
                    int ly = pos.getY() & 15;
                    int lz = pos.getZ() & 15;
                    BlockState state = section.getBlockState(lx, ly, lz);
                    if (state == null || state.isAir()) {
                        continue;
                    }

                    MaterialSet materials = extractMaterials(blockEntity);
                    if (materials == null) {
                        materials = baseMaterialsFor(state);
                    }
                    if (materials == null) {
                        continue;
                    }

                    MaterialKey mk = MATERIAL_KEYS.get(materials);
                    if (mk == null) {
                        me.cortex.voxy.commonImpl.PerfStats.copycatKeyMiss.increment();
                        mk = createMaterialKey(materials);
                        MaterialKey prior = MATERIAL_KEYS.putIfAbsent(materials, mk);
                        if (prior != null) {
                            mk = prior;
                        }
                    } else {
                        me.cortex.voxy.commonImpl.PerfStats.copycatKeyHit.increment();
                    }

                    int mappedId = mapper.getIdForBlockStateVariant(state, VARIANT_TYPE, mk.key, mk.data);
                    materialsFor(mapper).putIfAbsent(mappedId, materials);
                    mappings.put(lx | (lz << 4) | (ly << 8), mappedId);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        mappings.active = mappings.touchedCount != 0;
        //Recorded while the block entities are readable, which is the only moment the material can be
        //derived at all. Rewritten whole per section, so a block that stopped being disguised leaves
        //with the re-scan that no longer sees it.
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
                    int index = mappings.touched[i];
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

    // 每个区段只取一次 ThreadLocal 数组，体素热循环直接按索引读取。
    public static int[] activeSectionIds() {
        if (!LOADED) {
            return null;
        }
        SectionMappings m = SECTION_MAPPINGS.get();
        return m.active ? m.ids : null;
    }

    // 世界加载时从 Mapper 存储恢复材料 NBT，保证变体 ID 可复现。
    public static void restoreVariant(Mapper mapper, int blockId, BlockState state, String variantType, CompoundTag data) {
        if (!LOADED || mapper == null || !VARIANT_TYPE.equals(variantType) || data == null || data.isEmpty()) {
            return;
        }
        try {
            MaterialSet materials = readMaterials(data);
            if (materials != null) {
                materialsFor(mapper).putIfAbsent(blockId, materials);
            }
        } catch (Throwable ignored) {
        }
    }

    // 模型烘焙时使用材料自身的渲染层，Copycat 包装模型只在该层返回四边形。
    public static RenderType renderLayerOverride(Mapper mapper, int blockId, BlockState state) {
        BlockState material = primaryMaterial(mapper, blockId);
        if (material == null) {
            material = baseMaterialFor(state);
        }
        if (material == null) {
            return null;
        }
        try {
            return ItemBlockRenderTypes.getChunkRenderType(material);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // 草方块、树叶等 Copycat 使用材料状态参与生物群系染色。
    public static BlockState getColourState(Mapper mapper, int blockId, BlockState fallback) {
        BlockState material = primaryMaterial(mapper, blockId);
        return material == null ? fallback : material;
    }

    // 烘焙计划同时携带两个模组使用的 ModelData 键和材料状态。
    public static DomumOrnamentumCompat.BakePlan getBakePlan(Mapper mapper, int blockId, BlockState state) {
        MaterialSet materials = materialSetFor(mapper, blockId);
        if (materials == null) {
            materials = baseMaterialsFor(state);
        }
        if (materials == null) {
            return DomumOrnamentumCompat.BakePlan.empty();
        }
        try {
            ModelData modelData = buildModelData(materials.parts(), true);
            boolean detailed = materialSetFor(mapper, blockId) != null
                    && me.cortex.voxy.client.config.VoxyConfig.CONFIG.distantCopycats;
            return new DomumOrnamentumCompat.BakePlan(modelData, null, materials.primary(),
                    -1, false, true, detailed);
        } catch (Throwable ignored) {
            return DomumOrnamentumCompat.BakePlan.empty();
        }
    }

    // 将材料写入两个模组可能读取的 ModelData 属性；虚拟渲染额外设置 virtual 标记。
    public static ModelData buildModelData(BlockState material) {
        return buildModelData(Map.of("material", material), false);
    }

    public static ModelData buildModelData(Map<String, BlockState> materials, boolean virtual) {
        resolveProperties();
        ModelData.Builder builder = ModelData.builder();
        BlockState material = materials.get("material");
        if (material == null) material = materials.values().stream().findFirst().orElse(null);
        if (createMaterialProperty != null) {
            builder.with(createMaterialProperty, material);
        }
        if (addonMaterialProperty != null) {
            builder.with(addonMaterialProperty, material);
        }
        if (addonMaterialsProperty != null) {
            builder.with(addonMaterialsProperty, new java.util.HashMap<>(materials));
        }
        if (virtual && virtualProperty != null) {
            builder.with(virtualProperty, true);
        }
        return builder.build();
    }

    public static void closeMapper(Mapper mapper) {
        if (LOADED && mapper != null) {
            MATERIALS_BY_MAPPER.remove(mapper);
        }
    }

    // ---- 材料提取与持久化 ---------------------------------------------

    // 未填充 Copycat 使用 Create 的基础方块作为临时材料。
    private static volatile BlockState baseSkeleton;

    private static BlockState baseMaterialFor(BlockState state) {
        if (!isCopycatState(state)) {
            return null;
        }
        BlockState base = baseSkeleton;
        if (base == null) {
            var block = BuiltInRegistries.BLOCK.get(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", "copycat_base"));
            base = block.defaultBlockState();
            baseSkeleton = base;
        }
        return base.isAir() ? null : base;
    }

    private static MaterialSet baseMaterialsFor(BlockState state) {
        BlockState base = baseMaterialFor(state);
        if (base == null) return null;
        try {
            Method method = GET_STORAGE_PROPERTIES_METHODS.get(state.getBlock().getClass()).orElse(null);
            if (method != null && method.invoke(state.getBlock()) instanceof Iterable<?> properties) {
                TreeMap<String, BlockState> parts = new TreeMap<>();
                for (Object property : properties) {
                    if (property instanceof String name) parts.put(name, base);
                }
                if (!parts.isEmpty()) return new MaterialSet(parts);
            }
        } catch (Throwable ignored) {
        }
        return new MaterialSet(Map.of("material", base));
    }

    // 从方块实体 NBT 恢复单材料或多部件材料。
    public static ModelData materialFromContraptionNbt(BlockState state, CompoundTag beNbt) {
        if (!isCopycatState(state)) {
            return null;
        }
        MaterialSet materials = null;
        try {
            materials = readMaterialsFromBlockEntityNbt(beNbt);
            if (beNbt != null && beNbt.contains("Material")) {
                BlockState material = NbtUtils.readBlockState(
                        BuiltInRegistries.BLOCK.asLookup(), beNbt.getCompound("Material"));
                if (materials == null && !material.isAir()) {
                    materials = new MaterialSet(Map.of("material", material));
                }
            }
        } catch (Throwable ignored) {
        }
        if (materials == null) {
            materials = baseMaterialsFor(state);
        }
        if (materials == null) {
            return null;
        }
        try {
            return buildModelData(materials.parts(), true);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static BlockState materialForQuad(Mapper mapper, int blockId, BakedQuad quad) {
        MaterialSet materials = materialSetFor(mapper, blockId);
        if (materials == null) return null;
        try {
            Method method = GET_SPRITE_PROPERTY_METHODS.get(quad.getSprite().getClass()).orElse(null);
            if (method != null && method.invoke(quad.getSprite()) instanceof String property) {
                BlockState material = materials.parts().get(property);
                if (material != null) return material;
            }
        } catch (Throwable ignored) {
        }
        return materials.primary();
    }

    private static BlockState primaryMaterial(Mapper mapper, int blockId) {
        MaterialSet materials = materialSetFor(mapper, blockId);
        return materials == null ? null : materials.primary();
    }

    private static MaterialSet materialSetFor(Mapper mapper, int blockId) {
        if (!LOADED || mapper == null) {
            return null;
        }
        Map<Integer, MaterialSet> materials = MATERIALS_BY_MAPPER.get(mapper);
        return materials == null ? null : materials.get(blockId);
    }

    private static Map<Integer, MaterialSet> materialsFor(Mapper mapper) {
        return MATERIALS_BY_MAPPER.computeIfAbsent(mapper, m -> new ConcurrentHashMap<>());
    }

    private static MaterialSet extractMaterials(BlockEntity blockEntity) throws ReflectiveOperationException {
        Method storageMethod = GET_STORAGE_METHODS.get(blockEntity.getClass()).orElse(null);
        if (storageMethod != null) {
            Object storage = storageMethod.invoke(blockEntity);
            if (storage != null) {
                Method mapMethod = GET_MATERIAL_MAP_METHODS.get(storage.getClass()).orElse(null);
                if (mapMethod != null && mapMethod.invoke(storage) instanceof Map<?, ?> raw) {
                    TreeMap<String, BlockState> parts = new TreeMap<>();
                    raw.forEach((key, value) -> {
                        if (key instanceof String name && value instanceof BlockState state && !state.isAir()) {
                            parts.put(name, state);
                        }
                    });
                    if (!parts.isEmpty()) return new MaterialSet(parts);
                }
            }
        }
        Method getMaterial = GET_MATERIAL_METHODS.get(blockEntity.getClass()).orElse(null);
        if (getMaterial != null && getMaterial.invoke(blockEntity) instanceof BlockState material && !material.isAir()) {
            return new MaterialSet(Map.of("material", material));
        }
        return null;
    }

    private static MaterialKey createMaterialKey(MaterialSet materials) {
        CompoundTag root = new CompoundTag();
        CompoundTag parts = new CompoundTag();
        StringBuilder key = new StringBuilder("v2;");
        new TreeMap<>(materials.parts()).forEach((name, state) -> {
            CompoundTag stateTag = NbtUtils.writeBlockState(state);
            parts.put(name, stateTag);
            key.append(name.length()).append(':').append(name).append('=').append(stateTag).append(';');
        });
        root.put(MATERIALS_KEY, parts);
        return new MaterialKey(root, key.toString());
    }

    private static MaterialSet readMaterials(CompoundTag data) {
        if (data.contains(MATERIALS_KEY, Tag.TAG_COMPOUND)) {
            CompoundTag partsTag = data.getCompound(MATERIALS_KEY);
            TreeMap<String, BlockState> parts = new TreeMap<>();
            for (String name : partsTag.getAllKeys()) {
                BlockState state = NbtUtils.readBlockState(
                        BuiltInRegistries.BLOCK.asLookup(), partsTag.getCompound(name));
                if (!state.isAir()) parts.put(name, state);
            }
            return parts.isEmpty() ? null : new MaterialSet(parts);
        }
        BlockState material = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), data);
        return material.isAir() ? null : new MaterialSet(Map.of("material", material));
    }

    private static MaterialSet readMaterialsFromBlockEntityNbt(CompoundTag beNbt) {
        if (beNbt == null || !beNbt.contains("material_data", Tag.TAG_COMPOUND)) return null;
        CompoundTag data = beNbt.getCompound("material_data");
        TreeMap<String, BlockState> parts = new TreeMap<>();
        for (String name : data.getAllKeys()) {
            CompoundTag item = data.getCompound(name);
            if (!item.contains("material", Tag.TAG_COMPOUND)) continue;
            BlockState state = NbtUtils.readBlockState(
                    BuiltInRegistries.BLOCK.asLookup(), item.getCompound("material"));
            if (!state.isAir()) parts.put(name, state);
        }
        return parts.isEmpty() ? null : new MaterialSet(parts);
    }

    // ---- 反射属性缓存 --------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void resolveProperties() {
        if (propertiesResolved) {
            return;
        }
        try {
            createMaterialProperty = (ModelProperty<BlockState>) Class
                    .forName(CREATE_PREFIX + ".CopycatModel")
                    .getField("MATERIAL_PROPERTY").get(null);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> addonModel = Class.forName(ADDON_PREFIX + ".foundation.copycat.model.neoforge.CopycatModelNeoForge");
            addonMaterialProperty = (ModelProperty<BlockState>) addonModel.getField("MATERIAL_PROPERTY").get(null);
            addonMaterialsProperty = (ModelProperty<Map<String, BlockState>>) addonModel.getField("MATERIALS_PROPERTY").get(null);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> helper = Class.forName("net.createmod.ponder.render.VirtualRenderHelper");
            virtualProperty = (ModelProperty<Boolean>) helper.getField("VIRTUAL_PROPERTY").get(null);
        } catch (Throwable ignored) {
        }
        propertiesResolved = true;
    }

    private static final class SectionMappings {
        private final int[] ids = new int[4096];
        private final int[] touched = new int[4096];
        private int touchedCount;
        private boolean active;

        void reset() {
            for (int i = 0; i < this.touchedCount; i++) {
                this.ids[this.touched[i]] = 0;
            }
            this.touchedCount = 0;
            this.active = false;
        }

        void put(int index, int id) {
            if (this.ids[index] == 0) {
                this.touched[this.touchedCount++] = index;
            }
            this.ids[index] = id;
        }
    }
}
