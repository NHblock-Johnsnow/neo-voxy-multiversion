package me.cortex.voxy.client.mixin;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 根据已加载模组选择客户端 Mixin，避免在专用服务端解析客户端类。 */
public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
    private static boolean valkyrienSkiesInstalled;
    private static boolean nvidiumInstalled;
    private static boolean connectorInstalled;
    private static boolean sableInstalled;
    private static boolean eclipticSeasonsInstalled;
    private static boolean createInstalled;
    private static boolean sodiumExtraInstalled;
    private static boolean aeronauticsInstalled;
    private static boolean simulatedInstalled;
    private static boolean bitsNBobsInstalled;
    private static boolean azimuthInstalled;
    private static boolean littleTilesInstalled;

    private static boolean isLoadedEarly(String modId) {
        var list = LoadingModList.get();
        return list != null && list.getModFileById(modId) != null;
    }

    @Override
    public void onLoad(String mixinPackage) {
        valkyrienSkiesInstalled = isLoadedEarly("valkyrienskies");
        nvidiumInstalled = isLoadedEarly("nvidium");
        connectorInstalled = isLoadedEarly("connector");
        sableInstalled = isLoadedEarly("sable");
        //Version-floored, not presence: the ClientLevel poll drives the stored-snow refresher,
        //whose store writes only render once the mesh view is armed - same gate as the view itself
        eclipticSeasonsInstalled =
                me.cortex.voxy.client.core.compat.eclipticseasons.EsCompatGate.shouldArm();
        createInstalled = isLoadedEarly("create");
        sodiumExtraInstalled = isLoadedEarly("sodium_extra");
        aeronauticsInstalled = isLoadedEarly("aeronautics");
        simulatedInstalled = isLoadedEarly("simulated");
        bitsNBobsInstalled = isLoadedEarly("bits_n_bobs");
        azimuthInstalled = isLoadedEarly("azimuth");
        littleTilesInstalled = isLoadedEarly("littletiles");

        if (isLoadedEarly("eclipticseasons_voxycompact")) {
            org.slf4j.LoggerFactory.getLogger("voxy").error(
                    "eclipticseasons_voxycompact detected: it targets the OFFICIAL voxy's internal"
                    + " classes, several of which do not exist in this fork, and its mixins are"
                    + " required - the game WILL crash during mixin bootstrap. Seasonal LOD support"
                    + " is built into this fork; remove eclipticseasons_voxycompact.");
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        // client 配置只声明渲染目标；专用服务端必须返回空列表，避免提前加载客户端类。
        if (FMLLoader.getDist() != Dist.CLIENT) {
            return mixins;
        }
        //(sable.MixinSableSubLevelRenderSectionManager omitted: its sable target class was removed in
        // sable 2.0.3 and its sodium ctor target no longer matches sodium 0.8.12.)
        if (sableInstalled) {
            mixins.add("minecraft.MixinGameRendererSableRenderDistance");
            mixins.add("sable.MixinSableReacharoundCulling");
            mixins.add("sable.MixinSableDepthShim");
        }
        if (valkyrienSkiesInstalled && !nvidiumInstalled) {
            mixins.add("sodium.MixinSodiumWorldRendererVS");
        } else {
            mixins.add("sodium.MixinDefaultChunkRenderer");
        }

        if (createInstalled) {
            mixins.add("create.MixinTrackRenderer");
            mixins.add("create.MixinTrackVisual");
            mixins.add("create.AccessorContraptionVisual");
            mixins.add("create.AccessorAbstractEntityVisual");
            mixins.add("create.MixinCarriageContraptionVisual");
            mixins.add("create.MixinCarriageContraptionEntityRenderer");
            mixins.add("create.MixinStationRenderer");
            mixins.add("create.MixinContraptionEntityRenderer");
            mixins.add("create.MixinContraptionVisual");
            mixins.add("create.AccessorAbstractBlockEntityVisual");
            mixins.add("create.AccessorAbstractVisualLevel");
            mixins.add("create.MixinKineticBlockEntityVisual");
            mixins.add("create.MixinKineticMachineVisuals");
            if (bitsNBobsInstalled) {
                mixins.add("create.MixinBnbKineticVisuals");
            }
            if (azimuthInstalled) {
                mixins.add("create.MixinAzimuthBehaviourVisual");
            }
            mixins.add("create.MixinVisualizationManagerImpl");
            mixins.add("create.MixinSafeBlockEntityRenderer");
            //Ship-borne contraptions: force open the plot-coordinate render gates that kill them
            //(vanilla dispatcher distance/frustum + EntityCulling, update-rate banding)
            mixins.add("create.MixinEntityRenderDispatcherShip");
            mixins.add("create.MixinBandedPrimeLimiter");
            mixins.add("create.AccessorControlledContraptionEntity");
            //Disassembly is the one removal with an explicit signal: kill the frozen snapshot at once
            //instead of letting the 2s presence grace show a ghost where the blocks just landed
            mixins.add("create.MixinContraptionDisassembly");
            // Remove a frozen kinetic copy as soon as the live block leaves a loaded chunk.
            mixins.add("create.MixinLevelChunkKineticRemoval");
            mixins.add("create.MixinSmartBlockEntity");
            // Gantry identity, per-entity Flywheel presence, and a shared capture clock.
            mixins.add("create.AccessorGantryContraptionEntity");
            mixins.add("create.AccessorFlywheelStorage");
            mixins.add("create.MixinAnimationTickHolder");
        }
        if (littleTilesInstalled) {
            mixins.add("littletiles.MixinLittleTilesBlockEntity");
        }
        if (sodiumExtraInstalled) {
            mixins.add("sodiumextra.MixinFogDistanceHelper");
        }
        if (aeronauticsInstalled) {
            mixins.add("aeronautics.MixinClientBalloonEffectRenderer");
        }
        if (simulatedInstalled) {
            mixins.add("simulated.MixinAbstractLaserRenderer");
        }

        if (eclipticSeasonsInstalled && FMLLoader.getDist() == Dist.CLIENT) {
            mixins.add("eclipticseasons.MixinClientLevel");
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
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
