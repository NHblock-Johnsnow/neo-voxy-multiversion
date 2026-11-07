package me.cortex.voxy.client.config;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.jellysquid.mods.sodium.client.gui.options.OptionFlag;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 1.20.1 Forge 的 Sodium 配置页。每个页面只负责一类运行时设置。 */
public abstract class VoxyConfigScreenPages {
    private static final int MAX_RENDER_DISTANCE = 64 * 16;

    private static final Component[] SSAO_MODE_LABELS = {
            Component.translatable("voxy.config.general.ssao_mode.auto"),
            Component.translatable("voxy.config.general.ssao_mode.basic"),
            Component.translatable("voxy.config.general.ssao_mode.better"),
            Component.translatable("voxy.config.general.ssao_mode.best")
    };
    private VoxyConfigScreenPages() {
    }

    public static List<OptionPage> pages() {
        VoxyConfig storage = VoxyConfig.CONFIG;
        return List.of(
                generalPage(storage),
                renderingPage(storage),
                requestDistancePage(storage),
                compatibilityPage(storage));
    }

    private static OptionPage generalPage(VoxyConfig storage) {
        List<OptionGroup> groups = new ArrayList<>();
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.general.enabled"))
                        .setTooltip(Component.translatable("voxy.config.general.enabled.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, value) -> {
                            s.enabled = value;
                            if (value && ClientSessionEvents.inSession) {
                                VoxyCommon.createInstance();
                            }
                            if (!value) {
                                var holder = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                                if (holder != null) {
                                    holder.voxy$shutdownRenderer();
                                }
                                VoxyCommon.shutdownInstance();
                            }
                            try {
                                IrisUtil.reload();
                            } catch (Throwable ignored) {
                            }
                        }, s -> s.enabled)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())

                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.serviceThreads"))
                        .setTooltip(Component.translatable("voxy.config.general.serviceThreads.tooltip"))
                        .setControl(option -> new SliderControl(option, 1,
                                Runtime.getRuntime().availableProcessors() * 2, 1,
                                value -> Component.literal(Integer.toString(value))))
                        .setBinding((s, value) -> {
                            s.serviceThreads = value;
                            var instance = VoxyCommon.getInstance();
                            if (instance != null) {
                                instance.updateDedicatedThreads();
                            }
                        }, s -> s.serviceThreads)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.general.useSodiumBuilder"))
                        .setTooltip(Component.translatable("voxy.config.general.useSodiumBuilder.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.VARIES)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setBinding((s, value) -> {
                            s.dontUseSodiumBuilderThreads = !value;
                            var instance = VoxyCommon.getInstance();
                            if (instance != null) {
                                instance.updateDedicatedThreads();
                            }
                        }, s -> !s.dontUseSodiumBuilderThreads)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.general.ingest"))
                        .setTooltip(Component.translatable("voxy.config.general.ingest.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, value) -> s.ingestEnabled = value, s -> s.ingestEnabled)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.general.showJoinMessage"))
                        .setTooltip(Component.translatable("voxy.config.general.showJoinMessage.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, value) -> s.showJoinMessage = value, s -> s.showJoinMessage)
                        .build())
                .build());

        return page("voxy.config.general", groups);
    }

    private static OptionPage renderingPage(VoxyConfig storage) {
        List<OptionGroup> groups = new ArrayList<>();
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.general.rendering"))
                        .setTooltip(Component.translatable("voxy.config.general.rendering.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, value) -> {
                            s.enableRendering = value;
                            var holder = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                            if (holder != null) {
                                if (value) {
                                    holder.voxy$createRenderer();
                                } else {
                                    holder.voxy$shutdownRenderer();
                                }
                            }
                            try {
                                IrisUtil.reload();
                            } catch (Throwable ignored) {
                            }
                        }, s -> s.enableRendering)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.subDivisionSize"))
                        .setTooltip(Component.translatable("voxy.config.general.subDivisionSize.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 6, 1,
                                value -> Component.translatable("voxy.config.general.renderQuality." + value)))
                        .setBinding((s, value) -> s.setRenderQualityLevel(value), VoxyConfig::getRenderQualityLevel)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.renderDistance"))
                        .setTooltip(Component.translatable("voxy.config.general.renderDistance.tooltip"))
                        .setControl(option -> new SliderControl(option, 10, MAX_RENDER_DISTANCE, 1,
                                value -> Component.literal(Integer.toString(value * 2))))
                        .setBinding((s, value) -> {
                            s.sectionRenderDistance = value / 16.0f;
                            var holder = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                            if (holder != null) {
                                var renderer = holder.voxy$getRenderSystem();
                                if (renderer != null) {
                                    renderer.setRenderDistance(s.sectionRenderDistance);
                                }
                            }
                        }, s -> Math.min(MAX_RENDER_DISTANCE, Math.round(s.sectionRenderDistance * 16)))
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.renderPressure"))
                        .setTooltip(Component.translatable("voxy.config.general.renderPressure.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 4, 1,
                                value -> Component.translatable("voxy.config.general.renderPressure." + value)))
                        .setBinding((s, value) -> s.renderPressure = value, VoxyConfig::getRenderPressureLevel)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.leafLodMode"))
                        .setTooltip(Component.translatable("voxy.config.general.leafLodMode.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 2, 1,
                                value -> Component.translatable("voxy.config.general.leafLodMode."
                                        + VoxyConfig.LeafLodMode.values()[value].name().toLowerCase(Locale.ROOT))))
                        .setBinding((s, value) -> s.setLeafLodMode(VoxyConfig.LeafLodMode.values()[value]),
                                s -> s.getLeafLodMode().ordinal())
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.earthCurveRatio"))
                        .setTooltip(Component.translatable("voxy.config.general.earthCurveRatio.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 10000, 50,
                                value -> Component.literal(Integer.toString(value))))
                        .setBinding((s, value) -> s.earthCurveRatio = value, s -> s.earthCurveRatio)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        OptionImpl<VoxyConfig, Boolean> adaptCloudDistance = OptionImpl.createBuilder(boolean.class, storage)
                .setName(Component.translatable("voxy.config.general.adaptCloudDistance"))
                .setTooltip(Component.translatable("voxy.config.general.adaptCloudDistance.tooltip"))
                .setControl(TickBoxControl::new)
                .setBinding((s, value) -> s.adaptCloudDistance = value, s -> s.adaptCloudDistance)
                .setImpact(OptionImpact.LOW)
                .build();
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.general.environmental_fog"))
                        .setTooltip(Component.translatable("voxy.config.general.environmental_fog.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, value) -> s.useEnvironmentalFog = value, s -> s.useEnvironmentalFog)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(SSAO.SSAOMode.class, storage)
                        .setName(Component.translatable("voxy.config.general.ssao_mode"))
                        .setTooltip(Component.translatable("voxy.config.general.ssao_mode.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SSAO.SSAOMode.class,
                                SSAO_MODE_LABELS))
                        .setBinding((s, value) -> {
                            s.setSSAOMode(value);
                            reloadActiveRenderer();
                        }, VoxyConfig::getSSAOMode)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(adaptCloudDistance)
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.cloudDistance"))
                        .setTooltip(Component.translatable("voxy.config.general.cloudDistance.tooltip"))
                        .setEnabledPredicate(() -> !adaptCloudDistance.getValue())
                        .setControl(option -> new SliderControl(option, 0, 2048, 2, value ->
                                adaptCloudDistance.getValue()
                                        ? Component.translatable("voxy.config.general.adaptive")
                                        : value < 1
                                        ? Component.translatable("voxy.config.general.default")
                                        : Component.literal(Integer.toString(value))))
                        .setBinding((s, value) -> s.cloudDistance = value, s -> s.cloudDistance)
                        .setImpact(OptionImpact.VARIES)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.fogIntensity"))
                        .setTooltip(Component.translatable("voxy.config.general.fogIntensity.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 100, 1,
                                value -> Component.literal(Integer.toString(value))))
                        .setBinding((s, value) -> s.fogIntensity = value / 100.0f,
                                s -> Math.round(s.fogIntensity * 100))
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.fogDensity"))
                        .setTooltip(Component.translatable("voxy.config.general.fogDensity.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 100, 1,
                                value -> Component.literal(Integer.toString(value))))
                        .setBinding((s, value) -> s.fogDensity = value / 100.0f,
                                s -> Math.round(s.fogDensity * 100))
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.skyFogDistance"))
                        .setTooltip(Component.translatable("voxy.config.general.skyFogDistance.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 1024, 1,
                                value -> Component.literal(Integer.toString(value))))
                        .setBinding((s, value) -> s.skyFogDistance = value, s -> s.skyFogDistance)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.fog_distance"))
                        .setTooltip(Component.translatable("voxy.config.general.fog_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 5, 200, 5,
                                value -> Component.literal(value + "%")))
                        .setBinding((s, value) -> s.fogDistancePercent = value,
                                s -> s.fogDistancePercent)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.biomeBlendRadius"))
                        .setTooltip(Component.translatable("voxy.config.general.biomeBlendRadius.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 7, 1,
                                value -> value == 0
                                        ? Component.translatable("voxy.config.general.biomeBlendRadius.off")
                                        : Component.literal(Integer.toString(value))))
                        .setBinding((s, value) -> s.biomeBlendRadius = value, s -> s.biomeBlendRadius)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.general.biomeBlendGrass"))
                        .setTooltip(Component.translatable("voxy.config.general.biomeBlendGrass.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, value) -> s.biomeBlendScope = value ? "water_grass" : "water",
                                s -> "water_grass".equals(s.biomeBlendScope))
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.compat.distantBeacons"))
                        .setTooltip(Component.translatable("voxy.config.compat.distantBeacons.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, value) -> s.distantBeacons = value, s -> s.distantBeacons)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.compat.distantBeaconDistance"))
                        .setTooltip(Component.translatable("voxy.config.compat.distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 512, 8,
                                VoxyConfigScreenPages::formatCompatDistance))
                        .setBinding((s, value) -> s.distantBeaconMaxChunks = value,
                                s -> s.distantBeaconMaxChunks)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        return page("voxy.config.rendering", groups);
    }

    private static OptionPage requestDistancePage(VoxyConfig storage) {
        List<OptionGroup> groups = new ArrayList<>();
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.fakesight.enabled"))
                        .setTooltip(Component.translatable("voxy.config.fakesight.enabled.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, value) -> {
                            s.enableExtendedRequestDistance = value;
                            if (Minecraft.getInstance().getConnection() != null) {
                                Minecraft.getInstance().options.broadcastOptions();
                            }
                        }, s -> s.enableExtendedRequestDistance)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.fakesight.distance"))
                        .setTooltip(Component.translatable("voxy.config.fakesight.distance.tooltip"))
                        .setControl(option -> new SliderControl(option, VoxyConfig.MIN_REQUEST_DISTANCE, VoxyConfig.MAX_REQUEST_DISTANCE, 1,
                                value -> Component.literal(Integer.toString(value))))
                        .setBinding((s, value) -> {
                            s.requestDistance = value;
                            if (Minecraft.getInstance().getConnection() != null) {
                                Minecraft.getInstance().options.broadcastOptions();
                            }
                        }, VoxyConfig::getRequestDistance)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .build());
        return page("voxy.config.fakesight", groups);
    }

    private static OptionPage compatibilityPage(VoxyConfig storage) {
        List<OptionGroup> groups = new ArrayList<>();


        if (net.minecraftforge.fml.ModList.get().isLoaded("create")) {
            groups.add(OptionGroup.createBuilder()
                    .add(createToggle(storage, "voxy.config.compat.distantTrains",
                            value -> storage.distantTrains = value,
                            () -> storage.distantTrains))
                    .add(createDistance(storage, "voxy.config.compat.distantTrainDistance",
                            value -> storage.distantTrainMaxChunks = value,
                            () -> storage.distantTrainMaxChunks))
                    .add(createToggle(storage, "voxy.config.compat.distantTracks",
                            value -> storage.distantTracks = value,
                            () -> storage.distantTracks))
                    .add(createDistance(storage, "voxy.config.compat.distantTrackDistance",
                            value -> storage.distantTrackMaxChunks = value,
                            () -> storage.distantTrackMaxChunks))
                    .add(createToggle(storage, "voxy.config.compat.distantContraptions",
                            value -> storage.distantContraptions = value,
                            () -> storage.distantContraptions))
                    .add(createDistance(storage, "voxy.config.compat.distantContraptionDistance",
                            value -> storage.distantContraptionMaxChunks = value,
                            () -> storage.distantContraptionMaxChunks))
                    .add(createToggle(storage, "voxy.config.compat.createKinetics",
                            value -> storage.distantKinetics = value,
                            () -> storage.distantKinetics))
                    .add(createDistance(storage, "voxy.config.compat.createKineticDistance",
                            value -> storage.distantKineticMaxChunks = value,
                            () -> storage.distantKineticMaxChunks))
                    .build());
        }
        return page("voxy.config.compat", groups);
    }

    private static OptionImpl<VoxyConfig, Boolean> createToggle(VoxyConfig storage, String key,
                                                                  java.util.function.Consumer<Boolean> setter,
                                                                  java.util.function.Supplier<Boolean> getter) {
        return OptionImpl.createBuilder(boolean.class, storage)
                .setName(Component.translatable(key))
                .setTooltip(Component.translatable(key + ".tooltip"))
                .setControl(TickBoxControl::new)
                .setBinding((s, value) -> setter.accept(value), s -> getter.get())
                .setImpact(OptionImpact.MEDIUM)
                .build();
    }

    private static OptionImpl<VoxyConfig, Integer> createDistance(VoxyConfig storage, String key,
                                                                    java.util.function.IntConsumer setter,
                                                                    java.util.function.IntSupplier getter) {
        return OptionImpl.createBuilder(int.class, storage)
                .setName(Component.translatable(key))
                .setTooltip(Component.translatable("voxy.config.compat.distance.tooltip"))
                .setControl(option -> new SliderControl(option, 0, 512, 8,
                        VoxyConfigScreenPages::formatCompatDistance))
                .setBinding((s, value) -> setter.accept(value), s -> getter.getAsInt())
                .setImpact(OptionImpact.MEDIUM)
                .build();
    }

    private static Component formatCompatDistance(int value) {
        return value == 0
                ? Component.translatable("voxy.config.compat.distanceFollowLod")
                : Component.translatable("voxy.config.compat.distanceChunks", value);
    }

    private static OptionPage page(String key, List<OptionGroup> groups) {
        return new OptionPage(Component.translatable(key), ImmutableList.copyOf(groups));
    }

    private static void reloadActiveRenderer() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            var holder = (IGetVoxyRenderSystem) minecraft.levelRenderer;
            if (holder != null && minecraft.level != null && VoxyConfig.CONFIG.isRenderingEnabled()) {
                holder.voxy$shutdownRenderer();
                holder.voxy$createRenderer();
            }
        } catch (Throwable ignored) {
        }
        try {
            IrisUtil.reload();
        } catch (Throwable ignored) {
        }
    }

}
