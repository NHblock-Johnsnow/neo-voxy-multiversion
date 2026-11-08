package me.cortex.voxy.commonImpl.compat.sable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.electronwill.nightconfig.toml.TomlParser;
import me.cortex.voxy.common.Logger;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class SableContraptionRenderDistance {
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("voxy-config.json");
    private static final Path SABLE_COMMON_CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("sable-common.toml");
    private static final int CONFIG_REFRESH_TICKS = 20;
    private static final int CHUNKS_PER_SECTION_RENDER_DISTANCE = 32;
    private static final int BLOCKS_PER_CHUNK = 16;
    private static final double DEFAULT_SECTION_RENDER_DISTANCE = 16.0;
    private static final int DEFAULT_MAX_CHUNKS = 0;
    private static final double DEDICATED_SERVER_FALLBACK_BLOCKS = 2048.0;

    private static final ConfigSnapshot DISABLED_CONFIG = new ConfigSnapshot(false, 0.0, DEFAULT_MAX_CHUNKS, false, 32, 4);

    private static ConfigSnapshot cachedConfig = DISABLED_CONFIG;
    private static volatile ConfigSnapshot runtimeClientConfig;
    private static volatile ServerLoadConfig serverLoadConfig = new ServerLoadConfig(false, 32, 4);
    private static long lastConfigRefreshTick = Long.MIN_VALUE;
    private static String cachedConfigSource;
    private static boolean configLoaded;
    private static double cachedDedicatedServerRangeBlocks = DEDICATED_SERVER_FALLBACK_BLOCKS;
    private static String cachedDedicatedServerConfigSource;
    private static boolean dedicatedServerConfigLoaded;
    private static long lastDedicatedServerConfigRefreshTick = Long.MIN_VALUE;

    private SableContraptionRenderDistance() {
    }

    public static double getRangeBlocks(ServerLevel level) {
        if (level.getServer().isDedicatedServer()) {
            return getDedicatedServerRangeBlocks(level.getGameTime());
        }

        ConfigSnapshot config = runtimeClientConfig;
        if (config == null) {
            config = getConfig(level.getGameTime());
        }
        if (!config.enabled()) {
            return 0.0;
        }

        int vanillaRenderDistanceChunks = getVanillaRenderDistanceChunks(level);
        int contraptionDistanceChunks = resolveRenderDistanceChunks(
                vanillaRenderDistanceChunks,
                config.sectionRenderDistance(),
                config.aeronauticsContraptionMaxChunks()
        );
        return contraptionDistanceChunks * BLOCKS_PER_CHUNK;
    }

    public static int resolveRenderDistanceChunks(int vanillaRenderDistanceChunks, double sectionRenderDistance, int aeronauticsContraptionMaxChunks) {
        int vanillaDistanceChunks = Math.max(0, vanillaRenderDistanceChunks);
        if (!Double.isFinite(sectionRenderDistance) || sectionRenderDistance <= 0.0) {
            return vanillaDistanceChunks;
        }

        int lodDistanceChunks = (int) Math.ceil(sectionRenderDistance * CHUNKS_PER_SECTION_RENDER_DISTANCE);
        int maxChunks = Math.max(0, aeronauticsContraptionMaxChunks);
        int configuredDistanceChunks = maxChunks == 0 ? lodDistanceChunks : Math.min(maxChunks, lodDistanceChunks);
        return Math.max(vanillaDistanceChunks, configuredDistanceChunks);
    }

    private static int getVanillaRenderDistanceChunks(ServerLevel level) {
        return Math.max(0, level.getServer().getPlayerList().getViewDistance());
    }

    public static void updateClientConfig(boolean enabled, double sectionRenderDistance, int aeronauticsContraptionMaxChunks,
                                          boolean sableLodForceLoading, int sableLodLoadDistanceChunks, int sableLodLoadBudget) {
        if (!enabled || !Double.isFinite(sectionRenderDistance) || sectionRenderDistance <= 0.0) {
            runtimeClientConfig = new ConfigSnapshot(false, 0.0, aeronauticsContraptionMaxChunks, false,
                    clampLoadDistance(sableLodLoadDistanceChunks), clampLoadBudget(sableLodLoadBudget));
            return;
        }

        runtimeClientConfig = new ConfigSnapshot(true, sectionRenderDistance, aeronauticsContraptionMaxChunks,
                sableLodForceLoading, clampLoadDistance(sableLodLoadDistanceChunks), clampLoadBudget(sableLodLoadBudget));
    }

    public static void updateServerConfig(boolean forceLoading, int loadDistanceChunks, int loadBudget) {
        serverLoadConfig = new ServerLoadConfig(forceLoading, clampLoadDistance(loadDistanceChunks), clampLoadBudget(loadBudget));
    }

    public record LoadPolicy(double rangeBlocks, int requestsPerTick) {
    }

    public static LoadPolicy getLoadPolicy(ServerLevel level) {
        boolean forceLoading;
        int distanceChunks;
        int budget;
        if (level.getServer().isDedicatedServer()) {
            ServerLoadConfig config = serverLoadConfig;
            forceLoading = config.forceLoading();
            distanceChunks = config.distanceChunks();
            budget = config.budget();
        } else {
            ConfigSnapshot config = runtimeClientConfig;
            if (config == null) config = getConfig(level.getGameTime());
            forceLoading = config.enabled() && config.forceLoading();
            distanceChunks = config.loadDistanceChunks();
            budget = config.loadBudget();
        }
        return new LoadPolicy(forceLoading ? Math.min(getRangeBlocks(level), distanceChunks * 16.0) : 0.0, budget);
    }

    private static int clampLoadDistance(int chunks) {
        return Math.max(8, Math.min(192, chunks));
    }

    private static int clampLoadBudget(int budget) {
        return Math.max(1, Math.min(64, budget));
    }

    private static double getDedicatedServerRangeBlocks(long gameTime) {
        if (lastDedicatedServerConfigRefreshTick != Long.MIN_VALUE
                && gameTime >= lastDedicatedServerConfigRefreshTick
                && gameTime - lastDedicatedServerConfigRefreshTick < CONFIG_REFRESH_TICKS) {
            return cachedDedicatedServerRangeBlocks;
        }
        lastDedicatedServerConfigRefreshTick = gameTime;

        try {
            String source = Files.exists(SABLE_COMMON_CONFIG_PATH) ? Files.readString(SABLE_COMMON_CONFIG_PATH) : null;
            if (!dedicatedServerConfigLoaded || !Objects.equals(cachedDedicatedServerConfigSource, source)) {
                cachedDedicatedServerRangeBlocks = loadDedicatedServerRangeBlocks(source);
                cachedDedicatedServerConfigSource = source;
                dedicatedServerConfigLoaded = true;
            }
        } catch (IOException e) {
            return DEDICATED_SERVER_FALLBACK_BLOCKS;
        }
        return cachedDedicatedServerRangeBlocks;
    }

    private static double loadDedicatedServerRangeBlocks(String source) {
        if (source == null) {
            return DEDICATED_SERVER_FALLBACK_BLOCKS;
        }

        try {
            Object value = new TomlParser().parse(new StringReader(source)).get("sub_level_tracking_range");
            if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
                return Math.max(0.0, number.doubleValue());
            }
        } catch (RuntimeException e) {
            Logger.error("Failed to load Sable sub_level_tracking_range for Voxy Sable LOD compatibility", e);
        }

        return DEDICATED_SERVER_FALLBACK_BLOCKS;
    }

    private static ConfigSnapshot getConfig(long gameTime) {
        if (lastConfigRefreshTick != Long.MIN_VALUE && gameTime >= lastConfigRefreshTick
                && gameTime - lastConfigRefreshTick < CONFIG_REFRESH_TICKS) {
            return cachedConfig;
        }
        lastConfigRefreshTick = gameTime;

        try {
            // 每秒读取一次小配置，仅内容改变时解析；保留 mtime 的外部编辑也能生效。
            String source = Files.exists(CONFIG_PATH) ? Files.readString(CONFIG_PATH) : null;
            if (!configLoaded || !Objects.equals(cachedConfigSource, source)) {
                cachedConfig = loadConfig(source);
                cachedConfigSource = source;
                configLoaded = true;
            }
        } catch (IOException e) {
            Logger.error("Failed to stat Voxy config for Sable simulated contraption render distance", e);
            return cachedConfig;
        }
        return cachedConfig;
    }

    private static ConfigSnapshot loadConfig(String source) {
        if (source == null) {
            return new ConfigSnapshot(true, DEFAULT_SECTION_RENDER_DISTANCE, DEFAULT_MAX_CHUNKS, false, 32, 4);
        }

        try {
            JsonObject root = JsonParser.parseString(source).getAsJsonObject();
            boolean enabled = getBoolean(root, "enabled", true) && getBoolean(root, "enable_rendering", true)
                    && getBoolean(root, "sable_lod_rendering", true);
            double sectionRenderDistance = getDouble(root, "section_render_distance", DEFAULT_SECTION_RENDER_DISTANCE);
            int aeronauticsContraptionMaxChunks = getInt(root, "aeronautics_contraption_max_chunks", DEFAULT_MAX_CHUNKS);
            boolean forceLoading = getBoolean(root, "sable_lod_force_loading", false);
            int loadDistance = clampLoadDistance(getInt(root, "sable_lod_load_distance_chunks", 32));
            int loadBudget = clampLoadBudget(getInt(root, "sable_lod_load_budget", 4));

            if (!enabled || !Double.isFinite(sectionRenderDistance) || sectionRenderDistance <= 0.0) {
                return new ConfigSnapshot(false, 0.0, aeronauticsContraptionMaxChunks, false, loadDistance, loadBudget);
            }

            return new ConfigSnapshot(true, sectionRenderDistance, aeronauticsContraptionMaxChunks, forceLoading, loadDistance, loadBudget);
        } catch (Exception e) {
            Logger.error("Failed to load Voxy config for Sable simulated contraption render distance", e);
            return DISABLED_CONFIG;
        }
    }

    private static boolean getBoolean(JsonObject root, String key, boolean fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        return root.get(key).getAsBoolean();
    }

    private static double getDouble(JsonObject root, String key, double fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        return root.get(key).getAsDouble();
    }

    private static int getInt(JsonObject root, String key, int fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        return root.get(key).getAsInt();
    }

    private record ConfigSnapshot(
            boolean enabled,
            double sectionRenderDistance,
            int aeronauticsContraptionMaxChunks,
            boolean forceLoading,
            int loadDistanceChunks,
            int loadBudget
    ) {
    }

    private record ServerLoadConfig(boolean forceLoading, int distanceChunks, int budget) {
    }
}
