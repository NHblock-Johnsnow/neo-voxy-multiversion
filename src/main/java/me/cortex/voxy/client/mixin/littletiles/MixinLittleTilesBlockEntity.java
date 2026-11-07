package me.cortex.voxy.client.mixin.littletiles;

import me.cortex.voxy.client.compat.create.SectionReingestQueue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "team.creative.littletiles.common.block.entity.BETiles", remap = false)
public abstract class MixinLittleTilesBlockEntity {
    @Inject(method = "loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V",
            at = @At("RETURN"), remap = false)
    private void voxy$afterTileData(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        this.voxy$queueSection();
    }

    @Inject(method = "setLevel(Lnet/minecraft/world/level/Level;)V", at = @At("RETURN"), remap = false)
    private void voxy$afterTileLevel(Level level, CallbackInfo ci) {
        this.voxy$queueSection();
    }

    @Unique
    private void voxy$queueSection() {
        // 初始 NBT 可能先于 setLevel；两条通知按区段合并，下一 tick 再读取完整内容。
        var entity = (BlockEntity) (Object) this;
        if (entity.getLevel() instanceof ClientLevel level) {
            SectionReingestQueue.scheduleSection(level, entity.getBlockPos());
        }
    }
}
