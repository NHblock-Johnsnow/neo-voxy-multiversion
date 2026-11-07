package me.cortex.voxy.compat.far;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class FarEntityClient {
    private static final FarPlayerTracker TRACKER = new FarPlayerTracker();
    private static final FarEntityRenderer RENDERER = new FarEntityRenderer(TRACKER);
    private static final FarEntityPackets.Assembler PACKETS = new FarEntityPackets.Assembler();
    private static ResourceLocation packetDimension;

    private FarEntityClient() {
    }

    public static boolean isVehicleProxy(net.minecraft.world.entity.Entity entity) {
        return RENDERER.isVehicleProxy(entity);
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        PACKETS.clear();
        packetDimension = null;
        TRACKER.clear();
        RENDERER.clear();
        sendHello();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PACKETS.clear();
        packetDimension = null;
        RENDERER.clear();
        TRACKER.clear();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        RENDERER.render(event);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        var dimension = level == null ? null : level.dimension().location();
        if (!java.util.Objects.equals(dimension, packetDimension)) {
            PACKETS.clear();
            packetDimension = dimension;
        }
        if (level == null || !isEnabled()) {
            PACKETS.clear();
            RENDERER.clear();
            TRACKER.clear();
        } else PACKETS.expire(System.nanoTime());
    }

    public static void handlePacket(Connection connection, Object packet) {
        var client = Minecraft.getInstance();
        var listener = client.getConnection();
        if (listener == null || listener.getConnection() != connection || !connection.isConnected()) return;
        if (client.level == null || !isEnabled()) {
            PACKETS.clear();
            RENDERER.clear();
            TRACKER.clear();
            return;
        }
        var dimension = client.level.dimension().location();
        if (!java.util.Objects.equals(dimension, packetDimension)) {
            PACKETS.clear();
            packetDimension = dimension;
        }
        if (packet instanceof FarEntityProtocol.PlayersPayload direct) {
            PACKETS.discard();
            if (client.level.dimension().location().toString().equals(direct.batch().dimensionKey())) handle(direct.batch());
        } else if (packet instanceof FarEntityPackets.FragmentPayload fragment) {
            FarEntityProtocol.PlayerBatch batch;
            try {
                batch = PACKETS.accept(fragment, System.nanoTime());
            } catch (RuntimeException malformed) {
                PACKETS.discard();
                return;
            }
            if (batch != null && client.level.dimension().location().toString().equals(batch.dimensionKey())) handle(batch);
        }
    }

    public static void handle(FarEntityProtocol.PlayerBatch batch) {
        if (!isEnabled()) {
            RENDERER.clear();
            TRACKER.clear();
            return;
        }
        TRACKER.apply(batch);
        RENDERER.onSnapshot();
    }

    static boolean isEnabled() {
        return me.cortex.voxy.client.ServerCapabilities.farEntities()
                && (VoxyConfig.CONFIG.enableFarPlayerRendering
                || VoxyConfig.CONFIG.enableFarVehicleRendering)
                && VoxyConfig.CONFIG.isRenderingEnabled()
                && !ModList.get().isLoaded("seeu");
    }

    public static void sendHello() {
        if (!me.cortex.voxy.client.ServerCapabilities.farEntities()) {
            return;
        }
        PacketDistributor.sendToServer(new FarEntityProtocol.HelloPayload(new FarEntityProtocol.Hello(
                FarEntityProtocol.VERSION,
                isEnabled(),
                VoxyConfig.CONFIG.enableFarVehicleRendering,
                VoxyConfig.CONFIG.getFarEntityRenderDistanceBlocks(),
                VoxyConfig.CONFIG.shareFarPlayerPosition
        )));
    }
}
