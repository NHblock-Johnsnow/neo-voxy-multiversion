package me.cortex.voxy.commonImpl.compat.create;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** 由采样线程持有；共享数组只计一次，编码前先预留最坏所需空间。 */
public final class DistantTrainEncodingBudget {
    public static final int MAX_BYTES = 4 * DistantTrainPackets.MAX_TRANSFER_BYTES;
    private static final long IDLE_NANOS = 15_000_000_000L;
    private final int maximumBytes;
    private final LinkedHashMap<Object, Lease> leases = new LinkedHashMap<>();
    private final LinkedHashSet<Object> waiting = new LinkedHashSet<>();
    private final IdentityHashMap<DistantTrainPackets.Encoded, Shared> shared = new IdentityHashMap<>();
    private final IdentityHashMap<Object, DistantTrainPackets.Encoded> sources = new IdentityHashMap<>();
    private int retainedBytes;

    private static final class Shared {
        int references = 1;
    }

    public DistantTrainEncodingBudget() {
        this(MAX_BYTES);
    }

    public DistantTrainEncodingBudget(int maximumBytes) {
        if (maximumBytes < DistantTrainPackets.MAX_TRANSFER_BYTES) throw new IllegalArgumentException("Encoding budget too small");
        this.maximumBytes = maximumBytes;
    }

    public Lease acquire(Object owner, int reservation, DistantTrainPackets.Encoded reusable,
                         Runnable expired, long now) {
        if (this.leases.containsKey(owner)) throw new IllegalStateException("Duplicate encoding owner");
        if (reservation <= 0 || reservation > DistantTrainPackets.MAX_TRANSFER_BYTES) {
            throw new IllegalArgumentException("Invalid encoding reservation");
        }
        // 已驻留的数组不增加占用，可以绕过等待新内存的队首。
        boolean alreadyShared = reusable != null && this.shared.containsKey(reusable);
        if (!alreadyShared) {
            while (reservation > this.maximumBytes - this.retainedBytes && !this.leases.isEmpty()) {
                Lease first = this.leases.values().iterator().next();
                if (now - first.touched < IDLE_NANOS) break;
                Runnable retire = first.expired;
                first.close();
                retire.run();
            }
            if (reservation > this.maximumBytes - this.retainedBytes
                    || !this.waiting.isEmpty() && this.waiting.iterator().next() != owner) {
                this.waiting.add(owner);
                return null;
            }
        }
        this.waiting.remove(owner);
        Lease lease = new Lease(owner, alreadyShared ? 0 : reservation, expired, now);
        this.leases.put(owner, lease);
        this.retainedBytes += lease.reserved;
        return lease;
    }

    public void cancel(Object owner) {
        this.waiting.remove(owner);
    }

    public DistantTrainPackets.Encoded reusable(Object source) {
        return this.sources.get(source);
    }

    public int retainedBytes() {
        return this.retainedBytes;
    }

    public int waitingCount() {
        return this.waiting.size();
    }

    public final class Lease implements AutoCloseable {
        private final Object owner;
        private Runnable expired;
        private int reserved;
        private long touched;
        private DistantTrainPackets.Encoded packet;
        private boolean closed;

        private Lease(Object owner, int reserved, Runnable expired, long now) {
            this.owner = owner;
            this.reserved = reserved;
            this.expired = expired;
            this.touched = now;
        }

        public void bind(DistantTrainPackets.Encoded packet) {
            if (this.closed || this.packet != null || packet == null || !packet.fragmented()) {
                throw new IllegalStateException("Invalid encoding lease binding");
            }
            Shared existing = shared.get(packet);
            if (existing == null && packet.bytes() > this.reserved) {
                throw new IllegalStateException("Encoding exceeds reservation");
            }
            retainedBytes -= this.reserved;
            this.reserved = 0;
            this.packet = packet;
            if (existing != null) {
                existing.references++;
            } else {
                shared.put(packet, new Shared());
                sources.put(packet.source(), packet);
                retainedBytes += packet.bytes();
            }
        }

        public void touch(long now) {
            if (this.closed) return;
            this.touched = now;
            leases.remove(this.owner);
            leases.put(this.owner, this);
        }

        @Override
        public void close() {
            if (this.closed) return;
            this.closed = true;
            leases.remove(this.owner);
            retainedBytes -= this.reserved;
            this.reserved = 0;
            if (this.packet != null) {
                Shared existing = shared.get(this.packet);
                if (--existing.references == 0) {
                    shared.remove(this.packet);
                    if (sources.get(this.packet.source()) == this.packet) sources.remove(this.packet.source());
                    retainedBytes -= this.packet.bytes();
                }
                this.packet = null;
            }
            this.expired = null;
        }
    }
}
