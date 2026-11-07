package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ClientSessionEvents;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Inject(method = "clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
    private void voxy$beforeClientLevelIsDiscarded(CallbackInfo ci) {
        ClientSessionEvents.sessionEnd();
    }

    // 所有退出重载汇入此处，暂停菜单不会经过无参 disconnect。
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("TAIL"))
    private void voxy$afterDisconnect(CallbackInfo ci) {
        ClientSessionEvents.sessionEnd();
    }
}
