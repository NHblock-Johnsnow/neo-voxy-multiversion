package me.cortex.voxy.client;

import java.nio.file.Path;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

public class VoxyClientInstance extends VoxyInstance {
   private final VoxyClientInstance.Config config;
   private final Path basePath;
   private final boolean noIngestOverride;
   private static final VoxyClientInstance.Config DEFAULT_STORAGE_CONFIG;

   public VoxyClientInstance() {
      Path path = getBasePath();
      this.noIngestOverride = false;
      Path basePath = this.basePath = path.normalize();
      this.config = StorageConfigUtil.getCreateStorageConfig(
         VoxyClientInstance.Config.class, c -> c.version == 1 && c.sectionStorageConfig != null, () -> DEFAULT_STORAGE_CONFIG, basePath
      );
      super();
      this.updateDedicatedThreads();
   }

   @Override
   protected boolean shouldCreateInstance() {
      return !this.config.disabled;
   }

   @Override
   public void updateDedicatedThreads() {
      this.setNumThreads(VoxyConfig.CONFIG.serviceThreads);
   }

   @Override
   protected ImportManager createImportManager() {
      return new ClientImportManager();
   }

   @Override
   protected SectionStorage createStorage(WorldIdentifier identifier) {
      ConfigBuildCtx ctx = new ConfigBuildCtx();
      ctx.setProperty("{base_save_path}", this.basePath.toString());
      ctx.setProperty("{world_identifier}", identifier.getWorldId());
      ctx.setProperty("{player_uuid}", Minecraft.getInstance().getUser().getProfileId().toString().replace(':', '-'));
      ctx.pushPath("{base_save_path}/{world_identifier}/storage/");
      return this.config.sectionStorageConfig.build(ctx);
   }

   public Path getStorageBasePath() {
      return this.basePath;
   }

   @Override
   public boolean isIngestEnabled(WorldIdentifier worldId) {
      return !this.noIngestOverride && VoxyConfig.CONFIG.ingestEnabled;
   }

   @Override
   public void shutdown() {
      if (net.neoforged.fml.ModList.get().isLoaded("voxyseedgen")) {
          try {
              Class.forName("mobius.voxyseedgen.voxy.SeedFill").getMethod("stopForWorldClosing").invoke(null);
          } catch (ReflectiveOperationException | LinkageError error) {
              me.cortex.voxy.common.Logger.warn("Could not stop the external SeedGen job", error);
          }
      }
      super.shutdown();
      RenderResourceReuse.clearResources();
   }

   private static Path getBasePath() {
      Path basePath = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves");
      IntegratedServer iserver = Minecraft.getInstance().getSingleplayerServer();
      if (iserver != null) {
         basePath = iserver.getWorldPath(LevelResource.ROOT).resolve("voxy");
      } else {
         MultiPlayerGameMode netHandle = Minecraft.getInstance().gameMode;
         if (netHandle == null) {
            Logger.error("Network handle null");
            basePath = basePath.resolve("UNKNOWN");
         } else {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            ServerData info = connection == null ? null : connection.getServerData();
            if (info == null) {
               Logger.error("Server info null");
               basePath = basePath.resolve("UNKNOWN");
            } else if (info.isRealm()) {
               basePath = basePath.resolve("realms");
            } else {
               basePath = basePath.resolve(info.ip.replace(":", "_"));
            }
         }
      }

      return basePath.toAbsolutePath();
   }

   static {
      VoxyClientInstance.Config config = new VoxyClientInstance.Config();
      config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
      DEFAULT_STORAGE_CONFIG = config;
   }

   private static class Config {
      public int version = 1;
      public boolean disabled = false;
      public SectionStorageConfig sectionStorageConfig;
   }
}
