package me.cortex.voxy.common.world.other;

import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

import static me.cortex.voxy.common.world.other.Mapper.withLight;

/** Selects a representative child when an eight-voxel cube becomes one parent voxel. */
public final class Mipper {
    private static final int[] CUBE_INDEX_TO_Y = {0, 0, 0, 0, 1, 1, 1, 1};
    private static final int META_FLUID = 1 << 4;
    private static final int META_PURE_FLUID = 1 << 5;
    private static final int META_TERRAIN_AIR = 1 << 6;
    private static final long BLOCK_ID_MASK = ((1L << 20) - 1L) << 27;
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private Mipper() {
    }

    private static int metadata(Scratch scratch, Mapper mapper, int blockId) {
        if (scratch.mapper != mapper) {
            scratch.mapper = mapper;
            Arrays.fill(scratch.metadata, (byte) -1);
        }
        if (blockId >= scratch.metadata.length) {
            int oldLength = scratch.metadata.length;
            int newLength = oldLength;
            while (newLength <= blockId) newLength <<= 1;
            scratch.metadata = Arrays.copyOf(scratch.metadata, newLength);
            Arrays.fill(scratch.metadata, oldLength, newLength, (byte) -1);
        }
        byte cached = scratch.metadata[blockId];
        if (cached != -1) return Byte.toUnsignedInt(cached);

        var state = mapper.getBlockStateFromBlockId(blockId);
        int value = Math.clamp(mapper.getBlockStateOpacity(blockId), 0, 15);
        if (state.isAir()) value |= META_TERRAIN_AIR;
        if (!state.getFluidState().isEmpty()) value |= META_FLUID;
        if (isPureFluid(state)) value |= META_PURE_FLUID;
        scratch.metadata[blockId] = (byte) value;
        return value;
    }

    private static boolean isPureFluid(BlockState state) {
        if (state.getBlock() instanceof LiquidBlock) {
            return true;
        }
        var fluidState = state.getFluidState();
        return !fluidState.isEmpty() && fluidState.createLegacyBlock().getBlock() == state.getBlock();
    }

    private static long uniformBlock(long i000, long i100, long i001, long i101,
                                     long i010, long i110, long i011, long i111,
                                     Mapper mapper) {
        int l000 = Mapper.getLightId(i000);
        int l100 = Mapper.getLightId(i100);
        int l001 = Mapper.getLightId(i001);
        int l101 = Mapper.getLightId(i101);
        int l010 = Mapper.getLightId(i010);
        int l110 = Mapper.getLightId(i110);
        int l011 = Mapper.getLightId(i011);
        int l111 = Mapper.getLightId(i111);

        int blockLight = ((l000 & 0xF0) + (l100 & 0xF0) + (l001 & 0xF0) + (l101 & 0xF0)
                + (l010 & 0xF0) + (l110 & 0xF0) + (l011 & 0xF0) + (l111 & 0xF0)) / 8 & 0xF0;
        int skyLight = Math.max(Math.max(Math.max(l000 & 0x0F, l100 & 0x0F),
                        Math.max(l001 & 0x0F, l101 & 0x0F)),
                Math.max(Math.max(l010 & 0x0F, l110 & 0x0F),
                        Math.max(l011 & 0x0F, l111 & 0x0F)));

        int blockId = Mapper.getBlockId(i000);
        if (blockId == 0 || (metadata(SCRATCH.get(), mapper, blockId) & META_TERRAIN_AIR) != 0) {
            return withLight(i111, blockLight | skyLight);
        }

        long selected = i000;
        int bestScore = l000;
        if (l100 > bestScore) { selected = i100; bestScore = l100; }
        if (l001 > bestScore) { selected = i001; bestScore = l001; }
        if (l101 > bestScore) { selected = i101; bestScore = l101; }
        int score = 128 + l010;
        if (score > bestScore) { selected = i010; bestScore = score; }
        score = 128 + l110;
        if (score > bestScore) { selected = i110; bestScore = score; }
        score = 128 + l011;
        if (score > bestScore) { selected = i011; bestScore = score; }
        score = 128 + l111;
        if (score > bestScore) selected = i111;
        return selected;
    }

    private static int pickRepresentative(Scratch scratch, int uniqueIndex,
                                          int preferredY, boolean requireFluid) {
        int meta = scratch.uniqueMetadata[uniqueIndex];
        if (requireFluid && (meta & META_FLUID) == 0) return -1;
        int bestIndex = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int index = 0; index < 8; index++) {
            if (scratch.stateUniqueIndex[index] != uniqueIndex) continue;
            int score = CUBE_INDEX_TO_Y[index] == preferredY ? 128 : 0;
            score += (meta & META_PURE_FLUID) != 0 ? 32 : 0;
            score += Mapper.getLightId(scratch.states[index]);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static int downsampleLight(int blockLightSum, int skyLight) {
        return (blockLightSum / 8 & 0xF0) | skyLight;
    }

    public static long mip(long i000, long i100, long i001, long i101,
                           long i010, long i110, long i011, long i111,
                           Mapper mapper) {
        // 边界表层尚未回填，仍需参与下一层取样，不能被空气快捷路径吞掉。
        if (Mapper.isSurfaceCarrier(i000 | i100 | i001 | i101 | i010 | i110 | i011 | i111)) {
            i000 = restorePendingSurface(i000);
            i100 = restorePendingSurface(i100);
            i001 = restorePendingSurface(i001);
            i101 = restorePendingSurface(i101);
            i010 = restorePendingSurface(i010);
            i110 = restorePendingSurface(i110);
            i011 = restorePendingSurface(i011);
            i111 = restorePendingSurface(i111);
        }
        long differingBlockBits = (i000 ^ i100) | (i000 ^ i001) | (i000 ^ i101)
                | (i000 ^ i010) | (i000 ^ i110) | (i000 ^ i011) | (i000 ^ i111);
        if ((differingBlockBits & BLOCK_ID_MASK) == 0L) {
            return uniformBlock(i000, i100, i001, i101, i010, i110, i011, i111, mapper);
        }

        Scratch scratch = SCRATCH.get();
        long[] states = scratch.states;
        states[0] = i000;
        states[1] = i100;
        states[2] = i001;
        states[3] = i101;
        states[4] = i010;
        states[5] = i110;
        states[6] = i011;
        states[7] = i111;

        int uniqueCount = 0;
        int nonAir = 0;
        int fluidLayer = -1;
        int fluidOccupancy = 0;
        int blockLight = 0;
        int skyLight = 0;

        //One classification pass over the eight children - this is the hot path of LOD generation, so
        //do not re-scan them from inside a nested loop.
        for (int index = 0; index < 8; index++) {
            long state = states[index];
            int light = Mapper.getLightId(state);
            blockLight += light & 0xF0;
            skyLight = Math.max(skyLight, light & 0x0F);
            if (Mapper.isAir(state)) {
                scratch.stateUniqueIndex[index] = -1;
                continue;
            }

            int blockId = Mapper.getBlockId(state);
            int stateMetadata = metadata(scratch, mapper, blockId);
            if ((stateMetadata & META_TERRAIN_AIR) != 0) {
                scratch.stateUniqueIndex[index] = -1;
                continue;
            }

            nonAir++;
            int uniqueIndex = 0;
            while (uniqueIndex < uniqueCount && scratch.uniqueBlockIds[uniqueIndex] != blockId) {
                uniqueIndex++;
            }
            if (uniqueIndex == uniqueCount) {
                scratch.uniqueBlockIds[uniqueIndex] = blockId;
                scratch.uniqueCounts[uniqueIndex] = 0;
                scratch.uniqueHighestY[uniqueIndex] = 0;
                scratch.uniqueFluidScores[uniqueIndex] = 0;
                scratch.uniqueMetadata[uniqueIndex] = stateMetadata;
                uniqueCount++;
            }

            scratch.stateUniqueIndex[index] = uniqueIndex;
            scratch.uniqueCounts[uniqueIndex]++;
            int y = CUBE_INDEX_TO_Y[index];
            scratch.uniqueHighestY[uniqueIndex] = Math.max(scratch.uniqueHighestY[uniqueIndex], y);
            if ((scratch.uniqueMetadata[uniqueIndex] & META_FLUID) != 0) {
                fluidLayer = Math.max(fluidLayer, y);
                fluidOccupancy++;
            }
        }

        if (fluidLayer >= 0 && fluidOccupancy >= 2) {
            boolean coveredByOpaque = false;
            int opaqueScore = 0;
            for (int index = 0; index < 8; index++) {
                int uniqueIndex = scratch.stateUniqueIndex[index];
                if (uniqueIndex < 0) continue;
                int y = CUBE_INDEX_TO_Y[index];
                int meta = scratch.uniqueMetadata[uniqueIndex];
                if (y > fluidLayer && (meta & 15) >= 15) {
                    coveredByOpaque = true;
                    break;
                }
                if (y != fluidLayer) continue;
                if ((meta & META_FLUID) != 0) {
                    scratch.uniqueFluidScores[uniqueIndex] +=
                            (meta & META_PURE_FLUID) != 0 ? 8 : 6;
                } else if ((meta & 15) >= 15) {
                    opaqueScore += 6;
                }
            }

            if (!coveredByOpaque) {
                int bestUnique = -1;
                int bestScore = Integer.MIN_VALUE;
                for (int uniqueIndex = 0; uniqueIndex < uniqueCount; uniqueIndex++) {
                    int score = scratch.uniqueFluidScores[uniqueIndex];
                    if (score > bestScore) {
                        bestScore = score;
                        bestUnique = uniqueIndex;
                    }
                }
                if (bestUnique >= 0 && bestScore > 0 && opaqueScore <= bestScore) {
                    int selected = pickRepresentative(scratch, bestUnique, fluidLayer, true);
                    if (selected >= 0) {
                        return withLight(states[selected], downsampleLight(blockLight, skyLight));
                    }
                }
            }
        }

        if (nonAir >= 4) {
            int bestUnique = -1;
            int bestScore = Integer.MIN_VALUE;
            for (int uniqueIndex = 0; uniqueIndex < uniqueCount; uniqueIndex++) {
                int meta = scratch.uniqueMetadata[uniqueIndex];
                int score = (scratch.uniqueCounts[uniqueIndex] << 8)
                        + ((meta & 15) << 3)
                        + (scratch.uniqueHighestY[uniqueIndex] << 2)
                        + ((meta & META_FLUID) != 0 ? 2 : 0)
                        + ((meta & META_PURE_FLUID) != 0 ? 1 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    bestUnique = uniqueIndex;
                }
            }
            if (bestUnique >= 0) {
                int selected = pickRepresentative(
                        scratch, bestUnique, scratch.uniqueHighestY[bestUnique], false);
                if (selected >= 0) {
                    boolean flatSurfaceTie = nonAir == 4
                            && scratch.stateUniqueIndex[0] >= 0
                            && scratch.stateUniqueIndex[1] >= 0
                            && scratch.stateUniqueIndex[2] >= 0
                            && scratch.stateUniqueIndex[3] >= 0
                            && scratch.stateUniqueIndex[4] < 0
                            && scratch.stateUniqueIndex[5] < 0
                            && scratch.stateUniqueIndex[6] < 0
                            && scratch.stateUniqueIndex[7] < 0;
                    long selectedState = withLight(states[selected], downsampleLight(blockLight, skyLight));
                    if (flatSurfaceTie) return Mapper.makeSurfaceCarrier(selectedState);
                    // 平票时保留屋顶和薄墙；仅下半层平面使用表层下移修正。
                    return selectedState;
                }
            }
        }

        // Do not return an arbitrary solid child when minority occupancy rounded
        // to air. Maximum skylight also prevents successive mips turning open air black.
        return withLight(nonAir == 0 ? i111 : 0L, downsampleLight(blockLight, skyLight));
    }

    private static long restorePendingSurface(long voxel) {
        return Mapper.isSurfaceCarrier(voxel) ? Mapper.restoreSurfaceCarrier(voxel) : voxel;
    }

    private static final class Scratch {
        private final long[] states = new long[8];
        private final int[] stateUniqueIndex = new int[8];
        private final int[] uniqueBlockIds = new int[8];
        private final int[] uniqueCounts = new int[8];
        private final int[] uniqueHighestY = new int[8];
        private final int[] uniqueMetadata = new int[8];
        private final int[] uniqueFluidScores = new int[8];
        private byte[] metadata = new byte[256];
        private Mapper mapper;

        private Scratch() {
            Arrays.fill(this.metadata, (byte) -1);
        }
    }
}
