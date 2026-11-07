package me.cortex.voxy.client.core.util;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import me.cortex.voxy.common.util.TrackedObject;

import java.lang.reflect.Array;
import java.util.Arrays;

import static org.lwjgl.opengl.ARBTimerQuery.GL_TIMESTAMP;
import static org.lwjgl.opengl.ARBTimerQuery.glQueryCounter;
import static org.lwjgl.opengl.GL15.glDeleteQueries;
import static org.lwjgl.opengl.GL15.glGenQueries;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL33.glGetQueryObjecti64;

public class GPUTiming {
    public static GPUTiming INSTANCE = new GPUTiming();

    private final GlTimestampQuerySet<String> timingSet = new GlTimestampQuerySet(String.class);

    private float[] timings = new float[0];
    private String[] lables = new String[0];

    private volatile boolean enabled = false;
    private final java.util.concurrent.atomic.AtomicInteger captures = new java.util.concurrent.atomic.AtomicInteger();

    public final class Capture implements AutoCloseable {
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

        private Capture() { captures.incrementAndGet(); }

        @Override
        public void close() {
            if (this.closed.compareAndSet(false, true)) captures.decrementAndGet();
        }
    }

    public Capture capture() { return new Capture(); }

    public boolean isEnabled() { return this.enabled || this.captures.get() != 0; }

    public void marker() {
        this.marker(null);
    }

    public void marker(String lable) {
        if (this.isEnabled()) {
            this.timingSet.capture(lable);
        }
    }

    public void setEnabled(boolean enable) {
        if (this.enabled != enable) {
            this.enabled = enable;
        }
    }

    public String getDebug() {
        if (!this.isEnabled()) {
            return "";
        }
        StringBuilder str = new StringBuilder("GpuTime: [");
        for (int i = 0; i < this.timings.length; i++) {
            if (this.lables[i] != null) {
                str.append(this.lables[i]+":"+String.format("%.2f", this.timings[i]));
            } else {
                str.append(String.format("%.2f", this.timings[i]));
            }
            if (i!=this.timings.length-1) {
                str.append(", ");
            }
        }
        str.append(']');
        return str.toString();
    }

    public void tick() {
        long captureId = me.cortex.voxy.commonImpl.VoxyProfile.gpuCaptureId();
        this.timingSet.download((meta,data)->{
            long current = data[0];
            boolean profile = captureId != 0 && captureId == me.cortex.voxy.commonImpl.VoxyProfile.gpuCaptureId();

            if (data.length-1!=this.timings.length) {
                this.timings = new float[data.length-1];
                this.lables = new String[meta.length-1];
            }

            Arrays.fill(this.lables, null);
            for (int i = 1; i < meta.length; i++) {
                long next = data[i];
                long delta = next - current;
                float time = (float) (((double)delta)/1_000_000);
                this.timings[i-1] = Math.max(this.timings[i-1]*0.99f+time*0.01f, time);
                this.lables[i-1] = meta[i-1];
                //Raw per-pass time to the profiler, not the rolling max kept above: a window wants the
                //average over its frames, and the rolling value never comes back down after a spike
                if (profile) me.cortex.voxy.commonImpl.VoxyProfile.recordGpuMillis(
                        meta[i-1] == null ? ("pass" + (i-1)) : meta[i-1], time);
                current = next;
            }
            // 只计算已返回且至少有一段耗时的帧，排除未就绪和旧采集窗口。
            if (profile && data.length > 1) me.cortex.voxy.commonImpl.VoxyProfile.noteGpuFrame();
        });
        this.timingSet.tick();
    }

    public void free() {
        this.timingSet.free();
    }

    public interface TimingDataConsumer <T> {
        void accept(T metadata, long[] timings);
    }
    private static final class GlTimestampQuerySet <T> extends TrackedObject {

        private record InflightRequest<T>(int[] queries, T[] meta, TimingDataConsumer<T[]> callback) {
            private boolean callbackIfReady(IntArrayFIFOQueue queryPool) {
                boolean ready = glGetQueryObjecti(this.queries[this.queries.length-1], GL_QUERY_RESULT_AVAILABLE) == GL_TRUE;
                if (!ready) {
                    return false;
                }
                long[] results = new long[this.queries.length];
                for (int i = 0; i < this.queries.length; i++) {
                    results[i] = glGetQueryObjecti64(this.queries[i], GL_QUERY_RESULT);
                    queryPool.enqueue(this.queries[i]);
                }
                this.callback.accept(this.meta, results);
                return true;
            }
        }
        private final IntArrayFIFOQueue POOL = new IntArrayFIFOQueue();
        private final ObjectArrayFIFOQueue<InflightRequest<T>> INFLIGHT = new ObjectArrayFIFOQueue();

        private final int[] queries = new int[64];
        private final T[] metadata;
        private int index;


        private GlTimestampQuerySet(Class<T> metaClass) {
            this.metadata = (T[]) Array.newInstance(metaClass, 64);
        }

        public void capture(T metadata) {
            if (this.index >= this.metadata.length) {
                throw new IllegalStateException();
            }
            int slot = this.index++;
            this.metadata[slot] = metadata;
            int query = this.getQuery();
            glQueryCounter(query, GL_TIMESTAMP);
            this.queries[slot] = query;

        }

        public void download(TimingDataConsumer<T[]> consumer) {
            if (this.index != 0) {
                var queries = Arrays.copyOf(this.queries, this.index);
                var metadata = Arrays.copyOf(this.metadata, this.index);
                Arrays.fill(this.metadata, null);
                this.index = 0;
                this.INFLIGHT.enqueue(new InflightRequest(queries, metadata, consumer));
            }
        }

        public void tick() {
            while (!INFLIGHT.isEmpty()) {
                if (INFLIGHT.first().callbackIfReady(POOL)) {
                    INFLIGHT.dequeue();
                } else {
                    break;
                }
            }
        }

        private int getQuery() {
            if (POOL.isEmpty()) {
                return glGenQueries();
            } else {
                return POOL.dequeueInt();
            }
        }

        @Override
        public void free() {
            super.free0();
            while (!POOL.isEmpty()) {
                glDeleteQueries(POOL.dequeueInt());
            }
            while (!INFLIGHT.isEmpty()) {
                glDeleteQueries(INFLIGHT.dequeue().queries);
            }
        }
    }
}
