package me.cortex.voxy.compat.far;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.compat.far.FarEntityProtocol.ItemSnapshot;
import me.cortex.voxy.compat.far.FarPlayerTracker.TrackedPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.HashMap;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

final class FarEntityRenderer {
    // Some modded vehicles inspect incomplete client-side state from their passenger acceptance hook.
    // Count failures instead of logging every frame; a missing pose must not take down the render loop.
    private static final java.util.concurrent.atomic.LongAdder FAR_MOUNT_ERRORS =
            new java.util.concurrent.atomic.LongAdder();

    public static long farMountErrors() {
        return FAR_MOUNT_ERRORS.sum();
    }

    private static final float WALK_ANIMATION_SCALE = 0.4F;
    private static final AtomicInteger NEXT_PROXY_ID = new AtomicInteger(1_000_000_000);
    private final FarPlayerTracker tracker;
    private final Map<UUID, PlayerProxy> playerProxies = new HashMap<>();
    private final Map<UUID, Entity> vehicleProxies = new HashMap<>();
    private final Map<UUID, VehicleRevision> vehicleProxyRevisions = new HashMap<>();
    private final Map<UUID, VehicleRevision> failedVehicleRevisions = new HashMap<>();
    private final Set<UUID> currentFailedVehicles = new HashSet<>();
    private final Set<UUID> activePlayers = new HashSet<>();
    private final Set<UUID> activeProxyVehicles = new HashSet<>();
    private final Set<UUID> renderedProxyVehicles = new HashSet<>();

    private static final class VehicleRevision {
        final String typeId;
        final byte[] renderData;
        final Map<TrackedPlayer, Integer> validatedOwners = new IdentityHashMap<>();

        VehicleRevision(TrackedPlayer tracked) {
            this.typeId = tracked.vehicleTypeId();
            this.renderData = tracked.vehicleRenderData();
            this.validatedOwners.put(tracked, tracked.vehicleDataRevision());
        }

        boolean matches(TrackedPlayer tracked) {
            Integer revision = this.validatedOwners.get(tracked);
            if (revision != null && revision == tracked.vehicleDataRevision()) return true;
            if (!this.typeId.equals(tracked.vehicleTypeId())
                    || !Arrays.equals(this.renderData, tracked.vehicleRenderData())) return false;
            this.validatedOwners.put(tracked, tracked.vehicleDataRevision());
            return true;
        }
    }

    void onSnapshot() {
        // revision 计数属于玩家，跨乘客需比较实际数据；同一快照内只比较一次。
        for (VehicleRevision revision : this.vehicleProxyRevisions.values()) revision.validatedOwners.clear();
        for (VehicleRevision revision : this.failedVehicleRevisions.values()) revision.validatedOwners.clear();
        if (this.failedVehicleRevisions.isEmpty()) return;
        for (TrackedPlayer tracked : this.tracker.players()) {
            if (!tracked.hasVehicle()) continue;
            VehicleRevision failed = this.failedVehicleRevisions.get(tracked.vehicleUuid());
            if (failed != null && failed.matches(tracked)) this.currentFailedVehicles.add(tracked.vehicleUuid());
        }
        this.failedVehicleRevisions.keySet().retainAll(this.currentFailedVehicles);
        this.currentFailedVehicles.clear();
    }

    FarEntityRenderer(FarPlayerTracker tracker) {
        this.tracker = tracker;
    }

    boolean isVehicleProxy(Entity entity) {
        return this.vehicleProxies.get(entity.getUUID()) == entity;
    }

    void clear() {
        for (PlayerProxy proxy : this.playerProxies.values()) {
            proxy.stopRiding();
        }
        for (Entity vehicle : this.vehicleProxies.values()) {
            vehicle.ejectPassengers();
        }
        this.playerProxies.clear();
        this.vehicleProxies.clear();
        this.vehicleProxyRevisions.clear();
        this.failedVehicleRevisions.clear();
        this.currentFailedVehicles.clear();
        this.activePlayers.clear();
        this.activeProxyVehicles.clear();
        this.renderedProxyVehicles.clear();
    }

    void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer localPlayer = minecraft.player;
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        if (level == null || localPlayer == null || poseStack == null || buffers == null) {
            this.clear();
            return;
        }
        if (!FarEntityClient.isEnabled()
                || !level.dimension().location().toString().equals(this.tracker.dimensionKey())) {
            this.clear();
            return;
        }
        if (this.tracker.isEmpty()) {
            if (!this.playerProxies.isEmpty() || !this.vehicleProxies.isEmpty() || !this.failedVehicleRevisions.isEmpty()) {
                this.clear();
            }
            return;
        }

        Vec3 cameraPosition = event.getCamera().getPosition();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        int maximumDistance = VoxyConfig.CONFIG.getFarEntityRenderDistanceBlocks();
        double maximumDistanceSquared = (double) maximumDistance * maximumDistance;
        int vanillaDistance = minecraft.options.getEffectiveRenderDistance() * 16;
        double vanillaDistanceSquared = (double) (vanillaDistance + 16) * (vanillaDistance + 16);
        int animationDistance = VoxyConfig.CONFIG.farPlayerAnimationDistance == 0
                ? maximumDistance : VoxyConfig.CONFIG.farPlayerAnimationDistance;
        double animationDistanceSquared = (double) animationDistance * animationDistance;
        int animationTick = localPlayer.tickCount;
        long now = System.nanoTime();
        double viewerX = localPlayer.getX();
        double viewerY = localPlayer.getY();
        double viewerZ = localPlayer.getZ();
        this.activePlayers.clear();
        this.activeProxyVehicles.clear();
        this.renderedProxyVehicles.clear();
        boolean renderedAny = false;
        boolean renderPlayers = VoxyConfig.CONFIG.enableFarPlayerRendering;
        boolean renderVehicles = VoxyConfig.CONFIG.enableFarVehicleRendering;

        for (TrackedPlayer tracked : this.tracker.players()) {
            float progress = tracked.progress(now);
            double positionX = tracked.renderX(progress);
            double positionY = tracked.renderY(progress);
            double positionZ = tracked.renderZ(progress);
            double dx = positionX - viewerX;
            double dy = positionY - viewerY;
            double dz = positionZ - viewerZ;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared > maximumDistanceSquared) {
                continue;
            }
            int chunkX = Mth.floor(positionX) >> 4;
            int chunkZ = Mth.floor(positionZ) >> 4;
            boolean realPlayerPresent = level.getPlayerByUUID(tracked.uuid()) != null;
            if (realPlayerPresent && level.hasChunk(chunkX, chunkZ)
                    && distanceSquared <= vanillaDistanceSquared) {
                continue;
            }

            PlayerProxy player = null;
            if (renderPlayers) {
                player = this.playerProxies.get(tracked.uuid());
                if (player == null || player.level() != level) {
                    player = new PlayerProxy(level, tracked.uuid(), tracked.name());
                    this.playerProxies.put(tracked.uuid(), player);
                }
                player.apply(tracked, positionX, positionY, positionZ, maximumDistance,
                        animationDistance > 0 && distanceSquared <= animationDistanceSquared,
                        animationTick, progress);
                this.activePlayers.add(tracked.uuid());
            }

            if (renderVehicles && tracked.hasVehicle()) {
                Entity liveVehicle = level.getEntity(tracked.vehicleEntityId());
                boolean useLiveVehicle = liveVehicle != null
                        && tracked.vehicleUuid().equals(liveVehicle.getUUID())
                        && tracked.vehicleTypeId().equals(typeId(liveVehicle));
                Entity vehicle = useLiveVehicle ? liveVehicle : this.getVehicleProxy(level, tracked);
                if (vehicle != null) {
                    if (!useLiveVehicle) {
                        this.applyVehicleState(vehicle, tracked, progress);
                        this.activeProxyVehicles.add(tracked.vehicleUuid());
                    }
                    if (player != null) player.mountVehicle(vehicle, !useLiveVehicle,
                            tracked.vehicleDataRevision(), animationTick);
                    if (!useLiveVehicle && this.renderedProxyVehicles.add(tracked.vehicleUuid())) {
                        PoseStack.Pose checkpoint = poseStack.last();
                        poseStack.pushPose();
                        try {
                            dispatcher.render(vehicle,
                                    tracked.renderVehicleX(progress) - cameraPosition.x,
                                    tracked.renderVehicleY(progress) - cameraPosition.y,
                                    tracked.renderVehicleZ(progress) - cameraPosition.z,
                                    tracked.renderVehicleYaw(progress), 0.0F,
                                    poseStack, buffers, LightTexture.FULL_BRIGHT);
                            renderedAny = true;
                        } catch (Throwable throwable) {
                            this.rejectVehicleProxy(tracked, player, vehicle, throwable);
                        } finally {
                            restorePose(poseStack, checkpoint);
                        }
                    }
                } else if (player != null) {
                    player.failedMount = null;
                    if (player.isPassenger()) player.stopRiding();
                }
            } else if (player != null) {
                player.failedMount = null;
                if (player.isPassenger()) player.stopRiding();
            }

            if (player != null) {
                PoseStack.Pose checkpoint = poseStack.last();
                poseStack.pushPose();
                try {
                    dispatcher.render(player,
                            positionX - cameraPosition.x,
                            positionY - cameraPosition.y,
                            positionZ - cameraPosition.z,
                            tracked.renderBodyYaw(progress), 0.0F,
                            poseStack, buffers, LightTexture.FULL_BRIGHT);
                    renderedAny = true;
                } catch (Throwable throwable) {
                    Entity proxyVehicle = tracked.hasVehicle()
                            ? this.vehicleProxies.get(tracked.vehicleUuid()) : null;
                    if (proxyVehicle != null && player.getVehicle() == proxyVehicle) {
                        this.rejectVehicleProxy(tracked, player, proxyVehicle, throwable);
                    } else if (throwable instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    } else if (throwable instanceof Error error) {
                        throw error;
                    } else {
                        throw new RuntimeException(throwable);
                    }
                } finally {
                    restorePose(poseStack, checkpoint);
                }
            }
        }

        if (renderedAny) buffers.endBatch();
        Iterator<Map.Entry<UUID, PlayerProxy>> playerIterator = this.playerProxies.entrySet().iterator();
        while (playerIterator.hasNext()) {
            Map.Entry<UUID, PlayerProxy> entry = playerIterator.next();
            if (!this.activePlayers.contains(entry.getKey())) {
                entry.getValue().stopRiding();
                playerIterator.remove();
            }
        }
        Iterator<Map.Entry<UUID, Entity>> vehicleIterator = this.vehicleProxies.entrySet().iterator();
        while (vehicleIterator.hasNext()) {
            Map.Entry<UUID, Entity> entry = vehicleIterator.next();
            if (!this.activeProxyVehicles.contains(entry.getKey())) {
                entry.getValue().ejectPassengers();
                this.vehicleProxyRevisions.remove(entry.getKey());
                vehicleIterator.remove();
            }
        }
    }

    private static void restorePose(PoseStack poseStack, PoseStack.Pose checkpoint) {
        // 实体渲染器抛错时可能遗留push，恢复到本次绘制前的栈顶。
        while (poseStack.last() != checkpoint) poseStack.popPose();
    }

    private Entity getVehicleProxy(ClientLevel level, TrackedPlayer tracked) {
        VehicleRevision failed = this.failedVehicleRevisions.get(tracked.vehicleUuid());
        if (failed != null && failed.matches(tracked)) return null;
        return this.vehicleProxies.compute(tracked.vehicleUuid(), (uuid, current) -> {
            VehicleRevision revision = current == null ? null : this.vehicleProxyRevisions.get(uuid);
            if (current != null && current.level() == level
                    && tracked.vehicleTypeId().equals(typeId(current))
                    && revision != null && revision.matches(tracked)) {
                return current;
            }
            if (current != null) {
                current.ejectPassengers();
            }
            Entity created = createVehicleProxy(level, tracked);
            if (created != null) {
                this.vehicleProxyRevisions.put(uuid, new VehicleRevision(tracked));
                this.failedVehicleRevisions.remove(uuid);
            } else {
                this.vehicleProxyRevisions.remove(uuid);
                this.failedVehicleRevisions.put(uuid, new VehicleRevision(tracked));
            }
            return created;
        });
    }

    private static Entity createVehicleProxy(ClientLevel level, TrackedPlayer tracked) {
        try {
            String entityTypeId = tracked.vehicleTypeId();
            EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(entityTypeId));
            if (entityType == null) {
                return null;
            }
            Entity entity = entityType.create(level);
            if (entity == null) {
                return null;
            }
            byte[] renderData = tracked.vehicleRenderData();
            if (renderData.length != 0) {
                var tag = NbtIo.readCompressed(
                        new ByteArrayInputStream(renderData),
                        NbtAccounter.create(FarEntityProtocol.MAX_VEHICLE_DATA_BYTES * 16L));
                entity.load(tag);
                if (entity.isRemoved()) {
                    return null;
                }
            }
            entity.setUUID(tracked.vehicleUuid());
            entity.setId(nextProxyId());
            entity.noPhysics = true;
            entity.setNoGravity(true);
            entity.setInvisible(false);
            return entity;
        } catch (Throwable throwable) {
            Logger.warn("Could not create far vehicle proxy for", tracked.vehicleTypeId(), throwable);
            return null;
        }
    }

    private void rejectVehicleProxy(TrackedPlayer tracked, PlayerProxy player,
                                    Entity vehicle, Throwable throwable) {
        FAR_MOUNT_ERRORS.increment();
        this.failedVehicleRevisions.put(tracked.vehicleUuid(), new VehicleRevision(tracked));
        if (this.vehicleProxies.remove(tracked.vehicleUuid(), vehicle)) {
            this.vehicleProxyRevisions.remove(tracked.vehicleUuid());
        }
        vehicle.ejectPassengers();
        if (player != null && player.getVehicle() == vehicle) {
            player.stopRiding();
        }
        Logger.warn("Disabled a failing far vehicle proxy for", tracked.vehicleTypeId(), throwable);
    }

    private static void applyVehicleState(Entity vehicle, TrackedPlayer tracked, float progress) {
        double x = tracked.renderVehicleX(progress);
        double y = tracked.renderVehicleY(progress);
        double z = tracked.renderVehicleZ(progress);
        float yaw = tracked.renderVehicleYaw(progress);
        float pitch = tracked.renderVehiclePitch(progress);
        vehicle.setOldPosAndRot();
        vehicle.xo = vehicle.xOld = x;
        vehicle.yo = vehicle.yOld = y;
        vehicle.zo = vehicle.zOld = z;
        vehicle.moveTo(x, y, z, yaw, pitch);
        vehicle.setYRot(yaw);
        vehicle.yRotO = yaw;
        vehicle.setXRot(pitch);
        vehicle.xRotO = pitch;
    }

    private static String typeId(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? "" : id.toString();
    }

    private static int nextProxyId() {
        return NEXT_PROXY_ID.getAndUpdate(value -> value == Integer.MAX_VALUE ? 1_000_000_000 : value + 1);
    }

    private static Pose pose(TrackedPlayer tracked) {
        if (tracked.gliding()) return Pose.FALL_FLYING;
        if (tracked.swimming()) return Pose.SWIMMING;
        if (tracked.sneaking()) return Pose.CROUCHING;
        return Pose.STANDING;
    }

    private static ItemStack item(ItemSnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            Item resolved = BuiltInRegistries.ITEM.get(ResourceLocation.parse(snapshot.itemId()));
            return resolved == null || resolved == Items.AIR
                    ? ItemStack.EMPTY
                    : new ItemStack(resolved, Math.max(1, snapshot.count()));
        } catch (RuntimeException ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static final class PlayerProxy extends RemotePlayer {
        private final UUID trackedUuid;
        private int maximumDistance;
        private double lastWalkX;
        private double lastWalkZ;
        private boolean hasLastWalkPosition;
        private int lastWalkTick = Integer.MIN_VALUE;
        private ItemSnapshot mainHandSnapshot;
        private ItemSnapshot offHandSnapshot;
        private ItemSnapshot feetSnapshot;
        private ItemSnapshot legsSnapshot;
        private ItemSnapshot chestSnapshot;
        private ItemSnapshot headSnapshot;
        private String customName;
        private boolean customNameVisible;
        private int appliedGeneration = Integer.MIN_VALUE;
        private Entity failedMount;
        private int failedMountRevision;
        private int nextMountAttemptTick;

        PlayerProxy(ClientLevel level, UUID uuid, String name) {
            super(level, new GameProfile(uuid, name));
            this.trackedUuid = uuid;
            this.setId(nextProxyId());
            this.noPhysics = true;
            this.setNoGravity(true);
            this.setInvisible(false);
        }

        void mountVehicle(Entity vehicle, boolean proxy, int revision, int tick) {
            if (this.getVehicle() == vehicle) {
                this.failedMount = null;
                return;
            }
            if (this.failedMount == vehicle && this.failedMountRevision == revision
                    && tick - this.nextMountAttemptTick < 0) return;
            try {
                if (this.isPassenger()) this.stopRiding();
                // 快照已确认乘坐关系；代理NBT中的满座映射不能再次阻止该玩家挂载。
                if (this.startRiding(vehicle, proxy)) {
                    this.failedMount = null;
                    return;
                }
            } catch (Throwable ignored) {
            }
            FAR_MOUNT_ERRORS.increment();
            this.failedMount = vehicle;
            this.failedMountRevision = revision;
            this.nextMountAttemptTick = tick + 20;
        }

        void apply(TrackedPlayer tracked, double x, double y, double z, int maximumDistance,
                   boolean animate, int animationTick, float progress) {
            float bodyYaw = tracked.renderBodyYaw(progress);
            float headYaw = tracked.renderHeadYaw(progress);
            float pitch = tracked.renderPitch(progress);
            this.maximumDistance = maximumDistance;
            this.tickCount = animationTick;
            this.setOldPosAndRot();
            this.xo = this.xOld = x;
            this.yo = this.yOld = y;
            this.zo = this.zOld = z;
            this.moveTo(x, y, z, bodyYaw, pitch);
            this.setYRot(bodyYaw);
            this.yRotO = bodyYaw;
            this.setXRot(pitch);
            this.xRotO = pitch;
            this.setYBodyRot(bodyYaw);
            this.yBodyRotO = bodyYaw;
            this.setYHeadRot(headYaw);
            this.yHeadRotO = headYaw;
            if (this.appliedGeneration != tracked.generation()) {
                this.appliedGeneration = tracked.generation();
                if (this.isShiftKeyDown() != tracked.sneaking()) this.setShiftKeyDown(tracked.sneaking());
                if (this.isSwimming() != tracked.swimming()) this.setSwimming(tracked.swimming());
                Pose pose = pose(tracked);
                if (this.getPose() != pose) this.setPose(pose);
                this.updateEquipment(tracked);
                if (!Objects.equals(this.customName, tracked.name())) {
                    this.customName = tracked.name();
                    this.setCustomName(Component.literal(this.customName));
                }
            }
            boolean showName = VoxyConfig.CONFIG.renderFarPlayerNames;
            if (this.customNameVisible != showName) {
                this.customNameVisible = showName;
                this.setCustomNameVisible(showName);
            }
            this.updateWalkAnimation(x, z, tracked, animate, animationTick);
        }

        private void updateEquipment(TrackedPlayer tracked) {
            if (!Objects.equals(this.mainHandSnapshot, tracked.mainHand())) {
                this.mainHandSnapshot = tracked.mainHand();
                this.setItemSlot(EquipmentSlot.MAINHAND, item(this.mainHandSnapshot));
            }
            if (!Objects.equals(this.offHandSnapshot, tracked.offHand())) {
                this.offHandSnapshot = tracked.offHand();
                this.setItemSlot(EquipmentSlot.OFFHAND, item(this.offHandSnapshot));
            }
            if (!Objects.equals(this.feetSnapshot, tracked.feet())) {
                this.feetSnapshot = tracked.feet();
                this.setItemSlot(EquipmentSlot.FEET, item(this.feetSnapshot));
            }
            if (!Objects.equals(this.legsSnapshot, tracked.legs())) {
                this.legsSnapshot = tracked.legs();
                this.setItemSlot(EquipmentSlot.LEGS, item(this.legsSnapshot));
            }
            if (!Objects.equals(this.chestSnapshot, tracked.chest())) {
                this.chestSnapshot = tracked.chest();
                this.setItemSlot(EquipmentSlot.CHEST, item(this.chestSnapshot));
            }
            if (!Objects.equals(this.headSnapshot, tracked.head())) {
                this.headSnapshot = tracked.head();
                this.setItemSlot(EquipmentSlot.HEAD, item(this.headSnapshot));
            }
        }

        private void updateWalkAnimation(double x, double z, TrackedPlayer tracked, boolean animate, int tick) {
            if (!this.hasLastWalkPosition) {
                this.lastWalkX = x;
                this.lastWalkZ = z;
                this.hasLastWalkPosition = true;
                this.lastWalkTick = tick;
                return;
            }
            if (tick == this.lastWalkTick) {
                return;
            }
            this.lastWalkTick = tick;
            float speed = 0.0F;
            if (animate && !tracked.gliding() && !tracked.swimming() && !tracked.hasVehicle()) {
                speed = Math.min((float) Mth.length(
                        x - this.lastWalkX, 0.0D,
                        z - this.lastWalkZ) * 4.0F, 1.0F);
            }
            this.walkAnimation.setSpeed(speed);
            this.walkAnimation.update(speed, WALK_ANIMATION_SCALE);
            this.lastWalkX = x;
            this.lastWalkZ = z;
        }

        @Override
        protected PlayerInfo getPlayerInfo() {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            PlayerInfo info = connection == null ? null : connection.getPlayerInfo(this.trackedUuid);
            return info != null ? info : super.getPlayerInfo();
        }

        @Override
        public boolean shouldRenderAtSqrDistance(double distanceSquared) {
            double maximum = Math.max(64, this.maximumDistance);
            return distanceSquared <= maximum * maximum;
        }
    }
}
