package me.cortex.voxy.commonImpl.compat.create;

import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ContraptionPose;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ContraptionPosesPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 由发送 worker 独占；完整可见集用于合并，网络仍按 UUID 增量发送。 */
final class DistantContraptionSendQueue {
    private static final int POSES_PER_PACKET = 1024;
    private final AtomicInteger outstandingBytes;
    private Map<UUID, ContraptionPose> desired = Map.of();
    private final LinkedHashMap<UUID, ContraptionPose> pending = new LinkedHashMap<>();
    private ResourceLocation dimension;
    private ContraptionPosesPayload active;
    private AtomicReference<Boolean> receipt;
    private int lastBytes;

    DistantContraptionSendQueue(AtomicInteger outstandingBytes) {
        this.outstandingBytes = outstandingBytes;
    }

    void offer(ContraptionPosesPayload snapshot) {
        if (!snapshot.dimension().equals(this.dimension)) {
            this.clear();
            this.dimension = snapshot.dimension();
        }
        Map<UUID, ContraptionPose> next = HashMap.newHashMap(snapshot.poses().size());
        for (var pose : snapshot.poses()) {
            next.put(pose.id(), pose);
            // canonical pose 对象也是采样 revision；同坐标的新对象可表示最后停下的样本。
            if (pose != this.desired.get(pose.id())) this.pending.put(pose.id(), pose);
        }
        this.pending.keySet().retainAll(next.keySet());
        this.desired = next;
    }

    void clear() {
        this.desired = Map.of();
        this.pending.clear();
        this.active = null;
        this.receipt = null;
        this.dimension = null;
    }

    void retainDimension(ResourceLocation current) {
        if (this.dimension != null && !this.dimension.equals(current)) this.clear();
    }

    int pendingCount() {
        return this.pending.size() + (this.active == null ? 0 : 1);
    }

    int lastBytes() {
        return this.lastBytes;
    }

    int drain(int packetLimit, int byteLimit, BooleanSupplier live, BooleanSupplier writable,
              DistantTrainSendQueue.Writer writer) {
        this.lastBytes = 0;
        int sent = 0;
        while (true) {
            if (this.active != null) {
                Boolean success = this.receipt.get();
                if (success == null) break;
                if (!success) {
                    // 失败后补发最新值；即使服务器不再采到变化，最终静止姿态也不会丢失。
                    for (var pose : this.active.poses()) {
                        var current = this.desired.get(pose.id());
                        if (current != null) this.pending.putIfAbsent(pose.id(), current);
                    }
                }
                this.active = null;
                this.receipt = null;
                if (!success) break;
            }
            if (sent >= packetLimit || this.pending.isEmpty() || !live.getAsBoolean()
                    || !writable.getAsBoolean()) break;
            int count = Math.min(POSES_PER_PACKET, this.pending.size());
            int dimensionBytes = this.dimension.toString().length();
            int cost = 128 + varIntBytes(dimensionBytes) + dimensionBytes + varIntBytes(count) + count * 45;
            if (cost > byteLimit - this.lastBytes
                    || cost > DistantTrainSendQueue.ROUND_BYTES - this.outstandingBytes.get()) break;
            var batch = new ArrayList<ContraptionPose>(count);
            var iterator = this.pending.values().iterator();
            for (int i = 0; i < count; i++) {
                batch.add(iterator.next());
                iterator.remove();
            }
            this.active = new ContraptionPosesPayload(this.dimension, batch);
            var result = new AtomicReference<Boolean>();
            this.receipt = result;
            this.outstandingBytes.addAndGet(cost);
            var allowance = this.outstandingBytes;
            java.util.function.Consumer<Boolean> completed = success -> {
                // 回调仅捕获单个结果和共享计数器，不保留整份可见集。
                if (result.compareAndSet(null, success)) allowance.addAndGet(-cost);
            };
            try {
                writer.send(this.active, completed);
            } catch (RuntimeException | Error failure) {
                completed.accept(false);
                throw failure;
            }
            this.lastBytes += cost;
            sent++;
        }
        return sent;
    }

    private static int varIntBytes(int value) {
        int count = 1;
        while ((value >>>= 7) != 0) count++;
        return count;
    }
}
