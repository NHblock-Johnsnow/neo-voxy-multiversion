package me.cortex.voxy.common.world.other;

/** 原始数据的一步表层视图；旧格式区域保持原值，禁止把派生值再次向下传递。 */
public final class SurfaceVoxelResolver {
    public static final int BELOW_KNOWN = 1;
    public static final int CURRENT_KNOWN = 2;
    public static final int ABOVE_KNOWN = 4;

    private SurfaceVoxelResolver() { }

    public static long resolve(long below, long current, long above, int knownMask) {
        if ((knownMask & CURRENT_KNOWN) == 0) return current;
        if (Mapper.isSurfaceCarrier(current)) {
            return (knownMask & BELOW_KNOWN) != 0 && isSupport(below)
                    ? Mapper.clearSurfaceCarrier(current) : Mapper.restoreSurfaceCarrier(current);
        }
        if ((knownMask & ABOVE_KNOWN) != 0 && isSupport(current) && Mapper.isSurfaceCarrier(above)) {
            return Mapper.applySurfaceCarrier(current, above);
        }
        return current;
    }

    private static boolean isSupport(long voxel) {
        return !Mapper.isAir(voxel) && !Mapper.isSurfaceCarrier(voxel);
    }
}
