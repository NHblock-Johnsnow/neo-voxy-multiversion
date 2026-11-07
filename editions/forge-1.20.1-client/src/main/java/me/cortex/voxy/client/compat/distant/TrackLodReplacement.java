package me.cortex.voxy.client.compat.distant;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;
import java.util.Collection;
import static org.lwjgl.opengl.GL45C.*;

public final class TrackLodReplacement {
    private static int buffer;
    private static boolean ready;
    private TrackLodReplacement() {}

    public static boolean isTrack(BlockState state) {
        for (Class<?> type = state.getBlock().getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("com.simibubi.create.content.trains.track.TrackBlock")) return true;
        }
        return false;
    }

    public static void upload(Collection<BlockPos> blocks) {
        int capacity = 1;
        while (capacity < blocks.size() * 2) capacity <<= 1;
        var data = MemoryUtil.memCalloc(capacity * 16);
        int replacement = 0;
        try {
            for (BlockPos p : blocks) {
                int slot = hash(p.getX(), p.getY(), p.getZ()) & (capacity - 1);
                while (data.getInt(slot * 16 + 12) != 0) slot = (slot + 1) & (capacity - 1);
                data.putInt(slot * 16, p.getX()).putInt(slot * 16 + 4, p.getY())
                        .putInt(slot * 16 + 8, p.getZ()).putInt(slot * 16 + 12, 1);
            }
            replacement = glCreateBuffers();
            glNamedBufferData(replacement, data, GL_STATIC_DRAW);
            // 新网格与替换表都构建成功后才交接，失败时保留旧表。
            int previous = buffer;
            buffer = replacement;
            replacement = 0;
            ready = !blocks.isEmpty();
            if (previous != 0) glDeleteBuffers(previous);
        } finally {
            if (replacement != 0) glDeleteBuffers(replacement);
            MemoryUtil.memFree(data);
        }
    }

    private static int hash(int x, int y, int z) {
        return x * 73856093 ^ y * 19349663 ^ z * 83492791;
    }

    public static boolean enabled() { return ready && VoxyConfig.CONFIG.distantTracks; }
    public static void bind() {
        if (buffer == 0) upload(java.util.List.of());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, buffer);
    }
    public static void clear() {
        if (buffer != 0) glDeleteBuffers(buffer);
        buffer = 0;
        ready = false;
    }
}
