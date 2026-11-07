package me.cortex.voxy.common.voxelization;

import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;
import me.cortex.voxy.common.world.other.SurfaceVoxelResolver;

public class WorldVoxilizedSectionMipper {
    private static int G(int x, int y, int z) {
        return ((y<<8)|(z<<4)|x);
    }

    private static int H(int x, int y, int z) {
        return ((y<<6)|(z<<3)|x) + 16*16*16;
    }

    private static int I(int x, int y, int z) {
        return ((y<<4)|(z<<2)|x) + 8*8*8 + 16*16*16;
    }

    private static int J(int x, int y, int z) {
        return ((y<<2)|(z<<1)|x) + 4*4*4 + 8*8*8 + 16*16*16;
    }

    private static void resolveSurfaceCarriers(long[] data, long[] raw, int base, int size) {
        int offset = base - 4096;
        int layer = size * size;
        System.arraycopy(data, base, raw, offset, layer * size);
        for (int y = 0; y < size; y++) {
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    int index = base + y * layer + z * size + x;
                    int source = index - 4096;
                    long current = raw[source];
                    // 底边 carrier 留给下一层继续选择，不能在未知支撑处提前丢标记。
                    if (y == 0 && Mapper.isSurfaceCarrier(current)) continue;
                    int mask = SurfaceVoxelResolver.CURRENT_KNOWN
                            | (y == 0 ? 0 : SurfaceVoxelResolver.BELOW_KNOWN)
                            | (y + 1 == size ? 0 : SurfaceVoxelResolver.ABOVE_KNOWN);
                    data[index] = SurfaceVoxelResolver.resolve(y == 0 ? 0 : raw[source - layer], current,
                            y + 1 == size ? 0 : raw[source + layer], mask);
                }
            }
        }
    }

    public static void mipSection(VoxelizedSection section, Mapper mapper) {
        var data = section.section;
        var raw = section.mipScratch();

        //Mip L1
        int i = 0;
        int MSK = 0b1110_1110_1110;
        int iMSK1 = (~MSK)+1;
        int q = 0;
        while (true) {
            data[16*16*16 + i++] = Mipper.mip(
                    data[q|G(0,0,0)], data[q|G(1,0,0)], data[q|G(0,0,1)], data[q|G(1,0,1)],
                    data[q|G(0,1,0)], data[q|G(1,1,0)], data[q|G(0,1,1)], data[q|G(1,1,1)],
                    mapper
            );
            if (q == MSK)
                break;
            q = (q+iMSK1)&MSK;
        }
        resolveSurfaceCarriers(data, raw, 16*16*16, 8);

        //Mip L2
        i = 0;
        for (int y = 0; y < 8; y+=2) {
            for (int z = 0; z < 8; z += 2) {
                for (int x = 0; x < 8; x += 2) {
                    data[16*16*16 + 8*8*8 + i++] =
                            Mipper.mip(
                                    data[H(x, y, z)],       data[H(x+1, y, z)],       data[H(x, y, z+1)],      data[H(x+1, y, z+1)],
                                    data[H(x, y+1, z)],  data[H(x+1, y+1, z)],  data[H(x, y+1, z+1)], data[H(x+1, y+1, z+1)],
                                    mapper);
                }
            }
        }
        resolveSurfaceCarriers(data, raw, 16*16*16 + 8*8*8, 4);

        //Mip L3
        i = 0;
        for (int y = 0; y < 4; y+=2) {
            for (int z = 0; z < 4; z += 2) {
                for (int x = 0; x < 4; x += 2) {
                    data[16*16*16 + 8*8*8 + 4*4*4 + i++] =
                            Mipper.mip(
                                    data[I(x, y, z)],       data[I(x+1, y, z)],       data[I(x, y, z+1)],      data[I(x+1, y, z+1)],
                                    data[I(x, y+1, z)],   data[I(x+1, y+1, z)],  data[I(x, y+1, z+1)], data[I(x+1, y+1, z+1)],
                                    mapper);
                }
            }
        }
        resolveSurfaceCarriers(data, raw, 16*16*16 + 8*8*8 + 4*4*4, 2);

        //Mip L4
        data[16*16*16 + 8*8*8 + 4*4*4 + 2*2*2] =
                Mipper.mip(
                        data[J(0, 0, 0)], data[J(1, 0, 0)], data[J(0, 0, 1)], data[J(1, 0, 1)],
                        data[J(0, 1, 0)], data[J(1, 1, 0)], data[J(0, 1, 1)], data[J(1, 1, 1)],
                        mapper);
        // 持久化各级未解析原值；上面的临时视图仅用于保留原有局部 mip 高度规则。
        System.arraycopy(raw, 0, data, 4096, raw.length);
    }
}
