package me.cortex.voxy.client.compat.seedgen;

import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.connect.IMixinConnector;

public final class ForgeSeedGenConnector implements IMixinConnector {
    @Override
    public void connect() {
        // Forge 47 只读取清单里的 Mixin 配置，补注册原包的 TOML 声明。
        if (!FMLLoader.getDist().isClient()) return;
        var mods = LoadingModList.get();
        var file = mods == null ? null : mods.getModFileById(SeedGenBootstrap.MOD_ID);
        if (file == null) return;
        Mixins.addConfiguration(SeedGenBootstrap.CONFIG);
        if (file.getMods().stream().anyMatch(mod -> SeedGenBootstrap.MOD_ID.equals(mod.getModId())
                && SeedGenBootstrap.supports(mod.getVersion().toString()))) {
            SeedGenBootstrap.prepare("voxyseedgen.refmap.json");
        }
    }
}
