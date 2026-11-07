package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.platform.PlatformUtil;
import me.cortex.voxy.common.platform.PlatformUtilImpl;

public class VoxyCommon {
    public static final String MOD_VERSION;
    public static final boolean IS_DEDICATED_SERVER;
    public static final boolean IS_IN_MINECRAFT;
    private static final PlatformUtil PLATFORM_UTIL = new PlatformUtilImpl();

    static {
        String version = PLATFORM_UTIL.getModVersion("voxy");
        if (version == null) {
            IS_IN_MINECRAFT = false;
            Logger.error("Running voxy without minecraft");
            MOD_VERSION = "<UNKNOWN>";
            IS_DEDICATED_SERVER = false;
        } else {
            IS_IN_MINECRAFT = true;
            MOD_VERSION = version;
            IS_DEDICATED_SERVER = PLATFORM_UTIL.isDedicatedServer();
            Serialization.init();
        }
    }

    //This is hardcoded like this because people do not understand what they are doing
    public static boolean isVerificationFlagOn(String name) {
        return isVerificationFlagOn(name, false);
    }

    public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
        return System.getProperty("voxy."+name, defaultOn?"true":"false").equals("true");
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }

    public static PlatformUtil getPlatformUtil() {
        return PLATFORM_UTIL;
    }

    public interface IInstanceFactory {VoxyInstance create();}
    private static VoxyInstance INSTANCE;
    private static IInstanceFactory FACTORY = null;

    public static void setInstanceFactory(IInstanceFactory factory) {
        if (FACTORY != null) {
            throw new IllegalStateException("Cannot set instance factory more than once");
        }
        FACTORY = factory;
    }

    public static VoxyInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            var instance = INSTANCE;
            instance.shutdown();
            INSTANCE = null;
        }
    }

    public static void createInstance() {
        if (FACTORY == null) {
            //Logger.info("Voxy factory");
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        try {
            INSTANCE = FACTORY.create();
        } catch (DontCreateInstance e) {
            Logger.info("Not creating instance due to DontCreateInstance");
        }
    }

    //Is voxy available in any capacity
    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static final boolean IS_MINE_IN_ABYSS = false;
}
