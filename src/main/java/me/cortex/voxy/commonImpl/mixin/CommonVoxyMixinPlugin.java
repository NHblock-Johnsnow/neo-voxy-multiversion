package me.cortex.voxy.commonImpl.mixin;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 选择通用/服务端兼容 Mixin；客户端专属目标由 ClientVoxyMixinPlugin 处理。 */
public class CommonVoxyMixinPlugin implements IMixinConfigPlugin {
    private boolean sableInstalled;
    private boolean createInstalled;
    private boolean simpleBackupsInstalled;

    /** 仅查询加载列表，不触发模组类初始化。 */
    private static boolean modOnLoadingList(String id) {
        try {
            var ll = FMLLoader.getLoadingModList();
            if (ll == null) {
                return false;
            }
            for (var m : ll.getMods()) {
                if (id.equals(m.getModId())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public void onLoad(String mixinPackage) {
        sableInstalled = modOnLoadingList("sable");
        createInstalled = modOnLoadingList("create");
        simpleBackupsInstalled = modOnLoadingList("simplebackups");
    }

    @Override
    public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        if (createInstalled) {
            mixins.add("create.MixinCarriageShapeRevision");
            mixins.add("create.MixinCarriageBlockChange");
        }
        if (simpleBackupsInstalled) {
            mixins.add("simplebackups.MixinBackupThread");
        }
        if (sableInstalled) {
            mixins.add("minecraft.MixinServerLevel");
            mixins.add("minecraft.MixinChunkHolder");
            if (FMLLoader.getDist() == Dist.CLIENT) {
                //References ClientLevel; attaching on a dedicated server crashes the mixin transformer once
                //sable's ClientSubLevel class gets touched server side.
                mixins.add("sable.MixinClientSubLevelFinalizeLighting");
            }
            mixins.add("sable.MixinPhysicsChunkTicketManager");
            mixins.add("sable.MixinSubLevelHoldingChunk");
            mixins.add("sable.MixinSubLevelHoldingChunkMap");
            mixins.add("sable.MixinSubLevelTrackingSystem");
            mixins.add("sable.SableSubLevelHoldingChunkMapAccessor");
            if (createInstalled) {
                //Ship-borne contraption entities must track as far as their ship's hull does, or the
                //structure pops off the distant hull at the entity view distance (references Create)
                mixins.add("sable.MixinChunkMapTrackedEntityShip");
            }
        }
        return mixins;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
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
