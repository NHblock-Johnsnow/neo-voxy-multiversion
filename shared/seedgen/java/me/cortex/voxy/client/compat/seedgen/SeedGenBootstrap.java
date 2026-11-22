package me.cortex.voxy.client.compat.seedgen;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.Config;

import java.util.List;
import java.util.Set;

public final class SeedGenBootstrap {
    public static final String MOD_ID = "voxyseedgen";
    public static final String CONFIG = "voxyseedgen.mixins.json";
    public static final String MIXIN = "seedgen.SeedGenTargets";
    private static final Set<String> INCOMPATIBLE = Set.of(
            "VoxyCoarseChildMixin", "VoxyCoarsePosMixin", "VoxyNodeManagerMixin", "VoxyInsertCensusMixin",
            "VoxyFarPlaneMixin", "VoxyCullDistanceMixin", "VoxyDrawCountMixin", "VoxyLevelCountsMixin",
            "VoxyTraversalCensusMixin", "VoxyVisibleCensusMixin", "VoxyBuildTaskAccessor", "VoxyMeshOrderMixin",
            "VoxyMeshPriorityMixin", "VoxyLoggerMixin", "VoxyRingGuardMixin");

    private SeedGenBootstrap() {}

    public static boolean supports(String version) {
        return "0.3.1".equals(version);
    }

    public static void prepare(String refmap) {
        // 配置可能已从待选队列移出，按名称取 Mixin 缓存中的同一对象。
        var handle = Config.create(CONFIG, MixinEnvironment.getDefaultEnvironment());
        if (handle == null) throw new IllegalStateException("Missing Voxy SeedGen mixin configuration");
        var config = handle.getConfig();
        try {
            var clientMixins = config.getClass().getDeclaredField("mixinClassesClient");
            clientMixins.setAccessible(true);
            @SuppressWarnings("unchecked")
            var names = (List<String>) clientMixins.get(config);
            names.removeIf(INCOMPATIBLE::contains);
            if (refmap != null) {
                var refmapper = config.getClass().getDeclaredField("refMapperConfig");
                refmapper.setAccessible(true);
                refmapper.set(config, refmap);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot prepare Voxy SeedGen 0.3.1 mixins", e);
        }
    }
}
