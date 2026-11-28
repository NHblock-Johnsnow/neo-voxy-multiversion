package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.core.util.RenderPassGuard;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SectionCollector.class, remap = false)
public class MixinSectionCollector {
    @Inject(method = "visit(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I)V", at = @At("HEAD"))
    private void voxy$collectVisibleSection(RenderSection section, int flags, CallbackInfo ci) {
        var levelRenderer = Minecraft.getInstance().levelRenderer;
        if (levelRenderer == null
                || IrisUtil.irisShadowActive()
                || RenderPassGuard.isSecondarySectionList()
                || !section.isBuilt()) {
            return;
        }

        var system = ((IGetVoxyRenderSystem) levelRenderer).voxy$getRenderSystem();
        if (system != null) {
            int x = section.getChunkX(), y = section.getChunkY(), z = section.getChunkZ();
            if (VoxyCommon.IS_MINE_IN_ABYSS) {
                int sector = (x+512)>>10;
                x -= sector<<10;
                y += 16+(256-32-sector*30);
            }
            system.chunkBoundRenderer.put(SectionPos.asLong(x, y, z));
        }
    }
}
