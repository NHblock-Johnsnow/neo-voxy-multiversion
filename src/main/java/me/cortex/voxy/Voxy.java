package me.cortex.voxy;

import me.cortex.voxy.client.VoxyJoinMessage;
import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.copycat.CopycatDistantRenderer;
import me.cortex.voxy.client.compat.create.DistantContraptionManager;
import me.cortex.voxy.client.compat.create.DistantContraptionRenderer;
import me.cortex.voxy.client.compat.create.DistantKineticRenderer;
import me.cortex.voxy.client.compat.create.DistantOcclusionDebug;
import me.cortex.voxy.client.compat.create.DistantTrackRenderer;
import me.cortex.voxy.client.compat.create.DistantTrainManager;
import me.cortex.voxy.client.compat.create.DistantTrainRenderer;
import me.cortex.voxy.client.compat.domum.DomumDistantRenderer;
import me.cortex.voxy.client.compat.littletiles.LittleTilesDistantRenderer;
import me.cortex.voxy.client.compat.powergrid.PowerGridWireRenderer;
import me.cortex.voxy.client.compat.simulated.DistantLaserRenderer;
import me.cortex.voxy.client.core.beacon.DistantBeaconRenderer;
import me.cortex.voxy.client.core.compat.eclipticseasons.EsCompatGate;
import me.cortex.voxy.client.core.compat.eclipticseasons.VoxyEsHandler;
import me.cortex.voxy.client.config.VoxyNeoForgeConfig;
import me.cortex.voxy.commonImpl.compat.create.CreateServerConfig;
import me.cortex.voxy.commonImpl.compat.create.CreateTrainSampler;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol;
import me.cortex.voxy.compat.far.FarEntityClient;
import me.cortex.voxy.compat.far.FarEntityProtocol;
import me.cortex.voxy.compat.far.FarEntityPackets;
import me.cortex.voxy.compat.far.FarEntityService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * NeoForge 1.21.1 的模组入口。
 *
 * 这里只负责把平台事件接到各功能模块；渲染和世界数据实现分别位于 client、common
 * 与 commonImpl 包中，避免入口类承担具体业务逻辑。
 */
@Mod("voxy")
public class Voxy {
    public static final String MODID = "voxy";

    private final FarEntityService farEntityService = new FarEntityService();

    public Voxy(IEventBus modEventBus, ModContainer container) {
        registerCommonEvents(modEventBus);
        registerCreateServerEvents(modEventBus, container);

        if (FMLLoader.getDist() == Dist.CLIENT) {
            registerClientEvents(container);
        }
    }

    /** 注册服务端和客户端都需要的远景实体通道。 */
    private void registerCommonEvents(IEventBus modEventBus) {
        modEventBus.addListener(this::registerFarEntityPayloads);
        NeoForge.EVENT_BUS.addListener(farEntityService::onServerTick);
        NeoForge.EVENT_BUS.addListener(farEntityService::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(farEntityService::onServerStopped);
    }

    /** Create 的采样和网络协议只能在 Create 存在时触碰，避免可选类提前加载。 */
    private static void registerCreateServerEvents(IEventBus modEventBus, ModContainer container) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }

        modEventBus.addListener(Voxy::registerPayloads);
        NeoForge.EVENT_BUS.register(CreateTrainSampler.INSTANCE);
        CreateServerConfig.register(container, modEventBus);
    }

    /** 注册客户端配置、渲染器和所有可选联动。 */
    private static void registerClientEvents(ModContainer container) {
        VoxyNeoForgeConfig.register(container);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.register(VoxyJoinMessage.INSTANCE);

        registerEclipticSeasons();
        registerCreateClientEvents();
        registerOptionalClientIntegrations();
        registerBeaconRenderer();
    }

    private static void registerEclipticSeasons() {
        if (EsCompatGate.shouldArm()) {
            NeoForge.EVENT_BUS.register(VoxyEsHandler.INSTANCE);
        }
    }

    /** Create 的每个远景渲染器独立注册，便于按功能开关和排查兼容问题。 */
    private static void registerCreateClientEvents() {
        if (!ModList.get().isLoaded("create")) {
            return;
        }

        var trainRenderer = new DistantTrainRenderer();
        NeoForge.EVENT_BUS.register(trainRenderer);
        LodPipelineHooks.register(trainRenderer);
        LodPipelineHooks.frameDebugProbe = DistantOcclusionDebug.PROBE;
        DistantTrainRenderer.bogeyMeshProvider = me.cortex.voxy.client.compat.create.DistantBogeyMeshes::getOrCapture;
        DistantTrainRenderer.bogeyMeshCaptureActive = me.cortex.voxy.client.compat.create.DistantBogeyMeshes::isCapturing;
        DistantTrainRenderer.bogeyMeshResetter = me.cortex.voxy.client.compat.create.DistantBogeyMeshes::clear;

        var trackRenderer = new DistantTrackRenderer();
        NeoForge.EVENT_BUS.register(trackRenderer);
        LodPipelineHooks.register(trackRenderer);

        var contraptionRenderer = new DistantContraptionRenderer();
        NeoForge.EVENT_BUS.register(contraptionRenderer);
        LodPipelineHooks.register(contraptionRenderer);

        var kineticRenderer = new DistantKineticRenderer();
        NeoForge.EVENT_BUS.register(kineticRenderer);
        LodPipelineHooks.register(kineticRenderer);

        var copycatRenderer = new CopycatDistantRenderer();
        NeoForge.EVENT_BUS.register(copycatRenderer);
        LodPipelineHooks.register(copycatRenderer);
        LodPipelineHooks.registerTranslucent(copycatRenderer);
    }

    /** 注册不依赖 Create 的可选客户端联动。 */
    private static void registerOptionalClientIntegrations() {
        if (ModList.get().isLoaded("littletiles")) {
            var renderer = new LittleTilesDistantRenderer();
            NeoForge.EVENT_BUS.register(renderer);
            LodPipelineHooks.register(renderer);
            LodPipelineHooks.registerTranslucent(renderer);
        }

        if (ModList.get().isLoaded("domum_ornamentum")) {
            var renderer = new DomumDistantRenderer();
            NeoForge.EVENT_BUS.register(renderer);
            LodPipelineHooks.register(renderer);
        }

        if (ModList.get().isLoaded("powergrid")) {
            var renderer = new PowerGridWireRenderer();
            NeoForge.EVENT_BUS.register(renderer);
            LodPipelineHooks.register(renderer);
        }

        if (ModList.get().isLoaded("simulated")) {
            var renderer = new DistantLaserRenderer();
            NeoForge.EVENT_BUS.register(renderer);
            LodPipelineHooks.registerTranslucent(renderer);
        }
    }

    private static void registerBeaconRenderer() {
        var renderer = new DistantBeaconRenderer();
        NeoForge.EVENT_BUS.register(renderer);
        LodPipelineHooks.register(renderer);
    }

    /** Create 服务端向客户端发送远景列车和动态结构状态。 */
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        if (!ModList.get().isLoaded("create")) return;
        var registrar = event.registrar("1").optional();
        registrar.playToClient(
                DistantTrainProtocol.CarriageShapePayload.TYPE,
                DistantTrainProtocol.CarriageShapePayload.CODEC,
                (payload, context) -> {
                    if (FMLLoader.getDist() == Dist.CLIENT) {
                        context.enqueueWork(() -> DistantTrainManager.handlePacket(context.connection(), payload));
                    }
                });
        registrar.playToClient(
                DistantTrainProtocol.TrainPosesPayload.TYPE,
                DistantTrainProtocol.TrainPosesPayload.CODEC,
                (payload, context) -> {
                    if (FMLLoader.getDist() == Dist.CLIENT) {
                        context.enqueueWork(() -> DistantTrainManager.handlePacket(context.connection(), payload));
                    }
                });
        registrar.playToClient(
                me.cortex.voxy.commonImpl.compat.create.DistantTrainPackets.FragmentPayload.TYPE,
                me.cortex.voxy.commonImpl.compat.create.DistantTrainPackets.FragmentPayload.CODEC,
                (payload, context) -> {
                    if (FMLLoader.getDist() == Dist.CLIENT) {
                        context.enqueueWork(() -> DistantTrainManager.handlePacket(context.connection(), payload));
                    }
                });
        registrar.playToClient(
                DistantTrainProtocol.ContraptionPosesPayload.TYPE,
                DistantTrainProtocol.ContraptionPosesPayload.CODEC,
                (payload, context) -> {
                    if (FMLLoader.getDist() == Dist.CLIENT && ModList.get().isLoaded("create")) {
                        context.enqueueWork(() -> DistantContraptionManager.handleRemotePoses(payload));
                    }
                });
    }

    /** 远景玩家协议在服务端始终注册，客户端接收端只在客户端分发。 */
    private void registerFarEntityPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("voxy")
                .versioned(Integer.toString(FarEntityProtocol.VERSION))
                .optional();

        registrar.playToServer(
                FarEntityProtocol.HelloPayload.TYPE,
                FarEntityProtocol.HelloPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        farEntityService.handleHello((ServerPlayer) context.player(), payload.hello())));

        if (FMLLoader.getDist() == Dist.CLIENT) {
            registrar.playToClient(
                    FarEntityProtocol.PlayersPayload.TYPE,
                    FarEntityProtocol.PlayersPayload.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() -> FarEntityClient.handlePacket(context.connection(), payload)));
            registrar.playToClient(
                    FarEntityPackets.FragmentPayload.TYPE,
                    FarEntityPackets.FragmentPayload.CODEC,
                    (payload, context) -> context.enqueueWork(() -> FarEntityClient.handlePacket(context.connection(), payload)));
        } else {
            registrar.playToClient(
                    FarEntityProtocol.PlayersPayload.TYPE,
                    FarEntityProtocol.PlayersPayload.STREAM_CODEC,
                    (payload, context) -> { });
            registrar.playToClient(
                    FarEntityPackets.FragmentPayload.TYPE,
                    FarEntityPackets.FragmentPayload.CODEC,
                    (payload, context) -> { });
        }
    }
}
