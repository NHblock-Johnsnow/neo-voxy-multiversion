package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.world.level.Level;

public final class DistantLightSampler {
    public static final int FALLBACK = 15; //plain full skylight, block light 0

    private DistantLightSampler() {}

    //Returns packed voxel light (block << 4 | sky), or FALLBACK when no data is stored
    public static int sample(Level level, int x, int y, int z) {
        try {
            var engine = WorldIdentifier.ofEngineNullable(level);
            if (engine == null) {
                return FALLBACK;
            }
            int ay = y + 1;
            for (int lvl = 0; lvl <= 4; lvl++) {
                var section = engine.acquireIfExists(lvl, x >> (5 + lvl), ay >> (5 + lvl), z >> (5 + lvl));
                if (section == null) {
                    continue;
                }
                try {
                    int lx = (x >> lvl) & 31, ly = (ay >> lvl) & 31, lz = (z >> lvl) & 31;
                    //get() never materialises. Note the uniform value may legitimately be 0, which the
                    //check below treats as "no data, try a coarser level" - so it must be returned as-is.
                    long voxel = section.get(lx | (lz << 5) | (ly << 10));
                    if (voxel == 0) {
                        continue; //void, try a coarser level
                    }
                    return Mapper.getLightId(voxel);
                } finally {
                    section.release();
                }
            }
        } catch (Throwable ignored) {
        }
        return FALLBACK;
    }

    /** 单次烘焙内复用区段引用；只在调用线程使用，结束时统一释放。 */
    public static Batch batch(Level level) {
        return new Batch(level);
    }

    public static final class Batch implements AutoCloseable {
        // 一个 16³ 输入加采光高度偏移，每级至多跨两个 32³ 区段。
        private final long[] keys = new long[10];
        private final me.cortex.voxy.common.world.WorldSection[] sections =
                new me.cortex.voxy.common.world.WorldSection[10];
        private me.cortex.voxy.common.world.WorldEngine engine;
        private int size;

        private Batch(Level level) {
            try {
                var engine = WorldIdentifier.ofEngineNullable(level);
                if (engine != null) {
                    engine.acquireRef();
                    this.engine = engine;
                }
            } catch (Throwable ignored) {
            }
        }

        public int sample(int x, int y, int z) {
            var engine = this.engine;
            if (engine == null) return FALLBACK;
            try {
                int ay = y + 1;
                for (int lvl = 0; lvl <= 4; lvl++) {
                    int sx = x >> (5 + lvl), sy = ay >> (5 + lvl), sz = z >> (5 + lvl);
                    long key = me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(lvl, sx, sy, sz);
                    me.cortex.voxy.common.world.WorldSection section = null;
                    for (int i = 0; i < this.size; i++) {
                        if (this.keys[i] == key) {
                            section = this.sections[i];
                            break;
                        }
                    }
                    boolean temporary = false;
                    if (section == null) {
                        section = engine.acquireIfExists(lvl, sx, sy, sz);
                        if (section == null) continue;
                        if (this.size < this.sections.length) {
                            this.keys[this.size] = key;
                            this.sections[this.size++] = section;
                        } else {
                            // 非区段内调用仍可采样，超出容量的引用当次归还。
                            temporary = true;
                        }
                    }
                    try {
                        int lx = (x >> lvl) & 31, ly = (ay >> lvl) & 31, lz = (z >> lvl) & 31;
                        long voxel = section.get(lx | (lz << 5) | (ly << 10));
                        if (voxel != 0) return Mapper.getLightId(voxel);
                    } finally {
                        if (temporary) section.release();
                    }
                }
            } catch (Throwable ignored) {
            }
            return FALLBACK;
        }

        @Override
        public void close() {
            var engine = this.engine;
            this.engine = null;
            var cleanup = new me.cortex.voxy.common.util.ExceptionCleanup(null);
            for (int i = 0; i < this.size; i++) {
                var section = this.sections[i];
                this.sections[i] = null;
                cleanup.run(section::release);
            }
            this.size = 0;
            if (engine != null) cleanup.run(engine::releaseRef);
            cleanup.rethrow();
        }
    }

    // Cache-only variant for render/client-tick callers. Cold samples are warmed on a low-priority
    // background thread instead of blocking the render thread on section storage.
    public static int samplePeek(Level level, int x, int y, int z) {
        return samplePeek(level, x, y, z, null);
    }

    public static int samplePeek(Level level, int x, int y, int z, Runnable onWarm) {
        try {
            var engine = WorldIdentifier.ofEngineNullable(level);
            if (engine == null) {
                return FALLBACK;
            }
            int ay = y + 1;
            boolean cold = false;
            int light = FALLBACK;
            for (int lvl = 0; lvl <= 4; lvl++) {
                var section = engine.acquireIfCached(lvl, x >> (5 + lvl), ay >> (5 + lvl), z >> (5 + lvl));
                if (section == null) {
                    if (lvl == 0) cold = true;
                    continue;
                }
                try {
                    int lx = (x >> lvl) & 31, ly = (ay >> lvl) & 31, lz = (z >> lvl) & 31;
                    long voxel = section.get(lx | (lz << 5) | (ly << 10));
                    if (voxel != 0) {
                        light = Mapper.getLightId(voxel);
                        break;
                    }
                } finally {
                    section.release();
                }
            }
            // 粗层光照可立即使用，细层冷缓存仍需异步补载。
            if (cold) warmUp(engine, x >> 5, ay >> 5, z >> 5, onWarm);
            return light;
        } catch (Throwable ignored) {
        }
        return FALLBACK;
    }

    private static final int MAX_WARMING = 128;
    private record WarmKey(me.cortex.voxy.common.world.WorldEngine engine, long section) {}
    private static final java.util.Map<WarmKey, java.util.Set<Runnable>> WARMING = new java.util.HashMap<>();
    private static final java.util.concurrent.ExecutorService WARM_POOL =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(MAX_WARMING), r -> {
                var thread = new Thread(r, "Voxy distant light warmup");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });

    private static void warmUp(me.cortex.voxy.common.world.WorldEngine engine, int sx, int sy, int sz, Runnable onWarm) {
        var key = new WarmKey(engine, me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(0, sx, sy, sz));
        boolean full;
        synchronized (WARMING) {
            var callbacks = WARMING.get(key);
            if (callbacks != null) {
                if (onWarm != null) callbacks.add(onWarm);
                return;
            }
            full = WARMING.size() >= MAX_WARMING;
            if (!full) {
                callbacks = new java.util.HashSet<>();
                if (onWarm != null) callbacks.add(onWarm);
                WARMING.put(key, callbacks);
            }
        }
        if (full) {
            if (onWarm != null) onWarm.run();
            return;
        }
        try {
            WARM_POOL.execute(() -> {
                boolean acquired = false;
                boolean loaded = false;
                try {
                    // 排队任务不占世界引用，只有实际读取期间阻止世界关闭。
                    engine.acquireRef();
                    acquired = true;
                    var section = engine.acquireIfExists(key.section);
                    if (section != null) {
                        section.release();
                        loaded = true;
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        if (acquired) engine.releaseRef();
                    } finally {
                        java.util.Set<Runnable> callbacks;
                        synchronized (WARMING) {
                            callbacks = WARMING.remove(key);
                        }
                        if (loaded && callbacks != null) {
                            for (Runnable callback : callbacks) {
                                try { callback.run(); } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            });
        } catch (RuntimeException exception) {
            synchronized (WARMING) {
                WARMING.remove(key);
            }
        }
    }

    public static int sky(int packed) {
        return packed & 0xF;
    }

    public static int block(int packed) {
        return (packed >> 4) & 0xF;
    }
}
