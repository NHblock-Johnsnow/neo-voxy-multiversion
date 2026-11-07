package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.util.ExceptionCleanup;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class WeakConcurrentCleanableHashMap<K extends LongSupplier, V> {
    //TODO could move to a Cleanable style system possibly?

    private final Consumer<V> valueCleaner;
    private final ReferenceQueue<K> cleanupQueue = new ReferenceQueue<>();

    private final ReentrantLock k2iLock = new ReentrantLock();
    private final Object2LongOpenHashMap<WeakReference<K>> k2i = new Object2LongOpenHashMap<>();
    {
        this.k2i.defaultReturnValue(-1);
    }
    private final Long2ObjectOpenHashMap<V>[] i2v = new Long2ObjectOpenHashMap[1<<4];
    private final ReentrantLock[] i2vLocks = new ReentrantLock[this.i2v.length];
    {
        for (int i = 0; i < this.i2v.length; i++) {
            this.i2v[i] = new Long2ObjectOpenHashMap<>();
            this.i2vLocks[i] = new ReentrantLock();
        }
    }

    private final AtomicInteger count = new AtomicInteger();

    public WeakConcurrentCleanableHashMap(Consumer<V> cleanupConsumer) {
        this.valueCleaner = cleanupConsumer;
    }

    private static int Id2Seg(long id, int MSK) {
        return HashCommon.mix((int)id)&MSK;
    }

    public V computeIfAbsent(K key, Supplier<V> valueOnAbsent) {
        this.cleanup();
        long id = key.getAsLong();
        int bucket = Id2Seg(id, this.i2v.length - 1);
        var values = this.i2v[bucket];
        var lock = this.i2vLocks[bucket];
        lock.lock();
        try {
            if (values.containsKey(id)) return values.get(id);
            V value = valueOnAbsent.get();
            this.k2iLock.lock();
            try {
                values.put(id, value);
                this.k2i.put(new WeakReference<>(key, this.cleanupQueue), id);
                this.count.incrementAndGet();
            } finally {
                this.k2iLock.unlock();
            }
            return value;
        } finally {
            // 工厂失败也必须解锁，读取和登记均在分段锁内完成。
            lock.unlock();
        }
    }

    public void cleanup() {
        WeakReference<K> ref = (WeakReference<K>) this.cleanupQueue.poll();
        if (ref == null) return;
        var ids = new LongArrayFIFOQueue();
        this.k2iLock.lock();
        try {
            do {
                long id = this.k2i.removeLong(ref);
                if (id >= 0) ids.enqueue(id);
            } while ((ref = (WeakReference<K>) this.cleanupQueue.poll()) != null);
        } finally {
            this.k2iLock.unlock();
        }
        var cleanup = new ExceptionCleanup(null);
        while (!ids.isEmpty()) {
            long id = ids.dequeueLong();
            int bucket = Id2Seg(id, this.i2v.length - 1);
            var lock = this.i2vLocks[bucket];
            V value;
            lock.lock();
            try {
                value = this.i2v[bucket].remove(id);
                if (value != null) this.count.decrementAndGet();
            } finally {
                lock.unlock();
            }
            if (value != null) cleanup.run(() -> this.valueCleaner.accept(value));
        }
        cleanup.rethrow();
    }

    public List<V> clear() {
        List<V> values = new ArrayList<>();
        for (var lock : this.i2vLocks) lock.lock();
        this.k2iLock.lock();
        try {
            this.k2i.clear();
            for (var entries : this.i2v) {
                values.addAll(entries.values());
                entries.clear();
            }
            this.count.set(0);
            return values;
        } finally {
            this.k2iLock.unlock();
            for (var lock : this.i2vLocks) lock.unlock();
        }
    }

    public int size() {
        return this.count.get();
    }
}
