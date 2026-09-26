package me.cortex.voxy.common.world;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;

import java.util.Arrays;

/** 用固定材质元数据验证降采样，避免依赖游戏注册表和模组启动。 */
public final class TerrainMipperVerification {
    private static final long STONE = Mapper.composeMappingId((byte) 0x0F, 1, 2);
    private static final long GRASS = Mapper.composeMappingId((byte) 0x0F, 2, 3);
    private static final long WATER = Mapper.composeMappingId((byte) 0x0F, 3, 4);
    private static final long AIR = Mapper.airWithLight(0x0F);
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        var scratchField = Mipper.class.getDeclaredField("SCRATCH");
        scratchField.setAccessible(true);
        var local = (ThreadLocal<?>) scratchField.get(null);
        Object scratch = local.get();
        var metadataField = scratch.getClass().getDeclaredField("metadata");
        metadataField.setAccessible(true);
        byte[] metadata = (byte[]) metadataField.get(scratch);
        metadata[1] = 15;
        metadata[2] = 15;
        metadata[3] = (1 << 4) | (1 << 5);
        try {
            verifyOccupancy();
            verifyThinPlanes();
            verifyFlatTerrain();
            verifyFluidsAndLight();
        } finally {
            local.remove();
        }
        System.out.println("Terrain mip checks: " + checks + ", failures: " + failures);
        if (failures != 0) throw new AssertionError("Terrain mip regressions: " + failures);
    }

    private static void verifyOccupancy() {
        int lostTies = 0;
        for (int mask = 0; mask < 256; mask++) {
            long[] children = new long[8];
            for (int i = 0; i < 8; i++) children[i] = (mask & (1 << i)) != 0 ? STONE : AIR;
            long parent = mip(children);
            boolean occupied = !Mapper.isAir(parent) || Mapper.isSurfaceCarrier(parent);
            if (Integer.bitCount(mask) == 4 && !occupied) lostTies++;
            check(occupied == (Integer.bitCount(mask) >= 4), "occupancy mask=" + mask);
        }
        System.out.println("Lost half-occupied arrangements: " + lostTies + "/70");
    }

    private static void verifyThinPlanes() {
        for (int axis = 0; axis < 3; axis++) {
            for (int position = 0; position < 16; position++) {
                VoxelizedSection section = VoxelizedSection.createEmpty();
                Arrays.fill(section.section, AIR);
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    if ((axis == 0 ? x : axis == 1 ? y : z) == position) {
                        section.section[y * 256 + z * 16 + x] = STONE;
                    }
                }
                WorldVoxilizedSectionMipper.mipSection(section, null);
                for (int level = 1; level <= 4; level++) {
                    int size = 16 >> level;
                    int occupied = 0;
                    for (int y = 0; y < size; y++) for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
                        long voxel = section.get(level, x, y, z);
                        if (!Mapper.isAir(voxel) || Mapper.isSurfaceCarrier(voxel)) occupied++;
                    }
                    check(occupied == size * size, "thin plane axis=" + axis + ", pos=" + position + ", level=" + level);
                }
            }
        }
    }

    private static void verifyFlatTerrain() throws Exception {
        var insert = WorldUpdater.class.getDeclaredMethod("insertSectionLvlIntoWorld",
                WorldEngine.class, VoxelizedSection.class, WorldSection.class);
        insert.setAccessible(true);
        // 真实写入会把悬而未决的表层回填到下方；不能把标记的坐标当作最终地面高度。
        for (int height = 1; height < 16; height++) {
            VoxelizedSection section = VoxelizedSection.createEmpty();
            Arrays.fill(section.section, AIR);
            for (int y = 0; y < height; y++) {
                Arrays.fill(section.section, y * 256, (y + 1) * 256, y == height - 1 ? GRASS : STONE);
            }
            WorldVoxilizedSectionMipper.mipSection(section, null);
            for (int sectionY : new int[]{1, -1, 1201}) {
                section.setPosition(0, sectionY, 0);
                for (int level = 1; level <= 4; level++) {
                    int scale = 1 << level;
                    int size = 16 >> level;
                    WorldSection target = WorldSection._createRawUntrackedUnsafeSection(
                            level, 0, sectionY >> (level + 1), 0);
                    int baseY = (sectionY & ((1 << (level + 1)) - 1)) << (4 - level);
                    long[] data = target.materialize();
                    Arrays.fill(data, AIR);
                    Arrays.fill(data, 0, baseY * 1024, STONE);
                    insert.invoke(null, null, section, target);
                    int top = baseY;
                    for (int y = baseY; y < baseY + size; y++) {
                        if (!Mapper.isAir(target.get(WorldSection.getIndex(0, y, 0)))) top = y + 1;
                    }
                    int actualHeight = (target.y * 32 + top) * scale;
                    int expectedHeight = sectionY * 16 + (height / scale) * scale;
                    check(actualHeight == expectedHeight, "supported terrain height=" + height
                            + ", sectionY=" + sectionY + ", level=" + level);
                    long surface = target.get(WorldSection.getIndex(0, top - 1, 0));
                    check(Mapper.getBlockId(surface) == 2 && Mapper.getBiomeId(surface) == 3,
                            "surface material and biome must survive level=" + level);
                    for (int y = 0; y < baseY + size; y++) {
                        check(!Mapper.isSurfaceCarrier(target.get(WorldSection.getIndex(0, y, 0))),
                                "transient surface marker persisted");
                    }
                    long status = (long) insert.invoke(null, null, section, target);
                    check((status & 1) == 0, "repeated terrain write must be stable");
                }
            }
        }
    }

    private static void verifyFluidsAndLight() {
        check(Mapper.getBlockId(mip(new long[]{WATER, WATER, WATER, WATER, AIR, AIR, AIR, AIR})) == 3,
                "open fluid surface retained");
        check(Mapper.getBlockId(mip(new long[]{WATER, WATER, WATER, WATER, STONE, STONE, STONE, STONE})) == 1,
                "opaque roof over fluid retained");
        check(Mapper.isAir(mip(new long[]{WATER, AIR, AIR, AIR, AIR, AIR, AIR, AIR})),
                "isolated fluid occupancy threshold unchanged");
        long litStone = Mapper.withLight(STONE, 0xA3);
        long carrier = mip(new long[]{litStone, litStone, litStone, litStone, AIR, AIR, AIR, AIR});
        check(Mapper.isSurfaceCarrier(carrier) && Mapper.getLightId(carrier) == 0x5F,
                "surface carrier retains averaged block light and maximum sky light");
        long parent = mip(new long[]{carrier, carrier, carrier, carrier, AIR, AIR, AIR, AIR});
        check(Mapper.isSurfaceCarrier(parent) && Mapper.getLightId(parent) == 0x2F,
                "pending surface participates in the next mip without losing light");
    }

    private static long mip(long[] c) {
        return Mipper.mip(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], null);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            if (failures < 12) System.out.println("FAIL: " + message);
            failures++;
        }
    }
}
