package me.cortex.voxy.compat.far;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/** 完整玩家集收齐后才发布；小包继续使用原通道。 */
public final class FarEntityPackets {
    public static final int FRAGMENT_BYTES = 64 * 1024;
    public static final int MAX_TRANSFER_BYTES = 64 * 1024 * 1024;
    static final int DIRECT_BYTES = (1 << 20) - 256;
    static final long EXPIRE_NANOS = 30_000_000_000L;
    private static final AtomicLong NEXT_TRANSFER = new AtomicLong();

    private FarEntityPackets() {}

    public record FragmentPayload(long transferId, int totalBytes, int offset,
                                  byte[] data) implements CustomPacketPayload {
        public static final Type<FragmentPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("voxy", "far_entity_fragment"));
        private static final StreamCodec<ByteBuf, byte[]> DATA_CODEC = ByteBufCodecs.byteArray(FRAGMENT_BYTES);
        public static final StreamCodec<ByteBuf, FragmentPayload> CODEC = StreamCodec.of(
                (buf, packet) -> {
                    buf.writeLong(packet.transferId());
                    ByteBufCodecs.VAR_INT.encode(buf, packet.totalBytes());
                    ByteBufCodecs.VAR_INT.encode(buf, packet.offset());
                    DATA_CODEC.encode(buf, packet.data());
                },
                buf -> new FragmentPayload(buf.readLong(), ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), DATA_CODEC.decode(buf)));

        public FragmentPayload {
            if (transferId <= 0 || totalBytes <= DIRECT_BYTES || totalBytes > MAX_TRANSFER_BYTES
                    || offset < 0 || offset >= totalBytes || offset % FRAGMENT_BYTES != 0
                    || data == null || data.length != Math.min(FRAGMENT_BYTES, totalBytes - offset)) {
                throw new IllegalArgumentException("Invalid far-entity fragment");
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static final class Encoded {
        final FarEntityProtocol.PlayersPayload direct;
        final byte[] data;
        final int bytes;
        final long transferId;

        Encoded(FarEntityProtocol.PlayerBatch batch, int bytes) {
            this.bytes = bytes;
            this.transferId = NEXT_TRANSFER.incrementAndGet();
            this.direct = bytes <= DIRECT_BYTES ? new FarEntityProtocol.PlayersPayload(batch) : null;
            this.data = this.direct == null ? new byte[bytes] : null;
            if (this.data != null) {
                ByteBuf buffer = Unpooled.wrappedBuffer(this.data).clear();
                try {
                    FarEntityProtocol.encodePlayers(buffer, batch);
                    if (buffer.writerIndex() != bytes) throw new IllegalStateException("Far-entity size mismatch");
                } finally {
                    buffer.release();
                }
            }
        }

        int count() { return this.data == null ? 1 : (this.bytes + FRAGMENT_BYTES - 1) / FRAGMENT_BYTES; }
        int packetBytes(int index) {
            return this.data == null ? this.bytes : Math.min(FRAGMENT_BYTES, this.bytes - index * FRAGMENT_BYTES);
        }
        CustomPacketPayload packet(int index) {
            if (index < 0 || index >= this.count()) throw new IndexOutOfBoundsException(index);
            if (this.data == null) return this.direct;
            int offset = index * FRAGMENT_BYTES;
            // 网络回调只保留本片，取消时可立即归还整份编码数组额度。
            return new FragmentPayload(this.transferId, this.bytes, offset,
                    Arrays.copyOfRange(this.data, offset, Math.min(offset + FRAGMENT_BYTES, this.bytes)));
        }
    }

    static int encodedBytes(FarEntityProtocol.PlayerBatch batch) {
        if (batch.players().size() > FarEntityProtocol.MAX_PLAYERS_PER_PACKET) {
            throw new IllegalArgumentException("Too many far players");
        }
        long bytes = utfBytes(batch.dimensionKey()) + variableBytes(batch.players().size());
        for (var player : batch.players()) {
            bytes += 56L + utfBytes(player.name());
            bytes += itemBytes(player.mainHand()) + itemBytes(player.offHand())
                    + itemBytes(player.feet()) + itemBytes(player.legs())
                    + itemBytes(player.chest()) + itemBytes(player.head());
            var vehicle = player.vehicle();
            if (vehicle != null) {
                bytes += 48L + variableBytes(vehicle.entityId()) + utfBytes(vehicle.entityTypeId())
                        + variableBytes(vehicle.renderData().length) + vehicle.renderData().length;
            }
        }
        if (bytes > MAX_TRANSFER_BYTES) throw new IllegalArgumentException("Far-entity snapshot exceeds transfer limit");
        return (int) bytes;
    }

    private static int itemBytes(FarEntityProtocol.ItemSnapshot item) {
        return utfBytes(item.itemId()) + variableBytes(item.count());
    }

    private static int variableBytes(int value) {
        int bytes = 1;
        while ((value & ~127) != 0) { value >>>= 7; bytes++; }
        return bytes;
    }

    private static int utfBytes(String value) {
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) { bytes += 4; i++; }
            else bytes += Character.isSurrogate(c) ? 1 : 3;
        }
        if (bytes > 32767) throw new IllegalArgumentException("Far-entity string is too long");
        return variableBytes(bytes) + bytes;
    }

    public static final class Assembler {
        private byte[] data;
        private long newestTransfer;
        private long lastFragment;
        private int offset;

        public FarEntityProtocol.PlayerBatch accept(FragmentPayload packet, long now) {
            this.expire(now);
            if (packet.offset() == 0) {
                if (packet.transferId() <= this.newestTransfer) return null;
                this.discard();
                this.newestTransfer = packet.transferId();
                this.data = new byte[packet.totalBytes()];
            }
            if (this.data == null || packet.transferId() != this.newestTransfer) return null;
            if (packet.totalBytes() != this.data.length || packet.offset() != this.offset) {
                this.discard();
                return null;
            }
            System.arraycopy(packet.data(), 0, this.data, this.offset, packet.data().length);
            this.offset += packet.data().length;
            this.lastFragment = now;
            if (this.offset != this.data.length) return null;
            ByteBuf buffer = Unpooled.wrappedBuffer(this.data);
            try {
                var batch = FarEntityProtocol.decodePlayers(buffer);
                if (buffer.isReadable()) throw new IllegalArgumentException("Trailing far-entity data");
                return batch;
            } finally {
                buffer.release();
                this.discard();
            }
        }

        public void expire(long now) {
            if (this.data != null && now - this.lastFragment >= EXPIRE_NANOS) this.discard();
        }
        public void discard() { this.data = null; this.offset = 0; }
        public void clear() { this.discard(); this.newestTransfer = 0; }
        int retainedBytes() { return this.data == null ? 0 : this.data.length; }
    }
}
