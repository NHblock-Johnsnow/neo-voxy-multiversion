package me.cortex.voxy.client.compat.create;

import dev.engine_room.flywheel.api.instance.Instance;

import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.lang.ref.WeakReference;

public final class AzimuthBehaviourIndex {
    private AzimuthBehaviourIndex() {}

    private static final Map<Object, List<Walker<?>>> BEHAVIOURS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private record Walker<T>(WeakReference<T> visual, BiConsumer<T, Consumer<Instance>> action) {
        boolean apply(Consumer<Instance> consumer) {
            T target = this.visual.get();
            if (target == null) return false;
            this.action.accept(target, consumer);
            return true;
        }
    }

    /** walker 使用无捕获方法引用，避免 value 沿 behaviour→parent 反向保活弱键。 */
    public static <T> void register(Object parentVisual, T visual, BiConsumer<T, Consumer<Instance>> walker) {
        if (parentVisual == null || visual == null) {
            return;
        }
        BEHAVIOURS.computeIfAbsent(parentVisual, k -> new CopyOnWriteArrayList<>())
                .add(new Walker<>(new WeakReference<>(visual), walker));
    }

    //Applies the action to every behaviour instance registered under this visual. A walker that throws
    //(its instance already deleted mid-teardown) is dropped rather than retried forever.
    public static void apply(Object parentVisual, Consumer<Instance> action) {
        List<Walker<?>> walkers = BEHAVIOURS.get(parentVisual);
        if (walkers == null) {
            return;
        }
        Walker<?> firstStale = null;
        Set<Walker<?>> stale = null;
        for (Walker<?> walker : walkers) {
            if (walker.visual.refersTo(null)) {
                if (firstStale == null) {
                    firstStale = walker;
                } else {
                    if (stale == null) {
                        stale = Collections.newSetFromMap(new IdentityHashMap<>());
                        stale.add(firstStale);
                    }
                    stale.add(walker);
                }
                continue;
            }
            // 回调前清除连续失效项，保持重入时可见的列表状态。
            if (firstStale != null) {
                removeStale(walkers, firstStale, stale);
                firstStale = null;
                stale = null;
            }
            try {
                if (!walker.apply(action)) walkers.remove(walker);
            } catch (Throwable e) {
                walkers.remove(walker);
            }
        }
        if (firstStale != null) {
            removeStale(walkers, firstStale, stale);
        }
    }
    private static void removeStale(List<Walker<?>> walkers, Walker<?> first, Set<Walker<?>> stale) {
        if (stale == null) {
            walkers.remove(first);
        } else {
            walkers.removeAll(stale);
        }
    }
}
