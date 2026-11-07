package me.cortex.voxy.client.core.beacon;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.core.BlockPos;

public final class BeaconBeamTracker {
    //32-block column key of a level-0 section
    private static long columnKey(int sx, int sz) {
        return ((long) sx << 32) | (sz & 0xFFFFFFFFL);
    }

    //column -> packed beacon BlockPos longs. A beacon registers in every column its 3x3 base touches,
    //so a base edge re-ingested from the neighbouring column still lands on it.
    private static volatile Long2ObjectMap<long[]> columnToBeacons = Long2ObjectMaps.emptyMap();

    private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();
    private static final LongOpenHashSet REMOVED = new LongOpenHashSet();
    private static final Object MUTATION_LOCK = new Object();
    private static Object bindingToken;
    private static volatile java.lang.ref.WeakReference<WorldEngine> boundEngine = new java.lang.ref.WeakReference<>(null);

    private BeaconBeamTracker() {
    }

    public static boolean isBound(WorldEngine engine) {
        return engine != null && boundEngine.get() == engine;
    }

    public static boolean isTracked(long pos) {
        long[] beacons = columnToBeacons.get(columnKey(BlockPos.getX(pos) >> 5, BlockPos.getZ(pos) >> 5));
        if (beacons == null) {
            return false;
        }
        for (long beacon : beacons) {
            if (beacon == pos) {
                return true;
            }
        }
        return false;
    }

    //Rebuilds the column map from the engine's index and hooks its membership diff. Render thread,
    //once per engine.
    public static void bind(WorldEngine engine) {
        Object token = new Object();
        engine.getBeaconIndex().initializeListener(new me.cortex.voxy.common.world.other.BeaconIndex.ChangeListener() {
            @Override
            public void onBeaconAdded(int x, int y, int z) {
                synchronized (MUTATION_LOCK) {
                    if (bindingToken != token) return;
                    var next = new Long2ObjectOpenHashMap<>(columnToBeacons);
                    registerColumns(next, x, y, z);
                    columnToBeacons = next;
                    DIRTY.add(BlockPos.asLong(x, y, z));
                }
            }

            @Override
            public void onBeaconRemoved(int x, int y, int z) {
                synchronized (MUTATION_LOCK) {
                    if (bindingToken != token) return;
                    var next = new Long2ObjectOpenHashMap<>(columnToBeacons);
                    unregisterColumns(next, x, y, z);
                    columnToBeacons = next;
                    long pos = BlockPos.asLong(x, y, z);
                    DIRTY.remove(pos);
                    REMOVED.add(pos);
                }
            }
        }, () -> {
            synchronized (MUTATION_LOCK) {
                DIRTY.clear();
                REMOVED.clear();
                var fresh = new Long2ObjectOpenHashMap<long[]>();
                engine.getBeaconIndex().forEach((x, y, z) -> registerColumns(fresh, x, y, z));
                columnToBeacons = fresh;
                bindingToken = token;
                boundEngine = new java.lang.ref.WeakReference<>(engine);
            }
        });
    }

    public static void reset(WorldEngine engine) {
        if (engine != null) {
            engine.getBeaconIndex().setListener(null);
        }
        synchronized (MUTATION_LOCK) {
            if (engine != null && boundEngine.get() != engine) return;
            bindingToken = null;
            boundEngine = new java.lang.ref.WeakReference<>(null);
            DIRTY.clear();
            REMOVED.clear();
            columnToBeacons = Long2ObjectMaps.emptyMap();
        }
    }

    //The ingest-worker filter. One volatile read and one probe for the overwhelmingly common case of a
    //section nowhere near a beacon.
    public static void onSectionDirty(WorldEngine engine, WorldSection section, int flags) {
        if (!isBound(engine) || section.lvl != 0 || (flags & WorldEngine.UPDATE_TYPE_BLOCK_BIT) == 0) {
            return;
        }
        long[] beacons = columnToBeacons.get(columnKey(section.x, section.z));
        if (beacons == null) {
            return;
        }
        for (long pos : beacons) {
            int by = BlockPos.getY(pos);
            //The beam reads from one below the beacon (its base) up to the scan roof
            int minSection = (by - 1) >> 5;
            int maxSection = (by + BeaconBeamSolver.MAX_SCAN_HEIGHT) >> 5;
            if (section.y >= minSection && section.y <= maxSection) {
                synchronized (MUTATION_LOCK) {
                    if (isBound(engine) && isTracked(pos)) DIRTY.add(pos);
                }
            }
        }
    }

    //Queued from anywhere a beam needs a re-solve without a voxel change (first sight, mapper retry)
    public static void queueDirty(WorldEngine engine, long pos) {
        synchronized (MUTATION_LOCK) {
            if (isBound(engine)) DIRTY.add(pos);
        }
    }

    /** 只取本帧能处理的 dirty，剩余项保留 FIFO 顺序，避免全取再放回。 */
    public static void drain(LongArrayList dirtyOut, LongArrayList removedOut, int maxDirty) {
        synchronized (MUTATION_LOCK) {
            for (int i = 0; i < maxDirty && !DIRTY.isEmpty(); i++) {
                dirtyOut.add(DIRTY.removeFirstLong());
            }
            if (!REMOVED.isEmpty()) {
                removedOut.addAll(REMOVED);
                REMOVED.clear();
            }
        }
    }

    private static void registerColumns(Long2ObjectOpenHashMap<long[]> map, int x, int y, int z) {
        long pos = BlockPos.asLong(x, y, z);
        forEachBaseColumn(x, z, key -> {
            long[] existing = map.get(key);
            if (existing == null) {
                map.put(key, new long[]{pos});
                return;
            }
            for (long p : existing) {
                if (p == pos) {
                    return;
                }
            }
            long[] grown = java.util.Arrays.copyOf(existing, existing.length + 1);
            grown[existing.length] = pos;
            map.put(key, grown);
        });
    }

    private static void unregisterColumns(Long2ObjectOpenHashMap<long[]> map, int x, int y, int z) {
        long pos = BlockPos.asLong(x, y, z);
        forEachBaseColumn(x, z, key -> {
            long[] existing = map.get(key);
            if (existing == null) {
                return;
            }
            if (existing.length == 1) {
                if (existing[0] == pos) {
                    map.remove(key);
                }
                return;
            }
            int at = -1;
            for (int i = 0; i < existing.length; i++) {
                if (existing[i] == pos) {
                    at = i;
                    break;
                }
            }
            if (at == -1) {
                return;
            }
            long[] shrunk = new long[existing.length - 1];
            System.arraycopy(existing, 0, shrunk, 0, at);
            System.arraycopy(existing, at + 1, shrunk, at, existing.length - 1 - at);
            map.put(key, shrunk);
        });
    }

    private interface ColumnConsumer {
        void accept(long columnKey);
    }

    private static void forEachBaseColumn(int x, int z, ColumnConsumer consumer) {
        int minCx = (x - 1) >> 5, maxCx = (x + 1) >> 5;
        int minCz = (z - 1) >> 5, maxCz = (z + 1) >> 5;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                consumer.accept(columnKey(cx, cz));
            }
        }
    }
}

