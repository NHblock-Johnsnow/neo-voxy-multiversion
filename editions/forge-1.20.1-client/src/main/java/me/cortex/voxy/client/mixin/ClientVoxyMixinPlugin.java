package me.cortex.voxy.client.mixin;

import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.client.compat.seedgen.SeedGenBootstrap;
import me.cortex.voxy.client.compat.seedgen.SeedGenCalls;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
    private boolean seedGenCompatible;
    private static boolean valkyrienSkiesInstalled;
    private static boolean nvidiumInstalled;
    private static boolean irisInstalled;
    private static boolean createInstalled;

    @Override
    public void onLoad(String mixinPackage) {
        if (net.minecraftforge.fml.loading.FMLLoader.getDist().isClient()) {
            var loadingMods = net.minecraftforge.fml.loading.LoadingModList.get();
            var seedGenFile = loadingMods == null ? null : loadingMods.getModFileById(SeedGenBootstrap.MOD_ID);
            seedGenCompatible = seedGenFile != null && seedGenFile.getMods().stream().anyMatch(mod ->
                    SeedGenBootstrap.MOD_ID.equals(mod.getModId()) && SeedGenBootstrap.supports(mod.getVersion().toString()));
        }
        valkyrienSkiesInstalled = VoxyCommon.getPlatformUtil().isModLoaded("valkyrienskies");
        nvidiumInstalled = VoxyCommon.getPlatformUtil().isModLoaded("nvidium");
        irisInstalled = VoxyCommon.getPlatformUtil().isModLoaded("iris")
                || VoxyCommon.getPlatformUtil().isModLoaded("oculus");
        createInstalled = VoxyCommon.getPlatformUtil().isModLoaded("create");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !mixinClassName.contains(".iris.") || irisInstalled;
    }

    @Override public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        if (seedGenCompatible) mixins.add(SeedGenBootstrap.MIXIN);
        if (valkyrienSkiesInstalled && !nvidiumInstalled) {
            mixins.add("sodium.MixinSodiumWorldRendererVS");
        } else {
            mixins.add("sodium.MixinDefaultChunkRenderer");
        }
        if (createInstalled) {
            mixins.add("create.MixinVisualizationManagerImpl");
            mixins.add("create.AccessorAbstractEntityVisual");
            mixins.add("create.MixinSafeBlockEntityRenderer");
            mixins.add("create.MixinSmartBlockEntity");
            mixins.add("create.MixinContraptionEntityRenderer");
            mixins.add("create.MixinContraptionVisual");
            mixins.add("create.MixinContraptionShapeRevision");
            mixins.add("create.MixinContraptionBlockChange");
            mixins.add("create.MixinCarriageShapeRevision");
            mixins.add("create.MixinCarriageBlockChange");
        }

        return mixins;
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (mixinClassName.startsWith("me.cortex.voxy.client.mixin.seedgen.")) {
            SeedGenCalls.apply(targetClass, false);
        }
    }
}
