package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import me.cortex.voxy.client.compat.create.KineticSnapshots;
import me.cortex.voxy.client.compat.create.SectionReingestQueue;
import me.cortex.voxy.commonImpl.compat.CreateCopycatCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SmartBlockEntity.class)
public abstract class MixinSmartBlockEntity {
    // Create 自定义方法不参与 Minecraft 名称映射。
    @Inject(method = "readClient(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V",
            at = @At("TAIL"), remap = false)
    private void voxy$invalidateKineticPacket(CallbackInfo ci) {
        KineticSnapshots.onDataUpdated((SmartBlockEntity) (Object) this);
        this.voxy$queueCopycat();
    }

    @Inject(method = "loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V",
            at = @At("TAIL"))
    private void voxy$invalidateKineticLoad(CallbackInfo ci) {
        KineticSnapshots.onDataUpdated((SmartBlockEntity) (Object) this);
        this.voxy$queueCopycat();
    }

    @Unique
    private void voxy$queueCopycat() {
        // 材质包可能不改变方块状态；延后读取并合并同区段通知。
        var entity = (SmartBlockEntity) (Object) this;
        if (entity.getLevel() instanceof ClientLevel level
                && CreateCopycatCompat.isCopycatState(entity.getBlockState())) {
            SectionReingestQueue.scheduleSection(level, entity.getBlockPos());
        }
    }
}
