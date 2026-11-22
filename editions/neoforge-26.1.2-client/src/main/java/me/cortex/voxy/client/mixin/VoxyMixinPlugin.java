package me.cortex.voxy.client.mixin;

import java.util.List;
import java.util.Set;
import me.cortex.voxy.client.compat.seedgen.SeedGenBootstrap;
import me.cortex.voxy.client.compat.seedgen.SeedGenCalls;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class VoxyMixinPlugin implements IMixinConfigPlugin {
   private boolean seedGenCompatible;
   private boolean irisLoaded;

   public void onLoad(String mixinPackage) {
      if (net.neoforged.fml.loading.FMLLoader.getCurrent().getDist().isClient()) {
         var loadingMods = net.neoforged.fml.loading.LoadingModList.get();
         var seedGenFile = loadingMods == null ? null : loadingMods.getModFileById(SeedGenBootstrap.MOD_ID);
         seedGenCompatible = seedGenFile != null && seedGenFile.getMods().stream().anyMatch(mod ->
                 SeedGenBootstrap.MOD_ID.equals(mod.getModId()) && SeedGenBootstrap.supports(mod.getVersion().toString()));
         if (seedGenCompatible) SeedGenBootstrap.prepare(null);
      }
      this.irisLoaded = isClassPresent("net.irisshaders.iris.Iris");
   }

   private static boolean isClassPresent(String className) {
      String resourceName = className.replace('.', '/') + ".class";
      return VoxyMixinPlugin.class.getClassLoader().getResource(resourceName) != null;
   }

   public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
      return !mixinClassName.startsWith("me.cortex.voxy.client.mixin.iris.") || this.irisLoaded;
   }

   public String getRefMapperConfig() {
      return null;
   }

   public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
   }

   public List<String> getMixins() {
      return seedGenCompatible ? List.of(SeedGenBootstrap.MIXIN) : null;
   }

   public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
   }

   public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
      if (mixinClassName.startsWith("me.cortex.voxy.client.mixin.seedgen.")) {
         SeedGenCalls.apply(targetClass, false);
      }
   }
}
