package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainConfig;
import me.cortex.voxy.commonImpl.compat.sable.SableContraptionRenderDistance;
import net.neoforged.fml.loading.FMLPaths;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Voxy 的客户端配置和配置文件生命周期。
 *
 * 字段名称是 JSON 对外格式；设置页可以转换显示方式，但保存时仍使用这些兼容字段。
 */
public class VoxyConfig {
    public enum LeafLodMode {
        FAST,
        BALANCED,
        QUALITY
    }

    // 配置范围和渲染精度档位是设置界面的共享来源。
    public static final int MAX_CLOUD_DISTANCE = 128;
    public static final float MIN_SUBDIVISION_SIZE = 28.0f;
    public static final float MAX_SUBDIVISION_SIZE = 1024.0f;
    public static final int DEFAULT_RENDER_QUALITY_LEVEL = 3;
    private static final float[] RENDER_QUALITY_SIZES = {1024, 768, 512, 256, 123, 64, 28};

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.TRANSIENT)
            .create();

    public static final VoxyConfig CONFIG = loadOrCreate();

    // 基础生命周期和数据采集。
    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public float sectionRenderDistance = 16;
    public boolean sableLodRendering = true;
    public boolean sableLodForceLoading = false;
    public int sableLodLoadDistanceChunks = 32;
    public int sableLodLoadBudget = 4;
    // 可选模组的总开关和距离上限；0 表示使用完整 LOD 半径。
    public int aeronauticsContraptionMaxChunks = 0;
    public boolean distantTrains = true;
    public boolean distantTracks = true;
    public boolean distantContraptions = true;
    public boolean distantBeacons = true;
    public int distantBeaconMaxChunks = 0;
    public boolean distantKinetics = true;
    public boolean kineticEnclosedCulling = true;
    public int distantTrainMaxChunks = 0;
    public int distantTrackMaxChunks = 0;
    public int distantContraptionMaxChunks = 0;
    public int distantKineticMaxChunks = 0;
    public boolean distantPowerGridWires = true;
    public int distantPowerGridWireMaxChunks = 0;
    public boolean distantCopycats = true;
    public int distantCopycatsMaxChunks = 0;
    public boolean distantSimulatedLasers = true;
    public int distantSimulatedLaserMaxChunks = 0;
    public boolean distantFramedBlocks = true;
    public int distantFramedBlocksMaxChunks = 0;
    public boolean distantLittleTiles = true;
    public int distantLittleTilesMaxChunks = 0;
    public boolean distantDomum = true;
    public int distantDomumMaxChunks = 0;
    public int distantContraptionGpuBudgetMiB = 48;
    public int distantKineticGpuBudgetMiB = 32;
    // 画面和资源开销选项。
    public boolean lodLiteShading = false;
    public int biomeBlendRadius = 2;
    public String biomeBlendScope = "water";
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount() / 1.5, 1);
    public float subDivisionSize = RENDER_QUALITY_SIZES[DEFAULT_RENDER_QUALITY_LEVEL];
    public int skyFogDistance = 96;
    public float fogIntensity = 1.0f;
    public float fogDensity = 0.0f;
    public int fogDistancePercent = 100;
    public boolean adaptCloudDistance = true;
    public int cloudDistance = 0;
    public boolean dontUseSodiumBuilderThreads = false;
    public int renderPressure = 2;
    public int lodBoundaryBuffer = 1;
    public boolean enableLodBoundaryFade = true;
    public int lodBoundaryFadeLength = 16;
    public int lodBoundaryInset = 8;
    public int earthCurveRatio = 0;
    public String ssaoMode;
    public boolean useEnvironmentalFog = true;
    public String leafLodMode = "balanced";
    public boolean enableFarPlayerRendering = true;
    public boolean enableFarVehicleRendering = true;
    public boolean renderFarPlayerNames = true;
    public int farPlayerAnimationDistance = 0;
    public boolean shareFarPlayerPosition = true;
    public boolean joinMessageShown = false;
    public boolean upgradeCleanupNoticeShown = false;
    // 实验性选项集中放在文件末段，设置页和配置迁移可统一处理。
    public boolean experimentalHiZCompute = false;
    public boolean experimentalCmdListHold = false;
    public int cmdListHoldMaxFrames = 4;
    public boolean experimentalChunkMaskReuse = false;
    public int sectionArrayPoolMiB = 100;
    public boolean experimentalOpaqueNearFirst = false;
    public boolean experimentalChunkMaskHalfRes = false;

    /** 将 LOD 半径转换为远景实体使用的方块距离。 */
    public int getFarEntityRenderDistanceBlocks() {
        return Math.clamp(Math.round(this.sectionRenderDistance * 32.0f * 16.0f), 64, 32768);
    }

    /** 返回有效的 LOD 构建压力档位，并修复旧配置中的越界值。 */
    public int getRenderPressureLevel() {
        if (this.renderPressure < 0 || this.renderPressure > 4) {
            this.renderPressure = 2;
        }
        return this.renderPressure;
    }

    /** 从旧版浮点细分值反推出最接近的七档渲染精度。 */
    public int getRenderQualityLevel() {
        if (!Float.isFinite(this.subDivisionSize) || this.subDivisionSize <= 0) {
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

    /** 将设置页档位转换回兼容旧配置格式的细分尺寸。 */
    public void setRenderQualityLevel(int level) {
        this.subDivisionSize = RENDER_QUALITY_SIZES[Math.clamp(level, 0, RENDER_QUALITY_SIZES.length - 1)];
    }

    /** 读取树叶 LOD 模式；未知字符串回退到平衡模式。 */
    public LeafLodMode getLeafLodMode() {
        if (this.leafLodMode == null) {
            return LeafLodMode.BALANCED;
        }

        try {
            return LeafLodMode.valueOf(this.leafLodMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LeafLodMode.BALANCED;
        }
    }

    public void setLeafLodMode(LeafLodMode mode) {
        this.leafLodMode = mode.name().toLowerCase(Locale.ROOT);
    }

    // Ecliptic Seasons 只影响重新烘焙的模型视图，缓存中的体素保持季节无关。
    public boolean eclipticSeasonsSnowLod = true;
    public boolean eclipticSeasonsLodAutoReload = false;
    public boolean eclipticSeasonsReloadOnSeasonChange = true;

    // 世界加入时显示版本和维护信息。
    public boolean showJoinMessage = true;

    public SSAO.SSAOMode getSSAOMode() {
        if (this.ssaoMode == null) {
            return SSAO.SSAOMode.AUTO;
        }

        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SSAO.SSAOMode.AUTO;
        }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }

    private static VoxyConfig loadOrCreate() {
        if (!VoxyCommon.isAvailable()) {
            var config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            config.syncRuntime();
            return config;
        }

        Path path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                VoxyConfig config = GSON.fromJson(reader, VoxyConfig.class);
                if (config != null) {
                    config.sanitize();
                    config.syncRuntime();
                    return config;
                }
                Logger.error("Failed to load Voxy config; resetting it");
            } catch (IOException | RuntimeException e) {
                Logger.error("Could not load Voxy config; resetting it", e);
                backupInvalidConfig(path);
            }
        }

        Logger.info("Config does not exist; creating a new one");
        var config = new VoxyConfig();
        config.save();
        return config;
    }

    public void sanitize() {
        this.cmdListHoldMaxFrames = Math.clamp(this.cmdListHoldMaxFrames, 2, 60);
        this.sectionArrayPoolMiB = Math.clamp(this.sectionArrayPoolMiB, 25, 1024);
        WorldSection.setArrayPoolCapMiB(this.sectionArrayPoolMiB);
        this.sectionRenderDistance = Math.clamp(this.sectionRenderDistance, 0.625f, 64.0f);
        this.setRenderQualityLevel(this.getRenderQualityLevel());
        // Older builds measured this percentage against one sixteenth of the LOD radius.
        if (this.fogDistancePercent > 200) {
            this.fogDistancePercent = Math.max(5, Math.round(this.fogDistancePercent / 16.0f));
        }
        this.fogDistancePercent = Math.clamp(this.fogDistancePercent, 5, 200);
        this.skyFogDistance = Math.clamp(this.skyFogDistance, 0, 1024);
        this.earthCurveRatio = Math.clamp(this.earthCurveRatio, 0, 5000);
        if (this.earthCurveRatio > 0 && this.earthCurveRatio < 50) this.earthCurveRatio = 50;
        this.cloudDistance = Math.clamp(this.cloudDistance, 0, MAX_CLOUD_DISTANCE);
        this.fogIntensity = Math.clamp(this.fogIntensity, 0.0f, 1.0f);
        this.fogDensity = Math.clamp(this.fogDensity, 0.0f, 1.0f);
        this.lodBoundaryBuffer = Math.clamp(this.lodBoundaryBuffer, 0, 4);
        this.lodBoundaryFadeLength = Math.clamp(this.lodBoundaryFadeLength, 8, 64);
        this.lodBoundaryInset = Math.clamp(this.lodBoundaryInset, 8, 32);
        this.setLeafLodMode(this.getLeafLodMode());
        this.farPlayerAnimationDistance = Math.clamp(this.farPlayerAnimationDistance, 0, 32768);
        this.aeronauticsContraptionMaxChunks = Math.clamp(this.aeronauticsContraptionMaxChunks, 0, 192);
        this.sableLodLoadDistanceChunks = Math.clamp(this.sableLodLoadDistanceChunks, 8, 192);
        this.sableLodLoadBudget = Math.clamp(this.sableLodLoadBudget, 1, 64);
        this.biomeBlendRadius = Math.clamp(this.biomeBlendRadius, 0, 7);
        if (!"water".equals(this.biomeBlendScope) && !"water_grass".equals(this.biomeBlendScope)) {
            this.biomeBlendScope = "water";
        }
    }

    public void save() {
        this.sanitize();
        this.syncRuntime();
        // CONFIG 初始化期间尚未赋值，不能从配置桥反向读取它。
        if (this == CONFIG) VoxyNeoForgeConfig.syncFromVoxyConfig();
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavailable");
            return;
        }

        Path path = getConfigPath();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, GSON.toJson(this));
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Logger.error("Failed to write Voxy config", e);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    private void syncRuntime() {
        this.syncSableContraptionRenderDistance();
        this.syncDistantTrainConfig();
    }

    private static void backupInvalidConfig(Path path) {
        try {
            Path backup = path.resolveSibling(path.getFileName() + ".invalid");
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Logger.error("Failed to back up invalid Voxy config", e);
        }
    }

    public void syncSableContraptionRenderDistance() {
        SableContraptionRenderDistance.updateClientConfig(
                this.isRenderingEnabled() && this.sableLodRendering,
                this.sectionRenderDistance,
                this.aeronauticsContraptionMaxChunks,
                this.sableLodForceLoading,
                this.sableLodLoadDistanceChunks,
                this.sableLodLoadBudget
        );
    }

    public void syncDistantTrainConfig() {
        DistantTrainConfig.updateClientConfig(
                this.isRenderingEnabled() && this.distantTrains,
                this.createRenderDistance(this.distantTrainMaxChunks)
        );
        DistantTrainConfig.updateClientContraptionConfig(
                this.isRenderingEnabled() && this.distantContraptions,
                this.createRenderDistance(this.distantContraptionMaxChunks)
        );
    }

    public double createLodRadius() {
        return 32.0 * 16.0 * this.sectionRenderDistance;
    }

    public double createRenderDistance(int maxChunks) {
        double lod = this.createLodRadius();
        return maxChunks > 0 ? Math.min(maxChunks * 16.0, lod) : lod;
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }
}
