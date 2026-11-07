package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class VoxyClient {
    private static final HashSet<String> FREX = new HashSet<>();
    private static FileLock EXCLUSIVE_LOCK;
    public static void initVoxyClient() {
        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (!systemSupported) {
             Logger.error("Voxy is unsupported on your system.");
        }

        if (systemSupported && System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            //Try acquire the lock file
            var vf = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
            if (!vf.toFile().isDirectory()) {
                vf.toFile().mkdir();
            }
            try {
                FileOutputStream fis = new FileOutputStream(vf.resolve("voxy.lock").toFile());
                EXCLUSIVE_LOCK = fis.getChannel().lock(0, Long.MAX_VALUE, false);
            } catch (NonWritableChannelException | IOException e) {
                //If some error write to log and unsupport
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                systemSupported = false;
            }

        }

        if (systemSupported) {

            SharedIndexBuffer.INSTANCE.id();

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            me.cortex.voxy.client.compat.LodPipelineHooks.register(
                    me.cortex.voxy.client.core.beacon.DistantBeaconRenderer.INSTANCE);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                    me.cortex.voxy.client.core.beacon.DistantBeaconRenderer.INSTANCE);
            if (isClassAvailable("com.simibubi.create.Create")) {
                var createRenderer = me.cortex.voxy.client.compat.create.DistantCreateRenderer.INSTANCE;
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(createRenderer);
                me.cortex.voxy.client.compat.LodPipelineHooks.register(createRenderer);
                var kineticRenderer = me.cortex.voxy.client.compat.create.DistantKineticRenderer.INSTANCE;
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(kineticRenderer);
                me.cortex.voxy.client.compat.LodPipelineHooks.register(kineticRenderer);
                var trackRenderer = me.cortex.voxy.client.compat.create.DistantTrackRenderer.INSTANCE;
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(trackRenderer);
                me.cortex.voxy.client.compat.LodPipelineHooks.register(trackRenderer);
            }

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        }
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    static void setFrexState(String name, boolean active) {
        if (active) {
            FREX.add(name);
        } else {
            FREX.remove(name);
        }
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    private static boolean isClassAvailable(String name) {
        try {
            Class.forName(name, false, VoxyClient.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}
