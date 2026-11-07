package me.cortex.voxy.client.core.beacon;

import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.core.BlockPos;

/** Loads a cold beacon column off the render thread, then schedules one cache-only retry. */
public final class BeaconColumnWarmer {
    private static final int MAX_WARMING = 128;
    private record WarmKey(WorldEngine engine, long beacon) {}
    private static final java.util.Set<WarmKey> WARMING = new java.util.HashSet<>();
    private static final java.util.concurrent.ExecutorService WARM_POOL =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(MAX_WARMING), r -> {
                var thread = new Thread(r, "Voxy beacon column warmup");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });

    private BeaconColumnWarmer() {}

    public static void warm(WorldEngine engine, int bx, int by, int bz, int walkTop) {
        long beaconPos = BlockPos.asLong(bx, by, bz);
        var key = new WarmKey(engine, beaconPos);
        synchronized (WARMING) {
            if (WARMING.size() >= MAX_WARMING || !WARMING.add(key)) {
                return;
            }
        }
        try {
            WARM_POOL.execute(() -> warmColumn(key, bx, by, bz, walkTop));
        } catch (RuntimeException exception) {
            synchronized (WARMING) {
                WARMING.remove(key);
            }
        }
    }

    private static void warmColumn(WarmKey key, int bx, int by, int bz, int walkTop) {
        WorldEngine engine = key.engine;
        boolean acquired = false;
        try {
            if (!BeaconBeamTracker.isBound(engine)) return;
            // 排队不阻止世界关闭；实际读盘时才持有引用。
            engine.acquireRef();
            acquired = true;
            if (engine.isLive()) {
                int baseY = (by - 1) >> 5;
                for (int sx = (bx - 1) >> 5; sx <= (bx + 1) >> 5; sx++) {
                    for (int sz = (bz - 1) >> 5; sz <= (bz + 1) >> 5; sz++) {
                        touch(engine, sx, baseY, sz);
                    }
                }
                for (int sy = (by + 1) >> 5; sy <= walkTop >> 5; sy++) {
                    touch(engine, bx >> 5, sy, bz >> 5);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (acquired) engine.releaseRef();
            } finally {
                synchronized (WARMING) {
                    WARMING.remove(key);
                }
            }
            BeaconBeamTracker.queueDirty(engine, key.beacon);
        }
    }

    private static void touch(WorldEngine engine, int sx, int sy, int sz) {
        if (!BeaconBeamTracker.isBound(engine)) return;
        var section = engine.acquireIfExists(0, sx, sy, sz);
        if (section != null) {
            section.release();
        }
    }
}
