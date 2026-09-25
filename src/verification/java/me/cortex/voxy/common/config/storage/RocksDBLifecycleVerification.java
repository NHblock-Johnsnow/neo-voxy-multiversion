package me.cortex.voxy.common.config.storage;

import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 使用临时数据库验证反复启停，以及辅助表遍历和关闭之间的互斥。 */
public final class RocksDBLifecycleVerification {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Path.of("build", "verification");
        Files.createDirectories(root);
        Path database = Files.createTempDirectory(root, "rocksdb-lifecycle-");
        byte[] value = {1, 2, 3};
        for (int cycle = 0; cycle < 8; cycle++) {
            var storage = new RocksDBStorageBackend(database.toString());
            if (cycle > 0) expect(Arrays.equals(value, storage.getAux("test", 1)), "重开后保留数据");
            storage.putAux("test", 1, value);
            expect(Arrays.equals(value, storage.getAux("test", 1)), "读取已写入数据");
            storage.putAux("test", 2, value);
            storage.deleteAux("test", 2);
            expect(storage.getAux("test", 2) == null, "正常删除");
            if (cycle == 0) verifyConcurrentClose(storage);
            else storage.close();
            storage.close();
            expect(!storage.supportsAuxTable("test"), "关闭后不再提供辅助表");
            expectClosed(() -> storage.putAux("test", 1, value));
            expectClosed(() -> storage.getAux("test", 1));
            expectClosed(() -> storage.deleteAux("test", 1));
            expectClosed(() -> storage.forEachAux("test", (key, bytes) -> {}));
            expectClosed(storage::flush);
        }
        System.out.println("Passed " + checks + " RocksDB lifecycle checks across 8 reopen cycles");
    }

    private static void verifyConcurrentClose(RocksDBStorageBackend storage) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closing = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var scan = workers.submit(() -> storage.forEachAux("test", (key, value) -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("遍历释放超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }));
            try {
                expect(entered.await(5, TimeUnit.SECONDS), "遍历已进入 native iterator");
                var close = workers.submit(() -> {
                    closing.countDown();
                    storage.close();
                });
                expect(closing.await(5, TimeUnit.SECONDS), "关闭已开始");
                try {
                    close.get(100, TimeUnit.MILLISECONDS);
                    throw new AssertionError("关闭越过了正在使用的 iterator");
                } catch (TimeoutException expected) {
                    checks++;
                } finally {
                    release.countDown();
                }
                scan.get(5, TimeUnit.SECONDS);
                close.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    private static void expectClosed(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("关闭后的操作必须在进入 JNI 前拒绝");
        } catch (IllegalStateException expected) {
            checks++;
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
