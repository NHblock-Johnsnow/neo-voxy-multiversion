package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import me.cortex.voxy.client.compat.create.DistantKineticRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SmartBlockEntity.class)
public abstract class MixinSmartBlockEntity {
    // Create 自定义方法不参与 Minecraft 名称映射。
    @Inject(method = "readClient(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"), remap = false)
    private void voxy$invalidateKineticPacket(CallbackInfo ci) {
        DistantKineticRenderer.onDataUpdated((SmartBlockEntity) (Object) this);
    }

    @Inject(method = "load(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
    private void voxy$invalidateKineticLoad(CallbackInfo ci) {
        DistantKineticRenderer.onDataUpdated((SmartBlockEntity) (Object) this);
    }
}
