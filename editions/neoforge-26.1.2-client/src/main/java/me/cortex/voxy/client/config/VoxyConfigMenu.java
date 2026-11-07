package me.cortex.voxy.client.config;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.config.SodiumConfigBuilder.BoolOption;
import me.cortex.voxy.client.config.SodiumConfigBuilder.EnumOption;
import me.cortex.voxy.client.config.SodiumConfigBuilder.Group;
import me.cortex.voxy.client.config.SodiumConfigBuilder.IntOption;
import me.cortex.voxy.client.config.SodiumConfigBuilder.Page;
import me.cortex.voxy.client.config.SodiumConfigBuilder.PostApplyOps;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/** 26.1.2 的 Sodium 配置页，按页面和功能组组织选项。 */
public class VoxyConfigMenu implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        if (!VoxyCommon.isAvailable()) {
            return;
        }

        var config = VoxyConfig.CONFIG;
        var options = builder.registerModOptions("voxy", "Neo-Voxy", VoxyCommon.MOD_VERSION)
                .setIcon(Identifier.parse("voxy:icon.png"));
        String renderReload = OptionFlag.REQUIRES_RENDERER_RELOAD.getId().toString();
        SodiumConfigBuilder.buildToSodium(builder, options, config::save, VoxyConfigMenu::registerPostApplyOps,
                generalPage(config, renderReload),
                renderingPage(config, renderReload),
                experimentalPage(config),
                requestDistancePage(config));
    }

    // 保存后的线程、光影和网络操作由 Sodium 按依赖关系调度。
    private static void registerPostApplyOps(PostApplyOps operations) {
        operations.register("voxy:update_threads", () -> {
                    var instance = VoxyCommon.getInstance();
                    if (instance != null) {
                        instance.updateDedicatedThreads();
                    }
                }, "voxy:enabled")
                .register("voxy:iris_reload", IrisUtil::reload)
                .register("voxy:refresh_chunk_request", () -> {
                    var minecraft = Minecraft.getInstance();
                    if (minecraft.getConnection() != null) {
                        minecraft.options.broadcastOptions();
                    }
                });
    }

    private static Page generalPage(VoxyConfig config, String renderReload) {
        return new Page(Component.translatable("voxy.config.general"),
                new Group(Component.translatable("voxy.config.group.general"),
                        new BoolOption("voxy:enabled", Component.translatable("voxy.config.general.enabled"),
                                () -> config.enabled, enabled -> {
                                    config.enabled = enabled;
                                    if (enabled && ClientSessionEvents.inSession) {
                                        VoxyCommon.createInstance();
                                    }
                                })
                                .setPostChangeRunner(enabled -> {
                                    if (!enabled) {
                                        var holder = IVoxyRenderSystemHolder.getNullableHolder();
                                        if (holder != null) {
                                            holder.voxy$shutdownRenderer();
                                        }
                                        VoxyCommon.shutdownInstance();
                                    }
                                })
                                .setPostChangeFlags(renderReload, "voxy:iris_reload")
                                .setEnabler(null)),
                new Group(Component.translatable("voxy.config.group.threads"),
                        new IntOption("voxy:thread_count", Component.translatable("voxy.config.general.serviceThreads"),
                                () -> config.serviceThreads, value -> config.serviceThreads = value,
                                new Range(1, CpuLayout.getCoreCount(), 1))
                                .setPostChangeFlags("voxy:update_threads"),
                        new BoolOption("voxy:use_sodium_threads", Component.translatable("voxy.config.general.useSodiumBuilder"),
                                () -> !config.dontUseSodiumBuilderThreads, value -> config.dontUseSodiumBuilderThreads = !value)
                                .setPostChangeFlags("voxy:update_threads", renderReload)),
                new Group(Component.translatable("voxy.config.group.data"),
                        new BoolOption("voxy:ingest_enabled", Component.translatable("voxy.config.general.ingest"),
                                () -> config.ingestEnabled, value -> config.ingestEnabled = value),
                        new BoolOption("voxy:show_join_message", Component.translatable("voxy.config.general.showJoinMessage"),
                                () -> config.showJoinMessage, value -> config.showJoinMessage = value)))
                .setEnabler("voxy:enabled");
    }

    private static Page renderingPage(VoxyConfig config, String renderReload) {
        return new Page(Component.translatable("voxy.config.rendering"),
                new Group(Component.translatable("voxy.config.group.activation"),
                        new BoolOption("voxy:rendering", Component.translatable("voxy.config.general.rendering"),
                                () -> config.enableRendering, value -> config.enableRendering = value)
                                .setPostChangeRunner(enabled -> {
                                    var holder = IVoxyRenderSystemHolder.getNullableHolder();
                                    if (holder != null) {
                                        if (enabled) {
                                            holder.voxy$createRenderer();
                                        } else {
                                            holder.voxy$shutdownRenderer();
                                        }
                                    }
                                }, "voxy:enabled", renderReload)
                                .setPostChangeFlags("voxy:iris_reload")
                                .setEnabler("voxy:enabled")),
                qualityGroup(config, renderReload),
                vanillaEffectsGroup(config, renderReload),
                fogGroup(config, renderReload),
                biomeColoursGroup(config, renderReload))
                .setEnablerAND("voxy:enabled", "voxy:rendering");
    }

    private static Group qualityGroup(VoxyConfig config, String renderReload) {
        return new Group(Component.translatable("voxy.config.group.quality"),
                new IntOption("voxy:subdivsize", Component.translatable("voxy.config.general.subDivisionSize"),
                        config::getRenderQualityLevel, config::setRenderQualityLevel, new Range(0, 6, 1))
                        .setFormatter(value -> Component.translatable("voxy.config.general.renderQuality." + value))
                        .setImpact(OptionImpact.HIGH),
                new IntOption("voxy:render_distance", Component.translatable("voxy.config.general.renderDistance"),
                        () -> Math.round(config.sectionRenderDistance * 16.0F), value -> config.sectionRenderDistance = value / 16.0F,
                        new Range(10, 1024, 1))
                        .setFormatter(value -> Component.literal(Integer.toString(value * 2)))
                        .setPostChangeRunner(value -> {
                            var renderer = IVoxyRenderSystemHolder.getNullable();
                            if (renderer != null) {
                                renderer.setRenderDistance(config.sectionRenderDistance);
                            }
                        }, "voxy:rendering", renderReload)
                        .setImpact(OptionImpact.MEDIUM),
                new IntOption("voxy:render_pressure", Component.translatable("voxy.config.general.renderPressure"),
                        config::getRenderPressureLevel, value -> config.renderPressure = value, new Range(0, 4, 1))
                        .setFormatter(value -> Component.translatable("voxy.config.general.renderPressure." + value))
                        .setImpact(OptionImpact.HIGH),
                new IntOption("voxy:leaf_lod_mode", Component.translatable("voxy.config.general.leafLodMode"),
                        () -> config.getLeafLodMode().ordinal(),
                        value -> config.setLeafLodMode(VoxyConfig.LeafLodMode.values()[value]), new Range(0, 2, 1))
                        .setFormatter(value -> Component.translatable("voxy.config.general.leafLodMode."
                                + VoxyConfig.LeafLodMode.values()[value].name().toLowerCase(Locale.ROOT)))
                        .setImpact(OptionImpact.MEDIUM)
                        .setPostChangeFlags(renderReload),
                new IntOption("voxy:earth_curve_ratio", Component.translatable("voxy.config.general.earthCurveRatio"),
                        () -> config.earthCurveRatio, value -> config.earthCurveRatio = value > 0 && value < 50 ? 50 : value,
                        new Range(0, 10000, 50))
                        .setPostChangeFlags(renderReload)
                        .setImpact(OptionImpact.LOW));
    }

    private static Page experimentalPage(VoxyConfig config) {
        return new Page(Component.translatable("voxy.config.experimental"), transitionGroup(config))
                .setEnablerAND("voxy:enabled", "voxy:rendering");
    }

    private static Group transitionGroup(VoxyConfig config) {
        return new Group(Component.translatable("voxy.config.group.experimentalTransition"),
                new BoolOption("voxy:lod_boundary_fade", Component.translatable("voxy.config.general.lodBoundaryFade"),
                        () -> config.enableLodBoundaryFade, value -> config.enableLodBoundaryFade = value)
                        .setImpact(OptionImpact.LOW),
                new IntOption("voxy:lod_boundary_fade_length", Component.translatable("voxy.config.general.lodBoundaryFadeLength"),
                        () -> config.lodBoundaryFadeLength, value -> config.lodBoundaryFadeLength = value, new Range(8, 64, 1))
                        .setFormatter(value -> Component.translatable("voxy.config.unit.blocks", value))
                        .setEnablerInherit(state -> state.readBooleanOption(Identifier.parse("voxy:lod_boundary_fade")), "voxy:lod_boundary_fade")
                        .setImpact(OptionImpact.LOW),
                new IntOption("voxy:lod_boundary_inset", Component.translatable("voxy.config.general.lodBoundaryInset"),
                        () -> config.lodBoundaryInset, value -> config.lodBoundaryInset = value, new Range(8, 32, 1))
                        .setFormatter(value -> Component.translatable("voxy.config.unit.blocks", value))
                        .setEnablerInherit(state -> state.readBooleanOption(Identifier.parse("voxy:lod_boundary_fade")), "voxy:lod_boundary_fade")
                        .setImpact(OptionImpact.LOW));
    }

    private static Group fogGroup(VoxyConfig config, String renderReload) {
        return new Group(Component.translatable("voxy.config.group.fog"),
                new IntOption("voxy:fog_intensity", Component.translatable("voxy.config.general.fogIntensity"),
                        () -> Math.round(config.fogIntensity * 100.0F), value -> config.fogIntensity = value / 100.0F,
                        new Range(0, 100, 1))
                        .setEnablerInherit(state -> state.readBooleanOption(Identifier.parse("voxy:environmental_fog")), "voxy:environmental_fog")
                        .setImpact(OptionImpact.LOW),
                new IntOption("voxy:fog_density", Component.translatable("voxy.config.general.fogDensity"),
                        () -> Math.round(config.fogDensity * 100.0F), value -> config.fogDensity = value / 100.0F,
                        new Range(0, 100, 1))
                        .setEnablerInherit(state -> state.readBooleanOption(Identifier.parse("voxy:environmental_fog")), "voxy:environmental_fog")
                        .setImpact(OptionImpact.LOW),
                new IntOption("voxy:sky_fog_distance", Component.translatable("voxy.config.general.skyFogDistance"),
                        () -> config.skyFogDistance, value -> config.skyFogDistance = value, new Range(0, 1024, 1))
                        .setPostChangeFlags(renderReload)
                        .setEnablerInherit(state -> state.readBooleanOption(Identifier.parse("voxy:environmental_fog")), "voxy:environmental_fog")
                        .setImpact(OptionImpact.LOW),
                new IntOption("voxy:fog_distance", Component.translatable("voxy.config.general.fog_distance"),
                        () -> config.fogDistancePercent, value -> config.fogDistancePercent = value, new Range(5, 200, 5))
                        .setFormatter(value -> Component.literal(value + "%"))
                        .setEnablerInherit(state -> state.readBooleanOption(Identifier.parse("voxy:environmental_fog")), "voxy:environmental_fog")
                        .setImpact(OptionImpact.LOW))
                .setEnablerInherit(state -> !IrisUtil.irisShadersEnabledInConfig(), ConfigState.UPDATE_ON_REBUILD);
    }

    private static Group vanillaEffectsGroup(VoxyConfig config, String renderReload) {
        return new Group(Component.translatable("voxy.config.group.vanillaEffects"),
                new BoolOption("voxy:environmental_fog", Component.translatable("voxy.config.general.environmental_fog"),
                        () -> config.useEnvironmentalFog, value -> config.useEnvironmentalFog = value)
                        .setPostChangeFlags(renderReload),
                new EnumOption<>("voxy:ssao_mode", SSAO.SSAOMode.class,
                        Component.translatable("voxy.config.general.ssao_mode"), config::getSSAOMode, config::setSSAOMode)
                        .setNameProvider(value -> Component.translatable("voxy.config.general.ssao_mode." + value.name().toLowerCase(Locale.ROOT)))
                        .setImpact(OptionImpact.MEDIUM)
                        .setPostChangeFlags(renderReload))
                .setEnablerInherit(state -> !IrisUtil.irisShadersEnabledInConfig(), ConfigState.UPDATE_ON_REBUILD);
    }

    private static Page requestDistancePage(VoxyConfig config) {
        return new Page(Component.translatable("voxy.config.fakesight"),
                new Group(Component.translatable("voxy.config.group.chunkRequests"),
                        new BoolOption("voxy:fakesight_enabled", Component.translatable("voxy.config.fakesight.enabled"),
                                () -> config.enableExtendedRequestDistance, value -> config.enableExtendedRequestDistance = value)
                                .setImpact(OptionImpact.HIGH)
                                .setPostChangeFlags("voxy:refresh_chunk_request"),
                        new IntOption("voxy:fakesight_request_distance", Component.translatable("voxy.config.fakesight.distance"),
                                config::getRequestDistance, value -> config.requestDistance = value, new Range(8, 48, 1))
                                .setImpact(OptionImpact.HIGH)
                                .setPostChangeFlags("voxy:refresh_chunk_request")
                                .setEnablerInherit(state -> state.readBooleanOption(Identifier.parse("voxy:fakesight_enabled")), "voxy:fakesight_enabled"))
                        .setEnablerInherit(state -> Minecraft.getInstance().getConnection() == null
                                || Minecraft.getInstance().hasSingleplayerServer(), ConfigState.UPDATE_ON_REBUILD))
                .setEnablerAND("voxy:enabled", "voxy:rendering");
    }

    private static Group biomeColoursGroup(VoxyConfig config, String renderReload) {
        return new Group(Component.translatable("voxy.config.group.biomeColours"),
                new IntOption("voxy:biome_blend_radius", Component.translatable("voxy.config.general.biomeBlendRadius"),
                        () -> config.biomeBlendRadius, value -> config.biomeBlendRadius = value, new Range(0, 7, 1))
                        .setFormatter(value -> value == 0
                                ? Component.translatable("voxy.config.general.biomeBlendRadius.off")
                                : Component.literal(Integer.toString(value)))
                        .setPostChangeFlags(renderReload)
                        .setImpact(OptionImpact.MEDIUM),
                new BoolOption("voxy:biome_blend_grass", Component.translatable("voxy.config.general.biomeBlendGrass"),
                        () -> "water_grass".equals(config.biomeBlendScope),
                        value -> config.biomeBlendScope = value ? "water_grass" : "water")
                        .setPostChangeFlags(renderReload)
                        .setImpact(OptionImpact.MEDIUM));
    }

}
