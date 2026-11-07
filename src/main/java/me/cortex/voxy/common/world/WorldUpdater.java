package me.cortex.voxy.common.world;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;

import static me.cortex.voxy.common.world.WorldEngine.*;

public class WorldUpdater {
    private static final long COVERAGE_CHANGED = 1L << 14;

    /** 将一个底层体素区写入存储，并把必要的变化向上冒泡到所有 LOD 层。 */
    public static void insertUpdate(WorldEngine into, VoxelizedSection section) {
        // Mine in Abyss 的世界坐标需要先映射到有限的存储范围。
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (section.x+512)>>10;
            section.setPosition(section.x-(sector<<10), section.y+16+(256-32-sector*30), section.z);//Note sector size mult is 30 because the top chunk is replicated (and so is bottom chunk)
        }

        if (!into.isLive) throw new IllegalStateException("World is not live");
        var base = into.acquire(0, section.x >> 1, section.y >> 1, section.z >> 1);
        try {
            // 同一底层区段的写入与父层传播保持顺序，避免季节刷新被迟到的旧 mip 覆盖。
            synchronized (base) {
                insertUpdateLocked(into, section, base);
            }
        } finally {
            base.release();
        }
    }

    private static void insertUpdateLocked(WorldEngine into, VoxelizedSection section, WorldSection base) {
        WorldSection previousSection = null;
        try {
            for (int lvl = 0; lvl <= MAX_LOD_LAYER; lvl++) {
                var worldSection = lvl == 0 ? base : into.acquire(lvl,
                        section.x >> (lvl + 1), section.y >> (lvl + 1), section.z >> (lvl + 1));
                boolean retain = false;
                try {
                    synchronized (worldSection) {
                        int emptinessStateChange = 0;
                        if (previousSection != null) {
                            emptinessStateChange = worldSection.updateEmptyChildState(previousSection);
                            var previous = previousSection;
                            previousSection = null;
                            if (previous != base) previous.release();
                        }

                        long status = insertSectionLvlIntoWorld(into, section, worldSection);
                        boolean coverageChanged = (status & COVERAGE_CHANGED) != 0;
                        boolean didStateChange = (status & 1) != 0 || (lvl != 0 && coverageChanged);
                        int nonAirCount = (int) ((status >> 1) & 0x1FFF);
                        if (lvl == 0) {
                            int delta = section.lvl0NonAirCount - nonAirCount;
                            if (delta != 0) {
                                worldSection.addNonEmptyBlockCount(delta);
                                emptinessStateChange = worldSection.updateLvl0State() ? 2 : 0;
                            }
                        }

                        if (didStateChange || emptinessStateChange != 0 || coverageChanged) {
                            int neighbors = didStateChange ? neighborMask(section, lvl) : 0;
                            into.markDirty(worldSection, (didStateChange ? UPDATE_TYPE_BLOCK_BIT : 0)
                                    | (emptinessStateChange != 0 ? UPDATE_TYPE_CHILD_EXISTENCE_BIT : 0), neighbors);
                        }
                        // 每层都核对当前子段，保证中断后的相同输入能补全父层状态。
                        if (lvl < MAX_LOD_LAYER) {
                            previousSection = worldSection;
                            retain = true;
                        }
                    }
                } finally {
                    if (worldSection != base && !retain) worldSection.release();
                }
            }
        } finally {
            if (previousSection != null && previousSection != base) previousSection.release();
        }
    }

    private static int neighborMask(VoxelizedSection section, int level) {
        int shift = level + 1;
        int neighbors = 0;
        neighbors |= ((section.y ^ (section.y - 1)) >> shift) == 0 ? 0 : 1;
        neighbors |= ((section.y ^ (section.y + 1)) >> shift) == 0 ? 0 : 2;
        neighbors |= ((section.x ^ (section.x - 1)) >> shift) == 0 ? 0 : 4;
        neighbors |= ((section.x ^ (section.x + 1)) >> shift) == 0 ? 0 : 8;
        neighbors |= ((section.z ^ (section.z - 1)) >> shift) == 0 ? 0 : 16;
        neighbors |= ((section.z ^ (section.z + 1)) >> shift) == 0 ? 0 : 32;
        int size = 16 >> level;
        int x = (section.x & ((1 << shift) - 1)) * size;
        int z = (section.z & ((1 << shift) - 1)) * size;
        if (level != 0) {
            int y = (section.y & ((1 << shift) - 1)) * size;
            neighbors &= ~3;
            if (y <= 1) neighbors |= 1;
            if (y + size >= 31) neighbors |= 2;
            // 下方季节边带还读取解析后的上方证据，依赖原值的前两行。
            if (y <= 1) neighbors |= 1 << 18;
            if (y + size == 32) neighbors |= 1 << 19;
        }
        // 高位按 -X/+X/-Z/+Z 保存距边界 1..7 的距离；0 表示超出最大采样半径。
        neighbors |= (x < 7 ? x + 1 : 0) << 6;
        neighbors |= (x + size > 25 ? 33 - x - size : 0) << 9;
        neighbors |= (z < 7 ? z + 1 : 0) << 12;
        neighbors |= (z + size > 25 ? 33 - z - size : 0) << 15;
        return neighbors;
    }

    public static int horizontalHaloMask(int neighborMask, int radius) {
        int mask = neighborMask & 60;
        for (int side = 0; side < 4; side++) {
            int distance = (neighborMask >>> (6 + side * 3)) & 7;
            if (distance != 0 && distance <= radius) mask |= 4 << side;
        }
        return mask;
    }

    /** 调用方持有区段锁；低位为体素变化，bits1..13 为原非空气数，bit14 为来源变化。 */
    private static long insertSectionLvlIntoWorld(WorldEngine into, VoxelizedSection section, WorldSection worldSection) {
        final long[] vdat = section.section;
        final int lvl = worldSection.lvl;
        final int msk = (1 << (lvl + 1)) - 1;
        final int bx = (section.x & msk) << (4 - lvl);
        final int by = (section.y & msk) << (4 - lvl);
        final int bz = (section.z & msk) << (4 - lvl);
        int nonAirCount = 0;
        boolean didStateChange = false;
        // 均匀输入相同则不物化数组，coverage 仍须记录并保存。
        if (worldSection._rawOrNull() == null) {
            long uniform = worldSection.getUniformValue();
            int first = VoxelizedSection.getBaseIndexForLevel(lvl);
            int end = first + (4096 >> (lvl * 3));
            boolean same = true;
            for (int i = first; i < end; i++) {
                if (vdat[i] != uniform) { same = false; break; }
            }
            if (same) {
                long status = lvl == 0 && !Mapper.isAir(uniform) ? 4096L << 1 : 0;
                me.cortex.voxy.commonImpl.PerfStats.sectionUniformWriteSkipped.increment();
                return completeInput(section, worldSection, bx | (bz << 5) | (by << 10), status);
            }
        }
        // 按区段的 X/Z/Y 位布局写入，保持底层数组顺序不变。
        {
            var secD = worldSection.materialize();
            int baseSec = bx | (bz << 5) | (by << 10);
            if (lvl == 0) {
                final int secMsk = 0b1100|(0xf << 5) | (0xf << 10);
                final int iSecMsk1 = (~secMsk) + 1;

                int secIdx = 0;

                // i.e. instead of doing 4 consecutive blocks, which would all be in the same cache line
                // do 4 seperate rows so they are in different cache lines, should allow
                // more instruction pipelining (in theory)
                for (int i = 0; i <= 0xFFF; i+=4) {
                    int cSecIdx = secIdx + baseSec;
                    secIdx = (secIdx + iSecMsk1) & secMsk;

                    long oldId0 = secD[cSecIdx+0]; secD[cSecIdx+0] = vdat[i+0];
                    long oldId1 = secD[cSecIdx+1]; secD[cSecIdx+1] = vdat[i+1];
                    long oldId2 = secD[cSecIdx+2]; secD[cSecIdx+2] = vdat[i+2];
                    long oldId3 = secD[cSecIdx+3]; secD[cSecIdx+3] = vdat[i+3];

                    nonAirCount += Mapper.isNotAirInt(oldId0); didStateChange |= vdat[i+0] != oldId0;
                    nonAirCount += Mapper.isNotAirInt(oldId1); didStateChange |= vdat[i+1] != oldId1;
                    nonAirCount += Mapper.isNotAirInt(oldId2); didStateChange |= vdat[i+2] != oldId2;
                    nonAirCount += Mapper.isNotAirInt(oldId3); didStateChange |= vdat[i+3] != oldId3;
                }
            } else {
                int baseVIdx = VoxelizedSection.getBaseIndexForLevel(lvl);

                int secMsk = 0xF >> lvl;
                secMsk |= (secMsk << 5) | (secMsk << 10);
                int iSecMsk1 = (~secMsk) + 1;

                int secIdx = 0;
                for (int i = baseVIdx; i <= (0xFFF >> (lvl * 3)) + baseVIdx; i++) {
                    int cSecIdx = secIdx + baseSec;
                    secIdx = (secIdx + iSecMsk1) & secMsk;
                    long newId = vdat[i];

                    long oldId = secD[cSecIdx];
                    didStateChange |= newId != oldId;
                    secD[cSecIdx] = newId;
                }
            }
        }
        long status = (didStateChange ? 1 : 0) | (Integer.toUnsignedLong(nonAirCount) << 1);
        return completeInput(section, worldSection, bx | (bz << 5) | (by << 10), status);
    }

    // 位图在体素写完后发布；旧区域即使字节相同，获得原始来源后也可能改变邻段表面。
    private static long completeInput(VoxelizedSection input, WorldSection section, int index, long status) {
        if (!section.surfaceData.isKnownVoxel(index)) {
            section.surfaceData = section.lvl == 0 && section.legacySurfaceSource
                    ? SurfaceDataCoverage.raw(0) : section.surfaceData.withKnownInput(input.x, input.y, input.z);
            section.legacySurfaceSource = false;
            status |= COVERAGE_CHANGED;
        }
        return status;
    }

    // 调用方持有 L0 来源锁；新摄取已知的区域不能被迟到的旧缓存迁移覆盖。
    static void migrateInput(WorldEngine into, VoxelizedSection input, WorldSection target) {
        synchronized (target) {
            int mask = (1 << (target.lvl + 1)) - 1;
            int index = WorldSection.getIndex((input.x & mask) << (4 - target.lvl),
                    (input.y & mask) << (4 - target.lvl), (input.z & mask) << (4 - target.lvl));
            if (target.surfaceData.isKnownVoxel(index)) return;
            insertSectionLvlIntoWorld(into, input, target);
            into.markDirty(target, UPDATE_TYPE_BLOCK_BIT, neighborMask(input, target.lvl));
        }
    }
}
