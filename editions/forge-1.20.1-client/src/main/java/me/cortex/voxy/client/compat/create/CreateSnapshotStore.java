package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class CreateSnapshotStore {
    private static final int MAGIC = 0x56435831;
    private static final int MAX_MESH_BYTES = 256 * 1024 * 1024;
    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Voxy Create snapshot storage");
        thread.setDaemon(true);
        return thread;
    });

    record Kinetic(long key, int x, int y, int z, int stateHash, byte[] mesh) {}
    record Contraption(UUID id, double x, double y, double z, long signature, boolean train,
                       Matrix4f local, int light, byte[] mesh, CreateMeshSource source) {
        Contraption(UUID id, double x, double y, double z, long signature, boolean train,
                    Matrix4f local, int light, byte[] mesh) {
            this(id, x, y, z, signature, train, local, light, mesh, null);
        }
    }

    private CreateSnapshotStore() {}

    static void saveKinetic(ClientLevel level, Kinetic value) {
        Path file = directory(level, "kinetic").resolve(Long.toUnsignedString(value.key(), 16) + ".bin");
        IO.execute(() -> write(file, out -> {
            out.writeInt(2);
            out.writeLong(value.key());
            out.writeInt(value.x());
            out.writeInt(value.y());
            out.writeInt(value.z());
            out.writeInt(value.stateHash());
            writeMesh(out, value.mesh());
        }));
    }

    static void removeKinetic(ClientLevel level, long key) {
        Path file = directory(level, "kinetic").resolve(Long.toUnsignedString(key, 16) + ".bin");
        IO.execute(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (Throwable t) {
                Logger.error("Removing stored Create kinetic snapshot", t);
            }
        });
    }

    static Load<Kinetic> loadKinetics(ClientLevel level) {
        return new Load<>(directory(level, "kinetic"), in -> {
            if (in.readInt() != 2) return null;
            long key = in.readLong();
            var value = new Kinetic(key, in.readInt(), in.readInt(), in.readInt(), in.readInt(), readMesh(in));
            return new Loaded<>(value, value.mesh().length + 128L);
        });
    }

    static void saveContraption(ClientLevel level, UUID id, double x, double y, double z, long signature,
                                boolean train, Matrix4f local, int light, byte[] mesh) {
        saveContraption(level, id, x, y, z, signature, train, local, light, mesh, null);
    }

    static void saveContraption(ClientLevel level, UUID id, double x, double y, double z, long signature,
                                boolean train, Matrix4f local, int light, byte[] mesh, CreateMeshSource source) {
        Path file = directory(level, "contraption").resolve(id + ".bin");
        // 在提交线程捕获姿态，后台不再读取仍会变化的矩阵。
        float[] matrix = new float[16];
        local.get(matrix);
        IO.execute(() -> write(file, out -> {
            out.writeInt(3);
            out.writeLong(id.getMostSignificantBits());
            out.writeLong(id.getLeastSignificantBits());
            out.writeDouble(x);
            out.writeDouble(y);
            out.writeDouble(z);
            out.writeLong(signature);
            out.writeBoolean(train);
            for (float value : matrix) out.writeFloat(value);
            out.writeInt(light);
            writeMesh(out, mesh);
            out.writeBoolean(source != null);
            if (source != null) source.write(out);
        }));
    }

    static Load<Contraption> loadContraptions(ClientLevel level) {
        return new Load<>(directory(level, "contraption"), in -> {
            int version = in.readInt();
            if (version < 1 || version > 3) return null;
            UUID id = new UUID(in.readLong(), in.readLong());
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            long signature = in.readLong();
            boolean train = in.readBoolean();
            float[] matrix = new float[16];
            for (int i = 0; i < matrix.length; i++) matrix[i] = in.readFloat();
            int light = version >= 2 ? in.readInt() : net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
            byte[] mesh = readMesh(in);
            CreateMeshSource source = null;
            if (version >= 3) {
                try {
                    if (in.readBoolean()) source = CreateMeshSource.read(in);
                } catch (Exception failure) {
                    Logger.error("Reading Create mesh source; retaining legacy mesh", failure);
                }
            }
            var value = new Contraption(id, x, y, z, signature, train, new Matrix4f().set(matrix), light, mesh, source);
            return new Loaded<>(value, mesh.length + 256L + (source == null ? 0 : source.retainedBytes()));
        });
    }

    private static Path directory(ClientLevel level, String kind) {
        var instance = VoxyCommon.getInstance();
        Path base = instance instanceof VoxyClientInstance client
                ? client.getStorageBasePath()
                : net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves");
        String dim = level.dimension().location().toString().replace(':', '_').replace('/', '_').replace('\\', '_');
        return base.resolve("create-snapshots-1.20.1").resolve(dim).resolve(kind);
    }

    private static void writeMesh(DataOutputStream out, byte[] mesh) throws Exception {
        if (mesh == null || mesh.length == 0 || mesh.length > MAX_MESH_BYTES) throw new IllegalArgumentException("mesh size");
        out.writeInt(mesh.length);
        out.write(mesh);
    }

    private static byte[] readMesh(DataInputStream in) throws Exception {
        int length = in.readInt();
        if (length <= 0 || length > MAX_MESH_BYTES) throw new IllegalArgumentException("mesh size");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new java.io.EOFException();
        return bytes;
    }

    static void flush() {
        try {
            IO.submit(() -> {}).get();
        } catch (Throwable t) {
            Logger.error("Flushing Create LOD snapshots", t);
        }
    }

    private interface Writer { void write(DataOutputStream out) throws Exception; }
    private interface Reader<T> { Loaded<T> read(DataInputStream in) throws Exception; }
    private record Loaded<T>(T value, long bytes) {}

    // 目录和解压只在存储线程执行；队列满即退出任务，不阻塞后面的保存或 flush。
    static final class Load<T> implements AutoCloseable {
        private static final int BATCH_SIZE = 16;
        private static final long BUFFER_BYTES = 16L * 1024 * 1024;
        private final Path directory;
        private final Reader<T> reader;
        private final ArrayDeque<Loaded<T>> ready = new ArrayDeque<>();
        private java.nio.file.DirectoryStream<Path> stream;
        private Iterator<Path> files;
        private Loaded<T> pending;
        private long bufferedBytes;
        private boolean scheduled;
        private boolean finished;
        private boolean cancelled;

        private Load(Path directory, Reader<T> reader) {
            this.directory = directory;
            this.reader = reader;
            synchronized (this) {
                this.schedule();
            }
        }

        synchronized T poll() {
            var value = this.ready.pollFirst();
            if (value != null) this.bufferedBytes -= value.bytes();
            this.schedule();
            return value == null ? null : value.value();
        }

        synchronized boolean isFinished() {
            return this.cancelled || this.finished && this.ready.isEmpty();
        }

        private boolean fits(Loaded<T> value) {
            // 合法的大记录独占缓冲；最多另保留一条已读但尚未接纳的记录。
            return this.ready.isEmpty() || this.ready.size() < BATCH_SIZE
                    && this.bufferedBytes + value.bytes() <= BUFFER_BYTES;
        }

        private void schedule() {
            if (this.scheduled || this.cancelled || this.finished || this.ready.size() >= BATCH_SIZE
                    || this.bufferedBytes >= BUFFER_BYTES || this.pending != null && !this.fits(this.pending)) return;
            this.scheduled = true;
            IO.execute(this::readBatch);
        }

        private void add(Loaded<T> value) {
            this.ready.addLast(value);
            this.bufferedBytes += value.bytes();
        }

        private void readBatch() {
            try {
                for (int attempts = 0; attempts < BATCH_SIZE; attempts++) {
                    synchronized (this) {
                        if (this.cancelled || this.finished) return;
                        if (this.pending != null) {
                            if (!this.fits(this.pending)) return;
                            this.add(this.pending);
                            this.pending = null;
                        }
                        if (this.ready.size() >= BATCH_SIZE || this.bufferedBytes >= BUFFER_BYTES) return;
                    }
                    if (this.stream == null) {
                        if (!Files.isDirectory(this.directory)) {
                            synchronized (this) { this.finished = true; }
                            return;
                        }
                        this.stream = Files.newDirectoryStream(this.directory);
                        this.files = this.stream.iterator();
                    }
                    if (!this.files.hasNext()) {
                        synchronized (this) { this.finished = true; }
                        return;
                    }
                    Path file = this.files.next();
                    if (!file.getFileName().toString().endsWith(".bin")) continue;
                    Loaded<T> value;
                    try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
                        value = in.readInt() == MAGIC ? this.reader.read(in) : null;
                    } catch (Throwable failure) {
                        Logger.error("Reading Create LOD snapshot " + file.getFileName(), failure);
                        continue;
                    }
                    if (value == null) continue;
                    synchronized (this) {
                        if (this.cancelled) return;
                        if (this.fits(value)) this.add(value);
                        else {
                            this.pending = value;
                            return;
                        }
                    }
                }
            } catch (Throwable failure) {
                Logger.error("Listing stored Create LOD snapshots", failure);
                synchronized (this) { this.finished = true; }
            } finally {
                boolean close;
                synchronized (this) {
                    this.scheduled = false;
                    close = this.cancelled || this.finished;
                    if (!close) this.schedule();
                }
                if (close && this.stream != null) {
                    try {
                        this.stream.close();
                    } catch (Exception failure) {
                        Logger.error("Closing stored Create LOD snapshots", failure);
                    }
                    this.stream = null;
                    this.files = null;
                }
            }
        }

        @Override
        public synchronized void close() {
            if (this.cancelled) return;
            this.cancelled = true;
            this.ready.clear();
            this.pending = null;
            this.bufferedBytes = 0;
            // 目录句柄始终由同一存储线程关闭；调用者无需等待读盘。
            if (!this.scheduled && !this.finished) {
                this.scheduled = true;
                IO.execute(this::readBatch);
            }
        }
    }

    private static void write(Path file, Writer writer) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp))))) {
                out.writeInt(MAGIC);
                writer.write(out);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            Logger.error("Storing Create LOD snapshot", t);
        }
    }

}
