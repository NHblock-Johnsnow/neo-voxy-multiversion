package me.cortex.voxy.client.compat;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;

import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;

/** 后台读取、解码，前台限量安装；引擎引用覆盖任务，队列按估算载荷背压。 */
public final class AuxiliaryTableLoader<T> implements AutoCloseable {
    private static final int MAX_ROWS = 128;
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(0, 3, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                var thread = new Thread(task, "Voxy auxiliary table reader");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });

    @FunctionalInterface
    public interface Decoder<T> {
        T decode(long key, byte[] bytes);
    }

    @FunctionalInterface
    public interface Consumer<T> {
        void accept(long key, T value);
    }

    private record Row<T>(long key, T value, long bytes) {}
    private static final class Cancelled extends RuntimeException {
        Cancelled() { super(null, null, false, false); }
    }
    private static final Cancelled CANCELLED = new Cancelled();

    private final WorldEngine engine;
    private final String table;
    private final Decoder<T> decoder;
    private final ToLongFunction<T> weight;
    private final ArrayDeque<Row<T>> rows = new ArrayDeque<>();
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet updatedKeys = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private volatile boolean cancelled;
    private boolean submitted, finished;
    private long bufferedBytes;
    private long retryAtNanos;

    public AuxiliaryTableLoader(WorldEngine engine, String table, Decoder<T> decoder, ToLongFunction<T> weight) {
        this.engine = engine;
        this.table = table;
        this.decoder = decoder;
        this.weight = weight;
    }

    private boolean usable() {
        return !this.cancelled && this.engine.isLive()
                && (this.engine.instanceIn == null || this.engine.instanceIn.isRunning());
    }

    public synchronized void start() {
        if (this.submitted || this.cancelled) return;
        if (this.retryAtNanos != 0 && System.nanoTime() - this.retryAtNanos < 0) return;
        if (!this.usable()) {
            this.close();
            this.finished = this.submitted = true;
            return;
        }
        this.engine.acquireRef();
        this.submitted = true;
        try {
            WORKERS.execute(this::read);
        } catch (RejectedExecutionException full) {
            // 换世界后旧任务尚未退出时，下一 tick 重试，不积压后台任务。
            this.submitted = false;
            this.engine.releaseRef();
        } catch (RuntimeException | Error failure) {
            this.submitted = false;
            this.engine.releaseRef();
            throw failure;
        }
    }

    private void read() {
        boolean complete = false;
        try {
            if (!this.usable()) return;
            this.engine.storage.forEachAux(this.table, (key, bytes) -> {
                if (!this.usable()) throw CANCELLED;
                synchronized (this) {
                    if (this.updatedKeys.contains(key)) return;
                }
                T value = this.decoder.decode(key, bytes);
                if (value == null) return;
                long cost = Math.max(bytes.length, Math.max(1, this.weight.applyAsLong(value)));
                synchronized (this) {
                    // 单个合法大条目独占队列，不因容量阈值被静默丢弃。
                    while (this.usable() && !this.updatedKeys.contains(key) && (this.rows.size() >= MAX_ROWS
                            || !this.rows.isEmpty() && cost > MAX_BYTES - this.bufferedBytes)) {
                        try {
                            this.wait(100);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw CANCELLED;
                        }
                    }
                    if (!this.usable()) throw CANCELLED;
                    if (this.updatedKeys.contains(key)) return;
                    this.rows.addLast(new Row<>(key, value, cost));
                    this.bufferedBytes += cost;
                }
            });
            complete = true;
        } catch (Cancelled ignored) {
        } catch (Throwable failure) {
            if (this.usable()) Logger.error("Reading auxiliary table " + this.table, failure);
        } finally {
            try {
                this.engine.releaseRef();
            } finally {
                synchronized (this) {
                    if (!this.usable()) {
                        this.cancelled = true;
                        this.rows.clear();
                        this.updatedKeys.clear();
                        this.bufferedBytes = 0;
                    }
                    this.finished = complete || !this.usable();
                    this.submitted = this.finished;
                    if (!this.finished) this.retryAtNanos = System.nanoTime() + 1_000_000_000L;
                    this.notifyAll();
                }
            }
        }
    }

    public int drain(int limit, Consumer<T> consumer) {
        this.start();
        int applied = 0;
        while (applied < limit && this.usable()) {
            Row<T> row;
            boolean updated;
            synchronized (this) {
                row = this.rows.pollFirst();
                if (row == null) break;
                this.bufferedBytes -= row.bytes();
                updated = this.updatedKeys.contains(row.key());
                this.notifyAll();
            }
            if (!updated) consumer.accept(row.key(), row.value());
            applied++;
        }
        return applied;
    }

    public synchronized boolean isFinished() {
        return this.finished && this.rows.isEmpty();
    }

    /** 实时修改（包括删除）优先于该次初始扫描，直到扫描与队列都结束。 */
    public synchronized void markUpdated(long key) {
        this.updatedKeys.add(key);
        this.notifyAll();
    }

    @Override
    public synchronized void close() {
        this.cancelled = true;
        if (!this.submitted) this.finished = true;
        this.rows.clear();
        this.updatedKeys.clear();
        this.bufferedBytes = 0;
        this.notifyAll();
    }
}
