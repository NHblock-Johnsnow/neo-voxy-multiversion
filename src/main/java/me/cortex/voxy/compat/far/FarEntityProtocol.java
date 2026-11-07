package me.cortex.voxy.compat.far;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class FarEntityProtocol {
    public static final int VERSION = 7;
    public static final int MAX_PLAYERS_PER_PACKET = 1024;
    private static final int MAX_STRING_BYTES = 32767;
    public static final int MAX_VEHICLE_DATA_BYTES = 256 * 1024;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private FarEntityProtocol() {
    }

    public record Hello(int version, boolean enabled, boolean includeVehicles,
                        int maximumDistanceBlocks, boolean shareSelf) {
    }

    public record ItemSnapshot(String itemId, int count) {
        public static final ItemSnapshot EMPTY = new ItemSnapshot("", 0);

        public ItemSnapshot {
            itemId = itemId == null ? "" : itemId;
            count = Math.max(0, count);
        }

        public boolean isEmpty() {
            return this.itemId.isEmpty() || this.count <= 0;
        }
    }

    public record VehicleSnapshot(
            UUID uuid, int entityId, String entityTypeId,
            double x, double y, double z, float yaw, float pitch,
            byte[] renderData
    ) {
        public VehicleSnapshot {
            entityTypeId = entityTypeId == null ? "" : entityTypeId;
            renderData = renderData == null ? EMPTY_BYTES : renderData;
            if (renderData.length > MAX_VEHICLE_DATA_BYTES) {
                throw new IllegalArgumentException("Far vehicle render data is too large: " + renderData.length);
            }
        }

        public VehicleSnapshot withoutRenderData() {
            return renderData.length == 0 ? this : new VehicleSnapshot(
                    uuid, entityId, entityTypeId, x, y, z, yaw, pitch, EMPTY_BYTES);
        }
    }

    public record PlayerSnapshot(
            UUID uuid, String name,
            double x, double y, double z,
            float bodyYaw, float headYaw, float pitch,
            boolean sneaking, boolean gliding, boolean swimming,
            ItemSnapshot mainHand, ItemSnapshot offHand,
            ItemSnapshot feet, ItemSnapshot legs, ItemSnapshot chest, ItemSnapshot head,
            VehicleSnapshot vehicle
    ) {
        public PlayerSnapshot {
            name = name == null ? "" : name;
            mainHand = sanitize(mainHand);
            offHand = sanitize(offHand);
            feet = sanitize(feet);
            legs = sanitize(legs);
            chest = sanitize(chest);
            head = sanitize(head);
        }

        private static ItemSnapshot sanitize(ItemSnapshot item) {
            return item == null ? ItemSnapshot.EMPTY : item;
        }

        public PlayerSnapshot withVehicle(VehicleSnapshot replacement) {
            return vehicle == replacement ? this : new PlayerSnapshot(
                    uuid, name, x, y, z, bodyYaw, headYaw, pitch,
                    sneaking, gliding, swimming,
                    mainHand, offHand, feet, legs, chest, head, replacement);
        }
    }

    public record PlayerBatch(String dimensionKey, List<PlayerSnapshot> players) {
        public PlayerBatch {
            dimensionKey = dimensionKey == null ? "" : dimensionKey;
            players = players == null ? List.of() : List.copyOf(players);
        }
    }

    public record HelloPayload(Hello hello) implements CustomPacketPayload {
        public static final Type<HelloPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "far_entity_hello"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HelloPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public HelloPayload decode(RegistryFriendlyByteBuf buf) {
                return new HelloPayload(decodeHello(buf));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, HelloPayload payload) {
                encodeHello(buf, payload.hello());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PlayersPayload(PlayerBatch batch) implements CustomPacketPayload {
        public static final Type<PlayersPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "far_entities"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayersPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public PlayersPayload decode(RegistryFriendlyByteBuf buf) {
                return new PlayersPayload(decodePlayers(buf));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, PlayersPayload payload) {
                encodePlayers(buf, payload.batch());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void encodeHello(ByteBuf buf, Hello hello) {
        writeVarInt(buf, hello.version());
        buf.writeBoolean(hello.enabled());
        buf.writeBoolean(hello.includeVehicles());
        writeVarInt(buf, hello.maximumDistanceBlocks());
        buf.writeBoolean(hello.shareSelf());
    }

    private static Hello decodeHello(ByteBuf buf) {
        return new Hello(readVarInt(buf), buf.readBoolean(), buf.readBoolean(),
                readVarInt(buf), buf.readBoolean());
    }

    static void encodePlayers(ByteBuf buf, PlayerBatch batch) {
        writeUtf(buf, batch.dimensionKey());
        if (batch.players().size() > MAX_PLAYERS_PER_PACKET) {
            throw new IllegalArgumentException("Too many far players: " + batch.players().size());
        }
        writeVarInt(buf, batch.players().size());
        for (PlayerSnapshot player : batch.players()) {
            writeUuid(buf, player.uuid());
            writeUtf(buf, player.name());
            buf.writeDouble(player.x());
            buf.writeDouble(player.y());
            buf.writeDouble(player.z());
            buf.writeFloat(player.bodyYaw());
            buf.writeFloat(player.headYaw());
            buf.writeFloat(player.pitch());
            buf.writeBoolean(player.sneaking());
            buf.writeBoolean(player.gliding());
            buf.writeBoolean(player.swimming());
            encodeItem(buf, player.mainHand());
            encodeItem(buf, player.offHand());
            encodeItem(buf, player.feet());
            encodeItem(buf, player.legs());
            encodeItem(buf, player.chest());
            encodeItem(buf, player.head());
            buf.writeBoolean(player.vehicle() != null);
            if (player.vehicle() != null) {
                encodeVehicle(buf, player.vehicle());
            }
        }
    }

    static PlayerBatch decodePlayers(ByteBuf buf) {
        String dimension = readUtf(buf);
        int size = readVarInt(buf);
        if (size < 0 || size > MAX_PLAYERS_PER_PACKET) {
            throw new IllegalArgumentException("Invalid far-player count: " + size);
        }
        List<PlayerSnapshot> players = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            players.add(new PlayerSnapshot(
                    readUuid(buf), readUtf(buf),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                    decodeItem(buf), decodeItem(buf), decodeItem(buf),
                    decodeItem(buf), decodeItem(buf), decodeItem(buf),
                    buf.readBoolean() ? decodeVehicle(buf) : null
            ));
        }
        return new PlayerBatch(dimension, players);
    }

    private static void encodeItem(ByteBuf buf, ItemSnapshot item) {
        ItemSnapshot safe = item == null ? ItemSnapshot.EMPTY : item;
        writeUtf(buf, safe.itemId());
        writeVarInt(buf, safe.count());
    }

    private static ItemSnapshot decodeItem(ByteBuf buf) {
        return new ItemSnapshot(readUtf(buf), readVarInt(buf));
    }

    private static void encodeVehicle(ByteBuf buf, VehicleSnapshot vehicle) {
        writeUuid(buf, vehicle.uuid());
        writeVarInt(buf, vehicle.entityId());
        writeUtf(buf, vehicle.entityTypeId());
        buf.writeDouble(vehicle.x());
        buf.writeDouble(vehicle.y());
        buf.writeDouble(vehicle.z());
        buf.writeFloat(vehicle.yaw());
        buf.writeFloat(vehicle.pitch());
        writeVarInt(buf, vehicle.renderData().length);
        buf.writeBytes(vehicle.renderData());
    }

    private static VehicleSnapshot decodeVehicle(ByteBuf buf) {
        return new VehicleSnapshot(
                readUuid(buf), readVarInt(buf), readUtf(buf),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(),
                readByteArray(buf, MAX_VEHICLE_DATA_BYTES)
        );
    }

    private static byte[] readByteArray(ByteBuf buf, int maximumLength) {
        int length = readVarInt(buf);
        if (length < 0 || length > maximumLength || length > buf.readableBytes()) {
            throw new IllegalArgumentException("Invalid far-entity byte array length: " + length);
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return bytes;
    }

    private static void writeUuid(ByteBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    private static void writeUtf(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("Far-entity string is too long");
        }
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readUtf(ByteBuf buf) {
        int length = readVarInt(buf);
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("Invalid far-entity string length: " + length);
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        int position = 0;
        while (true) {
            if (position >= 35) {
                throw new IllegalArgumentException("VarInt is too big");
            }
            byte current = buf.readByte();
            value |= (current & 127) << position;
            if ((current & 128) == 0) {
                return value;
            }
            position += 7;
        }
    }
}
