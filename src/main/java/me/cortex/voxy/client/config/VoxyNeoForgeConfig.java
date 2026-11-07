package me.cortex.voxy.client.config;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.compat.far.FarEntityClient;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** NeoForge Mods 菜单使用的客户端配置桥接，负责 TOML 与 VoxyConfig 双向同步。 */
@EventBusSubscriber(modid = "voxy", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class VoxyNeoForgeConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ---- 基础渲染与线程 -----------------------------------------------
    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Enable Voxy LOD rendering system")
            .define("enabled", true);

    private static final ModConfigSpec.BooleanValue ENABLE_RENDERING = BUILDER
            .comment("Enable LOD terrain rendering (can be disabled while keeping data ingestion)")
            .define("enableRendering", true);

    private static final ModConfigSpec.BooleanValue INGEST_ENABLED = BUILDER
            .comment("Enable automatic chunk data ingestion for LOD generation")
            .define("ingestEnabled", true);

    private static final ModConfigSpec.DoubleValue SECTION_RENDER_DISTANCE = BUILDER
            .comment("LOD section render distance (multiplied by 32 for actual chunk distance)",
                     "Example: 16 = 512 chunks render distance")
            .defineInRange("sectionRenderDistance", 16.0, 0.625, 64.0);

    private static final ModConfigSpec.IntValue SERVICE_THREADS = BUILDER
            .comment("Number of background threads for LOD processing",
                     "Default is based on CPU core count.")
            .defineInRange("serviceThreads", Math.max((int) (CpuLayout.getCoreCount() / 1.5), 1), 1, CpuLayout.getCoreCount());

    private static final ModConfigSpec.DoubleValue SUB_DIVISION_SIZE = BUILDER
            .comment("Render precision presets: 1024, 768, 512, 256 (default), 123, 64, 28. Lower values give more detail.")
            .defineInRange("subDivisionSize", 256.0, VoxyConfig.MIN_SUBDIVISION_SIZE, VoxyConfig.MAX_SUBDIVISION_SIZE);

    private static final ModConfigSpec.BooleanValue USE_ENVIRONMENTAL_FOG = BUILDER
            .comment("Apply environmental fog to LOD terrain")
            .define("useEnvironmentalFog", true);

    private static final ModConfigSpec.BooleanValue DONT_USE_SODIUM_BUILDER_THREADS = BUILDER
            .comment("Don't share threads with Sodium's chunk builder")
            .define("dontUseSodiumBuilderThreads", false);

    private static final ModConfigSpec.IntValue LOD_BOUNDARY_BUFFER = BUILDER
            .comment("LOD boundary safety overlap in blocks")
            .defineInRange("lodBoundaryBuffer", 1, 0, 4);

    private static final ModConfigSpec.BooleanValue ENABLE_LOD_BOUNDARY_FADE = BUILDER
            .comment("Use a camera-centred circular handoff between vanilla terrain and Voxy LOD.",
                     "Disable this when the active shader pack already supplies an LOD fade (for example Photon).")
            .define("enableLodBoundaryFade", true);

    private static final ModConfigSpec.IntValue LOD_BOUNDARY_FADE_LENGTH = BUILDER
            .comment("Width of the stable dithered terrain handoff, in blocks")
            .defineInRange("lodBoundaryFadeLength", 16, 8, 64);

    private static final ModConfigSpec.IntValue LOD_BOUNDARY_INSET = BUILDER
            .comment("Safety distance between the handoff outer edge and vanilla render distance, in blocks")
            .defineInRange("lodBoundaryInset", 8, 8, 32);

    private static final ModConfigSpec.BooleanValue ENABLE_EXTENDED_REQUEST_DISTANCE = BUILDER
            .comment("Enable FakeSight-style extended chunk requests for Voxy ingestion")
            .define("enableExtendedRequestDistance", false);

    private static final ModConfigSpec.IntValue REQUEST_DISTANCE = BUILDER
            .comment("Requested chunk radius. Higher values increase world-generation load.")
            .defineInRange("requestDistance", 48,
                    VoxyConfig.MIN_REQUEST_DISTANCE, VoxyConfig.MAX_REQUEST_DISTANCE);

    // ---- 世界兼容与远景实体 -------------------------------------------

    private static final ModConfigSpec.IntValue EARTH_CURVE_RATIO = BUILDER
            .comment("World curvature effect - simulates standing on a spherical planet",
                     "0 = disabled (flat world)",
                     "1 = real Earth curvature (6371km radius)",
                     "Higher values = more extreme curvature (smaller planet effect)",
                     "Valid range: 0 (off), or 50-5000. Values 1-49 are auto-corrected to 50.",
                     "Inspired by Distant Horizons' earth curvature feature")
            .defineInRange("earthCurveRatio", 0, 0, 5000);

    private static final ModConfigSpec.BooleanValue ENABLE_FAR_PLAYER_RENDERING = BUILDER
            .comment("Render far players with lightweight server snapshots.",
                     "Multiplayer requires Voxy on the server; standalone SeeU takes precedence when installed.")
            .define("enableFarPlayerRendering", true);

    private static final ModConfigSpec.BooleanValue ENABLE_FAR_VEHICLE_RENDERING = BUILDER
            .comment("Render ridden vehicles and mounts from far-entity snapshots independently of players.")
            .define("enableFarVehicleRendering", true);

    private static final ModConfigSpec.BooleanValue RENDER_FAR_PLAYER_NAMES = BUILDER
            .comment("Render name tags above far-player proxies.")
            .define("renderFarPlayerNames", true);

    private static final ModConfigSpec.IntValue FAR_PLAYER_ANIMATION_DISTANCE = BUILDER
            .comment("Maximum distance in blocks for far-player walk animation.",
                     "Set to 0 to follow the LOD render distance.")
            .defineInRange("farPlayerAnimationDistance", 0, 0, 32768);

    private static final ModConfigSpec.BooleanValue SHARE_FAR_PLAYER_POSITION = BUILDER
            .comment("Allow other Voxy clients on the same server to receive your far-player snapshot.")
            .define("shareFarPlayerPosition", true);

    private static final ModConfigSpec.BooleanValue RENDER_STATISTICS = BUILDER
            .comment("Show render statistics in F3 debug screen",
                     "Displays LOD traversal counts, visible sections, and quad counts")
            .define("renderStatistics", false);

    private static final ModConfigSpec.BooleanValue DISTANT_TRAINS = BUILDER
            .comment("Render Create trains beyond the vanilla view distance, in the LOD")
            .define("distantTrains", true);

    private static final ModConfigSpec.IntValue DISTANT_TRAIN_MAX_CHUNKS = BUILDER
            .comment("Max distance to render distant trains, in chunks. 0 = follow the LOD radius.",
                     "Lower renders trains nearer; on the integrated server it also shrinks the",
                     "server's train pose-stream window, cutting bandwidth.")
            .defineInRange("distantTrainMaxChunks", 0, 0, 192);

    private static final ModConfigSpec.BooleanValue DISTANT_TRACKS = BUILDER
            .comment("Render the Create track network beyond the view distance, in the LOD")
            .define("distantTracks", true);

    private static final ModConfigSpec.IntValue DISTANT_TRACK_MAX_CHUNKS = BUILDER
            .comment("Max distance to render the distant track network, in chunks. 0 = follow the LOD radius.")
            .defineInRange("distantTrackMaxChunks", 0, 0, 192);

    private static final ModConfigSpec.BooleanValue DISTANT_CONTRAPTIONS = BUILDER
            .comment("Render snapshots of Create contraptions (bearings/pistons/gantries/mounted)",
                     "beyond the view distance, in the LOD")
            .define("distantContraptions", true);

    private static final ModConfigSpec.IntValue DISTANT_CONTRAPTION_MAX_CHUNKS = BUILDER
            .comment("Max distance to render distant contraptions, in chunks. 0 = follow the LOD radius.")
            .defineInRange("distantContraptionMaxChunks", 0, 0, 192);

    private static final ModConfigSpec.BooleanValue DISTANT_BEACONS = BUILDER
            .comment("Draw beacon beams past vanilla's block-entity render range")
            .define("distantBeacons", true);

    private static final ModConfigSpec.IntValue DISTANT_BEACON_MAX_CHUNKS = BUILDER
            .comment("Maximum beacon-beam distance in chunks. 0 follows Voxy's LOD radius.")
            .defineInRange("distantBeaconMaxChunks", 0, 0, 512);

    private static final ModConfigSpec.BooleanValue DISTANT_KINETICS = BUILDER
            .comment("Cull placed kinetic machine moving parts (rotating shafts/gears) beyond the render",
                     "distance so they stop floating over the LOD. Off = Create draws them natively.")
            .define("distantKinetics", true);

    private static final ModConfigSpec.BooleanValue DISTANT_POWERGRID_WIRES = BUILDER
            .comment("Render cached PowerGrid hanging wires beyond their entity tracking range")
            .define("distantPowerGridWires", true);

    private static final ModConfigSpec.IntValue DISTANT_POWERGRID_WIRE_MAX_CHUNKS = BUILDER
            .comment("Maximum PowerGrid wire LOD distance in chunks. 0 follows Voxy's LOD radius.")
            .defineInRange("distantPowerGridWireMaxChunks", 0, 0, 192);

    private static final ModConfigSpec.BooleanValue DISTANT_COPYCATS = BUILDER
            .comment("Render Create and Copycats+ camouflage blocks with dedicated LOD meshes")
            .define("distantCopycats", true);

    private static final ModConfigSpec.IntValue DISTANT_COPYCATS_MAX_CHUNKS = BUILDER
            .comment("Maximum Copycats+ LOD distance in chunks. 0 follows Voxy's LOD radius.")
            .defineInRange("distantCopycatsMaxChunks", 0, 0, 192);

    private static final ModConfigSpec.BooleanValue DISTANT_SIMULATED_LASERS = BUILDER
            .comment("Render Simulated laser-pointer beams beyond block-entity render distance")
            .define("distantSimulatedLasers", true);

    private static final ModConfigSpec.IntValue DISTANT_SIMULATED_LASER_MAX_CHUNKS = BUILDER
            .comment("Maximum Simulated laser LOD distance in chunks. 0 follows Voxy's LOD radius.")
            .defineInRange("distantSimulatedLaserMaxChunks", 0, 0, 192);

    private static final ModConfigSpec.BooleanValue DISTANT_FRAMED_BLOCKS = BUILDER
            .comment("Render FramedBlocks camouflaged models in the LOD")
            .define("distantFramedBlocks", true);

    private static final ModConfigSpec.IntValue DISTANT_FRAMED_BLOCKS_MAX_CHUNKS = BUILDER
            .comment("Maximum FramedBlocks LOD distance in chunks. 0 follows Voxy's LOD radius.")
            .defineInRange("distantFramedBlocksMaxChunks", 0, 0, 192);

    private static final ModConfigSpec.BooleanValue DISTANT_LITTLETILES = BUILDER
            .comment("Render cached LittleTiles microblock meshes beyond vanilla view distance")
            .define("distantLittleTiles", true);

    private static final ModConfigSpec.IntValue DISTANT_LITTLETILES_MAX_CHUNKS = BUILDER
            .comment("Maximum LittleTiles LOD distance in chunks. 0 follows Voxy's LOD radius.")
            .defineInRange("distantLittleTilesMaxChunks", 0, 0, 192);

    private static final ModConfigSpec.BooleanValue DISTANT_DOMUM = BUILDER
            .comment("Render detailed Domum Ornamentum models in the LOD")
            .define("distantDomum", true);

    private static final ModConfigSpec.IntValue DISTANT_DOMUM_MAX_CHUNKS = BUILDER
            .comment("Maximum Domum Ornamentum LOD distance in chunks. 0 follows Voxy's LOD radius.")
            .defineInRange("distantDomumMaxChunks", 0, 0, 192);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private VoxyNeoForgeConfig() {
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC, "voxy-client.toml");
    }

    /** 从 NeoForge 配置快照写入运行时配置，并触发一次持久化。 */
    private static void syncToVoxyConfig() {
        VoxyConfig.CONFIG.enabled = ENABLED.get();
        VoxyConfig.CONFIG.enableRendering = ENABLE_RENDERING.get();
        VoxyConfig.CONFIG.ingestEnabled = INGEST_ENABLED.get();
        VoxyConfig.CONFIG.sectionRenderDistance = SECTION_RENDER_DISTANCE.get().floatValue();
        VoxyConfig.CONFIG.serviceThreads = SERVICE_THREADS.get();
        VoxyConfig.CONFIG.subDivisionSize = SUB_DIVISION_SIZE.get().floatValue();
        VoxyConfig.CONFIG.useEnvironmentalFog = USE_ENVIRONMENTAL_FOG.get();
        VoxyConfig.CONFIG.dontUseSodiumBuilderThreads = DONT_USE_SODIUM_BUILDER_THREADS.get();
        VoxyConfig.CONFIG.lodBoundaryBuffer = LOD_BOUNDARY_BUFFER.get();
        VoxyConfig.CONFIG.enableLodBoundaryFade = ENABLE_LOD_BOUNDARY_FADE.get();
        VoxyConfig.CONFIG.lodBoundaryFadeLength = LOD_BOUNDARY_FADE_LENGTH.get();
        VoxyConfig.CONFIG.lodBoundaryInset = LOD_BOUNDARY_INSET.get();
        VoxyConfig.CONFIG.enableExtendedRequestDistance = ENABLE_EXTENDED_REQUEST_DISTANCE.get();
        VoxyConfig.CONFIG.requestDistance = REQUEST_DISTANCE.get();
        VoxyConfig.CONFIG.earthCurveRatio = EARTH_CURVE_RATIO.get();
        VoxyConfig.CONFIG.distantTrains = DISTANT_TRAINS.get();
        VoxyConfig.CONFIG.distantTrainMaxChunks = DISTANT_TRAIN_MAX_CHUNKS.get();
        VoxyConfig.CONFIG.distantTracks = DISTANT_TRACKS.get();
        VoxyConfig.CONFIG.distantTrackMaxChunks = DISTANT_TRACK_MAX_CHUNKS.get();
        VoxyConfig.CONFIG.distantContraptions = DISTANT_CONTRAPTIONS.get();
        VoxyConfig.CONFIG.distantContraptionMaxChunks = DISTANT_CONTRAPTION_MAX_CHUNKS.get();
        VoxyConfig.CONFIG.distantBeacons = DISTANT_BEACONS.get();
        VoxyConfig.CONFIG.distantBeaconMaxChunks = DISTANT_BEACON_MAX_CHUNKS.get();
        VoxyConfig.CONFIG.distantKinetics = DISTANT_KINETICS.get();
        VoxyConfig.CONFIG.distantPowerGridWires = DISTANT_POWERGRID_WIRES.get();
        VoxyConfig.CONFIG.distantPowerGridWireMaxChunks = DISTANT_POWERGRID_WIRE_MAX_CHUNKS.get();
        VoxyConfig.CONFIG.distantCopycats = DISTANT_COPYCATS.get();
        VoxyConfig.CONFIG.distantCopycatsMaxChunks = DISTANT_COPYCATS_MAX_CHUNKS.get();
        VoxyConfig.CONFIG.distantSimulatedLasers = DISTANT_SIMULATED_LASERS.get();
        VoxyConfig.CONFIG.distantSimulatedLaserMaxChunks = DISTANT_SIMULATED_LASER_MAX_CHUNKS.get();
        VoxyConfig.CONFIG.distantFramedBlocks = DISTANT_FRAMED_BLOCKS.get();
        VoxyConfig.CONFIG.distantFramedBlocksMaxChunks = DISTANT_FRAMED_BLOCKS_MAX_CHUNKS.get();
        VoxyConfig.CONFIG.distantLittleTiles = DISTANT_LITTLETILES.get();
        VoxyConfig.CONFIG.distantLittleTilesMaxChunks = DISTANT_LITTLETILES_MAX_CHUNKS.get();
        VoxyConfig.CONFIG.distantDomum = DISTANT_DOMUM.get();
        VoxyConfig.CONFIG.distantDomumMaxChunks = DISTANT_DOMUM_MAX_CHUNKS.get();
        VoxyConfig.CONFIG.enableFarPlayerRendering = ENABLE_FAR_PLAYER_RENDERING.get();
        VoxyConfig.CONFIG.enableFarVehicleRendering = ENABLE_FAR_VEHICLE_RENDERING.get();
        VoxyConfig.CONFIG.renderFarPlayerNames = RENDER_FAR_PLAYER_NAMES.get();
        VoxyConfig.CONFIG.farPlayerAnimationDistance = FAR_PLAYER_ANIMATION_DISTANCE.get();
        VoxyConfig.CONFIG.shareFarPlayerPosition = SHARE_FAR_PLAYER_POSITION.get();
        VoxyConfig.CONFIG.sanitize();
        RenderStatistics.enabled = RENDER_STATISTICS.get();

        VoxyConfig.CONFIG.save();
    }

    /** 将 VoxyConfig 的当前值回写到 NeoForge 配置，以保持 Mods 菜单一致。 */
    public static void syncFromVoxyConfig() {
        if (!SPEC.isLoaded() || VoxyConfig.CONFIG == null) return;
        VoxyConfig.CONFIG.sanitize();
        ENABLED.set(VoxyConfig.CONFIG.enabled);
        ENABLE_RENDERING.set(VoxyConfig.CONFIG.enableRendering);
        INGEST_ENABLED.set(VoxyConfig.CONFIG.ingestEnabled);
        SECTION_RENDER_DISTANCE.set((double) VoxyConfig.CONFIG.sectionRenderDistance);
        SERVICE_THREADS.set(VoxyConfig.CONFIG.serviceThreads);
        SUB_DIVISION_SIZE.set((double) VoxyConfig.CONFIG.subDivisionSize);
        USE_ENVIRONMENTAL_FOG.set(VoxyConfig.CONFIG.useEnvironmentalFog);
        DONT_USE_SODIUM_BUILDER_THREADS.set(VoxyConfig.CONFIG.dontUseSodiumBuilderThreads);
        LOD_BOUNDARY_BUFFER.set(VoxyConfig.CONFIG.lodBoundaryBuffer);
        ENABLE_LOD_BOUNDARY_FADE.set(VoxyConfig.CONFIG.enableLodBoundaryFade);
        LOD_BOUNDARY_FADE_LENGTH.set(VoxyConfig.CONFIG.lodBoundaryFadeLength);
        LOD_BOUNDARY_INSET.set(VoxyConfig.CONFIG.lodBoundaryInset);
        ENABLE_EXTENDED_REQUEST_DISTANCE.set(VoxyConfig.CONFIG.enableExtendedRequestDistance);
        REQUEST_DISTANCE.set(VoxyConfig.CONFIG.requestDistance);
        EARTH_CURVE_RATIO.set(VoxyConfig.CONFIG.earthCurveRatio);
        DISTANT_TRAINS.set(VoxyConfig.CONFIG.distantTrains);
        DISTANT_TRAIN_MAX_CHUNKS.set(VoxyConfig.CONFIG.distantTrainMaxChunks);
        DISTANT_TRACKS.set(VoxyConfig.CONFIG.distantTracks);
        DISTANT_TRACK_MAX_CHUNKS.set(VoxyConfig.CONFIG.distantTrackMaxChunks);
        DISTANT_CONTRAPTIONS.set(VoxyConfig.CONFIG.distantContraptions);
        DISTANT_CONTRAPTION_MAX_CHUNKS.set(VoxyConfig.CONFIG.distantContraptionMaxChunks);
        DISTANT_BEACONS.set(VoxyConfig.CONFIG.distantBeacons);
        DISTANT_BEACON_MAX_CHUNKS.set(VoxyConfig.CONFIG.distantBeaconMaxChunks);
        DISTANT_KINETICS.set(VoxyConfig.CONFIG.distantKinetics);
        DISTANT_POWERGRID_WIRES.set(VoxyConfig.CONFIG.distantPowerGridWires);
        DISTANT_POWERGRID_WIRE_MAX_CHUNKS.set(VoxyConfig.CONFIG.distantPowerGridWireMaxChunks);
        DISTANT_COPYCATS.set(VoxyConfig.CONFIG.distantCopycats);
        DISTANT_COPYCATS_MAX_CHUNKS.set(VoxyConfig.CONFIG.distantCopycatsMaxChunks);
        DISTANT_SIMULATED_LASERS.set(VoxyConfig.CONFIG.distantSimulatedLasers);
        DISTANT_SIMULATED_LASER_MAX_CHUNKS.set(VoxyConfig.CONFIG.distantSimulatedLaserMaxChunks);
        DISTANT_FRAMED_BLOCKS.set(VoxyConfig.CONFIG.distantFramedBlocks);
        DISTANT_FRAMED_BLOCKS_MAX_CHUNKS.set(VoxyConfig.CONFIG.distantFramedBlocksMaxChunks);
        DISTANT_LITTLETILES.set(VoxyConfig.CONFIG.distantLittleTiles);
        DISTANT_LITTLETILES_MAX_CHUNKS.set(VoxyConfig.CONFIG.distantLittleTilesMaxChunks);
        DISTANT_DOMUM.set(VoxyConfig.CONFIG.distantDomum);
        DISTANT_DOMUM_MAX_CHUNKS.set(VoxyConfig.CONFIG.distantDomumMaxChunks);
        ENABLE_FAR_PLAYER_RENDERING.set(VoxyConfig.CONFIG.enableFarPlayerRendering);
        ENABLE_FAR_VEHICLE_RENDERING.set(VoxyConfig.CONFIG.enableFarVehicleRendering);
        RENDER_FAR_PLAYER_NAMES.set(VoxyConfig.CONFIG.renderFarPlayerNames);
        FAR_PLAYER_ANIMATION_DISTANCE.set(VoxyConfig.CONFIG.farPlayerAnimationDistance);
        SHARE_FAR_PLAYER_POSITION.set(VoxyConfig.CONFIG.shareFarPlayerPosition);
        RenderStatistics.enabled = RENDER_STATISTICS.get();
    }

    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            syncFromVoxyConfig();
        }
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            syncToVoxyConfig();
            FarEntityClient.sendHello();
        }
    }

    // ---- 少量只读访问器 ------------------------------------------------
    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static boolean isRenderingEnabled() {
        return ENABLE_RENDERING.get();
    }

    public static boolean isIngestEnabled() {
        return INGEST_ENABLED.get();
    }

    public static float getSectionRenderDistance() {
        return SECTION_RENDER_DISTANCE.get().floatValue();
    }

    public static int getServiceThreads() {
        return SERVICE_THREADS.get();
    }

    public static float getSubDivisionSize() {
        return SUB_DIVISION_SIZE.get().floatValue();
    }

    public static boolean useEnvironmentalFog() {
        return USE_ENVIRONMENTAL_FOG.get();
    }

    public static boolean dontUseSodiumBuilderThreads() {
        return DONT_USE_SODIUM_BUILDER_THREADS.get();
    }

    public static boolean isRenderStatisticsEnabled() {
        return RENDER_STATISTICS.get();
    }

    public static int getEarthCurveRatio() {
        return EARTH_CURVE_RATIO.get();
    }
}
