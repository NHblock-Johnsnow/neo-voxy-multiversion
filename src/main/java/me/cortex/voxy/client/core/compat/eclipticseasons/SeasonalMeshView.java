package me.cortex.voxy.client.core.compat.eclipticseasons;

import com.teamtea.eclipticseasons.api.constant.solar.SolarTerm;
import com.teamtea.eclipticseasons.client.color.season.FoliageColorSource;
import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import com.teamtea.eclipticseasons.client.util.ClientCon;
import com.teamtea.eclipticseasons.client.util.ClientRef;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.map.stub.PlainsStubHolder;
import com.teamtea.eclipticseasons.config.ClientConfig;
import com.teamtea.eclipticseasons.config.CommonConfig;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.bakery.ReuseVertexConsumer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.SurfaceWorldView;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.SeasonalIdSpace;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

public class SeasonalMeshView implements SeasonalLod.View {
    private static final ThreadLocal<RandomSource> RANDOM =
            ThreadLocal.withInitial(RandomSource::createNewThreadLocalInstance);
    private static final ThreadLocal<BlockPos.MutableBlockPos> POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    //Per-real-block-id verdicts: whether the block has seasonal model definitions, whether it is
    //freezable water, plus the snow-type flag. Static between data reloads; racy rebuilds only
    //recompute the same value.
    private static final int F_COMPUTED = 1 << 31, F_SEASONAL = 1 << 30, F_FREEZABLE = 1 << 29;
    private static final int FLAG_MASK = 0xFFFF;

    private static final int[] EMPTY_CANDIDATES = new int[0];
    @SuppressWarnings("unchecked")
    private static final Holder<Biome>[] EMPTY_BIOMES = new Holder[0];

    private static final class JudgementCaches {
        final Map<Mapper, int[]> candidates = new WeakHashMap<>();
        final Map<Mapper, Holder<Biome>[]> biomes = new WeakHashMap<>();
    }

    private static volatile JudgementCaches judgementCaches = new JudgementCaches();

    //The season definitions and snow tables are data driven: a solar-term change or a logout can
    //sit on top of a /reload that changed them, so both events drop the verdicts wholesale
    public static void clearJudgementCaches() {
        // 旧 worker 只能回填旧容器，不能把 reload 前的判断重新发布到新缓存。
        judgementCaches = new JudgementCaches();
    }

    private static final class Ctx {
        Level level;
        Mapper mapper;
        JudgementCaches caches;
        int[] candidates;
        Holder<Biome>[] biomes;
        boolean snowyWinter;
        boolean glowGate;
        int glowLevel;
        boolean frozenWater;
        boolean frozenWaterCheckLight;
        boolean snowyTree;
        boolean snowUnderFence;
    }

    private static Ctx makeCtx(Level level, Mapper mapper) {
        var ctx = new Ctx();
        ctx.level = level;
        ctx.mapper = mapper;
        ctx.caches = judgementCaches;
        synchronized (ctx.caches.candidates) {
            var cache = ctx.caches.candidates.get(mapper);
            ctx.candidates = cache != null ? cache : EMPTY_CANDIDATES;
        }
        synchronized (ctx.caches.biomes) {
            var cache = ctx.caches.biomes.get(mapper);
            ctx.biomes = cache != null ? cache : EMPTY_BIOMES;
        }
        ctx.snowyWinter = CommonConfig.isSnowyWinter();
        ctx.glowGate = CommonConfig.Snow.notSnowyNearGlowingBlock.get();
        ctx.glowLevel = CommonConfig.Snow.notSnowyNearGlowingBlockLevel.get();
        ctx.frozenWater = ClientConfig.Debug.frozenWater.get();
        ctx.frozenWaterCheckLight = ClientConfig.Debug.frozenWaterCheckLight.get();
        ctx.snowyTree = CommonConfig.Snow.snowyTree.get();
        ctx.snowUnderFence = ClientConfig.Renderer.snowUnderFence.get();
        return ctx;
    }

    private static int computeCandidate(Ctx ctx, int realBlockId) {
        BlockState state = ctx.mapper.getBlockStateFromBlockId(realBlockId);
        int v = F_COMPUTED;
        if (ClientRef.seasonDef.containsKey(state.getBlock())) v |= F_SEASONAL;
        if (state.is(Blocks.WATER) && state.getFluidState().isSourceOfType(Fluids.WATER)) v |= F_FREEZABLE;
        //FLAG_IGNORE is negative: clamp it instead of masking, or the mask would turn ES's
        //"never treat this block" marker into a large snowable flag
        int snowFlag = Math.max(MapChecker.getDefaultBlockTypeFlag(state), 0);
        if (snowFlag <= MapChecker.FLAG_NONE) {
            // Client snow definitions can add an overlay to a block that has no built-in snow
            // flag. ExtraModelManager uses the first definition for this same eligibility test.
            var snowDefinitions = ClientRef.snowClientDef.get(state.getBlock());
            if (snowDefinitions != null && !snowDefinitions.isEmpty()) {
                snowFlag = Math.max(snowDefinitions.getFirst().getInfo().getFlag(), 0);
            }
        }
        v |= snowFlag & FLAG_MASK;
        synchronized (ctx.caches.candidates) {
            int[] cache = ctx.caches.candidates.get(ctx.mapper);
            if (cache == null || realBlockId >= cache.length) {
                int[] grown = new int[Math.max(realBlockId + 1,
                        cache == null ? 1024 : cache.length << 1)];
                if (cache != null) System.arraycopy(cache, 0, grown, 0, cache.length);
                cache = grown;
            }
            cache[realBlockId] = v;
            ctx.caches.candidates.put(ctx.mapper, cache);
            ctx.candidates = cache;
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Holder<Biome> biomeFor(Ctx ctx, int biomeId) {
        if (biomeId < 0) return null;
        var cache = ctx.biomes;
        if (biomeId < cache.length) {
            Holder<Biome> hit = cache[biomeId];
            if (hit != null) return hit;
        }
        var entry = ctx.mapper.getBiomeEntry(biomeId);
        if (entry == null) return null;
        var key = ResourceKey.create(Registries.BIOME, ResourceLocation.parse(entry.biome));
        Holder<Biome> resolved = ctx.level.registryAccess().registryOrThrow(Registries.BIOME)
                .getHolder(key).map(h -> (Holder<Biome>) h).orElse(PlainsStubHolder.PLAINS);
        synchronized (ctx.caches.biomes) {
            var current = ctx.caches.biomes.get(ctx.mapper);
            if (current == null || current.length <= biomeId) {
                var grown = new Holder[Math.max(biomeId + 1,
                        current == null ? 512 : current.length << 1)];
                if (current != null) System.arraycopy(current, 0, grown, 0, current.length);
                current = (Holder<Biome>[]) grown;
            }
            current[biomeId] = resolved;
            ctx.caches.biomes.put(ctx.mapper, current);
            ctx.biomes = current;
        }
        return resolved;
    }

    private static boolean seasonInactive() {
        var term = ClientCon.nowSolarTerm;
        return term == null || term == SolarTerm.NONE;
    }

    @Override
    public long[] substituteSection(WorldEngine world, WorldSection section, long[] source) {
        try (var surface = new SurfaceWorldView().open(world, section)) {
            return this.substituteSection(world, section, source, surface);
        }
    }

    @Override
    public long[] substituteSection(WorldEngine world, WorldSection section, long[] source,
                                    SurfaceWorldView surface) {
        if (!VoxyConfig.CONFIG.eclipticSeasonsSnowLod || seasonInactive()) {
            return restoreBaseIds(world.getMapper(), source);
        }
        Level level = ClientCon.getUseLevel();
        if (level == null) return restoreBaseIds(world.getMapper(), source);

        var ctx = makeCtx(level, world.getMapper());
        long[] output = source;

        int scale = 1 << section.lvl;
        int centerOffset = section.lvl == 0 ? 0 : scale >> 1;
        int baseX = ((section.x << 5) << section.lvl) + centerOffset;
        int baseY = ((section.y << 5) << section.lvl) + centerOffset;
        int baseZ = ((section.z << 5) << section.lvl) + centerOffset;

        var pos = POS.get();
        WorldSection aboveSection = null;
        boolean aboveChecked = false;
        for (int index = 0; index < source.length; index++) {
            long mappingId = source[index];
            if (Mapper.isAir(mappingId)) continue;

            int storedBlockId = Mapper.getBlockId(mappingId);
            //Decode first: legacy archives hold complement-encoded snow, and re-encoding an
            //already-encoded id must be impossible for the pass to be idempotent
            int originalBlockId = SeasonalIdSpace.decode(ctx.mapper, storedBlockId);

            //Cache-first: the common verdict (not seasonal, not snowable) must cost one array
            //read, with no BlockState fetch on the hit path
            int cand = originalBlockId < ctx.candidates.length ? ctx.candidates[originalBlockId] : 0;
            if (cand == 0) cand = computeCandidate(ctx, originalBlockId);
            if ((cand & (F_SEASONAL | F_FREEZABLE)) == 0
                    && (cand & FLAG_MASK) <= MapChecker.FLAG_NONE) {
                output = setRenderBlock(source, output, index, originalBlockId);
                continue;
            }

            long aboveMappingId;
            boolean aboveKnown = true;
            int localY = index >>> 10;
            if (localY < 31) {
                aboveMappingId = source[index + 1024];
            } else {
                if (!aboveChecked) {
                    aboveChecked = true;
                    aboveSection = surface.sectionAt(0, 1, 0);
                }
                if (aboveSection == null) {
                    // Snow and ice require evidence about the voxel above, but seasonal models
                    // whose definition does not require emptyAbove can still be selected.
                    if ((cand & F_SEASONAL) == 0) {
                        output = setRenderBlock(source, output, index, originalBlockId);
                        continue;
                    }
                    aboveKnown = false;
                    aboveMappingId = 0;
                } else {
                    //Single-voxel read: materialising the neighbour would permanently expand a
                    //uniform section into a pooled 256KiB array for a 1024-voxel row
                    aboveMappingId = surface.get(index & 31, 32, (index >> 5) & 31);
                }
            }

            //Light and winter gates before any BlockState fetch: snow and ice need a snowy
            //winter and lit sky above, seasonal models need neither
            boolean canSnow = aboveKnown && canSnow(ctx, aboveMappingId);
            boolean canFreeze = aboveKnown && canFreeze(ctx, aboveMappingId);
            if (!canSnow && !canFreeze && (cand & F_SEASONAL) == 0) {
                output = setRenderBlock(source, output, index, originalBlockId);
                continue;
            }

            pos.set(baseX + (index & 31) * scale,
                    baseY + localY * scale,
                    baseZ + ((index >> 5) & 31) * scale);

            int renderBlockId = judge(ctx, mappingId, aboveMappingId, aboveKnown,
                    originalBlockId, cand, canSnow, canFreeze, pos);
            output = setRenderBlock(source, output, index, renderBlockId);
        }
        return output;
    }

    @Override
    public void substituteLateralSlices(WorldEngine world, WorldSection section,
                                         long[] neighborFaces, int neighborMsk) {
        try (var surface = new SurfaceWorldView().open(world, section)) {
            this.substituteLateralSlices(world, section, neighborFaces, neighborMsk, surface);
        }
    }

    @Override
    public void substituteLateralSlices(WorldEngine world, WorldSection section,
                                         long[] faces, int mask, SurfaceWorldView surface) {
        // 同一纯几何视图也提供顶行的上方证据，避免邻面与主段采用不同雪/冰规则。
        if ((mask & 1) != 0) this.substituteRegion(world, surface, faces, 0, 1, 32, 32, 1, 32, -1, 0, 0);
        if ((mask & 2) != 0) this.substituteRegion(world, surface, faces, 1024, 1, 32, 32, 1, 32, 32, 0, 0);
        if ((mask & 16) != 0) this.substituteRegion(world, surface, faces, 4096, 32, 32, 1, 32, 32, 0, 0, -1);
        if ((mask & 32) != 0) this.substituteRegion(world, surface, faces, 5120, 32, 32, 1, 32, 32, 0, 0, 32);
    }

    @Override
    public void substituteRegion(WorldEngine world, SurfaceWorldView surface, long[] data,
                                 int offset, int width, int height, int depth,
                                 int rowStride, int layerStride, int x, int y, int z) {
        boolean active = VoxyConfig.CONFIG.eclipticSeasonsSnowLod && !seasonInactive();
        Level level = active ? ClientCon.getUseLevel() : null;
        Ctx ctx = level == null ? null : makeCtx(level, world.getMapper());
        Mapper mapper = world.getMapper();
        WorldSection section = surface.origin();
        int scale = 1 << section.lvl;
        int center = section.lvl == 0 ? 0 : scale >> 1;
        int baseX = (section.x << (5 + section.lvl)) + center;
        int baseY = (section.y << (5 + section.lvl)) + center;
        int baseZ = (section.z << (5 + section.lvl)) + center;
        var pos = POS.get();
        for (int iy = 0; iy < height; iy++) {
            for (int iz = 0; iz < depth; iz++) {
                for (int ix = 0; ix < width; ix++) {
                    int index = offset + iy * layerStride + iz * rowStride + ix;
                    long value = data[index];
                    if (Mapper.isAir(value)) continue;
                    int original = SeasonalIdSpace.decode(mapper, Mapper.getBlockId(value));
                    int render = original;
                    if (ctx != null) {
                        int cand = original < ctx.candidates.length ? ctx.candidates[original] : 0;
                        if (cand == 0) cand = computeCandidate(ctx, original);
                        if ((cand & (F_SEASONAL | F_FREEZABLE)) != 0 || (cand & FLAG_MASK) > MapChecker.FLAG_NONE) {
                            int vx = x + ix, vy = y + iy, vz = z + iz;
                            boolean aboveKnown = surface.sectionAt(vx >> 5, (vy + 1) >> 5, vz >> 5) != null;
                            long above = aboveKnown ? surface.get(vx, vy + 1, vz) : 0;
                            boolean snow = aboveKnown && canSnow(ctx, above);
                            boolean freeze = aboveKnown && canFreeze(ctx, above);
                            if (snow || freeze || (cand & F_SEASONAL) != 0) {
                                pos.set(baseX + vx * scale, baseY + vy * scale, baseZ + vz * scale);
                                render = judge(ctx, value, above, aboveKnown, original, cand, snow, freeze, pos);
                            }
                        }
                    }
                    if (render != Mapper.getBlockId(value)) data[index] = withRenderBlock(value, render);
                }
            }
        }
    }

    private static long[] setRenderBlock(long[] source, long[] output, int index, int renderBlockId) {
        long mappingId = source[index];
        if (Mapper.getBlockId(mappingId) == renderBlockId) return output;
        if (output == source) output = Arrays.copyOf(source, source.length);
        output[index] = withRenderBlock(mappingId, renderBlockId);
        return output;
    }

    private static long withRenderBlock(long mappingId, int blockId) {
        return (mappingId & ~(0xFFFFFL << 27)) | ((long) blockId << 27);
    }

    private static long[] restoreBaseIds(Mapper mapper, long[] source) {
        long[] output = source;
        int stateCount = mapper.getBlockStateCount();
        for (int index = 0; index < source.length; index++) {
            int stored = Mapper.getBlockId(source[index]);
            if (stored >= stateCount) {
                output = setRenderBlock(source, output, index, SeasonalIdSpace.decode(mapper, stored));
            }
        }
        return output;
    }

    //Voxel light packs sky in the low nibble, block in the high (VoxelIngestService). The
    //SnowyWinter config gate matches the near field, where it sits in front of every snow and
    //ice placement but not the seasonal model channel.
    private static boolean canSnow(Ctx ctx, long aboveMappingId) {
        if (!ctx.snowyWinter) return false;
        int light = Mapper.getLightId(aboveMappingId);
        return (light & 0xF) > 9
                && (!ctx.glowGate || ((light >> 4) & 0xF) < ctx.glowLevel);
    }

    private static boolean canFreeze(Ctx ctx, long aboveMappingId) {
        if (!ctx.snowyWinter || !ctx.frozenWater) return false;
        int light = Mapper.getLightId(aboveMappingId);
        return (light & 0xF) > 9
                && (!ctx.frozenWaterCheckLight || !ctx.glowGate
                || ((light >> 4) & 0xF) < ctx.glowLevel);
    }

    private static int judge(Ctx ctx, long mappingId, long aboveMappingId, boolean aboveKnown,
                             int originalBlockId, int cand, boolean canSnow, boolean canFreeze,
                             BlockPos pos) {
        boolean seasonalModelRelated = (cand & F_SEASONAL) != 0;
        //Snow and ice are light gated; seasonal models are not (leaves change colour in the dark)
        boolean freezable = canFreeze && (cand & F_FREEZABLE) != 0;
        int flag = canSnow ? (cand & FLAG_MASK) : MapChecker.FLAG_NONE;
        if (!seasonalModelRelated && !freezable && flag <= MapChecker.FLAG_NONE) return originalBlockId;

        Mapper mapper = ctx.mapper;
        BlockState state = mapper.getBlockStateFromBlockId(originalBlockId);
        BlockState above = mapper.getBlockStateFromBlockId(Mapper.getBlockId(aboveMappingId));
        if (state.is(Blocks.SNOW_BLOCK)) flag = MapChecker.FLAG_NONE;
        boolean snowRenderable = flag > MapChecker.FLAG_NONE && canRenderSnow(ctx, state, above, flag);
        if (!seasonalModelRelated && !freezable && !snowRenderable) return originalBlockId;

        Holder<Biome> biome = biomeFor(ctx, Mapper.getBiomeId(mappingId));
        if (biome == null) return originalBlockId;

        long seed = state.getSeed(pos);
        if (freezable && above.isAir()
                && MapChecker.shouldSnowAtBiome(ctx.level, biome.value(), state, RANDOM.get(), seed, pos)) {
            return SeasonalIdSpace.VIRTUAL_ICE_ID;
        }

        boolean snowy = snowRenderable && MapChecker.shouldSnowAtBiome(
                ctx.level, biome.value(), state, RANDOM.get(), seed, pos);

        int model = seasonalModelRelated
                ? findSeasonalModel(mapper, originalBlockId, biome, state,
                aboveKnown && above.isAir(), pos, seed, snowy)
                : originalBlockId;
        return model != originalBlockId ? model
                : snowy ? SeasonalIdSpace.MAX_BLOCK_ID - originalBlockId : originalBlockId;
    }

    private static int findSeasonalModel(Mapper mapper, int originalBlockId, Holder<Biome> biome,
                                         BlockState state, boolean airAbove, BlockPos pos,
                                         long seed, boolean snowy) {
        var defs = ClientRef.seasonDef.get(state.getBlock());
        if (defs == null) return originalBlockId;
        for (int i = 0; i < defs.size(); i++) {
            var def = defs.get(i);
            var holders = def.getFlatSliceEnumMap().get(ClientCon.nowSolarTerm);
            if (holders == null || holders.isEmpty()) continue;
            //Unconditional containment, matching the near field: an empty biome set never
            //matches, so a definition without biomes shows on neither side of the LOD border
            if (!def.getBiomes().contains(biome)) continue;
            for (int j = 0; j < holders.size(); j++) {
                var flatSlice = holders.get(j).flatSlice();
                if (flatSlice.emptyAbove() && !airAbove) continue;
                //Per-block staggered transition: blocks flip from the first to the second model
                //as the in-term progress (0-100) advances past their own stable threshold
                ResourceLocation modelId = flatSlice.transitionModels() == null
                        ? flatSlice.mid()
                        : Mth.abs((int) (seed + pos.getX())) % 100 > ClientCon.progress
                        ? flatSlice.transitionModels().getFirst()
                        : flatSlice.transitionModels().getSecond();
                if (modelId == null) continue;
                return SeasonalIdSpace.getOrCreate(mapper, originalBlockId, modelId, snowy);
            }
        }
        return originalBlockId;
    }

    private static boolean canRenderSnow(Ctx ctx, BlockState state, BlockState above, int flag) {
        if (MapChecker.leaveLike(flag)) {
            boolean specialLeaves = above.is(state.getBlock())
                    && (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(above)
                    || MapChecker.extraSnowPassable(above));
            return !specialLeaves || ctx.snowyTree;
        }
        if (MapChecker.extraSnowPassable(state)) {
            return !MapChecker.extraSnowPassable(above);
        }
        return ctx.snowUnderFence || !MapChecker.solidTest(above);
    }

    @Override
    public boolean isSeasonalConstantTint(BlockState state, Object colourProvider) {
        return colourProvider instanceof FoliageColorSource
                || colourProvider instanceof FoliageColorSource.Impl
                || state.is(Blocks.BIRCH_LEAVES) || state.is(Blocks.SPRUCE_LEAVES)
                || state.is(Blocks.MANGROVE_LEAVES);
    }

    @Override
    public SeasonalLod.SeasonalBakedModel resolveSeasonalModel(BlockState state, ResourceLocation modelId) {
        var tester = ExtraModelManager.getSeasonalModel(state, modelId);
        if (tester == null) return null;
        var model = ExtraModelManager.getExtraModel(tester.modelResourceLocation());
        return model == null ? null : new SeasonalLod.SeasonalBakedModel(model, tester.replace());
    }

    @Override
    public void renderSnowOverlay(BlockState state, RenderType layer,
                                  ReuseVertexConsumer translucentVC, ReuseVertexConsumer opaqueVC) {
        VoxyClientTool.renderToStream(state, layer, translucentVC, opaqueVC);
    }

    @Override
    public void clearCaches() {
        clearJudgementCaches();
    }
}
