package me.cortex.voxy.common.world;

import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.SurfaceVoxelResolver;

import java.util.Arrays;
import java.util.Objects;

/** 建面任务共用的原始体素视图；只获取实际采样的邻段，不修改存储。 */
public final class SurfaceWorldView implements AutoCloseable {
    // 横向各一段；纵向多留一段，供邻面和季节暴露判定读取上下原始值。
    private static final int CENTER = 22;
    private final WorldSection[] sections = new WorldSection[45];
    private final boolean[] checked = new boolean[45];
    private WorldEngine world;
    private WorldSection origin;
    private long[] scratch;

    public SurfaceWorldView open(WorldEngine world, WorldSection origin) {
        if (this.world != null) throw new IllegalStateException("Surface view already open");
        Objects.requireNonNull(world);
        Objects.requireNonNull(origin);
        world.requestSurfaceMigration(origin);
        this.world = world;
        this.origin = origin;
        this.sections[CENTER] = origin; // 主段由调用方持有，close 不归还它。
        this.checked[CENTER] = true;
        return this;
    }

    public WorldSection origin() {
        if (this.world == null) throw new IllegalStateException("Surface view closed");
        return this.origin;
    }

    public WorldSection sectionAt(int dx, int dy, int dz) {
        var origin = this.origin();
        if (dx < -1 || dx > 1 || dz < -1 || dz > 1 || dy < -2 || dy > 2) {
            throw new IndexOutOfBoundsException("Surface neighborhood: " + dx + "," + dy + "," + dz);
        }
        int slot = dx + 1 + (dz + 1) * 3 + (dy + 2) * 9;
        if (!this.checked[slot]) {
            var section = this.world.acquireIfExists(origin.lvl, origin.x + dx, origin.y + dy, origin.z + dz);
            this.sections[slot] = section;
            this.checked[slot] = true; // 缺失也缓存；采样循环不重复查询存储。
        }
        return this.sections[slot];
    }

    private static long raw(WorldSection section, int index) {
        return section.data[index];
    }

    private static long[] dense(WorldSection section) {
        return section.data;
    }

    /** 坐标相对主段，可读取相邻面和边带。缺失区域保持未知，不作为实体支撑。 */
    public long get(int x, int y, int z) {
        var section = this.sectionAt(x >> 5, y >> 5, z >> 5);
        if (section == null) return 0;
        int index = (x & 31) | ((z & 31) << 5) | ((y & 31) << 10);
        return this.resolve(section, index, raw(section, index));
    }

    private long resolve(WorldSection section, int index, long current) {
        if (section.lvl == 0 || !section.getSurfaceData().isKnownVoxel(index)) return current;
        boolean carrier = Mapper.isSurfaceCarrier(current);
        if (!carrier && Mapper.isAir(current)) return current;

        int otherIndex = index + (carrier ? -1024 : 1024);
        WorldSection other = section;
        if (otherIndex < 0 || otherIndex >= 32768) {
            other = this.sectionAt(section.x - this.origin.x,
                    section.y - this.origin.y + (carrier ? -1 : 1), section.z - this.origin.z);
            otherIndex &= 32767;
        }
        int mask = SurfaceVoxelResolver.CURRENT_KNOWN;
        long adjacent = 0;
        if (other != null && other.getSurfaceData().isKnownVoxel(otherIndex)) {
            adjacent = raw(other, otherIndex);
            mask |= carrier ? SurfaceVoxelResolver.BELOW_KNOWN : SurfaceVoxelResolver.ABOVE_KNOWN;
        }
        return carrier ? SurfaceVoxelResolver.resolve(adjacent, current, 0, mask)
                : SurfaceVoxelResolver.resolve(0, current, adjacent, mask);
    }

    /** 旧格式 dense 直接复用；有派生变化才复制。均匀段使用 worker 私有缓冲。 */
    public long[] sectionData() {
        var section = this.origin();
        long[] data = dense(section);
        boolean resolve = section.lvl != 0 && section.getSurfaceData().mode() != 0;
        if (data != null && !resolve) return data;
        long[] output = data;
        if (data == null) {
            if (this.scratch == null) this.scratch = new long[32768];
            Arrays.fill(this.scratch, raw(section, 0));
            output = this.scratch;
        }
        if (resolve) {
            for (int index = 0; index < 32768; index++) {
                long value = data == null ? raw(section, 0) : data[index];
                long resolved = this.resolve(section, index, value);
                if (resolved == value) continue;
                if (output == data) {
                    if (this.scratch == null) this.scratch = new long[32768];
                    System.arraycopy(data, 0, this.scratch, 0, 32768);
                    output = this.scratch;
                }
                output[index] = resolved;
            }
        }
        return output;
    }

    /** 连续边带优先整行复制；只有原始格式区域逐点解析，均匀段不物化。 */
    public void copyRow(int x, int y, int z, long[] output, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, output.length);
        while (length != 0) {
            int count = Math.min(length, 32 - (x & 31));
            var section = this.sectionAt(x >> 5, y >> 5, z >> 5);
            if (section == null) {
                Arrays.fill(output, offset, offset + count, 0);
            } else {
                int index = (x & 31) | ((z & 31) << 5) | ((y & 31) << 10);
                long[] data = dense(section);
                boolean resolve = section.lvl != 0 && section.getSurfaceData().mode() != 0;
                if (!resolve) {
                    if (data == null) Arrays.fill(output, offset, offset + count, raw(section, index));
                    else System.arraycopy(data, index, output, offset, count);
                } else {
                    for (int i = 0; i < count; i++) {
                        output[offset + i] = this.resolve(section, index + i, raw(section, index + i));
                    }
                }
            }
            x += count;
            offset += count;
            length -= count;
        }
    }

    /** 只解析所需的 1024 点邻面；返回 false 表示整面来源缺失。 */
    public boolean copyFace(int face, long[] output, int offset, long missing) {
        int dx = face == 0 ? -1 : face == 1 ? 1 : 0;
        int dy = face == 2 ? -1 : face == 3 ? 1 : 0;
        int dz = face == 4 ? -1 : face == 5 ? 1 : 0;
        if (face < 0 || face >= 6) throw new IllegalArgumentException("Face: " + face);
        var section = this.sectionAt(dx, dy, dz);
        if (section == null) {
            Arrays.fill(output, offset, offset + 1024, missing);
            return false;
        }
        long[] data = dense(section);
        boolean resolve = section.lvl != 0 && section.getSurfaceData().mode() != 0;
        if (data == null && !resolve) {
            Arrays.fill(output, offset, offset + 1024, raw(section, 0));
            return true;
        }
        for (int i = 0; i < 1024; i++) {
            int horizontal = i & 31, vertical = i >> 5;
            int x = dx == 0 ? horizontal : dx < 0 ? 31 : 0;
            int y = dy == 0 ? vertical : dy < 0 ? 31 : 0;
            int z = dz == 0 ? dx == 0 ? vertical : horizontal : dz < 0 ? 31 : 0;
            int index = x | (z << 5) | (y << 10);
            long value = data == null ? raw(section, index) : data[index];
            output[offset + i] = resolve ? this.resolve(section, index, value) : value;
        }
        return true;
    }

    @Override
    public void close() {
        Throwable failure = null;
        for (int i = 0; i < this.sections.length; i++) {
            var section = this.sections[i];
            this.sections[i] = null;
            this.checked[i] = false;
            if (section == null || i == CENTER) continue;
            try {
                section.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        this.world = null;
        this.origin = null;
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
}
