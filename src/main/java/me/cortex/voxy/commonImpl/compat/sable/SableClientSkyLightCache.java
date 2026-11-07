package me.cortex.voxy.commonImpl.compat.sable;

import me.cortex.voxy.common.Logger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class SableClientSkyLightCache {
    private static final long SKY_LIGHT_TTL_TICKS = 400L;
    private static final long SWEEP_INTERVAL_TICKS = 20L;
    private static final int MAX_CACHED_SECTIONS = 4096;

    private static final Map<ClientLevel, CacheState> CACHES = new WeakHashMap<>();
    private static long revisionSequence;
    private static boolean unavailable;

    private SableClientSkyLightCache() {
    }

    public static synchronized void cacheFromPacket(ClientLevel level, ClientboundLevelChunkWithLightPacket packet) {
        if (unavailable) {
            return;
        }

        try {
            PacketLightData lightData = PacketLightData.from(level, packet.getLightData());
            if (lightData == null || !lightData.hasSkyLight()) {
                return;
            }

            CacheState state = CACHES.computeIfAbsent(level, ignored -> new CacheState());
            int chunkX = packet.getX();
            int chunkZ = packet.getZ();
            int minSection = level.getMinSection();
            int sectionCount = level.getSectionsCount();
            long gameTime = level.getGameTime();
            boolean cachedAny = false;

            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                DataLayer skyLight = lightData.skyLight(sectionIndex);
                if (skyLight == null) {
                    continue;
                }

                state.skyLightSections.put(
                        SectionPos.asLong(chunkX, minSection + sectionIndex, chunkZ),
                        new CachedSkyLight(skyLight, gameTime)
                );
                cachedAny = true;
            }

            if (cachedAny) {
                state.revision = ++revisionSequence;
            }
            enforceMaxSize(state);
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Sable sky light packet cache after packet light read failed", e);
            unavailable = true;
            CACHES.clear();
        }
    }

    public static synchronized int getSkyLight(ClientLevel level, BlockPos pos) {
        CacheState state = CACHES.get(level);
        if (state == null || state.skyLightSections.isEmpty()) {
            return -1;
        }

        int sectionY = SectionPos.blockToSectionCoord(pos.getY());
        if (sectionY < level.getMinSection() || sectionY >= level.getMaxSection()) {
            return -1;
        }

        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        long sectionKey = SectionPos.asLong(chunkX, sectionY, chunkZ);
        CachedSkyLight cached = state.skyLightSections.get(sectionKey);
        if (cached == null) {
            return -1;
        }

        long gameTime = level.getGameTime();
        if (gameTime - cached.gameTime > SKY_LIGHT_TTL_TICKS) {
            state.skyLightSections.remove(sectionKey);
            state.revision = ++revisionSequence;
            return -1;
        }

        if (cached.skyLight.isEmpty()) {
            return 0;
        }

        return Math.min(15, cached.skyLight.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15));
    }

    public static synchronized long revision(ClientLevel level) {
        CacheState state = CACHES.get(level);
        return state == null ? 0L : state.revision;
    }

    public static synchronized void tick(ClientLevel level) {
        try {
            CacheState state = CACHES.get(level);
            if (state == null) {
                return;
            }

            long gameTime = level.getGameTime();
            if (gameTime < state.nextSweepGameTime) {
                return;
            }

            state.nextSweepGameTime = gameTime + SWEEP_INTERVAL_TICKS;
            Iterator<Map.Entry<Long, CachedSkyLight>> iterator = state.skyLightSections.entrySet().iterator();
            boolean removedAny = false;
            while (iterator.hasNext()) {
                CachedSkyLight cached = iterator.next().getValue();
                if (cached == null || gameTime - cached.gameTime > SKY_LIGHT_TTL_TICKS) {
                    iterator.remove();
                    removedAny = true;
                }
            }

            if (removedAny) {
                state.revision = ++revisionSequence;
            }
            if (state.skyLightSections.isEmpty()) {
                CACHES.remove(level);
            }
        } catch (RuntimeException e) {
            Logger.error("Clearing Sable sky light packet cache after sweep failed", e);
            CACHES.remove(level);
        }
    }

    public static synchronized void clear(ClientLevel level) {
        CACHES.remove(level);
    }

    private static void enforceMaxSize(CacheState state) {
        Iterator<Long> iterator = state.skyLightSections.keySet().iterator();
        boolean removed = false;
        while (state.skyLightSections.size() > MAX_CACHED_SECTIONS && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
            removed = true;
        }
        if (removed) state.revision = ++revisionSequence;
    }

    private static final class CacheState {
        private final Map<Long, CachedSkyLight> skyLightSections = new HashMap<>();
        private long nextSweepGameTime;
        private long revision;
    }

    private record CachedSkyLight(DataLayer skyLight, long gameTime) {
    }

    private record PacketLightData(DataLayer[] skyLight) {
        private static @Nullable PacketLightData from(ClientLevel level, ClientboundLightUpdatePacketData lightData) {
            DataLayer[] skyLight = new DataLayer[level.getSectionsCount()];
            boolean hasSkyLight = unpackLayer(
                    level,
                    lightData.getSkyYMask(),
                    lightData.getEmptySkyYMask(),
                    lightData.getSkyUpdates(),
                    skyLight
            );

            if (!hasSkyLight) {
                return null;
            }

            return new PacketLightData(skyLight);
        }

        private static boolean unpackLayer(ClientLevel level, BitSet dataMask, BitSet emptyMask, List<byte[]> updates, DataLayer[] target) {
            LevelLightEngine lightEngine = level.getLightEngine();
            int minLightSection = lightEngine.getMinLightSection();
            int lightSectionCount = lightEngine.getLightSectionCount();
            int minChunkSection = level.getMinSection();
            boolean foundData = false;
            Iterator<byte[]> iterator = updates.iterator();

            for (int lightSectionIndex = 0; lightSectionIndex < lightSectionCount; lightSectionIndex++) {
                int sectionY = minLightSection + lightSectionIndex;
                boolean hasData = dataMask.get(lightSectionIndex);
                boolean empty = emptyMask.get(lightSectionIndex);
                if (!hasData && !empty) {
                    continue;
                }

                DataLayer layer;
                if (hasData) {
                    if (!iterator.hasNext()) {
                        return foundData;
                    }
                    layer = new DataLayer(iterator.next().clone());
                } else {
                    layer = new DataLayer();
                }

                int sectionIndex = sectionY - minChunkSection;
                if (sectionIndex < 0 || sectionIndex >= target.length) {
                    continue;
                }

                target[sectionIndex] = layer;
                foundData = true;
            }

            return foundData;
        }

        private @Nullable DataLayer skyLight(int sectionIndex) {
            return this.skyLight[sectionIndex];
        }

        private boolean hasSkyLight() {
            for (DataLayer layer : this.skyLight) {
                if (layer != null) {
                    return true;
                }
            }
            return false;
        }
    }
}
