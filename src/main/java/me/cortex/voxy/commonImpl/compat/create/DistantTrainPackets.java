package me.cortex.voxy.commonImpl.compat.create;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 大形状与完整姿态保留原 codec，传输层收齐后才解码发布。 */
public final class DistantTrainPackets {
    public static final int FRAGMENT_BYTES = 64 * 1024;
    public static final int MAX_TRANSFER_BYTES = 64 * 1024 * 1024;
    private static final int DIRECT_BYTES = (1 << 20) - 256;
    private static final byte SHAPE = 1;
    private static final byte POSES = 2;
    private static final AtomicLong NEXT_TRANSFER = new AtomicLong();

    private DistantTrainPackets() {}

    public record FragmentPayload(byte kind, long transferId, int totalBytes, int offset,
                                  byte[] data, int dataOffset, int length) implements CustomPacketPayload {
        public static final Type<FragmentPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("voxy", "train_fragment"));
        private static final StreamCodec<ByteBuf, byte[]> DATA_CODEC = ByteBufCodecs.byteArray(FRAGMENT_BYTES);
        public static final StreamCodec<ByteBuf, FragmentPayload> CODEC = StreamCodec.of(
                (buf, fragment) -> {
                    buf.writeByte(fragment.kind());
                    buf.writeLong(fragment.transferId());
                    ByteBufCodecs.VAR_INT.encode(buf, fragment.totalBytes());
                    ByteBufCodecs.VAR_INT.encode(buf, fragment.offset());
                    ByteBufCodecs.VAR_INT.encode(buf, fragment.length());
                    buf.writeBytes(fragment.data(), fragment.dataOffset(), fragment.length());
                },
                buf -> {
                    byte kind = buf.readByte();
                    long transfer = buf.readLong();
                    int total = ByteBufCodecs.VAR_INT.decode(buf);
                    int offset = ByteBufCodecs.VAR_INT.decode(buf);
                    byte[] data = DATA_CODEC.decode(buf);
                    return new FragmentPayload(kind, transfer, total, offset, data, 0, data.length);
                });

        public FragmentPayload {
            if ((kind != SHAPE && kind != POSES) || transferId <= 0
                    || totalBytes <= 0 || totalBytes > MAX_TRANSFER_BYTES
                    || offset < 0 || offset >= totalBytes || offset % FRAGMENT_BYTES != 0
                    || length != Math.min(FRAGMENT_BYTES, totalBytes - offset)
                    || dataOffset < 0 || data == null || dataOffset > data.length - length) {
                throw new IllegalArgumentException("Invalid distant train fragment");
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public boolean isPoses() {
            return this.kind == POSES;
        }
    }

    public static final class Encoded {
        private final CustomPacketPayload original;
        private final byte kind;
        private final byte[] data;
        private final int encodedBytes;
        private final long transferId;

        private Encoded(CustomPacketPayload original, byte kind, int encodedBytes, byte[] data) {
            this.original = original;
            this.kind = kind;
            this.data = data;
            this.encodedBytes = encodedBytes;
            this.transferId = NEXT_TRANSFER.incrementAndGet();
        }

        public int bytes() {
            return this.encodedBytes;
        }

        CustomPacketPayload source() {
            return this.original;
        }

        public boolean fragmented() {
            return this.data != null;
        }

        public int packetCount() {
            return this.fragmented() ? (this.data.length + FRAGMENT_BYTES - 1) / FRAGMENT_BYTES : 1;
        }

        public CustomPacketPayload packet(int index) {
            if (index < 0 || index >= this.packetCount()) throw new IndexOutOfBoundsException(index);
            if (!this.fragmented()) return this.original;
            int offset = index * FRAGMENT_BYTES;
            // 所有分片只读共享同一编码数组，不为每位玩家复制整份形状。
            return new FragmentPayload(this.kind, this.transferId, this.data.length, offset,
                    this.data, offset, Math.min(FRAGMENT_BYTES, this.data.length - offset));
        }

        public CustomPacketPayload packetForSend(int index) {
            CustomPacketPayload packet = this.packet(index);
            if (!(packet instanceof FragmentPayload fragment)) return packet;
            // 网络等待只持有本片；编码额度被抢占时可以立即释放整份大数组。
            byte[] part = java.util.Arrays.copyOfRange(fragment.data(), fragment.dataOffset(),
                    fragment.dataOffset() + fragment.length());
            return new FragmentPayload(fragment.kind(), fragment.transferId(), fragment.totalBytes(),
                    fragment.offset(), part, 0, part.length);
        }
    }

    public record Prepared(int reservationBytes, Supplier<Encoded> encoder, Supplier<Encoded> cached) {
        public boolean large() { return this.reservationBytes != 0; }
        public Encoded encode() { return this.encoder.get(); }
    }

    public static Encoded encode(DistantTrainProtocol.CarriageShapePayload payload) {
        return encode(payload, SHAPE, DistantTrainProtocol.CarriageShapePayload.CODEC);
    }

    public static Encoded encode(DistantTrainProtocol.TrainPosesPayload payload) {
        return prepare(payload).encode();
    }

    public static Prepared prepare(DistantTrainProtocol.TrainPosesPayload payload) {
        // ResourceLocation 仅允许 ASCII；姿态没有 NBT，可直接算出准确编码长度。
        int dimensionBytes = payload.dimension().toString().length();
        long size = 16L + variableBytes(dimensionBytes) + dimensionBytes + variableBytes(payload.carriages().size());
        for (var carriage : payload.carriages()) {
            size += variableBytes(Integer.toUnsignedLong(carriage.carriageIndex())) + variableBytes(carriage.shapeId())
                    + 32L + variableBytes(carriage.bogeys().size()) + 33L * carriage.bogeys().size();
        }
        if (size > MAX_TRANSFER_BYTES) throw new IllegalArgumentException("Distant train poses exceed transfer limit");
        if (size <= DIRECT_BYTES) {
            Encoded direct = new Encoded(payload, POSES, (int) size, null);
            return new Prepared(0, () -> direct, () -> direct);
        }
        return new Prepared((int) size, () -> encode(payload, POSES, DistantTrainProtocol.TrainPosesPayload.CODEC), () -> null);
    }

    private static int variableBytes(long value) {
        int bytes = 1;
        while ((value & ~127L) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    public static final class ShapeCache {
        private static final class Cached {
            final DistantTrainProtocol.CarriageShapePayload source;
            Encoded direct;
            boolean failed;
            int bytes;

            Cached(DistantTrainProtocol.CarriageShapePayload source) { this.source = source; }
        }
        private final LinkedHashMap<Long, Cached> shapes = new LinkedHashMap<>(16, 0.75f, true);
        private final LinkedHashMap<Long, Encoded> arrays = new LinkedHashMap<>(16, 0.75f, true);
        private int retainedBytes;

        public Encoded get(DistantTrainProtocol.CarriageShapePayload shape) {
            Prepared prepared = this.prepare(shape);
            return prepared == null ? null : prepared.encode();
        }

        public Prepared prepare(DistantTrainProtocol.CarriageShapePayload shape) {
            Cached cached = this.shapes.get(shape.shapeId());
            if (cached == null || cached.source != shape) {
                this.remove(shape.shapeId());
                cached = new Cached(shape);
                try {
                    // 小形状无需等待大数组额度；大形状至多探测 1 MiB，随后再预留全量空间。
                    cached.direct = encode(shape, SHAPE, DistantTrainProtocol.CarriageShapePayload.CODEC, DIRECT_BYTES);
                } catch (IndexOutOfBoundsException needsLargeBuffer) {
                    cached.bytes = MAX_TRANSFER_BYTES;
                } catch (RuntimeException failure) {
                    cached.failed = true;
                    me.cortex.voxy.common.Logger.error("Encoding distant train shape " + shape.shapeId(), failure);
                }
                this.shapes.put(shape.shapeId(), cached);
                while (this.shapes.size() > 4096) this.remove(this.shapes.keySet().iterator().next());
            }
            if (cached.failed) return null;
            if (cached.direct != null) {
                Encoded direct = cached.direct;
                return new Prepared(0, () -> direct, () -> direct);
            }
            return new Prepared(cached.bytes, () -> this.encodeLarge(shape), () -> this.cachedArray(shape));
        }

        private Encoded cachedArray(DistantTrainProtocol.CarriageShapePayload shape) {
            var cached = this.shapes.get(shape.shapeId());
            return cached != null && cached.source == shape ? this.arrays.get(shape.shapeId()) : null;
        }

        private Encoded encodeLarge(DistantTrainProtocol.CarriageShapePayload shape) {
            var cached = this.shapes.get(shape.shapeId());
            if (cached == null || cached.source != shape) return this.get(shape);
            if (cached.failed) return null;
            Encoded packet = this.arrays.get(shape.shapeId());
            if (packet != null) return packet;
            try {
                packet = encode(shape);
            } catch (RuntimeException failure) {
                cached.failed = true;
                me.cortex.voxy.common.Logger.error("Encoding distant train shape " + shape.shapeId(), failure);
                return null;
            }
            cached.bytes = packet.bytes();
            this.arrays.put(shape.shapeId(), packet);
            this.retainedBytes += packet.bytes();
            // 只淘汰大数组，保留探测结果与已知长度，避免反复探测同一稳定来源。
            while (this.retainedBytes > MAX_TRANSFER_BYTES) {
                this.removeArray(this.arrays.keySet().iterator().next());
            }
            return packet;
        }

        private void removeArray(long shapeId) {
            Encoded removed = this.arrays.remove(shapeId);
            if (removed != null) this.retainedBytes -= removed.bytes();
        }

        public void remove(long shapeId) {
            this.shapes.remove(shapeId);
            this.removeArray(shapeId);
        }

        public int retainedBytes() {
            return this.retainedBytes;
        }

        public int size() {
            return this.shapes.size();
        }
    }

    private static <T extends CustomPacketPayload> Encoded encode(T payload, byte kind, StreamCodec<ByteBuf, T> codec) {
        return encode(payload, kind, codec, MAX_TRANSFER_BYTES);
    }

    private static <T extends CustomPacketPayload> Encoded encode(T payload, byte kind, StreamCodec<ByteBuf, T> codec, int maximum) {
        ByteBuf buffer = Unpooled.buffer(8192, maximum);
        try {
            codec.encode(buffer, payload);
            int size = buffer.readableBytes();
            byte[] data = size > DIRECT_BYTES ? new byte[size] : null;
            if (data != null) buffer.readBytes(data);
            return new Encoded(payload, kind, size, data);
        } finally {
            buffer.release();
        }
    }

    public static final class Assembler {
        private static final long IDLE_NANOS = 30_000_000_000L;
        private static final int MAX_PENDING = 4;
        private static final int MAX_PENDING_BYTES = MAX_TRANSFER_BYTES;
        private final LinkedHashMap<Long, Assembly> pending = new LinkedHashMap<>();
        private int pendingBytes;

        private static final class Assembly {
            final byte kind;
            final byte[] data;
            int offset;
            long touched;

            Assembly(FragmentPayload first, long now) {
                this.kind = first.kind();
                this.data = new byte[first.totalBytes()];
                this.touched = now;
            }
        }

        public CustomPacketPayload accept(FragmentPayload fragment, long now) {
            this.expire(now);
            Assembly assembly = this.pending.get(fragment.transferId());
            if (fragment.offset() == 0) {
                this.remove(fragment.transferId());
                while (!this.pending.isEmpty() && (this.pending.size() >= MAX_PENDING
                        || this.pendingBytes > MAX_PENDING_BYTES - fragment.totalBytes())) {
                    this.remove(this.pending.keySet().iterator().next());
                }
                assembly = new Assembly(fragment, now);
                this.pending.put(fragment.transferId(), assembly);
                this.pendingBytes += fragment.totalBytes();
            }
            if (assembly == null) return null;
            if (assembly.kind != fragment.kind() || assembly.data.length != fragment.totalBytes()
                    || assembly.offset != fragment.offset()) {
                this.remove(fragment.transferId());
                return null;
            }
            System.arraycopy(fragment.data(), fragment.dataOffset(), assembly.data, assembly.offset, fragment.length());
            assembly.offset += fragment.length();
            assembly.touched = now;
            if (assembly.offset != assembly.data.length) return null;
            this.remove(fragment.transferId());
            ByteBuf buffer = Unpooled.wrappedBuffer(assembly.data);
            try {
                CustomPacketPayload payload = assembly.kind == SHAPE
                        ? DistantTrainProtocol.CarriageShapePayload.CODEC.decode(buffer)
                        : DistantTrainProtocol.TrainPosesPayload.CODEC.decode(buffer);
                if (buffer.isReadable()) throw new IllegalArgumentException("Trailing distant train data");
                return payload;
            } finally {
                buffer.release();
            }
        }

        public void expire(long now) {
            var iterator = this.pending.values().iterator();
            while (iterator.hasNext()) {
                Assembly assembly = iterator.next();
                if (now - assembly.touched >= IDLE_NANOS) {
                    this.pendingBytes -= assembly.data.length;
                    iterator.remove();
                }
            }
        }

        private void remove(long id) {
            Assembly removed = this.pending.remove(id);
            if (removed != null) this.pendingBytes -= removed.data.length;
        }

        public int pendingBytes() {
            return this.pendingBytes;
        }

        public int pendingCount() {
            return this.pending.size();
        }

        public void clear() {
            this.pending.clear();
            this.pendingBytes = 0;
        }
    }
}
