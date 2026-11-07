package me.cortex.voxy.commonImpl.compat.littletiles;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LittleTilesCompat {
    public static final boolean ENABLED = ModList.get().isLoaded("littletiles");
    private static final int RESOLUTION = 8;
    private static final ThreadLocal<long[]> ACTIVE_HOLDERS = new ThreadLocal<>();
    private static volatile Access access;
    private static volatile boolean reflectionFailed;

    public record Material(BlockState state, int color) {}
    public record Cell(int coordinate, int material, int light) {}
    public record SectionSnapshot(int sx, int sy, int sz, List<Material> materials,
                                  List<Cell> cells, long[] holders) {
        public boolean hasHolder(int localIndex) {
            return (holders[localIndex >>> 6] & (1L << (localIndex & 63))) != 0;
        }
    }

    public static final class CapturedChunk {
        private final Map<Integer, SectionSnapshot> sections;
        private CapturedChunk(Map<Integer, SectionSnapshot> sections) { this.sections = sections; }
        public SectionSnapshot section(int y) {
            return sections.get(y);
        }
    }

    private LittleTilesCompat() {}

    public static CapturedChunk capture(LevelChunk chunk) {
        if (!ENABLED || chunk == null || reflectionFailed) return null;
        var grouped = new HashMap<Integer, Builder>();
        try {
            Access a = access();
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (!a.beClass.isInstance(be)) continue;
                int sectionY = be.getBlockPos().getY() >> 4;
                var builder = grouped.computeIfAbsent(sectionY,
                        y -> new Builder(chunk.getPos().x, y, chunk.getPos().z));
                builder.capture(a, be);
            }
            var result = new HashMap<Integer, SectionSnapshot>();
            for (var entry : grouped.entrySet()) result.put(entry.getKey(), entry.getValue().finish());
            return new CapturedChunk(result);
        } catch (Throwable t) {
            reflectionFailed = true;
            Logger.error("LittleTiles compatibility disabled: unsupported LittleTiles API", t);
            return null;
        }
    }

    public static void beginSection(SectionStorage storage, SectionSnapshot snapshot, LevelChunkSection section,
                                    int sx, int sy, int sz) {
        if (!ENABLED) return;
        boolean restoring = false;
        if (snapshot == null) {
            if (section != null && section.maybeHas(LittleTilesCompat::isLittleTilesState)) {
                SectionSnapshot stored = LittleTilesStore.load(storage, sx, sy, sz);
                if (stored != null) {
                    snapshot = stored;
                    restoring = true;
                } else {
                    ACTIVE_HOLDERS.remove();
                    return;
                }
            }
            if (snapshot == null) {
                snapshot = new SectionSnapshot(sx, sy, sz, List.of(), List.of(), new long[64]);
            }
        }
        SectionSnapshot positioned = snapshot.sx() == sx && snapshot.sy() == sy && snapshot.sz() == sz
                ? snapshot : new SectionSnapshot(sx, sy, sz, snapshot.materials(), snapshot.cells(), snapshot.holders());
        var original = positioned;
        positioned = retainPresentHolders(positioned, section);
        if (positioned.cells().isEmpty()) ACTIVE_HOLDERS.remove();
        else ACTIVE_HOLDERS.set(positioned.holders());
        if (!restoring || positioned != original) LittleTilesStore.save(storage, positioned);
        publish(storage, positioned);
    }

    private static SectionSnapshot retainPresentHolders(SectionSnapshot snapshot, LevelChunkSection section) {
        if (section == null) return snapshot;
        long[] retained = null;
        boolean any = false;
        // 只访问 holder 位图中的坐标；全部有效时复用快照，不复制 cells。
        for (int word = 0; word < snapshot.holders().length; word++) {
            long bits = snapshot.holders()[word];
            while (bits != 0) {
                int bit = Long.numberOfTrailingZeros(bits);
                int index = (word << 6) | bit;
                if (isLittleTilesState(section.getBlockState(index & 15, index >>> 8, (index >>> 4) & 15))) {
                    any = true;
                } else {
                    if (retained == null) retained = snapshot.holders().clone();
                    retained[word] &= ~(1L << bit);
                }
                bits &= bits - 1;
            }
        }
        if (retained == null) return snapshot;
        if (!any) return new SectionSnapshot(snapshot.sx(), snapshot.sy(), snapshot.sz(),
                List.of(), List.of(), retained);
        var cells = new ArrayList<Cell>();
        for (Cell cell : snapshot.cells()) {
            int coordinate = cell.coordinate();
            int index = ((coordinate >>> 3) & 15) | (((coordinate >>> 10) & 15) << 4)
                    | (((coordinate >>> 17) & 15) << 8);
            if ((retained[index >>> 6] & (1L << (index & 63))) != 0) cells.add(cell);
        }
        return new SectionSnapshot(snapshot.sx(), snapshot.sy(), snapshot.sz(),
                snapshot.materials(), List.copyOf(cells), retained);
    }

    private static void publish(SectionStorage storage, SectionSnapshot snapshot) {
        if (FMLEnvironment.dist.isClient()) {
            try {
                me.cortex.voxy.client.compat.littletiles.LittleTilesDistantRenderer.accept(storage, snapshot);
            } catch (Throwable t) {
                Logger.error("Publishing LittleTiles LOD section", t);
            }
        }
    }

    private static boolean isLittleTilesState(BlockState state) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null || !"littletiles".equals(id.getNamespace())) return false;
        return switch (id.getPath()) {
            case "tiles", "tiles_ticking", "tiles_rendered", "tiles_ticking_rendered" -> true;
            default -> false;
        };
    }

    public static void endSection() {
        if (ENABLED) ACTIVE_HOLDERS.remove();
    }

    public static long[] activeHolders() {
        return ENABLED ? ACTIVE_HOLDERS.get() : null;
    }

    private static Access access() throws Exception {
        Access a = access;
        if (a != null) return a;
        synchronized (LittleTilesCompat.class) {
            if (access == null) access = new Access();
            return access;
        }
    }

    private static final class Access {
        final Class<?> beClass = Class.forName("team.creative.littletiles.common.block.entity.BETiles");
        final Method getGrid = beClass.getMethod("getGrid");
        final Method allTiles = beClass.getMethod("allTiles");
        final Field gridCount;
        final Field pairValue;
        final Method tileState;
        final Field tileColor;
        final Field minX, minY, minZ, maxX, maxY, maxZ;

        Access() throws Exception {
            Class<?> grid = Class.forName("team.creative.littletiles.common.grid.LittleGrid");
            gridCount = grid.getField("count");
            Class<?> pair = Class.forName("team.creative.creativecore.common.util.type.list.Pair");
            pairValue = pair.getField("value");
            Class<?> tile = Class.forName("team.creative.littletiles.common.block.little.tile.LittleTile");
            tileState = tile.getMethod("getState");
            tileColor = tile.getField("color");
            Class<?> box = Class.forName("team.creative.littletiles.common.math.box.LittleBox");
            minX = box.getField("minX"); minY = box.getField("minY"); minZ = box.getField("minZ");
            maxX = box.getField("maxX"); maxY = box.getField("maxY"); maxZ = box.getField("maxZ");
        }
    }

    private static final class Builder {
        final int sx, sy, sz;
        final long[] holders = new long[64];
        final Map<Material, Integer> palette = new HashMap<>();
        final List<Material> materials = new ArrayList<>();
        final Map<Integer, Cell> cells = new HashMap<>();

        Builder(int sx, int sy, int sz) { this.sx = sx; this.sy = sy; this.sz = sz; }

        void capture(Access a, BlockEntity be) throws Exception {
            int bx = be.getBlockPos().getX() & 15;
            int by = be.getBlockPos().getY() & 15;
            int bz = be.getBlockPos().getZ() & 15;
            int holderIndex = bx | (bz << 4) | (by << 8);
            holders[holderIndex >>> 6] |= 1L << (holderIndex & 63);
            Object grid = a.getGrid.invoke(be);
            int count = Math.max(1, a.gridCount.getInt(grid));
            int sky = be.getLevel() == null ? 15 : be.getLevel().getBrightness(net.minecraft.world.level.LightLayer.SKY, be.getBlockPos());
            int block = be.getLevel() == null ? 0 : be.getLevel().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, be.getBlockPos());
            int light = (Math.min(15, sky) << 4) | Math.min(15, block);
            for (Object pair : (Iterable<?>) a.allTiles.invoke(be)) {
                Object tile = a.pairValue.get(pair);
                Material material = new Material((BlockState) a.tileState.invoke(tile), a.tileColor.getInt(tile));
                int materialId = palette.computeIfAbsent(material, value -> {
                    materials.add(value);
                    return materials.size() - 1;
                });
                for (Object box : (Iterable<?>) tile) {
                    int x0 = quantizeMin(a.minX.getInt(box), count), y0 = quantizeMin(a.minY.getInt(box), count), z0 = quantizeMin(a.minZ.getInt(box), count);
                    int x1 = quantizeMax(a.maxX.getInt(box), count), y1 = quantizeMax(a.maxY.getInt(box), count), z1 = quantizeMax(a.maxZ.getInt(box), count);
                    for (int y = y0; y < y1; y++) for (int z = z0; z < z1; z++) for (int x = x0; x < x1; x++) {
                        int gx = bx * RESOLUTION + x, gy = by * RESOLUTION + y, gz = bz * RESOLUTION + z;
                        int coordinate = gx | (gz << 7) | (gy << 14);
                        cells.put(coordinate, new Cell(coordinate, materialId, light));
                    }
                }
            }
        }

        SectionSnapshot finish() {
            var ordered = new ArrayList<>(cells.values());
            ordered.sort(java.util.Comparator.comparingInt(Cell::coordinate));
            return new SectionSnapshot(sx, sy, sz, List.copyOf(materials), List.copyOf(ordered), Arrays.copyOf(holders, holders.length));
        }

        private static int quantizeMin(int value, int count) {
            return Math.clamp((value * RESOLUTION) / count, 0, RESOLUTION - 1);
        }
        private static int quantizeMax(int value, int count) {
            return Math.clamp((value * RESOLUTION + count - 1) / count, 1, RESOLUTION);
        }
    }
}
