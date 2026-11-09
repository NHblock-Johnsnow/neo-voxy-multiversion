package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.fml.loading.FMLPaths;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** 26.1.2 NeoForge 的持久化配置；保存前统一校验运行时范围。 */
public class VoxyConfig {
    public enum LeafLodMode {
        FAST,
        BALANCED,
        QUALITY
    }

    public static final float MIN_SUBDIVISION_SIZE = 28.0F;
    public static final float MAX_SUBDIVISION_SIZE = 1024.0F;
    public static final int DEFAULT_RENDER_QUALITY_LEVEL = 3;
    private static final float[] RENDER_QUALITY_SIZES = {1024, 768, 512, 256, 123, 64, 28};

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.TRANSIENT)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    // ---- 基础渲染 ------------------------------------------------------

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public float sectionRenderDistance = 16.0F;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount() / 1.5, 1.0);
    public float subDivisionSize = RENDER_QUALITY_SIZES[DEFAULT_RENDER_QUALITY_LEVEL];
    public boolean useEnvironmentalFog = true;
    public int skyFogDistance = 96;
    public float fogIntensity = 1.0F;
    public float fogDensity = 0.0F;
    public int fogDistancePercent = 100;
    public boolean dontUseSodiumBuilderThreads;
    public int renderPressure = 2;
    public String leafLodMode = "balanced";
    public int earthCurveRatio;
    public int biomeBlendRadius = 2;
    public String biomeBlendScope = "water";
    public boolean showJoinMessage = true;
    public boolean upgradeCleanupNoticeShown;
    public boolean enableLodBoundaryFade = true;
    public int lodBoundaryFadeLength = 16;
    public int lodBoundaryInset = 8;
    public String ssaoMode;

    // ---- 值转换与兼容 --------------------------------------------------

    public SSAO.SSAOMode getSSAOMode() {
        if (this.ssaoMode == null) {
            return SSAO.SSAOMode.AUTO;
        }
        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return SSAO.SSAOMode.AUTO;
        }
    }

    public int getRenderPressureLevel() {
        return Math.max(0, Math.min(4, this.renderPressure));
    }

    /** 将旧版细分值映射到设置页使用的七档渲染精度。 */
    public int getRenderQualityLevel() {
        if (!Float.isFinite(this.subDivisionSize) || this.subDivisionSize <= 0.0F) {
            return DEFAULT_RENDER_QUALITY_LEVEL;
        }
        int closest = 0;
        double distance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < RENDER_QUALITY_SIZES.length; i++) {
            double candidate = Math.abs(Math.log(this.subDivisionSize / RENDER_QUALITY_SIZES[i]));
            if (candidate < distance) {
                closest = i;
                distance = candidate;
            }
        }
        return closest;
    }

    /** 将设置页档位转换回兼容旧配置格式的细分值。 */
    public void setRenderQualityLevel(int level) {
        this.subDivisionSize = RENDER_QUALITY_SIZES[Math.clamp(level, 0, RENDER_QUALITY_SIZES.length - 1)];
    }

    public LeafLodMode getLeafLodMode() {
        try {
            return LeafLodMode.valueOf(this.leafLodMode.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return LeafLodMode.BALANCED;
        }
    }

    public void setLeafLodMode(LeafLodMode mode) {
        this.leafLodMode = mode.name().toLowerCase(Locale.ROOT);
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }

    // ---- 文件读写 ------------------------------------------------------

    private static VoxyConfig loadOrCreate() {
        if (!VoxyCommon.isAvailable()) {
            VoxyConfig config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            return config;
        }

        Path path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                VoxyConfig config = GSON.fromJson(reader, VoxyConfig.class);
                if (config != null) {
                    config.save();
                    return config;
                }
                Logger.error("Failed to load voxy config, resetting");
            } catch (IOException exception) {
                Logger.error("Could not load config", exception);
            } catch (JsonParseException exception) {
                Logger.error("Could not parse config", exception);
            }
            Logger.info("Error during config loading, creating new");
        } else {
            Logger.info("Config file doesnt exist, creating new");
        }

        VoxyConfig config = new VoxyConfig();
        config.save();
        return config;
    }

    public void save() {
        this.sanitize();
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavalible");
            return;
        }
        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException exception) {
            Logger.error("Failed to write config file", exception);
        }
    }

    // ---- 范围校验 ------------------------------------------------------

    public void sanitize() {
        this.sectionRenderDistance = Math.clamp(this.sectionRenderDistance, 0.625F, 64.0F);
        this.subDivisionSize = Math.clamp(this.subDivisionSize, MIN_SUBDIVISION_SIZE, MAX_SUBDIVISION_SIZE);
        this.skyFogDistance = Math.clamp(this.skyFogDistance, 0, 1024);
        this.fogIntensity = Math.clamp(this.fogIntensity, 0.0F, 1.0F);
        this.fogDensity = Math.clamp(this.fogDensity, 0.0F, 1.0F);
        this.fogDistancePercent = Math.clamp(this.fogDistancePercent, 5, 200);
        this.lodBoundaryFadeLength = Math.clamp(this.lodBoundaryFadeLength, 8, 64);
        this.lodBoundaryInset = Math.clamp(this.lodBoundaryInset, 8, 32);
        this.biomeBlendRadius = Math.clamp(this.biomeBlendRadius, 0, 7);
        if (!"water".equals(this.biomeBlendScope) && !"water_grass".equals(this.biomeBlendScope)) {
            this.biomeBlendScope = "water";
        }
        this.setLeafLodMode(this.getLeafLodMode());
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }
}
