package me.cortex.voxy.commonImpl.compat.create;

import com.simibubi.create.Create;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.Train;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.BogeyPose;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.CarriagePose;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.CarriageShapePayload;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ContraptionPose;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ContraptionPosesPayload;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBlock;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBogey;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.TrainPosesPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CreateTrainSampler {
    public static final CreateTrainSampler INSTANCE = new CreateTrainSampler();


    private static double minSendDistance(MinecraftServer server, ServerPlayer player) {
        int effectiveViewChunks = Math.min(server.getPlayerList().getViewDistance(), player.requestedViewDistance());
        return Math.max(32, Math.min((effectiveViewChunks - 2) * 16, 208));
    }

    //Diagnostics surfaced by /voxy debug trains (integrated server) and worth having on dedicated
    public static volatile String lastError;
    public static volatile long lastSampleAtMs;
    public static volatile int lastTrainsSeen = -1;
    public static volatile int lastPosePacketsSent;

    private volatile SamplingSession session;

    // 每次开服独立持有缓存和任务门禁；旧 worker 只能访问旧会话。
    private static final class SamplingSession {
        final MinecraftServer server;
        final java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean();
        volatile SamplingTask samplingTask;
        final java.util.concurrent.atomic.AtomicBoolean sampleRequested = new java.util.concurrent.atomic.AtomicBoolean();
        int tickCounter;
        boolean loggedFirstSend;
        boolean cttReflectionFailed;
        int shapeBuildsThisRound;
        int sendPlayerCursor;
        volatile boolean transportPending;
        final java.util.concurrent.atomic.AtomicBoolean transportRequested = new java.util.concurrent.atomic.AtomicBoolean();
        long contraptionRevision;
        // 离开范围后丢弃投递记录，再次进入时重新发送。
        final Map<UUID, Map<UUID, Map<Long, CarriageShapePayload>>> sentShapes = new ConcurrentHashMap<>();
        final Map<UUID, Set<UUID>> visibleTrains = new ConcurrentHashMap<>();
        final Map<UUID, DistantTrainSendQueue> outgoing = new ConcurrentHashMap<>();
        final Map<UUID, ContraptionVisibility> visibleContraptions = new ConcurrentHashMap<>();
        final Map<UUID, ContraptionMailbox> contraptionMailboxes = new ConcurrentHashMap<>();
        // 车厢形状在会话内复用，避免为每个玩家重新构造。
        final Map<Long, CarriageShapePayload> shapeCache = new ConcurrentHashMap<>();
        final DistantTrainPackets.ShapeCache encodedShapes = new DistantTrainPackets.ShapeCache();
        final DistantTrainEncodingBudget encodingBudget = new DistantTrainEncodingBudget();
        final java.util.concurrent.ConcurrentLinkedQueue<DistantTrainSendQueue> retiredOutgoing = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final Set<UUID> loggedOut = ConcurrentHashMap.newKeySet();
        final Set<Long> failedShapes = ConcurrentHashMap.newKeySet();
        final Map<Long, ShapeSource> shapeSources = new ConcurrentHashMap<>();
        final Map<UUID, Map<Integer, Long>> shapeIds = new ConcurrentHashMap<>();
        final Map<UUID, ShapeRegistryState> shapeRegistry = new java.util.HashMap<>();
        final java.util.concurrent.atomic.AtomicLong nextShapeId = new java.util.concurrent.atomic.AtomicLong();
        final Map<ResourceKey<Level>, Set<ControlledContraptionEntity>> loadedContraptions = new ConcurrentHashMap<>();
        final Map<UUID, RemoteContraptionState> contraptionStates = new ConcurrentHashMap<>();

        SamplingSession(MinecraftServer server) {
            this.server = server;
        }
    }

    private static final class ShapeSource {
        final java.lang.ref.WeakReference<Carriage> carriage;
        final java.lang.ref.WeakReference<Contraption> contraption;
        final long revision;

        ShapeSource(Carriage carriage, Contraption contraption, long revision) {
            this.carriage = new java.lang.ref.WeakReference<>(carriage);
            this.contraption = new java.lang.ref.WeakReference<>(contraption);
            this.revision = revision;
        }

        boolean matches(Carriage carriage, Contraption live, long revision) {
            return this.carriage.get() == carriage && this.revision == revision
                    && (live == null || this.contraption.get() == live);
        }
    }

    private record RemoteContraptionState(ContraptionPose pose, boolean moving) {}
    private record ShapeRegistryState(java.lang.ref.WeakReference<Train> train, int carriages) {}
    private record ContraptionMailbox(Object connection,
            java.util.concurrent.atomic.AtomicReference<ContraptionPosesPayload> latest) {}
    private record ContraptionVisibility(ResourceKey<Level> dimension, int count, Vec3 playerPosition,
                                         double minimumDistanceSq, double maximumDistanceSq, long revision) {}

    //Key for the per-round pose cache: a carriage pose is identical for every player in a dimension
    private record PoseKey(long shapeId, ResourceKey<Level> dim) {}

    private static Field serialisedEntityField;

    private static final boolean CTT_LOADED = net.neoforged.fml.ModList.get().isLoaded("createthreadedtrains");
    private static Field cttThreadField;
    private static Method cttSubmitMethod;

    private CreateTrainSampler() {}

    @SubscribeEvent
    public void onServerStarting(ServerAboutToStartEvent event) {
        SamplingSession previous = this.session;
        this.session = new SamplingSession(event.getServer());
        if (previous != null) this.retireSession(previous);
        lastError = null;
        lastSampleAtMs = 0;
        lastTrainsSeen = -1;
        lastPosePacketsSent = 0;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        SamplingSession session = this.session;
        if (session == null || session.server != event.getServer()) return;
        if (++session.tickCounter >= DistantTrainConfig.sampleInterval()) {
            session.tickCounter = 0;
            this.sampleContraptionsSafe(session);
            this.scheduleSample(session);
        } else if (session.transportPending || session.transportRequested.get()) {
            this.scheduleWork(session);
        }
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        SamplingSession session = this.session;
        if (session != null && session.server == event.getLevel().getServer()
                && !event.getLevel().isClientSide() && event.getEntity() instanceof ControlledContraptionEntity contraption) {
            if (session.loadedContraptions.computeIfAbsent(event.getLevel().dimension(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(contraption)) session.contraptionRevision++;
        }
    }

    @SubscribeEvent
    public void onEntityLeave(EntityLeaveLevelEvent event) {
        SamplingSession session = this.session;
        if (session != null && session.server == event.getLevel().getServer()
                && !event.getLevel().isClientSide() && event.getEntity() instanceof ControlledContraptionEntity contraption) {
            var entities = session.loadedContraptions.get(event.getLevel().dimension());
            if (entities != null) {
                entities.remove(contraption);
            }
            session.contraptionStates.remove(contraption.getUUID());
            session.contraptionRevision++;
        }
    }

    private void sampleContraptionsSafe(SamplingSession session) {
        try {
            this.sampleContraptions(session);
        } catch (Throwable e) {
            Logger.error("Distant contraption pose sampling failed", e);
        }
    }

    private void sampleContraptions(SamplingSession session) {
        if (!DistantTrainConfig.contraptionsEnabled() || session.loadedContraptions.isEmpty()) {
            for (var player : session.server.getPlayerList().getPlayers()) {
                if (session.visibleContraptions.containsKey(player.getUUID())) this.sendContraptionPoses(session, player, List.of());
            }
            session.visibleContraptions.clear();
            session.contraptionStates.clear();
            return;
        }
        double maximumDistance = DistantTrainConfig.contraptionMaxDistance();
        double maximumDistanceSq = maximumDistance * maximumDistance;
        Map<ResourceKey<Level>, List<ContraptionPose>> posesByDimension = new java.util.HashMap<>();
        for (var dimensionEntry : session.loadedContraptions.entrySet()) {
            List<ContraptionPose> poses = null;
            for (ControlledContraptionEntity entity : dimensionEntry.getValue()) {
                if (entity.isRemoved() || entity.getContraption() == null) {
                    if (session.contraptionStates.remove(entity.getUUID()) != null) session.contraptionRevision++;
                    continue;
                }
                var axis = entity.getRotationAxis();
                if (axis == null) {
                    if (session.contraptionStates.remove(entity.getUUID()) != null) session.contraptionRevision++;
                    continue;
                }
                var pose = new ContraptionPose(entity.getUUID(), entity.getX(), entity.getY(), entity.getZ(),
                        (byte) axis.ordinal(), entity.getAngle(1.0f));
                var previous = session.contraptionStates.get(entity.getUUID());
                boolean changed = previous != null && poseChanged(previous.pose(), pose);
                if (previous == null || changed || previous.moving()) {
                    session.contraptionRevision++;
                    // 与上次有效姿态比较，避免每轮微小位移都被阈值吞掉。
                    previous = new RemoteContraptionState(pose, changed);
                    session.contraptionStates.put(entity.getUUID(), previous);
                }
                if (poses == null) poses = new ArrayList<>();
                poses.add(previous.pose());
            }
            if (poses != null) {
                posesByDimension.put(dimensionEntry.getKey(), poses);
            }
        }
        for (ServerPlayer player : session.server.getPlayerList().getPlayers()) {
            if (!player.connection.hasChannel(ContraptionPosesPayload.TYPE)) {
                if (session.visibleContraptions.remove(player.getUUID()) != null) this.sendContraptionPoses(session, player, List.of());
                continue;
            }
            var source = posesByDimension.get(player.level().dimension());
            if (source == null) {
                if (session.visibleContraptions.remove(player.getUUID()) != null) this.sendContraptionPoses(session, player, List.of());
                continue;
            }
            var visibility = session.visibleContraptions.get(player.getUUID());
            double minimumDistance = minSendDistance(session.server, player);
            double minimumDistanceSq = minimumDistance * minimumDistance;
            boolean sameDimension = visibility != null && visibility.dimension().equals(player.level().dimension());
            // 玩家、距离窗口和构装体姿态都未变化时，复用上次范围结果。
            if (sameDimension && visibility.revision() == session.contraptionRevision
                    && visibility.playerPosition().equals(player.position())
                    && visibility.minimumDistanceSq() == minimumDistanceSq
                    && visibility.maximumDistanceSq() == maximumDistanceSq) continue;
            List<ContraptionPose> poses = new ArrayList<>();
            for (ContraptionPose pose : source) {
                double dx = pose.x() - player.getX();
                double dy = pose.y() - player.getY();
                double dz = pose.z() - player.getZ();
                double distanceSq = dx * dx + dy * dy + dz * dz;
                if (distanceSq < minimumDistanceSq || distanceSq > maximumDistanceSq) {
                    continue;
                }
                poses.add(pose);
            }
            // 邮箱只保留最新完整可见集；worker 按 UUID 合并，失败重试不依赖再次采样。
            this.sendContraptionPoses(session, player, poses);
            session.visibleContraptions.put(player.getUUID(),
                    new ContraptionVisibility(player.level().dimension(), poses.size(), player.position(),
                            minimumDistanceSq, maximumDistanceSq, session.contraptionRevision));
        }
    }

    private void sendContraptionPoses(SamplingSession session, ServerPlayer player, List<ContraptionPose> poses) {
        if (this.session != session || session.loggedOut.contains(player.getUUID())
                || session.server.getPlayerList().getPlayer(player.getUUID()) != player) return;
        var connection = player.connection.getConnection();
        var mailbox = session.contraptionMailboxes.get(player.getUUID());
        if (mailbox == null || mailbox.connection() != connection) {
            mailbox = new ContraptionMailbox(connection, new java.util.concurrent.atomic.AtomicReference<>());
            session.contraptionMailboxes.put(player.getUUID(), mailbox);
        }
        mailbox.latest().set(new ContraptionPosesPayload(player.level().dimension().location(), List.copyOf(poses)));
        session.transportRequested.set(true);
    }

    private static boolean poseChanged(ContraptionPose a, ContraptionPose b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz > 1.0e-8
                || a.axis() != b.axis()
                || Math.abs(net.minecraft.util.Mth.wrapDegrees(a.angle() - b.angle())) > 0.01f;
    }

    private void sampleSafe(SamplingSession session) {
        if (this.session != session) return;
        try {
            this.sample(session);
            if (this.session == session) lastSampleAtMs = System.currentTimeMillis();
        } catch (Throwable e) {
            if (this.session != session) return;
            var top = e.getStackTrace().length > 0 ? " @ " + e.getStackTrace()[0] : "";
            lastError = e + top;
            Logger.error("Distant train sampling failed", e);
        }
    }

    private void scheduleSample(SamplingSession session) {
        session.sampleRequested.set(true);
        this.scheduleWork(session);
    }

    // 只接管尚未开始的任务；领取后释放闭包，外部队列不再保活旧会话。
    private static final class SamplingTask extends java.util.concurrent.atomic.AtomicReference<Runnable> implements Runnable {
        SamplingTask(Runnable work) {
            super(work);
        }

        @Override
        public void run() {
            Runnable work = this.getAndSet(null);
            if (work != null) work.run();
        }
    }

    private void scheduleWork(SamplingSession session) {
        if (!session.pending.compareAndSet(false, true)) return;
        var task = new SamplingTask(() -> {
            try {
                if (session.sampleRequested.getAndSet(false)) this.sampleSafe(session);
                this.drainSafe(session);
            } finally {
                this.closeRetired(session);
                session.samplingTask = null;
                session.pending.set(false);
                if (this.session != session) this.closeRetiredWhenIdle(session);
            }
        });
        session.samplingTask = task;
        if (this.session != session || !CTT_LOADED || !this.submitToTrainWorker(session, task)) task.run();
    }

    //Queue the sample onto createthreadedtrains' worker so it never races the off-thread train tick.
    //The worker is recreated per server run, so the field is re-read every time.
    private boolean submitToTrainWorker(SamplingSession session, Runnable task) {
        if (session.cttReflectionFailed) {
            return false;
        }
        try {
            if (cttThreadField == null) {
                Class<?> main = Class.forName("de.mrjulsen.ctt.CreateThreadedTrains");
                cttThreadField = main.getDeclaredField("thread");
                cttThreadField.setAccessible(true);
                cttSubmitMethod = Class.forName("de.mrjulsen.ctt.WorkerThread").getMethod("submitTask", Runnable.class);
            }
            Object worker = cttThreadField.get(null);
            if (worker == null) {
                return false;
            }
            cttSubmitMethod.invoke(worker, task);
            return true;
        } catch (Throwable e) {
            session.cttReflectionFailed = true;
            Logger.warn("createthreadedtrains present but its worker is unreachable; distant train sampling stays on the server thread", e);
            return false;
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SamplingSession session = this.session;
        if (session == null || session.server != event.getEntity().getServer()) return;
        session.loggedOut.add(event.getEntity().getUUID());
        session.sentShapes.remove(event.getEntity().getUUID());
        session.visibleTrains.remove(event.getEntity().getUUID());
        var outgoing = session.outgoing.remove(event.getEntity().getUUID());
        if (outgoing != null) {
            outgoing.requestClose();
            session.retiredOutgoing.add(outgoing);
            session.transportRequested.set(true);
        }
        session.visibleContraptions.remove(event.getEntity().getUUID());
        session.contraptionMailboxes.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        SamplingSession session = this.session;
        if (session != null && session.server == event.getEntity().getServer()) {
            session.loggedOut.remove(event.getEntity().getUUID());
            session.visibleContraptions.remove(event.getEntity().getUUID());
            session.contraptionMailboxes.remove(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        SamplingSession session = this.session;
        if (session != null && session.server == event.getServer()) {
            this.session = null;
            this.retireSession(session);
        }
    }

    private static final int SHAPE_BUILDS_PER_ROUND = 8;

    private void sample(SamplingSession session) {
        session.shapeBuildsThisRound = 0;
        this.pruneShapes(session);
        var server = session.server;
        var players = server.getPlayerList().getPlayers();
        //DistantTrainConfig combines the client's preference (integrated server) with the dedicated
        //server's uniform ceiling (voxy-server.toml); either side disabling it stops streaming.
        if (players.isEmpty() || !DistantTrainConfig.enabled() || Create.RAILWAYS.trains.isEmpty()) {
            if (!session.outgoing.isEmpty() || !session.visibleTrains.isEmpty()) {
                //Flush removals for anyone who still has meshes
                for (var player : players) {
                    this.sendRemovals(session, player, session.visibleTrains.get(player.getUUID()), Set.of());
                }
            }
            return;
        }

        //Snapshot: on the fallback path under createthreadedtrains the map may still be mutated
        //concurrently; a torn copy at worst drops one sampling round (caller catches).
        var trains = new ArrayList<>(Create.RAILWAYS.trains.values());
        if (this.session != session) return;
        lastTrainsSeen = trains.size();
        double streamMax = DistantTrainConfig.maxDistance();
        double streamMaxSq = streamMax * streamMax;
        Map<PoseKey, CarriagePose> poseRoundCache = new java.util.HashMap<>();
        for (ServerPlayer player : players) {
            if (this.session != session) return;
            if (session.loggedOut.contains(player.getUUID())) continue;
            if (!player.connection.hasChannel(CarriageShapePayload.TYPE)
                    || !player.connection.hasChannel(TrainPosesPayload.TYPE)) continue;
            var outgoing = this.queueFor(session, player);
            if (outgoing.isClosed()) continue;
            var playerDim = player.level().dimension();
            var playerPos = player.position();
            var nowVisible = new HashSet<UUID>();
            var shapesByTrain = session.sentShapes.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>());
            double minDist = minSendDistance(server, player);

            for (Train train : trains) {
                if (this.session != session || outgoing.isClosed()) break;
                List<CarriagePose> poses = null;
                for (int i = 0; i < train.carriages.size(); i++) {
                    Carriage carriage = train.carriages.get(i);
                    var dce = carriage.getDimensionalIfPresent(playerDim);
                    if (dce == null || dce.positionAnchor == null) {
                        continue;
                    }
                    double distSq = dce.positionAnchor.distanceToSqr(playerPos);
                    if (distSq < minDist * minDist || distSq > streamMaxSq) {
                        continue;
                    }

                    long shapeId = shapeId(session, train.id, i);
                    //Shape may lag behind (e.g. the carriage has not serialized yet) - poses stream
                    //regardless so the client tracks the train and renders the moment a shape lands
                    var shape = this.getOrBuildShape(session, player.serverLevel(), train, carriage, i, shapeId);
                    if (this.session != session) return;
                    if (shape != null) {
                        this.sendShape(session, player, shapesByTrain, train.id, shapeId, shape);
                    } else {
                        outgoing.cancelShape(train.id, shapeId);
                    }
                    if (shape != null && !session.loggedFirstSend) {
                        session.loggedFirstSend = true;
                        Logger.info("Distant train sampling active: first carriage shape queued for " + player.getGameProfile().getName());
                    }

                    //Pose is player-independent - compute once per (shapeId, dimension) per round
                    CarriagePose pose = poseRoundCache.get(new PoseKey(shapeId, playerDim));
                    if (pose != null) {
                        me.cortex.voxy.commonImpl.PerfStats.trainPoseCacheHit.increment();
                    }
                    if (pose == null) {
                        me.cortex.voxy.commonImpl.PerfStats.trainPoseCacheMiss.increment();
                        Vec3 leading = dce.rotationAnchors.getFirst();
                        Vec3 trailing = dce.rotationAnchors.getSecond();
                        float yaw = 0, pitch = 0;
                        if (leading != null && trailing != null) {
                            //Same convention as Carriage.DimensionalCarriageEntity.alignEntity
                            Vec3 diff = leading.subtract(trailing);
                            yaw = (float) (Math.atan2(diff.z, diff.x) * (180.0 / Math.PI)) + 180.0f;
                            pitch = (float) (-Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)) * (180.0 / Math.PI));
                        }
                        pose = new CarriagePose(i, shapeId,
                                dce.positionAnchor.x, dce.positionAnchor.y, dce.positionAnchor.z, yaw, pitch,
                                buildBogeyPoses(train, carriage, playerDim));
                        poseRoundCache.put(new PoseKey(shapeId, playerDim), pose);
                    }

                    if (poses == null) {
                        poses = new ArrayList<>(train.carriages.size());
                    }
                    poses.add(pose);
                }

                if (poses != null) {
                    if (this.session != session) return;
                    this.sendTrainPoses(session, player,
                            new TrainPosesPayload(train.id, playerDim.location(), poses));
                    nowVisible.add(train.id);
                }
            }

            var previouslyVisible = session.visibleTrains.get(player.getUUID());
            this.sendRemovals(session, player, previouslyVisible, nowVisible);
        }
    }

    private void sendShape(SamplingSession session, ServerPlayer player,
                           Map<UUID, Map<Long, CarriageShapePayload>> shapesByTrain,
                           UUID trainId, long shapeId, CarriageShapePayload shape) {
        if (this.session != session) return;
        var outgoing = this.queueFor(session, player);
        if (outgoing.isClosed()) return;
        if (outgoing.cancelRemoval(trainId)) shapesByTrain.remove(trainId);
        var delivered = shapesByTrain.computeIfAbsent(trainId, ignored -> new ConcurrentHashMap<>());
        // 同 ID 内容更新后重发；只有成功发送才记录，异常留给下一轮重试。
        if (delivered.get(shapeId) == shape || this.session != session) return;
        var encodedShapes = session.encodedShapes;
        outgoing.shape(shape, () -> encodedShapes.prepare(shape), () -> delivered.put(shapeId, shape));
    }

    private void sendTrainPoses(SamplingSession session, ServerPlayer player, TrainPosesPayload poses) {
        if (this.session != session) return;
        var outgoing = this.queueFor(session, player);
        if (outgoing.isClosed()) return;
        if (outgoing.cancelRemoval(poses.trainId())) {
            var shapes = session.sentShapes.get(player.getUUID());
            if (shapes != null) shapes.remove(poses.trainId());
        }
        // 首片已提交就可能到达客户端；取消半包时同样保留移除责任。
        outgoing.poses(poses, () -> session.visibleTrains
                .computeIfAbsent(player.getUUID(), ignored -> ConcurrentHashMap.newKeySet()).add(poses.trainId()));
    }

    private void sendRemovals(SamplingSession session, ServerPlayer player, Set<UUID> previous, Set<UUID> current) {
        var outgoing = this.queueFor(session, player);
        if (outgoing.isClosed()) return;
        outgoing.retainTrains(current);
        if (previous == null || !player.connection.hasChannel(TrainPosesPayload.TYPE)) {
            return;
        }
        var shapesByTrain = session.sentShapes.get(player.getUUID());
        for (UUID trainId : previous) {
            if (!current.contains(trainId)) {
                if (this.session != session) return;
                outgoing.remove(new TrainPosesPayload(trainId,
                        ResourceLocation.fromNamespaceAndPath("voxy", "removed"), List.of()), () -> {
                    previous.remove(trainId);
                    if (shapesByTrain != null) shapesByTrain.remove(trainId);
                });
            }
        }
    }

    private DistantTrainSendQueue queueFor(SamplingSession session, ServerPlayer player) {
        var connection = player.connection.getConnection();
        if (this.session == session && !session.loggedOut.contains(player.getUUID())
                && session.server.getPlayerList().getPlayer(player.getUUID()) == player) {
            var cached = session.outgoing.get(player.getUUID());
            if (cached != null && cached.connection == connection && !cached.isClosed()) return cached;
            var outgoing = session.outgoing.compute(player.getUUID(), (id, queue) -> {
                // 旧玩家快照不能移除同 UUID 新连接的队列和投递记录。
                if (session.loggedOut.contains(id) || session.server.getPlayerList().getPlayer(id) != player) return queue;
                if (queue != null) queue.close();
                session.sentShapes.remove(id);
                session.visibleTrains.remove(id);
                return new DistantTrainSendQueue(connection, session.encodingBudget);
            });
            if (outgoing != null && outgoing.connection == connection && !outgoing.isClosed()
                    && !session.loggedOut.contains(player.getUUID())
                    && session.server.getPlayerList().getPlayer(player.getUUID()) == player) return outgoing;
        }
        var closed = new DistantTrainSendQueue(connection, session.encodingBudget);
        closed.close();
        return closed;
    }

    private void closeRetired(SamplingSession session) {
        DistantTrainSendQueue retired;
        while ((retired = session.retiredOutgoing.poll()) != null) retired.close();
    }

    private void retireSession(SamplingSession session) {
        for (var outgoing : session.outgoing.values()) {
            outgoing.requestClose();
            session.retiredOutgoing.add(outgoing);
        }
        // worker 可能已停止而遗留排队任务；已开始的任务由其 finally 收尾。
        SamplingTask task = session.samplingTask;
        if (task != null) task.run();
        this.closeRetiredWhenIdle(session);
    }

    private void closeRetiredWhenIdle(SamplingSession session) {
        // 释放门禁后再检查，接住退出与 worker finally 之间迟到的清理项。
        while (!session.retiredOutgoing.isEmpty() && session.pending.compareAndSet(false, true)) {
            try {
                this.closeRetired(session);
            } finally {
                session.pending.set(false);
            }
        }
    }

    private void drainSafe(SamplingSession session) {
        session.transportRequested.set(false);
        this.closeRetired(session);
        if (this.session != session) return;
        int[] posePackets = {0};
        boolean pending = false;
        long now = System.nanoTime();
        var players = session.server.getPlayerList().getPlayers();
        int visited = 0, serviced = 0;
        int cursor = players.isEmpty() ? 0 : Math.floorMod(session.sendPlayerCursor, players.size());
        // 每 tick 全服至多八份玩家额度；轮换起点，避免固定玩家顺序造成饥饿。
        while (visited < players.size() && serviced < 8) {
            var player = players.get(cursor);
            cursor = (cursor + 1) % players.size();
            visited++;
            var mailbox = session.contraptionMailboxes.get(player.getUUID());
            if (mailbox != null && mailbox.connection() == player.connection.getConnection()) {
                var snapshot = mailbox.latest().getAndSet(null);
                if (snapshot != null) this.queueFor(session, player).contraptions(snapshot);
            }
            var queue = session.outgoing.get(player.getUUID());
            if (queue != null) queue.retainContraptionDimension(player.level().dimension().location());
            if (queue == null || queue.pendingCount() == 0 && queue.outstandingBytes() == 0) continue;
            serviced++;
            var connection = player.connection.getConnection();
            try {
                queue.drain(player.connection.hasChannel(DistantTrainPackets.FragmentPayload.TYPE),
                        () -> this.session == session && queue.connection == connection
                                && player.connection.getConnection() == connection
                                && session.outgoing.get(player.getUUID()) == queue && connection.isConnected(),
                        () -> connection.channel() != null && connection.channel().isWritable(),
                        (packet, completed) -> {
                            player.connection.send(new ClientboundCustomPayloadPacket(packet), new PacketSendListener() {
                                @Override
                                public void onSuccess() { completed.accept(true); }

                                @Override
                                public Packet<?> onFailure() {
                                    completed.accept(false);
                                    return null;
                                }
                            });
                            if (packet instanceof TrainPosesPayload
                                    || packet instanceof DistantTrainPackets.FragmentPayload fragment && fragment.isPoses()) {
                                posePackets[0]++;
                            }
                        }, now);
            } catch (RuntimeException failure) {
                if (this.session == session) {
                    lastError = failure.toString();
                    Logger.error("Distant train send failed for " + player.getGameProfile().getName(), failure);
                }
            }
            pending |= queue.pendingCount() != 0 || queue.outstandingBytes() != 0;
        }
        if (this.session == session) {
            session.sendPlayerCursor = cursor;
            session.transportPending = pending || visited < players.size();
            lastPosePacketsSent = posePackets[0];
        }
    }

    private CarriageShapePayload getOrBuildShape(SamplingSession session, ServerLevel level, Train train,
                                                Carriage carriage, int carriageIndex, long shapeId) {
        var entity = carriage.anyAvailableEntity();
        Contraption live = entity == null ? null : entity.getContraption();
        long revision = carriage instanceof CarriageShapeRevision source ? source.voxy$shapeRevision() : 0;
        var source = session.shapeSources.get(shapeId);
        if (source != null && !source.matches(carriage, live, revision)) {
            this.discardShape(session, shapeId);
            source = null;
        }
        var cached = session.shapeCache.get(shapeId);
        if (cached != null) {
            return cached;
        }
        if (session.failedShapes.contains(shapeId)) {
            return null;
        }
        //Budget the heavy deserialize+build; over budget this round, retry next tick (not a failure)
        if (session.shapeBuildsThisRound >= SHAPE_BUILDS_PER_ROUND) {
            me.cortex.voxy.commonImpl.PerfStats.trainShapeBuildDeferred.increment();
            return null;
        }
        // 只记录生命周期与活体身份，不强持有已卸载车体，也不逐玩家扫描方块/NBT。
        if (source == null) {
            source = new ShapeSource(carriage, live, revision);
            session.shapeSources.put(shapeId, source);
        }
        session.shapeBuildsThisRound++;
        try {
            Contraption contraption = resolveContraption(level, carriage);
            if (contraption == null) {
                //Not failed - the carriage may simply not have serialized yet; retry next round
                session.shapeBuildsThisRound--;
                return null;
            }
            List<ShapeBlock> blocks = new ArrayList<>();
            for (var entry : contraption.getBlocks().entrySet()) {
                var pos = entry.getKey();
                var state = entry.getValue().state();
                if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                    continue;
                }
                if (Math.abs(pos.getX()) > 127 || Math.abs(pos.getY()) > 127 || Math.abs(pos.getZ()) > 127) {
                    continue;
                }
                //Copycat looks live in block entity data, not the state - carry the material slice
                CompoundTag renderNbt = me.cortex.voxy.commonImpl.compat.CopycatCommon
                        .renderNbt(state, entry.getValue().nbt());
                blocks.add(new ShapeBlock((byte) pos.getX(), (byte) pos.getY(), (byte) pos.getZ(), state,
                        java.util.Optional.ofNullable(renderNbt)));
            }
            if (blocks.isEmpty()) {
                session.failedShapes.add(shapeId);
                return null;
            }
            List<ShapeBogey> bogeys = new ArrayList<>(2);
            collectBogeyInfo(carriage.bogeys.getFirst(), bogeys);
            if (carriage.isOnTwoBogeys()) {
                collectBogeyInfo(carriage.bogeys.getSecond(), bogeys);
            }
            var currentEntity = carriage.anyAvailableEntity();
            Contraption current = currentEntity == null ? null : currentEntity.getContraption();
            long currentRevision = carriage instanceof CarriageShapeRevision owner ? owner.voxy$shapeRevision() : 0;
            if (!source.matches(carriage, current, currentRevision)) return null;
            var payload = new CarriageShapePayload(train.id, carriageIndex, shapeId, resolveInitialYaw(carriage), blocks, bogeys);
            session.shapeCache.put(shapeId, payload);
            return payload;
        } catch (Throwable e) {
            session.failedShapes.add(shapeId);
            Logger.error("Failed to build distant shape for train " + train.id + " carriage " + carriageIndex, e);
            return null;
        }
    }

    private static void collectBogeyInfo(CarriageBogey bogey, List<ShapeBogey> out) {
        var style = bogey.getStyle();
        var size = bogey.getSize();
        var data = bogey.bogeyData != null ? bogey.bogeyData.copy() : new CompoundTag();
        out.add(new ShapeBogey(style.id, size.id(), size.wheelRadius(), data));
    }

    private static List<BogeyPose> buildBogeyPoses(Train train, Carriage carriage, ResourceKey<Level> dim) {
        if (train.derailed || train.graph == null) {
            return List.of();
        }
        List<BogeyPose> out = new ArrayList<>(2);
        if (!appendBogeyPose(train, carriage.bogeys.getFirst(), dim, out)) {
            return List.of();
        }
        if (carriage.isOnTwoBogeys() && !appendBogeyPose(train, carriage.bogeys.getSecond(), dim, out)) {
            return List.of();
        }
        return out;
    }

    private static boolean appendBogeyPose(Train train, CarriageBogey bogey, ResourceKey<Level> dim, List<BogeyPose> out) {
        var lead = bogey.leading();
        var trail = bogey.trailing();
        if (lead.edge == null || trail.edge == null || !dim.equals(bogey.getDimension())) {
            return false;
        }
        Vec3 p1 = lead.getPosition(train.graph);
        Vec3 p2 = trail.getPosition(train.graph);
        Vec3 anchor = p1.add(p2).scale(0.5);
        double dx = p1.x - p2.x, dy = p1.y - p2.y, dz = p1.z - p2.z;
        //CarriageBogey.updateAngles stores yaw = -yRot, pitch = xRot
        float yRot = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) + 90.0f;
        float xRot = (float) (Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI));
        out.add(new BogeyPose(anchor.x, anchor.y, anchor.z, -yRot, xRot, bogey.isUpsideDown()));
        return true;
    }

    //Assembly orientation, matching what applyLocalTransforms rotates by on a real entity. Read
    //from the live entity when present, else from the serialized entity NBT.
    private static float resolveInitialYaw(Carriage carriage) {
        try {
            var entity = carriage.anyAvailableEntity();
            if (entity != null) {
                return entity.getInitialYaw();
            }
            if (serialisedEntityField == null) {
                serialisedEntityField = Carriage.class.getDeclaredField("serialisedEntity");
                serialisedEntityField.setAccessible(true);
            }
            CompoundTag tag = (CompoundTag) serialisedEntityField.get(carriage);
            if (tag != null && tag.contains("InitialOrientation")) {
                return net.minecraft.core.Direction.valueOf(tag.getString("InitialOrientation").toUpperCase(java.util.Locale.ROOT)).toYRot();
            }
        } catch (Throwable e) {
            Logger.warn("Could not resolve initial yaw for a distant carriage, defaulting to 0", e);
        }
        return 0;
    }

    // 优先复用活体；模拟中的车厢只为远景读取存档形状。
    private static Contraption resolveContraption(ServerLevel level, Carriage carriage) throws Exception {
        var entity = carriage.anyAvailableEntity();
        if (entity != null && entity.getContraption() != null) {
            return entity.getContraption();
        }
        if (serialisedEntityField == null) {
            serialisedEntityField = Carriage.class.getDeclaredField("serialisedEntity");
            serialisedEntityField.setAccessible(true);
        }
        CompoundTag serialized = (CompoundTag) serialisedEntityField.get(carriage);
        if (serialized == null || !serialized.contains("Contraption")) {
            return null;
        }
        return readShapeContraption(level, serialized.getCompound("Contraption"));
    }

    private static Contraption readShapeContraption(ServerLevel level, CompoundTag saved) {
        Contraption shape = ContraptionType.fromType(saved.getString("Type"));
        if (shape.getClass() != CarriageContraption.class) {
            return Contraption.fromNBT(level, saved, false);
        }
        // 仅改变临时读取对象，避免逐块写入数组映射产生平方级查找。
        shape.isLegacy = new it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap<>();
        shape.readNBT(level, saved, false);
        // 调用者只读blocks，无需恢复碰撞世界或计算全部碰撞箱。
        return shape;
    }

    private static long shapeId(SamplingSession session, UUID trainId, int carriageIndex) {
        var ids = session.shapeIds.computeIfAbsent(trainId, ignored -> new ConcurrentHashMap<>());
        return ids.computeIfAbsent(carriageIndex,
                ignored -> session.nextShapeId.incrementAndGet());
    }

    private void discardShape(SamplingSession session, long shapeId) {
        session.shapeCache.remove(shapeId);
        session.encodedShapes.remove(shapeId);
        session.failedShapes.remove(shapeId);
        session.shapeSources.remove(shapeId);
    }

    private void pruneShapes(SamplingSession session) {
        var trains = session.shapeIds.entrySet().iterator();
        while (trains.hasNext()) {
            var entry = trains.next();
            Train train = Create.RAILWAYS.trains.get(entry.getKey());
            if (train == null) {
                for (long id : entry.getValue().values()) this.discardShape(session, id);
                session.shapeRegistry.remove(entry.getKey());
                trains.remove();
                continue;
            }
            int count = train.carriages.size();
            var previous = session.shapeRegistry.get(entry.getKey());
            // 稳定列车仅比较弱身份和车厢数，不重新遍历车厢或形状内容。
            if (previous != null && previous.train().get() == train && previous.carriages() == count) continue;
            if (previous != null && previous.train().get() != train) {
                for (long id : entry.getValue().values()) this.discardShape(session, id);
            }
            entry.getValue().entrySet().removeIf(shape -> {
                if (shape.getKey() < count) return false;
                this.discardShape(session, shape.getValue());
                return true;
            });
            session.shapeRegistry.put(entry.getKey(), new ShapeRegistryState(new java.lang.ref.WeakReference<>(train), count));
        }
    }

    //Full server-side pipeline dump for /voxy debug trains on an integrated server: every train,
    //every carriage, with distance, window verdict, shape cache state and shape source health.
    public static String debugDump(ServerPlayer player) {
        SamplingSession session = INSTANCE.session;
        if (session == null || session.server != player.getServer()) return "sampler: no active session";
        var sb = new StringBuilder("sampler: lastRun=");
        sb.append(lastSampleAtMs == 0 ? "never" : (System.currentTimeMillis() - lastSampleAtMs) + "ms ago")
                .append(" trainsSeen=").append(lastTrainsSeen)
                .append(" posePackets=").append(lastPosePacketsSent)
                .append(" shapeCache=").append(session.shapeCache.size())
                .append(" failedShapes=").append(session.failedShapes.size())
                .append(" ctt=").append(CTT_LOADED);
        if (lastError != null) {
            sb.append("\n lastError=").append(lastError);
        }
        var dim = player.level().dimension();
        var pos = player.position();
        double minDist = minSendDistance(player.serverLevel().getServer(), player);
        double streamMax = DistantTrainConfig.maxDistance();
        sb.append("\n distantTrains=").append(DistantTrainConfig.enabled())
                .append(" interval=").append(DistantTrainConfig.sampleInterval()).append("t")
                .append(" window=[").append((int) minDist).append(", ").append((int) streamMax)
                .append("] (min=view-derived, max=min(client,server))");
        for (Train train : new ArrayList<>(Create.RAILWAYS.trains.values())) {
            sb.append("\n train ").append(train.id.toString(), 0, 8)
                    .append(" carriages=").append(train.carriages.size())
                    .append(" graph=").append(train.graph != null)
                    .append(" derailed=").append(train.derailed);
            for (int i = 0; i < train.carriages.size(); i++) {
                Carriage carriage = train.carriages.get(i);
                sb.append("\n  #").append(i);
                var dce = carriage.getDimensionalIfPresent(dim);
                if (dce == null) {
                    sb.append(" <no presence in ").append(dim.location()).append('>');
                    continue;
                }
                if (dce.positionAnchor == null) {
                    sb.append(" positionAnchor=null");
                    continue;
                }
                int dist = (int) Math.sqrt(dce.positionAnchor.distanceToSqr(pos));
                long sid = shapeId(session, train.id, i);
                String shapeState = session.shapeCache.containsKey(sid) ? "cached"
                        : (session.failedShapes.contains(sid) ? "FAILED" : "pending");
                sb.append(" dist=").append(dist)
                        .append(dist < minDist ? " (inside min: Create's own entity renders)"
                                : (dist > streamMax ? " (beyond max)" : " (IN WINDOW)"))
                        .append(" shapeId=").append(Long.toHexString(sid))
                        .append(" shape=").append(shapeState)
                        .append(" src=").append(describeShapeSource(carriage));
            }
        }
        return sb.toString();
    }

    private static String describeShapeSource(Carriage carriage) {
        try {
            var entity = carriage.anyAvailableEntity();
            if (entity != null && entity.getContraption() != null) {
                return "live-entity";
            }
            if (serialisedEntityField == null) {
                serialisedEntityField = Carriage.class.getDeclaredField("serialisedEntity");
                serialisedEntityField.setAccessible(true);
            }
            CompoundTag tag = (CompoundTag) serialisedEntityField.get(carriage);
            if (tag == null || tag.isEmpty()) {
                return "serialised-nbt-empty";
            }
            return tag.contains("Contraption") ? "serialised-nbt-ok" : "serialised-nbt-missing-contraption";
        } catch (Throwable e) {
            return "probe-error:" + e;
        }
    }
}
