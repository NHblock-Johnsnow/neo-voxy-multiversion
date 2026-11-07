package me.cortex.voxy.common.voxelization;

import me.cortex.voxy.commonImpl.mixin.minecraft.AccessorPalettedContainer;
import me.cortex.voxy.commonImpl.mixin.minecraft.AccessorPalettedContainerData;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;
import me.cortex.voxy.commonImpl.compat.DomumOrnamentumCompat;
import net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette;
import net.neoforged.fml.ModList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.SingleValuePalette;
import java.util.WeakHashMap;

public class WorldConversionFactory {
    private static final boolean LITHIUM_INSTALLED = ModList.get().isLoaded("lithium");

    private static final class Cache {
        private final int[] biomeCache = new int[4*4*4];
        private final WeakHashMap<Mapper, Reference2IntOpenHashMap<BlockState>> localMapping = new WeakHashMap<>();
        private final WeakHashMap<Mapper, Reference2IntOpenHashMap<Holder<Biome>>> localBiomeMapping = new WeakHashMap<>();
        private int[] paletteCache = new int[1024];
        private final long[] zoomCellCache = new long[5*5*5];
        private Reference2IntOpenHashMap<BlockState> getLocalMapping(Mapper mapper) {
            return this.localMapping.computeIfAbsent(mapper, (a_)->new Reference2IntOpenHashMap<>());
        }
        private Reference2IntOpenHashMap<Holder<Biome>> getLocalBiomeMapping(Mapper mapper) {
            return this.localBiomeMapping.computeIfAbsent(mapper, (a_)->new Reference2IntOpenHashMap<>());
        }
        private int[] getPaletteCache(int size) {
            if (this.paletteCache.length < size) {
                this.paletteCache = new int[size];
            }
            return this.paletteCache;
        }
    }

    private static final ThreadLocal<Cache> THREAD_LOCAL = ThreadLocal.withInitial(Cache::new);

    private static boolean setupLithiumLocalPallet(Palette<BlockState> vp, Reference2IntOpenHashMap<BlockState> blockCache, Mapper mapper, int[] pc)  {
        if (vp instanceof LithiumHashPalette<BlockState>) {
            for (int i = 0; i < vp.getSize(); i++) {
                BlockState state = null;
                int blockId = -1;
                try { state = vp.valueFor(i); } catch (Exception e) {}
                if (state != null) {
                    blockId = blockCache.getOrDefault(state, -1);
                    if (blockId == -1) {
                        blockId = mapper.getIdForBlockState(state);
                        blockCache.put(state, blockId);
                    }
                }
                pc[i] = blockId;
            }
            return true;
        }
        return false;
    }
    private static int setupLocalPalette(Palette<BlockState> vp, Reference2IntOpenHashMap<BlockState> blockCache, Mapper mapper, int[] pc) {
        int c = vp.getSize();
        if (vp instanceof LinearPalette<BlockState>) {
            for (int i = 0; i < vp.getSize(); i++) {
                var state = vp.valueFor(i);
                int blockId = -1;
                if (state != null) {
                    blockId = blockCache.getOrDefault(state, -1);
                    if (blockId == -1) {
                        blockId = mapper.getIdForBlockState(state);
                        blockCache.put(state, blockId);
                    }
                }
                pc[i] = blockId;
            }
        } else if (vp instanceof HashMapPalette<BlockState> pal) {

            for (int i = 0; i < vp.getSize(); i++) {
                BlockState state = null;
                int blockId = -1;
                try { state = vp.valueFor(i); } catch (Exception e) {}
                if (state != null) {
                    blockId = blockCache.getOrDefault(state, -1);
                    if (blockId == -1) {
                        blockId = mapper.getIdForBlockState(state);
                        blockCache.put(state, blockId);
                    }
                }
                pc[i] = blockId;
            }

        } else if (vp instanceof SingleValuePalette<BlockState>) {
            int blockId = -1;
            var state = vp.valueFor(0);
            if (state != null) {
                blockId = blockCache.getOrDefault(state, -1);
                if (blockId == -1) {
                    blockId = mapper.getIdForBlockState(state);
                    blockCache.put(state, blockId);
                }
            }
            pc[0] = blockId;
        } else {
            if (!(LITHIUM_INSTALLED && setupLithiumLocalPallet(vp, blockCache, mapper, pc))) {
                throw new IllegalStateException("Unknown palette type: " + vp);
            }
        }
        return c;
    }

    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           PalettedContainer<BlockState> blockContainer,
                                           PalettedContainerRO<Holder<Biome>> biomeContainer,
                                           ILightingSupplier lightSupplier) {
        return convert(section, stateMapper, blockContainer, biomeContainer, lightSupplier, false, 0);
    }

    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           PalettedContainer<BlockState> blockContainer,
                                           PalettedContainerRO<Holder<Biome>> biomeContainer,
                                           ILightingSupplier lightSupplier,
                                           boolean shouldZoom,
                                           long zoomSeed) {
        //Cheat by creating a local pallet then read the data directly
        var cache = THREAD_LOCAL.get();
        var blockCache = cache.getLocalMapping(stateMapper);
        var biomeCacheMap = cache.getLocalBiomeMapping(stateMapper);

        var biomes = cache.biomeCache;
        var data = section.section;
        var zoomCells = cache.zoomCellCache;

        {
            int i = 0;
            int inital = -1;
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        var biomeHolder = biomeContainer.get(x, y, z);
                        int bid = biomeCacheMap.getOrDefault(biomeHolder, -1);
                        if (bid == -1) {
                            me.cortex.voxy.commonImpl.PerfStats.biomeCacheMiss.increment();
                            bid = stateMapper.getIdForBiome(biomeHolder);
                            biomeCacheMap.put(biomeHolder, bid);
                        } else {
                            me.cortex.voxy.commonImpl.PerfStats.biomeCacheHit.increment();
                        }
                        biomes[i++] = bid;
                        if (inital==-1) inital = bid;
                        shouldZoom &= inital == bid;
                    }
                }
            }

            if (shouldZoom) {
                computeZoomCells(biomes, zoomSeed, zoomCells);
            }
        }


        int nonZeroCnt = 0;
        // Domum Ornamentum model data is only needed for sections that actually
        // contain material-textured block entities. Avoid the extra palette and
        // ThreadLocal lookups for every voxel in normal sections.
        final boolean hasDomumMappings = DomumOrnamentumCompat.hasSectionMappings();
        final int[] copycatIds = me.cortex.voxy.commonImpl.compat.CreateCopycatCompat.activeSectionIds();
        final int[] framedBlockIds = me.cortex.voxy.commonImpl.compat.FramedBlocksCompat.activeSectionIds();
        final long[] littleTilesHolders = me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.activeHolders();
        final boolean hasVariantMappings = hasDomumMappings || copycatIds != null || framedBlockIds != null || littleTilesHolders != null;
        if (blockContainer.getClass() != PalettedContainer.class) {
            return convertCustomContainer(section, stateMapper, blockContainer, lightSupplier, biomes,
                    hasDomumMappings, copycatIds, framedBlockIds, littleTilesHolders);
        }

        var blockData = ((AccessorPalettedContainer<BlockState>) (Object) blockContainer).voxy$getData();
        var blockDataAccessor = (AccessorPalettedContainerData<BlockState>) (Object) blockData;
        var vp = blockDataAccessor.voxy$getPalette();
        var pc = cache.getPaletteCache(vp.getSize());
        GlobalPalette<BlockState> bps = null;

        int pcc;
        if (vp instanceof GlobalPalette<BlockState> _bps) {
            bps = _bps;
            pcc = bps.getSize();
        } else {
            pcc = setupLocalPalette(vp, blockCache, stateMapper, pc);
            pcc = Math.max(0,pcc-1);
        }
        var blockStorage = blockDataAccessor.voxy$getStorage();
        if (blockStorage instanceof SimpleBitStorage bStor) {
            var bDat = bStor.getRaw();
            int iterPerLong = (64 / bStor.getBits()) - 1;

            int MSK = (1 << bStor.getBits()) - 1;
            int eBits = bStor.getBits();

            long sample = 0;
            int c = 0;
            int dec = 0;
            for (int i = 0; i <= 0xFFF; i++) {
                if (dec-- == 0) {
                    sample = bDat[c++];
                    dec = iterPerLong;
                }
                int paletteIndex = (int) (sample & MSK);
                int bId;
                BlockState voxelState;
                if (bps == null) {
                    int clampedPaletteIndex = Math.min(paletteIndex, pcc);
                    bId = pc[clampedPaletteIndex];
                    voxelState = null;
                    if (hasVariantMappings) {
                        try { voxelState = vp.valueFor(clampedPaletteIndex); } catch (Throwable ignored) {}
                    }
                } else {
                    voxelState = bps.valueFor(paletteIndex);
                    bId = stateMapper.getIdForBlockState(voxelState);
                }
                if (hasVariantMappings && voxelState != null) {
                    if (hasDomumMappings) {
                        bId = DomumOrnamentumCompat.mapBlockId(
                                stateMapper, voxelState, bId, i);
                    }
                    if (copycatIds != null) { int m = copycatIds[i]; if (m != 0) bId = m; }
                    if (framedBlockIds != null) { int m = framedBlockIds[i]; if (m != 0) bId = m; }
                    if (littleTilesHolders != null && (littleTilesHolders[i >>> 6] & (1L << (i & 63))) != 0) bId = 0;
                }
                sample >>>= eBits;

                byte light = lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF);
                nonZeroCnt += (bId != 0)?1:0;
                data[i] = Mapper.composeMappingId(light, bId, biomes[Integer.compress(i,0b1100_1100_1100)]);
            }
        } else {
            if (!(blockStorage instanceof ZeroBitStorage)) {
                throw new IllegalStateException();
            }
            int bId = pc[0];
            if (bId == 0) {//Its air
                for (int i = 0; i <= 0xFFF; i++) {
                    data[i] = Mapper.airWithLight(lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF));
                }
            } else {
                BlockState voxelState = null;
                if (hasVariantMappings) {
                    try { voxelState = vp.valueFor(0); } catch (Throwable ignored) {}
                }
                for (int i = 0; i <= 0xFFF; i++) {
                    byte light = lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF);
                    int mappedBlockId = bId;
                    if (hasVariantMappings && voxelState != null) {
                        if (hasDomumMappings) {
                            mappedBlockId = DomumOrnamentumCompat.mapBlockId(
                                    stateMapper, voxelState, mappedBlockId, i);
                        }
                        if (copycatIds != null) { int m = copycatIds[i]; if (m != 0) mappedBlockId = m; }
                        if (framedBlockIds != null) { int m = framedBlockIds[i]; if (m != 0) mappedBlockId = m; }
                        if (littleTilesHolders != null && (littleTilesHolders[i >>> 6] & (1L << (i & 63))) != 0) mappedBlockId = 0;
                    }
                    nonZeroCnt += mappedBlockId != 0 ? 1 : 0;
                    data[i] = Mapper.composeMappingId(light, mappedBlockId, biomes[Integer.compress(i,0b1100_1100_1100)]);
                }
            }
        }
        section.lvl0NonAirCount = nonZeroCnt;
        return section;
    }

    private static VoxelizedSection convertCustomContainer(
            VoxelizedSection section, Mapper stateMapper, PalettedContainer<BlockState> blockContainer,
            ILightingSupplier lightSupplier, int[] biomes, boolean hasDomumMappings,
            int[] copycatIds, int[] framedBlockIds, long[] littleTilesHolders) {
        int nonZeroCnt = 0;
        long[] data = section.section;
        for (int i = 0; i <= 0xFFF; i++) {
            int x = i & 0xF;
            int y = (i >> 8) & 0xF;
            int z = (i >> 4) & 0xF;
            BlockState state = blockContainer.get(x, y, z);
            int blockId = state == null ? 0 : stateMapper.getIdForBlockState(state);
            if (state != null) {
                if (hasDomumMappings) {
                    blockId = DomumOrnamentumCompat.mapBlockId(stateMapper, state, blockId, i);
                }
                if (copycatIds != null && copycatIds[i] != 0) blockId = copycatIds[i];
                if (framedBlockIds != null && framedBlockIds[i] != 0) blockId = framedBlockIds[i];
                if (littleTilesHolders != null
                        && (littleTilesHolders[i >>> 6] & (1L << (i & 63))) != 0) {
                    blockId = 0;
                }
            }
            byte light = lightSupplier.supply(x, y, z);
            nonZeroCnt += blockId != 0 ? 1 : 0;
            data[i] = Mapper.composeMappingId(light, blockId, biomes[Integer.compress(i, 0b1100_1100_1100)]);
        }
        section.lvl0NonAirCount = nonZeroCnt;
        return section;
    }


    private static void computeZoomCells(int[] biomes, long zoomSeed, long[] zoomInfo) {
        for (int cy = 0; cy<4; cy++) {
            for (int cz = 0; cz<4; cz++) {
                for (int cx = 0; cx<4; cx++) {

                }
            }
        }
    }

    //Support for other mods etc that use this entry point
    @Deprecated(forRemoval = true)
    public static void mipSection(VoxelizedSection section, Mapper mapper) {
        WorldVoxilizedSectionMipper.mipSection(section, mapper);
    }
}
