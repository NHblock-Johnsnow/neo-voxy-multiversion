package me.cortex.voxy.client.mixin.sable;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import me.cortex.voxy.client.compat.sable.SableReacharoundCulling;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = VanillaSubLevelRenderDispatcher.class, remap = false)
public abstract class MixinSableReacharoundCulling {
    @ModifyVariable(method = "renderSectionLayer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Iterable<ClientSubLevel> voxy$cullChunkedSubLevels(
            Iterable<ClientSubLevel> subLevels,
            Iterable<ClientSubLevel> originalSubLevels,
            RenderType renderType,
            ShaderInstance shader,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f modelView,
            Matrix4f projection,
            float partialTicks
    ) {
        return SableReacharoundCulling.filter(subLevels, cameraX, cameraY, cameraZ, modelView, projection);
    }

    @ModifyVariable(method = "renderBlockEntities", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Iterable<ClientSubLevel> voxy$cullBlockEntitySubLevels(
            Iterable<ClientSubLevel> subLevels,
            Iterable<ClientSubLevel> originalSubLevels,
            SubLevelRenderDispatcher.BlockEntityRenderer blockEntityRenderer,
            double cameraX,
            double cameraY,
            double cameraZ,
            float partialTicks
    ) {
        return SableReacharoundCulling.filter(subLevels, cameraX, cameraY, cameraZ, null, null);
    }

    @ModifyVariable(method = "renderAfterSections", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Iterable<ClientSubLevel> voxy$cullSingleBlockSubLevels(
            Iterable<ClientSubLevel> subLevels,
            Iterable<ClientSubLevel> originalSubLevels,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f modelView,
            Matrix4f projection,
            float partialTicks
    ) {
        return SableReacharoundCulling.filter(subLevels, cameraX, cameraY, cameraZ, modelView, projection);
    }
}
