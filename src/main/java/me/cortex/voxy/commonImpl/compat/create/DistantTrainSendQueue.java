package me.cortex.voxy.commonImpl.compat.create;

import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.CarriageShapePayload;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ContraptionPosesPayload;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.TrainPosesPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 仅采样线程修改队列；网络回调只释放额度和发布写入结果。 */
public final class DistantTrainSendQueue {
    public static final int ROUND_BYTES = 1024 * 1024;
    public static final int ROUND_PACKETS = 16;
    private static final int PACKET_OVERHEAD = 128;
    private static final long RESTART_NANOS = 15_000_000_000L;
    private static final byte SHAPE = 1, POSES = 2, REMOVE = 3;

    @FunctionalInterface
    public interface Writer {
        void send(CustomPacketPayload packet, Consumer<Boolean> completed);
    }

    private record Key(UUID train, byte kind, long shape) {}

    private static final class Job {
        final Key key;
        final Object source;
        final Supplier<DistantTrainPackets.Prepared> prepare;
        final Runnable started;
        final Runnable delivered;
        final AtomicInteger outstanding = new AtomicInteger();
        final AtomicBoolean failed = new AtomicBoolean();
        DistantTrainPackets.Encoded encoded;
        DistantTrainPackets.Prepared prepared;
        DistantTrainEncodingBudget.Lease lease;
        int nextPacket;
        boolean began;
        long lastWrite;

        Job(Key key, Object source, Supplier<DistantTrainPackets.Prepared> prepare,
            Runnable started, Runnable delivered) {
            this.key = key;
            this.source = source;
            this.prepare = prepare;
            this.started = started;
            this.delivered = delivered;
        }
    }

    public final Object connection;
    private final LinkedHashMap<UUID, LinkedHashMap<Key, Job>> pending = new LinkedHashMap<>();
    private final LinkedHashMap<Key, WeakReference<Object>> blockedShapes = new LinkedHashMap<>();
    private int waitingCount;
    private final AtomicInteger outstandingBytes = new AtomicInteger();
    private final DistantContraptionSendQueue contraptions = new DistantContraptionSendQueue(this.outstandingBytes);
    private boolean contraptionsFirst;
    private Job active;
    private boolean fragmentsSupported;
    private final DistantTrainEncodingBudget encodingBudget;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DistantTrainSendQueue(Object connection) {
        this(connection, new DistantTrainEncodingBudget());
    }

    public DistantTrainSendQueue(Object connection, DistantTrainEncodingBudget encodingBudget) {
        this.connection = connection;
        this.encodingBudget = encodingBudget;
    }

    public void shape(CarriageShapePayload shape, Supplier<DistantTrainPackets.Prepared> prepare, Runnable delivered) {
        if (this.closed.get()) return;
        Key key = new Key(shape.trainId(), SHAPE, shape.shapeId());
        var blocked = this.blockedShapes.get(key);
        if (blocked != null) {
            if (blocked.get() == shape) return;
            this.blockedShapes.remove(key);
        }
        if (this.active != null && this.active.key.equals(key)) {
            if (this.active.source == shape) return;
            this.cancelActive();
        }
        Job previous = this.waiting(key);
        if (previous != null && previous.source == shape) return;
        // 替换等待项不改变排队位置，后来的列车不会被持续更新挤到队尾。
        this.enqueue(new Job(key, shape, prepare, () -> {}, delivered));
    }

    public void poses(TrainPosesPayload poses, Runnable started) {
        if (this.closed.get()) return;
        Key key = new Key(poses.trainId(), POSES, 0);
        if (this.active != null && this.active.key.equals(key)
                && !((TrainPosesPayload) this.active.source).dimension().equals(poses.dimension())) {
            this.cancelActive();
        }
        // 正在传输的完整快照继续完成，队列中只保留一份最新姿态。
        this.enqueue(new Job(key, poses, () -> DistantTrainPackets.prepare(poses), started, () -> {}));
    }

    public void contraptions(ContraptionPosesPayload snapshot) {
        if (!this.closed.get()) this.contraptions.offer(snapshot);
    }

    public void retainContraptionDimension(net.minecraft.resources.ResourceLocation dimension) {
        this.contraptions.retainDimension(dimension);
    }

    public void remove(TrainPosesPayload removal, Runnable delivered) {
        if (this.closed.get()) return;
        Key key = new Key(removal.trainId(), REMOVE, 0);
        if (this.waiting(key) != null || this.active != null && this.active.key.equals(key)) return;
        this.enqueue(new Job(key, removal, () -> DistantTrainPackets.prepare(removal), () -> {}, delivered));
    }

    /** 移除包已提交时，重新进入范围必须补发形状。 */
    public boolean cancelRemoval(UUID train) {
        Key key = new Key(train, REMOVE, 0);
        Job waiting = this.removeWaiting(key);
        boolean began = waiting != null && waiting.began;
        if (waiting != null) this.release(waiting);
        if (this.active == null || !this.active.key.equals(key)) return began;
        began |= this.active.began;
        this.cancelActive();
        return began;
    }

    public void cancelShape(UUID train, long shape) {
        Key key = new Key(train, SHAPE, shape);
        Job removed = this.removeWaiting(key);
        if (removed != null) this.release(removed);
        if (this.active != null && this.active.key.equals(key)) this.cancelActive();
    }

    public void retainTrains(Set<UUID> current) {
        var iterator = this.pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (current.contains(entry.getKey())) continue;
            int before = entry.getValue().size();
            entry.getValue().entrySet().removeIf(job -> {
                if (job.getKey().kind() == REMOVE) return false;
                this.release(job.getValue());
                return true;
            });
            this.waitingCount -= before - entry.getValue().size();
            if (entry.getValue().isEmpty()) iterator.remove();
        }
        if (this.active != null && this.active.key.kind() != REMOVE
                && !current.contains(this.active.key.train())) this.cancelActive();
    }

    private Job waiting(Key key) {
        var train = this.pending.get(key.train());
        return train == null ? null : train.get(key);
    }

    private void enqueue(Job job) {
        var train = this.pending.computeIfAbsent(job.key.train(), ignored -> new LinkedHashMap<>());
        Job previous = train.put(job.key, job);
        if (previous == null) this.waitingCount++;
        else if (previous != job) this.release(previous);
    }

    private Job removeWaiting(Key key) {
        var train = this.pending.get(key.train());
        if (train == null) return null;
        Job removed = train.remove(key);
        if (removed != null) this.waitingCount--;
        if (train.isEmpty()) this.pending.remove(key.train());
        return removed;
    }

    private void rotate(UUID trainId) {
        var train = this.pending.remove(trainId);
        if (train != null) this.pending.put(trainId, train);
    }

    private void cancelActive() {
        this.release(this.active);
        this.rotate(this.active.key.train());
        this.active = null;
    }

    private void release(Job job) {
        this.encodingBudget.cancel(job);
        if (job.lease != null) job.lease.close();
        job.lease = null;
        job.encoded = null;
        job.prepared = null;
    }

    private void defer(Job job) {
        this.active = null;
        if (!this.closed.get() && this.waiting(job.key) == null) this.enqueue(job);
        else this.release(job);
        this.rotate(job.key.train());
    }

    private void retire(Job job) {
        this.release(job);
        job.nextPacket = 0;
        if (this.active == job) this.defer(job);
    }

    public void requestClose() {
        this.closed.set(true);
    }

    public boolean isClosed() {
        return this.closed.get();
    }

    public void close() {
        this.requestClose();
        this.contraptions.clear();
        if (this.active != null) this.cancelActive();
        for (var train : this.pending.values()) for (var job : train.values()) this.release(job);
        this.pending.clear();
        this.blockedShapes.clear();
        this.waitingCount = 0;
    }

    public int drain(boolean fragmentsSupported, BooleanSupplier live, BooleanSupplier writable,
                     Writer writer, long now) {
        if (this.fragmentsSupported != fragmentsSupported) {
            this.fragmentsSupported = fragmentsSupported;
            this.blockedShapes.clear();
        }
        int sent = 0, bytes = 0;
        // 两类流量轮换优先权，并共享本轮及尚未完成写入的额度。
        boolean contraptionsFirst = this.contraptionsFirst;
        this.contraptionsFirst = !contraptionsFirst;
        BooleanSupplier open = () -> !this.closed.get() && live.getAsBoolean();
        if (contraptionsFirst) {
            sent = this.contraptions.drain(ROUND_PACKETS, ROUND_BYTES, open, writable, writer);
            bytes = this.contraptions.lastBytes();
        }
        // 空编码、旧客户端和损坏源也必须受工作量上限约束。
        int examined = 0;
        while (sent < ROUND_PACKETS && examined < ROUND_PACKETS && !this.closed.get() && live.getAsBoolean()) {
            if (this.active == null) {
                if (this.pending.isEmpty()) break;
                // 取消后的旧写入完成前，先等待其有界网络额度归还。
                if (!writable.getAsBoolean() || this.outstandingBytes.get() != 0) break;
                this.active = this.pending.values().iterator().next().values().iterator().next();
                this.removeWaiting(this.active.key);
                examined++;
                try {
                    if (this.active.prepared == null) {
                        var reusable = this.encodingBudget.reusable(this.active.source);
                        this.active.prepared = reusable == null ? this.active.prepare.get()
                                : new DistantTrainPackets.Prepared(reusable.bytes(), () -> reusable, () -> reusable);
                    }
                    if (this.closed.get() || !live.getAsBoolean()) break;
                    var prepared = this.active.prepared;
                    if (prepared != null && (!prepared.large() || fragmentsSupported)) {
                        if (prepared.large()) {
                            Job job = this.active;
                            job.lease = this.encodingBudget.acquire(job, prepared.reservationBytes(),
                                    prepared.cached().get(), () -> this.retire(job), now);
                            if (job.lease == null) {
                                this.defer(job);
                                // 只剩同一等待项时，不在本轮重复申请同一份额度。
                                if (this.waitingCount == 1) break;
                                continue;
                            }
                        }
                        this.active.encoded = prepared.encode();
                        if (this.active.encoded != null && this.active.encoded.fragmented()) {
                            this.active.lease.bind(this.active.encoded);
                        }
                    }
                } catch (RuntimeException | Error failure) {
                    this.cancelActive();
                    throw failure;
                }
                if (this.active.encoded == null
                        || this.active.encoded.fragmented() && !fragmentsSupported) {
                    if (this.active.key.kind() == SHAPE) {
                        // 旧客户端和无效源不逐轮重复编码；弱引用不延长历史形状寿命。
                        this.blockedShapes.put(this.active.key, new WeakReference<>(this.active.source));
                        if (this.blockedShapes.size() > 4096) {
                            this.blockedShapes.remove(this.blockedShapes.keySet().iterator().next());
                        }
                    }
                    this.cancelActive();
                    continue;
                }
            }
            Job job = this.active;
            if (job.failed.get()) {
                if (job.outstanding.get() != 0) break;
                job.failed.set(false);
                job.nextPacket = 0;
                // 失败项移到队尾，单个有问题的列车不能阻塞所有后续工作。
                if (this.waiting(job.key) == null) this.enqueue(job);
                this.cancelActive();
                break;
            }
            // 接收端按无新片时间过期；慢连接恢复后从首片重新建立装配。
            if (job.encoded.fragmented() && job.nextPacket != 0 && now - job.lastWrite >= RESTART_NANOS) {
                if (job.outstanding.get() != 0) break;
                job.nextPacket = 0;
            }
            if (job.nextPacket == job.encoded.packetCount()) {
                if (job.outstanding.get() != 0) break;
                // 最后一个失败回调可能恰好发生在前一次 failed 读取之后。
                if (job.failed.get()) continue;
                // 每完成一份快照轮换列车，长编组不能独占后续全部形状额度。
                this.cancelActive();
                job.delivered.run();
                continue;
            }
            int index = job.nextPacket;
            int cost = PACKET_OVERHEAD + (job.encoded.fragmented()
                    ? Math.min(DistantTrainPackets.FRAGMENT_BYTES,
                            job.encoded.bytes() - index * DistantTrainPackets.FRAGMENT_BYTES)
                    : job.encoded.bytes());
            if (cost > ROUND_BYTES - bytes || cost > ROUND_BYTES - this.outstandingBytes.get()
                    || !writable.getAsBoolean()) break;
            if (this.closed.get() || !live.getAsBoolean()) break;
            if (!job.began) {
                job.began = true;
                job.started.run();
            }
            this.outstandingBytes.addAndGet(cost);
            job.outstanding.incrementAndGet();
            var settled = new AtomicBoolean();
            Consumer<Boolean> completed = success -> {
                if (!settled.compareAndSet(false, true)) return;
                if (!success) job.failed.set(true);
                this.outstandingBytes.addAndGet(-cost);
                job.outstanding.decrementAndGet();
            };
            try {
                writer.send(job.encoded.packetForSend(index), completed);
            } catch (RuntimeException | Error failure) {
                completed.accept(false);
                throw failure;
            }
            job.nextPacket++;
            job.lastWrite = now;
            if (job.lease != null) job.lease.touch(now);
            bytes += cost;
            sent++;
        }
        if (!contraptionsFirst) sent += this.contraptions.drain(
                ROUND_PACKETS - sent, ROUND_BYTES - bytes, open, writable, writer);
        return sent;
    }

    public int outstandingBytes() {
        return this.outstandingBytes.get();
    }

    public int pendingCount() {
        return this.waitingCount + (this.active == null ? 0 : 1) + this.contraptions.pendingCount();
    }
}
