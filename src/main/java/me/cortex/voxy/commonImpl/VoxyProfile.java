package me.cortex.voxy.commonImpl;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class VoxyProfile {
    private VoxyProfile() {}

    //Read on every instrumented call, so it is the one thing that must stay cheap when off
    public static volatile boolean enabled;

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static volatile long windowStartNanos;
    private static Thread renderThread;

    private static final class Entry {
        final LongAdder nanos = new LongAdder();
        final LongAdder calls = new LongAdder();
        final LongAdder renderThreadNanos = new LongAdder();
    }

    //Call once from the render thread so the report can tell the two buckets apart without a
    //ThreadLocal on the hot path
    public static void markRenderThread() {
        renderThread = Thread.currentThread();
    }

    public static long begin() {
        return enabled ? System.nanoTime() : 0L;
    }

    public static void end(String name, long start) {
        if (start == 0L || !enabled) {
            return;
        }
        long elapsed = System.nanoTime() - start;
        var entry = ENTRIES.computeIfAbsent(name, k -> new Entry());
        entry.nanos.add(elapsed);
        entry.calls.increment();
        if (Thread.currentThread() == renderThread) {
            entry.renderThreadNanos.add(elapsed);
        }
    }

    private static final Map<String, Entry> GPU = new ConcurrentHashMap<>();
    private static volatile int gpuSamples;

    public static long gpuCaptureId() {
        return enabled ? windowStartNanos : 0;
    }

    public static void recordGpuMillis(String label, double millis) {
        if (!enabled) {
            return;
        }
        var entry = GPU.computeIfAbsent(label, k -> new Entry());
        entry.nanos.add((long) (millis * 1.0e6));
        entry.calls.increment();
    }

    public static void noteGpuFrame() {
        if (enabled) {
            gpuSamples++;
        }
    }

    public static void start() {
        ENTRIES.clear();
        GPU.clear();
        gpuSamples = 0;
        windowStartNanos = System.nanoTime();
        enabled = true;
    }

    public static void stop() {
        enabled = false;
    }

    public static boolean isRunning() {
        return enabled;
    }

    public static String report() {
        double windowMs = (System.nanoTime() - windowStartNanos) / 1.0e6;
        if (windowMs <= 0) {
            return "profile window is empty";
        }
        var rows = new ArrayList<Map.Entry<String, Entry>>(ENTRIES.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue().nanos.sum(), a.getValue().nanos.sum()));

        var sb = new StringBuilder(String.format("voxy profile over %.1f s%n", windowMs / 1000.0));
        sb.append(String.format("  %-28s %9s %9s %9s %8s%n", "section", "calls", "total ms", "ms/s", "on rt"));
        long renderTotal = 0;
        for (var row : rows) {
            var e = row.getValue();
            long total = e.nanos.sum();
            long rt = e.renderThreadNanos.sum();
            renderTotal += rt;
            sb.append(String.format("  %-28s %,9d %9.1f %9.2f %7.0f%%%n",
                    row.getKey(),
                    e.calls.sum(),
                    total / 1.0e6,
                    (total / 1.0e6) / (windowMs / 1000.0),
                    total == 0 ? 0.0 : (100.0 * rt / total)));
        }
        if (rows.isEmpty()) {
            sb.append("  (nothing recorded - is voxy rendering, and did anything ingest?)\n");
        }
        //The only line that maps directly onto frame rate
        sb.append(String.format("  %-28s %9s %9.1f %9.2f%n", "== render thread total", "",
                renderTotal / 1.0e6, (renderTotal / 1.0e6) / (windowMs / 1000.0)));
        sb.append("  ms/s on the render thread is milliseconds lost per second of play; anything not\n");
        sb.append("  marked 'on rt' is worker time and only costs frames through contention.\n");

        if (GPU.isEmpty()) {
            sb.append("  (no GPU samples - the pipeline did not run, or timer queries are unsupported)");
            return sb.toString();
        }
        var gpuRows = new ArrayList<Map.Entry<String, Entry>>(GPU.entrySet());
        gpuRows.sort((a, b) -> Long.compare(b.getValue().nanos.sum(), a.getValue().nanos.sum()));
        double gpuTotalPerFrame = 0;
        int frames = gpuSamples;
        sb.append(String.format("  GPU passes over %,d sampled frames:%n", frames));
        for (var row : gpuRows) {
            var e = row.getValue();
            double perFrame = frames == 0 ? 0 : (e.nanos.sum() / 1.0e6) / frames;
            gpuTotalPerFrame += perFrame;
            sb.append(String.format("  %-28s %19.3f ms/frame%n", row.getKey(), perFrame));
        }
        sb.append(String.format("  %-28s %19.3f ms/frame%n", "== gpu total", gpuTotalPerFrame));
        sb.append("  16.7 ms/frame is the whole 60fps budget, spent inside voxy alone.");
        return sb.toString();
    }
}
