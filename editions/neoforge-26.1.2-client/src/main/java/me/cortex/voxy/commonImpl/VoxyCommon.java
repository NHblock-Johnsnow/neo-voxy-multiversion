package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;

public class VoxyCommon {
   public static final String MOD_VERSION;
   public static final boolean IS_DEDICATED_SERVER;
   public static final boolean IS_IN_MINECRAFT;
   private static VoxyInstance INSTANCE;
   private static VoxyCommon.IInstanceFactory FACTORY;
   public static final boolean IS_MINE_IN_ABYSS = false;

   public static boolean isVerificationFlagOn(String name) {
      return isVerificationFlagOn(name, false);
   }

   public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
      return System.getProperty("voxy." + name, defaultOn ? "true" : "false").equals("true");
   }

   public static void breakpoint() {
      int breakpoint = 0;
   }

   public static void setInstanceFactory(VoxyCommon.IInstanceFactory factory) {
      if (FACTORY != null) {
         throw new IllegalStateException("Cannot set instance factory more than once");
      } else {
         FACTORY = factory;
      }
   }

   public static VoxyInstance getInstance() {
      return INSTANCE;
   }

   public static void shutdownInstance() {
      if (INSTANCE != null) {
         VoxyInstance instance = INSTANCE;
         instance.shutdown();
         INSTANCE = null;
      }
   }

   public static void createInstance() {
      if (FACTORY != null) {
         if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
         } else {
            try {
               INSTANCE = FACTORY.create();
            } catch (DontCreateInstance var1) {
               Logger.info("Not creating instance due to DontCreateInstance");
            }
         }
      }
   }

   public static boolean isAvailable() {
      return FACTORY != null;
   }

   static {
      ModContainer mod = (ModContainer)ModList.get().getModContainerById("voxy").orElse(null);
      if (mod == null) {
         IS_IN_MINECRAFT = false;
         Logger.error("Running voxy without minecraft");
         MOD_VERSION = "<UNKNOWN>";
         IS_DEDICATED_SERVER = false;
      } else {
         IS_IN_MINECRAFT = true;
         MOD_VERSION = mod.getModInfo().getVersion().toString();
         IS_DEDICATED_SERVER = false;
      }

      FACTORY = null;
   }

   public interface IInstanceFactory {
      VoxyInstance create();
   }
}
