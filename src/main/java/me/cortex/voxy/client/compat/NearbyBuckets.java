package me.cortex.voxy.client.compat;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;

/** 按占用桶数与查询格数择少遍历，保留原来的先Z后X顺序。 */
public final class NearbyBuckets {
    private NearbyBuckets() {}

    public static <T> void forEach(Map<Long, T> buckets, int centerX, int centerZ, int radius,
                                   Consumer<? super T> consumer) {
        if (radius < 0) throw new IllegalArgumentException("Negative bucket radius");
        long minX = Math.max(Integer.MIN_VALUE, (long) centerX - radius);
        long maxX = Math.min(Integer.MAX_VALUE, (long) centerX + radius);
        long minZ = Math.max(Integer.MIN_VALUE, (long) centerZ - radius);
        long maxZ = Math.min(Integer.MAX_VALUE, (long) centerZ + radius);
        long side = 2L * radius + 1;
        // 用除法比较面积，避免大半径乘方溢出。
        if (side <= buckets.size() / side) {
            for (long z = minZ; z <= maxZ; z++) {
                for (long x = minX; x <= maxX; x++) {
                    T bucket = buckets.get((x << 32) ^ (z & 0xFFFFFFFFL));
                    if (bucket != null) consumer.accept(bucket);
                }
            }
            return;
        }

        var nearby = new ArrayList<Map.Entry<Long, T>>();
        for (var entry : buckets.entrySet()) {
            long key = entry.getKey();
            int x = (int) (key >> 32), z = (int) key;
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ && entry.getValue() != null) {
                nearby.add(entry);
            }
        }
        nearby.sort((a, b) -> {
            long left = a.getKey(), right = b.getKey();
            int z = Integer.compare((int) left, (int) right);
            return z != 0 ? z : Integer.compare((int) (left >> 32), (int) (right >> 32));
        });
        for (var entry : nearby) consumer.accept(entry.getValue());
    }
}
