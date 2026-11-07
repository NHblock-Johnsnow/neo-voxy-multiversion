package me.cortex.voxy.compat.far;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** 单个完整在途快照，加一个可替换的最新输入；仅服务器线程推进。 */
final class FarEntitySendQueue {
    static final int MAX_PACKETS = 16;
    static final int MAX_BYTES = 1 << 20;
    private static final int PACKET_OVERHEAD = 128;
    private final Budget budget;
    private final AtomicLong outstanding = new AtomicLong();
    private FarEntityProtocol.PlayerBatch latest;
    private FarEntityProtocol.PlayerBatch active;
    private Prepared prepared;
    private int preparedBytes;
    private FarEntityPackets.Encoded encoded;
    private Receipt receipt;
    private int cursor;
    private int reservation;
    private long lastWrite;
    private boolean closed;
    private boolean warned;

    @FunctionalInterface
    interface Writer { void send(CustomPacketPayload payload, Consumer<Boolean> completed); }

    record Prepared(FarEntityProtocol.PlayerBatch payload, Runnable delivered) {}

    static final class Budget {
        private final int limit;
        private final LinkedHashSet<FarEntitySendQueue> waiting = new LinkedHashSet<>();
        private int used;

        Budget(int limit) { this.limit = limit; }
        boolean acquire(FarEntitySendQueue owner, int bytes) {
            this.waiting.add(owner);
            if (this.waiting.iterator().next() != owner || bytes > this.limit - this.used) return false;
            this.waiting.remove(owner);
            this.used += bytes;
            return true;
        }
        void cancel(FarEntitySendQueue owner) { this.waiting.remove(owner); }
        void release(int bytes) { this.used -= bytes; }
        int usedBytes() { return this.used; }
        int waitingCount() { return this.waiting.size(); }
    }

    private static final class Receipt {
        final AtomicInteger pending = new AtomicInteger();
        final AtomicBoolean failed = new AtomicBoolean();
    }

    FarEntitySendQueue(Budget budget) { this.budget = budget; }

    void submit(FarEntityProtocol.PlayerBatch snapshot) {
        if (this.closed) return;
        if (this.active != null && !this.active.dimensionKey().equals(snapshot.dimensionKey())) this.retire();
        if (this.active != null && this.encoded == null) {
            // 尚未发送的旧输入直接合并；保留等待总额度的先后位置。
            this.active = null;
            this.prepared = null;
            this.preparedBytes = 0;
        }
        this.latest = snapshot;
    }

    void drain(boolean fragments, BooleanSupplier valid, BooleanSupplier writable, Writer writer,
               Function<FarEntityProtocol.PlayerBatch, Prepared> prepare, long now) {
        if (this.closed) return;
        if (!valid.getAsBoolean()) { this.close(); return; }
        if (this.receipt != null) {
            if (this.receipt.failed.get()) {
                this.retry();
            } else if (this.cursor == this.encoded.count() && this.receipt.pending.get() == 0) {
                // 最后一个完成回调先发布失败标志，再归还 pending。
                if (this.receipt.failed.get()) this.retry();
                else { this.prepared.delivered().run(); this.retire(); }
            } else if (now - this.lastWrite >= FarEntityPackets.EXPIRE_NANOS) {
                this.retry();
            }
        }
        if (!writable.getAsBoolean()) {
            if (this.encoded == null) this.budget.cancel(this);
            return;
        }
        if (this.encoded == null) {
            if (this.active == null) {
                if (this.latest == null) return;
                this.active = this.latest;
                this.latest = null;
            }
            int bytes;
            try {
                if (this.prepared == null) {
                    this.prepared = prepare.apply(this.active);
                    this.preparedBytes = FarEntityPackets.encodedBytes(this.prepared.payload());
                }
                bytes = this.preparedBytes;
            } catch (RuntimeException invalid) {
                this.warn(invalid.toString());
                this.retire();
                return;
            }
            if (bytes > FarEntityPackets.DIRECT_BYTES) {
                if (!fragments) {
                    this.warn("client has no far_entity_fragment channel");
                    this.retire();
                    return;
                }
                if (!this.budget.acquire(this, bytes)) return;
                this.reservation = bytes;
            } else this.budget.cancel(this);
            try {
                this.encoded = new FarEntityPackets.Encoded(this.prepared.payload(), bytes);
            } catch (RuntimeException invalid) {
                this.warn(invalid.toString());
                this.retire();
                return;
            }
            this.receipt = new Receipt();
            this.cursor = 0;
            this.lastWrite = now;
        }
        Receipt attempt = this.receipt;
        int bytes = 0;
        for (int packets = 0; packets < MAX_PACKETS && this.cursor < this.encoded.count(); packets++) {
            int size = this.encoded.packetBytes(this.cursor) + PACKET_OVERHEAD;
            if (size > MAX_BYTES - bytes || size > MAX_BYTES - this.outstanding.get()
                    || !valid.getAsBoolean() || !writable.getAsBoolean()) break;
            var packet = this.encoded.packet(this.cursor);
            this.outstanding.addAndGet(size);
            attempt.pending.incrementAndGet();
            AtomicBoolean completed = new AtomicBoolean();
            Consumer<Boolean> completion = success -> {
                if (!completed.compareAndSet(false, true)) return;
                if (!success) attempt.failed.set(true);
                this.outstanding.addAndGet(-size);
                attempt.pending.decrementAndGet();
            };
            try {
                writer.send(packet, completion);
            } catch (RuntimeException failed) {
                completion.accept(false);
            }
            this.cursor++;
            this.lastWrite = now;
            bytes += size;
            if (attempt.failed.get()) break;
        }
    }

    private void retry() {
        var retry = this.latest != null ? this.latest : this.active;
        this.retire();
        this.latest = retry;
    }

    private void retire() {
        this.budget.cancel(this);
        this.budget.release(this.reservation);
        this.reservation = 0;
        this.active = null;
        this.prepared = null;
        this.preparedBytes = 0;
        this.encoded = null;
        this.receipt = null;
        this.cursor = 0;
    }

    void close() { this.closed = true; this.retire(); this.latest = null; }
    boolean pending() { return this.active != null || this.latest != null; }
    long outstandingBytes() { return this.outstanding.get(); }

    private void warn(String reason) {
        if (!this.warned) {
            this.warned = true;
            me.cortex.voxy.common.Logger.warn("Skipping an unsupported far-entity snapshot:", reason);
        }
    }
}
