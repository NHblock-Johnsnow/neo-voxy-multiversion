package me.cortex.voxy.commonImpl.compat.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.mixin.sable.SableSubLevelHoldingChunkMapAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public final class SableLodChunkManager {
    private static final TicketType<ChunkPos> VOXY_SABLE_LOD_TICKET = TicketType.create("voxy_sable_lod", Comparator.comparingLong(ChunkPos::toLong));
    private static final int ANCHOR_TICKET_DISTANCE = 2;
    private static final int FOOTPRINT_TICKET_DISTANCE = 0;

    private static final Map<ServerLevel, LongSet> activeChunkLoads = new WeakHashMap<>();

    private static final class LevelSignature {
        long stateHash;
        long nextForcedRebuildTick;
        LongSet desiredTicking = new LongOpenHashSet();
        LongSet desiredFull = new LongOpenHashSet();
        LongSet desiredHolding = new LongOpenHashSet();
        LongIterator pendingTicking = desiredTicking.iterator();
        LongIterator pendingFull = desiredFull.iterator();
        LongIterator pendingHolding = desiredHolding.iterator();
        int nextRequestType;
        Set<UUID> plannedSubLevels = new HashSet<>();
    }

    private static final long FORCED_REBUILD_INTERVAL_TICKS = 40L;
    private static final Map<ServerLevel, LevelSignature> SIGNATURES = new WeakHashMap<>();

    private static boolean sableUnavailable;

    private SableLodChunkManager() {
    }

    public static void updateTickets(ServerLevel level, LongSet trackedTickingChunks, LongSet trackedFullChunks, LongSet trackedHoldingChunks) {
        if (sableUnavailable) {
            clearTickets(level, trackedTickingChunks, trackedFullChunks, trackedHoldingChunks);
            return;
        }

        try {
            var policy = SableContraptionRenderDistance.getLoadPolicy(level);
            double horizontalRenderDistanceBlocks = policy.rangeBlocks();
            if (horizontalRenderDistanceBlocks <= 0.0) {
                clearTickets(level, trackedTickingChunks, trackedFullChunks, trackedHoldingChunks);
                return;
            }

            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                clearTickets(level, trackedTickingChunks, trackedFullChunks, trackedHoldingChunks);
                return;
            }

            if (level.players().isEmpty()) {
                clearTickets(level, trackedTickingChunks, trackedFullChunks, trackedHoldingChunks);
                return;
            }

            double maxHorizontalDistanceSquared = horizontalRenderDistanceBlocks * horizontalRenderDistanceBlocks;

            LevelSignature signature = SIGNATURES.computeIfAbsent(level, ignored -> new LevelSignature());
            long gameTime = level.getGameTime();
            long stateHash = computeStateHash(level, container, horizontalRenderDistanceBlocks, maxHorizontalDistanceSquared);
            if (stateHash == signature.stateHash && gameTime < signature.nextForcedRebuildTick) {
                applyTargets(level, container.getHoldingChunkMap(), signature, trackedTickingChunks,
                        trackedFullChunks, trackedHoldingChunks, policy.requestsPerTick(), false);
                return;
            }
            signature.stateHash = stateHash;
            signature.nextForcedRebuildTick = gameTime + FORCED_REBUILD_INTERVAL_TICKS;

            LongSet desiredTicking = new LongOpenHashSet();
            LongSet desiredFull = new LongOpenHashSet();
            LongSet desiredHoldingChunks = new LongOpenHashSet();
            Set<UUID> plannedSubLevels = new HashSet<>();

            double nearBlocks = Math.max(2, level.getServer().getPlayerList().getViewDistance()) * 16.0 + 32.0;
            double nearSq = nearBlocks * nearBlocks;

            for (ServerSubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel.isRemoved()) {
                    continue;
                }

                BoundingBox3dc bounds = subLevel.boundingBox();
                if (bounds == null || !isWithinHorizontalDistance(level, bounds, maxHorizontalDistanceSquared)) {
                    continue;
                }
                plannedSubLevels.add(subLevel.getUniqueId());

                if (isWithinHorizontalDistance(level, bounds, nearSq)) {
                    addChunkBounds(level, bounds, desiredTicking, maxHorizontalDistanceSquared);
                    continue;
                }
                addChunkBounds(level, bounds, desiredFull, maxHorizontalDistanceSquared);
                var anchor = subLevel.logicalPose().position();
                ChunkPos anchorChunk = new ChunkPos(Mth.floor(anchor.x()) >> 4, Mth.floor(anchor.z()) >> 4);
                if (isChunkWithinHorizontalDistance(level, anchorChunk, maxHorizontalDistanceSquared)) {
                    desiredTicking.add(anchorChunk.toLong());
                }
            }
            desiredFull.removeAll(desiredTicking);

            var holdingMap = container.getHoldingChunkMap();
            collectHoldingTargets(level, holdingMap, desiredFull, desiredHoldingChunks, plannedSubLevels, maxHorizontalDistanceSquared);
            // Native Sable can move a partially admitted structure back into holding.
            // Its canonical holding entry keeps the plan alive until restore or deletion.
            if (holdingMap != null) {
                for (UUID id : signature.plannedSubLevels) {
                    if (plannedSubLevels.contains(id)) continue;
                    HoldingSubLevel held = holdingMap.getHoldingSubLevel(id);
                    if (held == null) continue;
                    BoundingBox3dc bounds = held.data().bounds();
                    if (bounds != null && isWithinHorizontalDistance(level, bounds, maxHorizontalDistanceSquared)) {
                        addChunkBounds(level, bounds, desiredFull, maxHorizontalDistanceSquared);
                        plannedSubLevels.add(id);
                    }
                }
            }
            //Holding-derived footprint chunks may overlap a live anchor; the anchor tier wins
            desiredFull.removeAll(desiredTicking);

            signature.desiredTicking = desiredTicking;
            signature.desiredFull = desiredFull;
            signature.desiredHolding = desiredHoldingChunks;
            signature.plannedSubLevels = plannedSubLevels;
            signature.pendingTicking = desiredTicking.iterator();
            signature.pendingFull = desiredFull.iterator();
            signature.pendingHolding = desiredHoldingChunks.iterator();
            applyTargets(level, container.getHoldingChunkMap(), signature, trackedTickingChunks,
                    trackedFullChunks, trackedHoldingChunks, policy.requestsPerTick(), true);
        } catch (NoClassDefFoundError e) {
            sableUnavailable = true;
            clearTickets(level, trackedTickingChunks, trackedFullChunks, trackedHoldingChunks);
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Voxy Sable LOD compatibility after direct access failed", e);
            sableUnavailable = true;
            clearTickets(level, trackedTickingChunks, trackedFullChunks, trackedHoldingChunks);
        }
    }

    private static long computeStateHash(ServerLevel level, ServerSubLevelContainer container,
                                         double rangeBlocks, double maxHorizontalDistanceSquared) {
        long h = Double.doubleToLongBits(rangeBlocks);
        for (var player : level.players()) {
            h = h * 0x9E3779B97F4A7C15L + ChunkPos.asLong(Mth.floor(player.getX()) >> 4, Mth.floor(player.getZ()) >> 4);
        }
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }
            var id = subLevel.getUniqueId();
            h = h * 0x9E3779B97F4A7C15L + (id == null ? 0 : id.getLeastSignificantBits() ^ id.getMostSignificantBits());
            BoundingBox3dc bounds = subLevel.boundingBox();
            if (bounds == null || !isWithinHorizontalDistance(level, bounds, maxHorizontalDistanceSquared)) {
                h = h * 31 + 1;
                continue;
            }
            h = h * 31 + ChunkPos.asLong(Mth.floor(bounds.minX() - 1.0) >> 4, Mth.floor(bounds.minZ() - 1.0) >> 4);
            h = h * 31 + ChunkPos.asLong(Mth.floor(bounds.maxX() + 1.0) >> 4, Mth.floor(bounds.maxZ() + 1.0) >> 4);
            var anchor = subLevel.logicalPose().position();
            h = h * 31 + ChunkPos.asLong(Mth.floor(anchor.x()) >> 4, Mth.floor(anchor.z()) >> 4);
        }
        h = h * 31 + SableHoldingChunkIndexSavedData.getOrLoad(level).revision();
        var holdingMap = container.getHoldingChunkMap();
        if (holdingMap != null) {
            var loaded = ((SableSubLevelHoldingChunkMapAccessor) holdingMap).voxy$getLoadedHoldingChunks();
            h = h * 31 + (loaded == null ? 0 : loaded.size());
        }
        return h;
    }

    public static void clearTickets(ServerLevel level, LongSet trackedTickingChunks, LongSet trackedFullChunks, LongSet trackedHoldingChunks) {
        activeChunkLoads.remove(level);
        SIGNATURES.remove(level);
        clearTicketSet(level, trackedTickingChunks, ANCHOR_TICKET_DISTANCE);
        clearTicketSet(level, trackedFullChunks, FOOTPRINT_TICKET_DISTANCE);
        clearHoldingChunkLoads(level, trackedHoldingChunks);
    }

    private static void clearTicketSet(ServerLevel level, LongSet trackedChunks, int ticketDistance) {
        if (trackedChunks.isEmpty()) {
            return;
        }

        LongIterator iterator = trackedChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            ChunkPos chunkPos = new ChunkPos(chunk);
            level.getChunkSource().removeRegionTicket(VOXY_SABLE_LOD_TICKET, chunkPos, ticketDistance, chunkPos);
            iterator.remove();
        }
    }

    // Only admitted ticket centers belong to this footprint. Called on the server thread.
    public static boolean isActiveLodChunk(ServerLevel level, long chunkLong) {
        LongSet activeChunks = activeChunkLoads.get(level);
        return activeChunks != null && activeChunks.contains(chunkLong);
    }

    public static boolean shouldTreatChunkAsLoaded(ServerLevel level, int chunkX, int chunkZ) {
        if (sableUnavailable) {
            return false;
        }

        long chunk = ChunkPos.asLong(chunkX, chunkZ);
        LongSet activeChunks = activeChunkLoads.get(level);
        return activeChunks != null && activeChunks.contains(chunk)
                && level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
    }

    public static boolean isSubLevelAlreadyActive(ServerLevel level, SubLevelData data) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }

        SubLevel byUuid = container.getSubLevel(data.uuid());
        if (byUuid != null && !byUuid.isRemoved()) {
            return true;
        }

        CompoundTag fullTag = data.fullTag();
        if (fullTag == null || !fullTag.contains("plot")) {
            return false;
        }

        CompoundTag plotTag = fullTag.getCompound("plot");
        if (!plotTag.contains("plot_x") || !plotTag.contains("plot_z")) {
            return false;
        }

        SubLevel byPlot = container.getSubLevel(plotTag.getInt("plot_x"), plotTag.getInt("plot_z"));
        return byPlot != null && !byPlot.isRemoved();
    }

    private static void collectHoldingTargets(
            ServerLevel level,
            SubLevelHoldingChunkMap holdingChunkMap,
            LongSet desiredChunks,
            LongSet desiredHoldingChunks,
            Set<UUID> plannedSubLevels,
            double maxHorizontalDistanceSquared) {
        if (holdingChunkMap == null) {
            return;
        }

        LongSet knownHoldingChunks = getKnownHoldingChunks(level, holdingChunkMap);
        Long2ObjectMap<SubLevelHoldingChunk> loadedHoldingChunks = ((SableSubLevelHoldingChunkMapAccessor) holdingChunkMap).voxy$getLoadedHoldingChunks();
        LongIterator iterator = knownHoldingChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            ChunkPos chunkPos = new ChunkPos(chunk);
            if (!isChunkWithinHorizontalDistance(level, chunkPos, maxHorizontalDistanceSquared)) {
                continue;
            }

            desiredHoldingChunks.add(chunk);

            SubLevelHoldingChunk holdingChunk = loadedHoldingChunks == null ? null : loadedHoldingChunks.get(chunk);
            if (holdingChunk == null) {
                continue;
            }

            for (HoldingSubLevel holdingSubLevel : holdingChunk.getLoadedHoldingSubLevels()) {
                BoundingBox3dc bounds = holdingSubLevel.data().bounds();
                if (bounds != null && isWithinHorizontalDistance(level, bounds, maxHorizontalDistanceSquared)) {
                    addChunkBounds(level, bounds, desiredChunks, maxHorizontalDistanceSquared);
                    plannedSubLevels.add(holdingSubLevel.data().uuid());
                }
            }
        }

    }

    private static void applyTargets(ServerLevel level, SubLevelHoldingChunkMap holdingMap, LevelSignature signature,
                                     LongSet trackedTicking, LongSet trackedFull, LongSet trackedHolding, int budget, boolean rebuilt) {
        LongSet active = activeChunkLoads.computeIfAbsent(level, ignored -> new LongOpenHashSet());
        if (rebuilt) {
            removeStaleTickets(level, trackedTicking, signature.desiredTicking, signature.desiredFull,
                    trackedFull, ANCHOR_TICKET_DISTANCE);
            removeStaleTickets(level, trackedFull, signature.desiredFull, signature.desiredTicking,
                    trackedTicking, FOOTPRINT_TICKET_DISTANCE);
            active.clear();
            active.addAll(trackedTicking);
            active.addAll(trackedFull);
            if (holdingMap != null) {
                var loadedHolding = ((SableSubLevelHoldingChunkMapAccessor) holdingMap).voxy$getLoadedHoldingChunks();
                LongIterator iterator = trackedHolding.iterator();
                while (iterator.hasNext()) {
                    long chunk = iterator.nextLong();
                    if (signature.desiredHolding.contains(chunk)
                            && (loadedHolding == null || !loadedHolding.containsKey(chunk))) {
                        iterator.remove();
                    }
                }
            }
        }
        if (holdingMap != null) {
            removeStaleHoldingChunkLoads(level, holdingMap, trackedHolding, signature.desiredHolding);
        }
        int exhaustedTypes = 0;
        while (budget > 0 && exhaustedTypes < 3) {
            int type = signature.nextRequestType;
            signature.nextRequestType = (type + 1) % 3;
            int remaining;
            if (type == 0) {
                remaining = budget;
                if (holdingMap != null) {
                    while (signature.pendingHolding.hasNext()) {
                        long chunk = signature.pendingHolding.nextLong();
                        // Sable already owns keep-loaded state for naturally loaded FULL chunks.
                        if (trackedHolding.contains(chunk) || isNativeFullChunk(level, chunk)) continue;
                        holdingMap.updateChunkStatus(new ChunkPos(chunk), true);
                        trackedHolding.add(chunk);
                        remaining--;
                        break;
                    }
                }
            } else if (type == 1) {
                remaining = addMissingTickets(level, trackedTicking, signature.pendingTicking, active,
                        trackedFull, ANCHOR_TICKET_DISTANCE, budget);
            } else {
                remaining = addMissingTickets(level, trackedFull, signature.pendingFull, active,
                        trackedTicking, FOOTPRINT_TICKET_DISTANCE, budget);
            }
            exhaustedTypes = remaining == budget ? exhaustedTypes + 1 : 0;
            budget = remaining;
        }
    }

    private static boolean isNativeFullChunk(ServerLevel level, long chunk) {
        return level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) != null;
    }

    private static LongSet getKnownHoldingChunks(ServerLevel level, SubLevelHoldingChunkMap holdingChunkMap) {
        LongSet chunks = SableHoldingChunkIndexSavedData.getOrLoad(level).copyChunks();

        Long2ObjectMap<SubLevelHoldingChunk> loadedHoldingChunks = ((SableSubLevelHoldingChunkMapAccessor) holdingChunkMap).voxy$getLoadedHoldingChunks();
        if (loadedHoldingChunks != null) {
            chunks.addAll(loadedHoldingChunks.keySet());
        }

        return chunks;
    }

    private static void removeStaleHoldingChunkLoads(ServerLevel level, SubLevelHoldingChunkMap holdingChunkMap, LongSet trackedHoldingChunks, LongSet desiredHoldingChunks) {
        LongIterator iterator = trackedHoldingChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (desiredHoldingChunks.contains(chunk)) {
                continue;
            }

            ChunkPos chunkPos = new ChunkPos(chunk);
            if (isNativeFullChunk(level, chunk)) {
                continue;
            }

            holdingChunkMap.updateChunkStatus(chunkPos, false);
            iterator.remove();
        }
    }

    private static void clearHoldingChunkLoads(ServerLevel level, LongSet trackedHoldingChunks) {
        if (trackedHoldingChunks.isEmpty()) {
            return;
        }

        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            SubLevelHoldingChunkMap holdingChunkMap = container == null ? null : container.getHoldingChunkMap();
            if (holdingChunkMap != null) {
                LongIterator iterator = trackedHoldingChunks.iterator();
                while (iterator.hasNext()) {
                    long chunk = iterator.nextLong();
                    ChunkPos chunkPos = new ChunkPos(chunk);
                    if (isNativeFullChunk(level, chunk)) {
                        continue;
                    }
                    holdingChunkMap.updateChunkStatus(chunkPos, false);
                    iterator.remove();
                }
            } else {
                trackedHoldingChunks.clear();
            }
        } catch (RuntimeException | LinkageError e) {
            Logger.warn("Failed to release Sable holding chunk loads", e);
        }
    }

    private static int addMissingTickets(ServerLevel level, LongSet trackedChunks, LongIterator iterator,
                                          LongSet active, LongSet otherTierChunks, int ticketDistance, int budget) {
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (trackedChunks.add(chunk)) {
                ChunkPos chunkPos = new ChunkPos(chunk);
                level.getChunkSource().addRegionTicket(VOXY_SABLE_LOD_TICKET, chunkPos, ticketDistance, chunkPos);
                active.add(chunk);
                // Keep the old tier until the replacement has actually been admitted.
                if (otherTierChunks.remove(chunk)) {
                    int oldDistance = ticketDistance == ANCHOR_TICKET_DISTANCE
                            ? FOOTPRINT_TICKET_DISTANCE : ANCHOR_TICKET_DISTANCE;
                    level.getChunkSource().removeRegionTicket(VOXY_SABLE_LOD_TICKET, chunkPos, oldDistance, chunkPos);
                }
                return budget - 1;
            }
        }
        return budget;
    }

    private static void removeStaleTickets(ServerLevel level, LongSet trackedChunks, LongSet desiredChunks,
                                            LongSet otherDesired, LongSet otherTracked, int ticketDistance) {
        LongIterator iterator = trackedChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (!desiredChunks.contains(chunk) && (!otherDesired.contains(chunk) || otherTracked.contains(chunk))) {
                ChunkPos chunkPos = new ChunkPos(chunk);
                level.getChunkSource().removeRegionTicket(VOXY_SABLE_LOD_TICKET, chunkPos, ticketDistance, chunkPos);
                iterator.remove();
            }
        }
    }

    private static void addChunkBounds(ServerLevel level, BoundingBox3dc bounds, LongSet desiredChunks, double maxHorizontalDistanceSquared) {
        int minChunkX = Mth.floor(bounds.minX() - 1.0) >> 4;
        int maxChunkX = Mth.floor(bounds.maxX() + 1.0) >> 4;
        int minChunkZ = Mth.floor(bounds.minZ() - 1.0) >> 4;
        int maxChunkZ = Mth.floor(bounds.maxZ() + 1.0) >> 4;

        if (!(maxHorizontalDistanceSquared >= 0.0)) return;
        double radius = Math.sqrt(maxHorizontalDistanceSquared);
        // 先裁到每位玩家的可见圆，再按列收窄；工作量不再随整艘巨船的面积增长。
        for (var player : level.players()) {
            double playerX = player.getX(), playerZ = player.getZ();
            int firstX = Math.max(minChunkX, Mth.floor(Math.nextDown((playerX - radius) / 16.0)));
            int lastX = Math.min(maxChunkX, Mth.floor(Math.nextUp((playerX + radius) / 16.0)));
            for (int chunkX = firstX; chunkX <= lastX; chunkX++) {
                double dx = distanceToRange(playerX, chunkX * 16.0, chunkX * 16.0 + 16.0);
                double remaining = maxHorizontalDistanceSquared - dx * dx;
                if (remaining < 0.0) continue;
                double span = Math.nextUp(Math.sqrt(remaining));
                int firstZ = Math.max(minChunkZ, Mth.floor(Math.nextDown((playerZ - span) / 16.0)));
                int lastZ = Math.min(maxChunkZ, Mth.floor(Math.nextUp((playerZ + span) / 16.0)));
                for (int chunkZ = firstZ; chunkZ <= lastZ; chunkZ++) {
                    long chunk = ChunkPos.asLong(chunkX, chunkZ);
                    if (desiredChunks.contains(chunk)) continue;
                    double dz = distanceToRange(playerZ, chunkZ * 16.0, chunkZ * 16.0 + 16.0);
                    if (dx * dx + dz * dz <= maxHorizontalDistanceSquared) desiredChunks.add(chunk);
                }
            }
        }
    }

    private static boolean isWithinHorizontalDistance(ServerLevel level, BoundingBox3dc bounds, double maxHorizontalDistanceSquared) {
        double minX = bounds.minX();
        double maxX = bounds.maxX();
        double minZ = bounds.minZ();
        double maxZ = bounds.maxZ();

        for (var player : level.players()) {
            double dx = distanceToRange(player.getX(), minX, maxX);
            double dz = distanceToRange(player.getZ(), minZ, maxZ);
            if ((dx * dx) + (dz * dz) <= maxHorizontalDistanceSquared) {
                return true;
            }
        }

        return false;
    }

    private static boolean isChunkWithinHorizontalDistance(ServerLevel level, ChunkPos chunkPos, double maxHorizontalDistanceSquared) {
        double minX = chunkPos.getMinBlockX();
        double maxX = chunkPos.getMaxBlockX() + 1.0;
        double minZ = chunkPos.getMinBlockZ();
        double maxZ = chunkPos.getMaxBlockZ() + 1.0;

        for (var player : level.players()) {
            double dx = distanceToRange(player.getX(), minX, maxX);
            double dz = distanceToRange(player.getZ(), minZ, maxZ);
            if ((dx * dx) + (dz * dz) <= maxHorizontalDistanceSquared) {
                return true;
            }
        }

        return false;
    }

    private static double distanceToRange(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }

}
