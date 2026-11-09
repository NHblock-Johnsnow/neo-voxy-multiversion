package me.cortex.voxy.client.config;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.config.SodiumConfigBuilder.*;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.compat.eclipticseasons.EsCompatGate;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.LiteShaderStatus;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.compat.far.FarEntityClient;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.util.Locale;

@ConfigEntryPointForge("voxy")
public class VoxyConfigMenu implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        if (!VoxyCommon.isAvailable()) {
            return;
        }

        var cfg = VoxyConfig.CONFIG;
        boolean sableInstalled = ModList.get().isLoaded("sable");
        boolean createInstalled = ModList.get().isLoaded("create");
        boolean powerGridInstalled = ModList.get().isLoaded("powergrid");
        boolean copycatsInstalled = ModList.get().isLoaded("copycats");
        boolean simulatedInstalled = ModList.get().isLoaded("simulated");
        boolean framedBlocksInstalled = ModList.get().isLoaded("framedblocks");
        boolean littleTilesInstalled = ModList.get().isLoaded("littletiles");
        boolean domumInstalled = ModList.get().isLoaded("domum_ornamentum");
        boolean seasonsInstalled = EsCompatGate.shouldArm();

        var options = builder.registerModOptions("voxy", VoxyCommon.displayName(), VoxyCommon.MOD_VERSION)
                .setIcon(ResourceLocation.parse("voxy:icon.png"));
        final var renderReload = OptionFlag.REQUIRES_RENDERER_RELOAD.getId().toString();

        SodiumConfigBuilder.buildToSodium(builder, options, cfg::save, VoxyConfigMenu::registerPostApplyOps,
                generalPage(cfg, renderReload),
                renderingPage(cfg, renderReload),
                experimentalPage(cfg, renderReload),
                compatibilityPage(cfg, renderReload, sableInstalled, createInstalled, powerGridInstalled,
                        copycatsInstalled, simulatedInstalled, framedBlocksInstalled, littleTilesInstalled,
                        domumInstalled, seasonsInstalled));
    }

    /** 配置提交后执行需要即时生效的运行时操作。 */
    private static void registerPostApplyOps(PostApplyOps postOp) {
        postOp.register("voxy:update_threads", () -> {
                    var instance = VoxyCommon.getInstance();
                    if (instance != null) {
                        instance.updateDedicatedThreads();
                    }
                }, "voxy:enabled")
                .register("voxy:iris_reload", IrisUtil::reload)
                .register("voxy:refresh_far_entities", FarEntityClient::sendHello);
    }

    private static Page generalPage(VoxyConfig cfg, String renderReload) {
        return new Page(Component.translatable("voxy.config.general"),
                        new Group(Component.translatable("voxy.config.group.general"),
                                new BoolOption(
                                        "voxy:enabled",
                                        Component.translatable("voxy.config.general.enabled"),
                                        ()->cfg.enabled, v->{
                                            cfg.enabled=v;
                                            if (v && ClientSessionEvents.inSession) {
                                                VoxyCommon.createInstance();
                                            }
                                        })
                                        .setPostChangeRunner(c->{
                                            if (!c) {
                                                var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                                                if (vrsh != null) {
                                                    vrsh.voxy$shutdownRenderer();
                                                }
                                                VoxyCommon.shutdownInstance();
                                            }
                                        }).setPostChangeFlags(renderReload, "voxy:iris_reload").setEnabler(null)
                        ), new Group(Component.translatable("voxy.config.group.threads"),
                                new IntOption(
                                        "voxy:thread_count",
                                        Component.translatable("voxy.config.general.serviceThreads"),
                                        ()->cfg.serviceThreads, v->cfg.serviceThreads=v,
                                        new Range(1, CpuLayout.getCoreCount(), 1))
                                        .setPostChangeFlags("voxy:update_threads"),
                                new BoolOption(
                                        "voxy:use_sodium_threads",
                                        Component.translatable("voxy.config.general.useSodiumBuilder"),
                                        ()->!cfg.dontUseSodiumBuilderThreads, v->cfg.dontUseSodiumBuilderThreads=!v)
                                        .setPostChangeFlags("voxy:update_threads", renderReload)
                        ), new Group(Component.translatable("voxy.config.group.data"),
                                new BoolOption(
                                        "voxy:ingest_enabled",
                                        Component.translatable("voxy.config.general.ingest"),
                                        ()->cfg.ingestEnabled, v->cfg.ingestEnabled=v),
                                new BoolOption(
                                        "voxy:show_join_message",
                                        Component.translatable("voxy.config.general.showJoinMessage"),
                                        ()->cfg.showJoinMessage, v->cfg.showJoinMessage=v)
                        )
                ).setEnabler("voxy:enabled");
    }

    private static Page renderingPage(VoxyConfig cfg, String renderReload) {
        return new Page(Component.translatable("voxy.config.rendering"),
                        new Group(Component.translatable("voxy.config.group.activation"),
                                new BoolOption(
                                        "voxy:rendering",
                                        Component.translatable("voxy.config.general.rendering"),
                                        ()->cfg.enableRendering, v->cfg.enableRendering=v)
                                        .setPostChangeRunner(c->{
                                            var vrsh = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                                            if (vrsh != null) {
                                                if (c) {
                                                    vrsh.voxy$createRenderer();
                                                } else {
                                                    vrsh.voxy$shutdownRenderer();
                                                }
                                            }
                                        },"voxy:enabled", renderReload)
                                        .setPostChangeFlags("voxy:iris_reload")
                                        .setEnabler("voxy:enabled")
                        ), new Group(Component.translatable("voxy.config.group.quality"),
                                new IntOption(
                                        "voxy:subdivsize",
                                        Component.translatable("voxy.config.general.subDivisionSize"),
                                        cfg::getRenderQualityLevel, cfg::setRenderQualityLevel,
                                        new Range(0, 6, 1))
                                        .setFormatter(v->Component.translatable("voxy.config.general.renderQuality." + v))
                                        .setDefault(VoxyConfig.DEFAULT_RENDER_QUALITY_LEVEL)
                                        .setImpact(OptionImpact.HIGH),
                                new IntOption(
                                        "voxy:render_distance",
                                        Component.translatable("voxy.config.general.renderDistance"),
                                        ()->Math.round(cfg.sectionRenderDistance*16), v->cfg.sectionRenderDistance=((float)v)/16,
                                        new Range(10, 64*16, 1))
                                        .setFormatter(v->Component.literal(Integer.toString(v*2)))
                                        .setPostChangeRunner(c->{
                                            var vrsh = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                                            if (vrsh != null) {
                                                var vrs = vrsh.voxy$getRenderSystem();
                                                if (vrs != null) {
                                                    vrs.setRenderDistance(cfg.sectionRenderDistance);
                                                }
                                            }
                                        }, "voxy:rendering", renderReload)
                                        .setImpact(OptionImpact.MEDIUM),
                                new IntOption(
                                        "voxy:render_pressure",
                                        Component.translatable("voxy.config.general.renderPressure"),
                                        ()->cfg.getRenderPressureLevel(), v->cfg.renderPressure=v,
                                        new Range(0, 4, 1))
                                        .setFormatter(v->Component.translatable("voxy.config.general.renderPressure." + v))
                                        .setImpact(OptionImpact.HIGH),
                                new IntOption(
                                        "voxy:leaf_lod_mode",
                                        Component.translatable("voxy.config.general.leafLodMode"),
                                        ()->cfg.getLeafLodMode().ordinal(),
                                        v->cfg.setLeafLodMode(VoxyConfig.LeafLodMode.values()[v]),
                                        new Range(0, 2, 1))
                                        .setFormatter(v->Component.translatable("voxy.config.general.leafLodMode."
                                                + VoxyConfig.LeafLodMode.values()[v].name().toLowerCase(Locale.ROOT)))
                                        .setDefault(VoxyConfig.LeafLodMode.BALANCED.ordinal())
                                        .setPostChangeFlags(renderReload)
                                        .setImpact(OptionImpact.MEDIUM),
                                new IntOption(
                                        "voxy:earth_curve_ratio",
                                        Component.translatable("voxy.config.general.earthCurveRatio"),
                                        ()->cfg.earthCurveRatio, v->cfg.earthCurveRatio=(v > 0 && v < 50) ? 50 : v,
                                        new Range(0, 10000, 50))
                                        .setPostChangeFlags(renderReload)
                                        .setImpact(OptionImpact.LOW)
                        ), new Group(Component.translatable("voxy.config.group.vanillaEffects"),
                                new BoolOption(
                                    "voxy:environmental_fog",
                                    Component.translatable("voxy.config.general.environmental_fog"),
                                    () -> cfg.useEnvironmentalFog,
                                    v -> cfg.useEnvironmentalFog = v),
                                new EnumOption<>("voxy:ssao_mode",
                                        SSAO.SSAOMode.class,
                                        Component.translatable("voxy.config.general.ssao_mode"),
                                        ()->cfg.getSSAOMode(), v->cfg.setSSAOMode(v))
                                        .setImpact(OptionImpact.MEDIUM)
                                        .setPostChangeFlags(renderReload)
                        )
                        .setEnablerInherit(s->!IrisUtil.irisShaderPackEnabled(), ConfigState.UPDATE_ON_REBUILD),
                        new Group(Component.translatable("voxy.config.group.clouds"),
                                new BoolOption(
                                        "voxy:adapt_cloud_distance",
                                        Component.translatable("voxy.config.general.adaptCloudDistance"),
                                        ()->cfg.adaptCloudDistance, v->cfg.adaptCloudDistance=v),
                                new IntOption(
                                        "voxy:cloud_distance",
                                        Component.translatable("voxy.config.general.cloudDistance"),
                                        ()->cfg.cloudDistance, v->cfg.cloudDistance=v,
                                        new Range(0, VoxyConfig.MAX_CLOUD_DISTANCE, 1))
                                        .setImpact(OptionImpact.LOW)
                        )
                        .setEnablerInherit(s->!IrisUtil.irisShaderPackEnabled(), ConfigState.UPDATE_ON_REBUILD),
                        new Group(Component.translatable("voxy.config.group.fog"),
                                new IntOption(
                                        "voxy:fog_intensity",
                                        Component.translatable("voxy.config.general.fogIntensity"),
                                        ()->Math.round(cfg.fogIntensity * 100), v->cfg.fogIntensity=v / 100.0f,
                                        new Range(0, 100, 1))
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:fog_density",
                                        Component.translatable("voxy.config.general.fogDensity"),
                                        ()->Math.round(cfg.fogDensity * 100), v->cfg.fogDensity=v / 100.0f,
                                        new Range(0, 100, 1))
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:sky_fog_distance",
                                        Component.translatable("voxy.config.general.skyFogDistance"),
                                        ()->cfg.skyFogDistance, v->cfg.skyFogDistance=v,
                                        new Range(0, 1024, 1))
                                        .setImpact(OptionImpact.LOW)
                                        .setPostChangeFlags(renderReload),
                                new IntOption(
                                        "voxy:fog_distance",
                                        Component.translatable("voxy.config.general.fog_distance"),
                                        ()->cfg.fogDistancePercent, v->cfg.fogDistancePercent=v,
                                        new Range(5, 200, 5))
                                        .setFormatter(v->Component.literal(v+"%"))
                                        .setImpact(OptionImpact.LOW)
                        )
                        .setEnablerInherit(s->!IrisUtil.irisShaderPackEnabled(), ConfigState.UPDATE_ON_REBUILD),
                        new Group(Component.translatable("voxy.config.group.biomeColours"),
                                new IntOption(
                                        "voxy:biome_blend_radius",
                                        Component.translatable("voxy.config.general.biomeBlendRadius"),
                                        ()->cfg.biomeBlendRadius, v->cfg.biomeBlendRadius=v,
                                        new Range(0, 7, 1))
                                        .setFormatter(v->v == 0
                                                ? Component.translatable("voxy.config.general.biomeBlendRadius.off")
                                                : Component.literal(Integer.toString(v)))
                                        .setPostChangeFlags(renderReload)
                                        .setImpact(OptionImpact.MEDIUM),
                                new BoolOption(
                                        "voxy:biome_blend_grass",
                                        Component.translatable("voxy.config.general.biomeBlendGrass"),
                                        ()->"water_grass".equals(cfg.biomeBlendScope),
                                        v->cfg.biomeBlendScope=v ? "water_grass" : "water")
                                        .setPostChangeFlags(renderReload)
                                        .setImpact(OptionImpact.MEDIUM)
                        ),
                        new Group(Component.translatable("voxy.config.farEntities"),
                                new BoolOption(
                                        "voxy:far_players",
                                        Component.translatable("voxy.config.farEntities.players"),
                                        ()->cfg.enableFarPlayerRendering, v->cfg.enableFarPlayerRendering=v)
                                        .setPostChangeFlags("voxy:refresh_far_entities"),
                                new BoolOption(
                                        "voxy:far_vehicles",
                                        Component.translatable("voxy.config.farEntities.vehicles"),
                                        ()->cfg.enableFarVehicleRendering, v->cfg.enableFarVehicleRendering=v)
                                        .setPostChangeFlags("voxy:refresh_far_entities"),
                                new BoolOption(
                                        "voxy:far_player_names",
                                        Component.translatable("voxy.config.farEntities.names"),
                                        ()->cfg.renderFarPlayerNames, v->cfg.renderFarPlayerNames=v)
                                        .setEnablerInherit("voxy:far_players"),
                                new IntOption(
                                        "voxy:far_player_animation_distance",
                                        Component.translatable("voxy.config.farEntities.animationDistance"),
                                        ()->cfg.farPlayerAnimationDistance, v->cfg.farPlayerAnimationDistance=v,
                                        new Range(0, 32768, 64))
                                        .setFormatter(v->v == 0
                                                ? Component.translatable("voxy.config.compat.distanceFollowLod")
                                                : Component.translatable("voxy.config.unit.blocks", v))
                                        .setEnablerInherit("voxy:far_players")
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:share_far_player_position",
                                        Component.translatable("voxy.config.farEntities.sharePosition"),
                                        ()->cfg.shareFarPlayerPosition, v->cfg.shareFarPlayerPosition=v)
                                        .setPostChangeFlags("voxy:refresh_far_entities")
                        ).setEnablerInherit(s->me.cortex.voxy.client.ServerCapabilities.canConfigureFarEntities(), ConfigState.UPDATE_ON_REBUILD),
                        new Group(Component.translatable("voxy.config.group.vanillaExtensions"),
                                new BoolOption(
                                        "voxy:distant_beacons",
                                        Component.translatable("voxy.config.compat.distantBeacons"),
                                        ()->cfg.distantBeacons, v->cfg.distantBeacons=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_beacon_distance",
                                        Component.translatable("voxy.config.compat.distantBeaconDistance"),
                                        ()->cfg.distantBeaconMaxChunks, v->cfg.distantBeaconMaxChunks=v,
                                        new Range(0, 512, 16))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW)
                        )
                ).setEnablerAND("voxy:enabled", "voxy:rendering");
    }

    private static Page experimentalPage(VoxyConfig cfg, String renderReload) {
        return new Page(Component.translatable("voxy.config.experimental"),
                        new Group(Component.translatable("voxy.config.group.experimentalStill"),
                                new BoolOption(
                                        "voxy:experimental_cmd_list_hold",
                                        Component.translatable("voxy.config.experimental.cmdListHold"),
                                        ()->cfg.experimentalCmdListHold, v->cfg.experimentalCmdListHold=v)
                                        .setDefault(false)
                                        .setImpact(OptionImpact.MEDIUM),
                                new IntOption(
                                        "voxy:cmd_list_hold_max_frames",
                                        Component.translatable("voxy.config.experimental.cmdListHoldMaxFrames"),
                                        ()->cfg.cmdListHoldMaxFrames, v->cfg.cmdListHoldMaxFrames=v,
                                        new Range(2, 60, 1))
                                        .setDefault(4)
                                        .setEnablerInherit("voxy:experimental_cmd_list_hold")
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:experimental_chunk_mask_reuse",
                                        Component.translatable("voxy.config.experimental.chunkMaskReuse"),
                                        ()->cfg.experimentalChunkMaskReuse, v->cfg.experimentalChunkMaskReuse=v)
                                        .setDefault(false)
                                        .setImpact(OptionImpact.LOW)
                        ),
                        new Group(Component.translatable("voxy.config.group.experimentalGpu"),
                                new BoolOption(
                                        "voxy:experimental_opaque_near_first",
                                        Component.translatable("voxy.config.experimental.opaqueNearFirst"),
                                        ()->cfg.experimentalOpaqueNearFirst, v->cfg.experimentalOpaqueNearFirst=v)
                                        .setDefault(false)
                                        .setImpact(OptionImpact.MEDIUM)
                                        .setPostChangeFlags(renderReload),
                                new BoolOption(
                                        "voxy:experimental_chunk_mask_half_res",
                                        Component.translatable("voxy.config.experimental.chunkMaskHalfRes"),
                                        ()->cfg.experimentalChunkMaskHalfRes, v->cfg.experimentalChunkMaskHalfRes=v)
                                        .setDefault(false)
                                        .setImpact(OptionImpact.MEDIUM)
                                        .setPostChangeFlags(renderReload),
                                new BoolOption(
                                        "voxy:experimental_hiz_compute",
                                        Component.translatable("voxy.config.experimental.hiZCompute"),
                                        ()->cfg.experimentalHiZCompute, v->cfg.experimentalHiZCompute=v)
                                        .setDefault(false)
                                        .setImpact(OptionImpact.LOW)
                                        .setPostChangeFlags(renderReload)
                        ),
                        new Group(Component.translatable("voxy.config.group.experimentalMemory"),
                                new IntOption(
                                        "voxy:section_array_pool_mib",
                                        Component.translatable("voxy.config.experimental.sectionArrayPoolMiB"),
                                        ()->cfg.sectionArrayPoolMiB, v->cfg.sectionArrayPoolMiB=v,
                                        new Range(25, 1024, 1))
                                        .setDefault(100)
                                        .setFormatter(v->Component.literal(v + " MiB"))
                                        //Heap, not frame time: a filled pool holds the whole budget
                                        .setImpact(OptionImpact.MEDIUM)
                        ),
                        new Group(Component.translatable("voxy.config.group.experimentalTransition"),
                                new BoolOption(
                                        "voxy:lod_boundary_fade",
                                        Component.translatable("voxy.config.general.lodBoundaryFade"),
                                        ()->cfg.enableLodBoundaryFade, v->cfg.enableLodBoundaryFade=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:lod_boundary_fade_length",
                                        Component.translatable("voxy.config.general.lodBoundaryFadeLength"),
                                        ()->cfg.lodBoundaryFadeLength, v->cfg.lodBoundaryFadeLength=v,
                                        new Range(8, 64, 1))
                                        .setFormatter(v->Component.translatable("voxy.config.unit.blocks", v))
                                        .setEnablerInherit("voxy:lod_boundary_fade")
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:lod_boundary_inset",
                                        Component.translatable("voxy.config.general.lodBoundaryInset"),
                                        ()->cfg.lodBoundaryInset, v->cfg.lodBoundaryInset=v,
                                        new Range(8, 32, 1))
                                        .setFormatter(v->Component.translatable("voxy.config.unit.blocks", v))
                                        .setEnablerInherit("voxy:lod_boundary_fade")
                                        .setImpact(OptionImpact.LOW)
                        ),
                        new Group(Component.translatable("voxy.config.group.experimentalShaders"),
                                new BoolOption(
                                        "voxy:lod_lite_shading",
                                        Component.translatable("voxy.config.general.lodLiteShading"),
                                        ()->cfg.lodLiteShading, v->cfg.lodLiteShading=v)
                                        .setTooltipSupplier(v->liteShaderTooltip())
                                        .setImpact(OptionImpact.HIGH)
                                        .setEnablerInherit(s->IrisUtil.irisShaderPackEnabled(), ConfigState.UPDATE_ON_REBUILD)
                                        .setPostChangeFlags(renderReload, "voxy:iris_reload")
                        )
                ).setEnablerAND("voxy:enabled", "voxy:rendering");
    }

    private static Page compatibilityPage(VoxyConfig cfg, String renderReload,
                                          boolean sableInstalled, boolean createInstalled,
                                          boolean powerGridInstalled, boolean copycatsInstalled,
                                          boolean simulatedInstalled, boolean framedBlocksInstalled,
                                          boolean littleTilesInstalled, boolean domumInstalled,
                                          boolean seasonsInstalled) {
        return new Page(Component.translatable("voxy.config.compat"),
                        new Group(Component.translatable("voxy.config.group.create"),
                                new BoolOption(
                                        "voxy:distant_trains",
                                        Component.translatable("voxy.config.compat.distantTrains"),
                                        ()->cfg.distantTrains, v->cfg.distantTrains=v)
                                        .setEnablerInherit(s->me.cortex.voxy.client.ServerCapabilities.canConfigureTrains(), ConfigState.UPDATE_ON_REBUILD)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_train_distance",
                                        Component.translatable("voxy.config.compat.distantTrainDistance"),
                                        ()->cfg.distantTrainMaxChunks, v->cfg.distantTrainMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setEnablerInherit(s->me.cortex.voxy.client.ServerCapabilities.canConfigureTrains(), ConfigState.UPDATE_ON_REBUILD)
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:distant_tracks",
                                        Component.translatable("voxy.config.compat.distantTracks"),
                                        ()->cfg.distantTracks, v->cfg.distantTracks=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_track_distance",
                                        Component.translatable("voxy.config.compat.distantTrackDistance"),
                                        ()->cfg.distantTrackMaxChunks, v->cfg.distantTrackMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:distant_contraptions",
                                        Component.translatable("voxy.config.compat.distantContraptions"),
                                        ()->cfg.distantContraptions, v->cfg.distantContraptions=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_contraption_distance",
                                        Component.translatable("voxy.config.compat.distantContraptionDistance"),
                                        ()->cfg.distantContraptionMaxChunks, v->cfg.distantContraptionMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:distant_kinetics",
                                        Component.translatable("voxy.config.compat.distantKinetics"),
                                        ()->cfg.distantKinetics, v->cfg.distantKinetics=v)
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:kinetic_enclosed_culling",
                                        Component.translatable("voxy.config.compat.kineticEnclosedCulling"),
                                        ()->cfg.kineticEnclosedCulling, v->cfg.kineticEnclosedCulling=v)
                                        .setImpact(OptionImpact.LOW)
                        ).setEnablerInherit(s->createInstalled),
                        new Group(Component.translatable("voxy.config.group.aeronautics"),
                                new BoolOption(
                                        "voxy:sable_lod",
                                        Component.translatable("voxy.config.compat.sableLod"),
                                        ()->cfg.sableLodRendering, v->cfg.sableLodRendering=v)
                                        .setEnablerInherit(s->sableInstalled),
                                new IntOption(
                                        "voxy:sable_lod_distance",
                                        Component.translatable("voxy.config.compat.sableLodDistance"),
                                        ()->cfg.aeronauticsContraptionMaxChunks,
                                        v->cfg.aeronauticsContraptionMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.MEDIUM)
                                        .setEnablerInherit(s->sableInstalled),
                                new BoolOption(
                                        "voxy:sable_lod_force_loading",
                                        Component.translatable("voxy.config.compat.sableLodForceLoading"),
                                        ()->cfg.sableLodForceLoading, v->cfg.sableLodForceLoading=v)
                                        .setImpact(OptionImpact.HIGH)
                                        .setEnablerInherit(s->sableInstalled),
                                new IntOption(
                                        "voxy:sable_lod_load_distance",
                                        Component.translatable("voxy.config.compat.sableLodLoadDistance"),
                                        ()->cfg.sableLodLoadDistanceChunks, v->cfg.sableLodLoadDistanceChunks=v,
                                        new Range(8, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.HIGH)
                                        .setEnablerInherit(s->sableInstalled && s.readBooleanOption(
                                                ResourceLocation.parse("voxy:sable_lod_force_loading")), "voxy:sable_lod_force_loading"),
                                new IntOption(
                                        "voxy:sable_lod_load_budget",
                                        Component.translatable("voxy.config.compat.sableLodLoadBudget"),
                                        ()->cfg.sableLodLoadBudget, v->cfg.sableLodLoadBudget=v,
                                        new Range(1, 64, 1))
                                        .setImpact(OptionImpact.HIGH)
                                        .setEnablerInherit(s->sableInstalled && s.readBooleanOption(
                                                ResourceLocation.parse("voxy:sable_lod_force_loading")), "voxy:sable_lod_force_loading"),
                                new BoolOption(
                                        "voxy:distant_simulated_lasers",
                                        Component.translatable("voxy.config.compat.distantSimulatedLasers"),
                                        ()->cfg.distantSimulatedLasers, v->cfg.distantSimulatedLasers=v)
                                        .setImpact(OptionImpact.LOW)
                                        .setEnablerInherit(s->simulatedInstalled),
                                new IntOption(
                                        "voxy:distant_simulated_laser_distance",
                                        Component.translatable("voxy.config.compat.distantSimulatedLaserDistance"),
                                        ()->cfg.distantSimulatedLaserMaxChunks, v->cfg.distantSimulatedLaserMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW)
                                        .setEnablerInherit(s->simulatedInstalled)
                        ).setEnablerInherit(s->sableInstalled || simulatedInstalled),
                        new Group(Component.translatable("voxy.config.group.powergrid"),
                                new BoolOption(
                                        "voxy:distant_powergrid_wires",
                                        Component.translatable("voxy.config.compat.distantPowerGridWires"),
                                        ()->cfg.distantPowerGridWires, v->cfg.distantPowerGridWires=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_powergrid_wire_distance",
                                        Component.translatable("voxy.config.compat.distantPowerGridWireDistance"),
                                        ()->cfg.distantPowerGridWireMaxChunks, v->cfg.distantPowerGridWireMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW)
                        ).setEnablerInherit(s->powerGridInstalled),
                        new Group(Component.translatable("voxy.config.group.copycats"),
                                new BoolOption(
                                        "voxy:distant_copycats",
                                        Component.translatable("voxy.config.compat.distantCopycats"),
                                        ()->cfg.distantCopycats, v->cfg.distantCopycats=v)
                                        .setPostChangeFlags(renderReload)
                                        .setImpact(OptionImpact.MEDIUM),
                                new IntOption(
                                        "voxy:distant_copycats_distance",
                                        Component.translatable("voxy.config.compat.distantCopycatsDistance"),
                                        ()->cfg.distantCopycatsMaxChunks, v->cfg.distantCopycatsMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.MEDIUM)
                        ).setEnablerInherit(s->copycatsInstalled),
                        new Group(Component.translatable("voxy.config.group.framedblocks"),
                                new BoolOption(
                                        "voxy:distant_framedblocks",
                                        Component.translatable("voxy.config.compat.distantFramedBlocks"),
                                        ()->cfg.distantFramedBlocks, v->cfg.distantFramedBlocks=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_framedblocks_distance",
                                        Component.translatable("voxy.config.compat.distantFramedBlocksDistance"),
                                        ()->cfg.distantFramedBlocksMaxChunks, v->cfg.distantFramedBlocksMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW)
                        ).setEnablerInherit(s->framedBlocksInstalled),
                        new Group(Component.translatable("voxy.config.group.littletiles"),
                                new BoolOption(
                                        "voxy:distant_littletiles",
                                        Component.translatable("voxy.config.compat.distantLittleTiles"),
                                        ()->cfg.distantLittleTiles, v->cfg.distantLittleTiles=v)
                                        .setImpact(OptionImpact.MEDIUM),
                                new IntOption(
                                        "voxy:distant_littletiles_distance",
                                        Component.translatable("voxy.config.compat.distantLittleTilesDistance"),
                                        ()->cfg.distantLittleTilesMaxChunks, v->cfg.distantLittleTilesMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.MEDIUM)
                        ).setEnablerInherit(s->littleTilesInstalled),
                        new Group(Component.translatable("voxy.config.group.domum"),
                                new BoolOption(
                                        "voxy:distant_domum",
                                        Component.translatable("voxy.config.compat.distantDomum"),
                                        ()->cfg.distantDomum, v->cfg.distantDomum=v)
                                        .setImpact(OptionImpact.MEDIUM),
                                new IntOption(
                                        "voxy:distant_domum_distance",
                                        Component.translatable("voxy.config.compat.distantDomumDistance"),
                                        ()->cfg.distantDomumMaxChunks, v->cfg.distantDomumMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.MEDIUM)
                        ).setEnablerInherit(s->domumInstalled),
                        new Group(Component.translatable("voxy.config.group.seasons"),
                                new BoolOption(
                                        "voxy:es_snow_lod",
                                        Component.translatable("voxy.config.compat.esSnowLod"),
                                        ()->cfg.eclipticSeasonsSnowLod, v->cfg.eclipticSeasonsSnowLod=v),
                                new BoolOption(
                                        "voxy:es_lod_auto_reload",
                                        Component.translatable("voxy.config.compat.esLodAutoReload"),
                                        ()->cfg.eclipticSeasonsLodAutoReload, v->cfg.eclipticSeasonsLodAutoReload=v),
                                new BoolOption(
                                        "voxy:es_reload_on_season_change",
                                        Component.translatable("voxy.config.compat.esReloadOnSeasonChange"),
                                        ()->cfg.eclipticSeasonsReloadOnSeasonChange, v->cfg.eclipticSeasonsReloadOnSeasonChange=v)
                        ).setEnablerInherit(s->seasonsInstalled)
                ).setEnabler("voxy:enabled");
    }
    private static Component formatCreateDistance(int chunks) {
        return chunks == 0
                ? Component.translatable("voxy.config.compat.distanceFollowLod")
                : Component.translatable("voxy.config.compat.distanceChunks", chunks);
    }

    private static Component liteShaderTooltip() {
        var state = LiteShaderStatus.get();
        Component status = switch (state.code()) {
            case ACTIVE -> Component.translatable("voxy.config.general.lodLiteShading.status.active",
                    state.packName(), state.testedVersions());
            case REQUESTED -> Component.translatable("voxy.config.general.lodLiteShading.status.requested");
            case NO_VOXY_PATCH -> Component.translatable("voxy.config.general.lodLiteShading.status.noPatch");
            case MISSING_PROGRAMS -> Component.translatable("voxy.config.general.lodLiteShading.status.missingPrograms");
            case MISSING_CONTRACT -> Component.translatable("voxy.config.general.lodLiteShading.status.missingContract");
            case CONTRACT_MISMATCH -> Component.translatable("voxy.config.general.lodLiteShading.status.contractMismatch");
            case API_MISMATCH -> Component.translatable("voxy.config.general.lodLiteShading.status.apiMismatch",
                    state.detail());
            case TRANSITION_REQUIRED -> Component.translatable("voxy.config.general.lodLiteShading.status.transitionRequired");
            case ERROR -> Component.translatable("voxy.config.general.lodLiteShading.status.error", state.detail());
            case DISABLED -> Component.translatable("voxy.config.general.lodLiteShading.status.disabled");
        };
        return Component.translatable("voxy.config.general.lodLiteShading.tooltip")
                .append("\n")
                .append(status);
    }

}
