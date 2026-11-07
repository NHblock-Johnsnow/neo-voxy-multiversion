package me.cortex.voxy.client;

import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ClientChunkRecovery {
    private ClientChunkRecovery() {}

    public static LevelChunk find(WorldIdentifier id, int chunkX, int chunkZ) {
        var level = Minecraft.getInstance().level;
        if (level == null || id == null || !id.equals(WorldIdentifier.of(level))) {
            return null;
        }
        return ((ICheekyClientChunkCache) level.getChunkSource()).voxy$cheekyGetChunk(chunkX, chunkZ);
    }
}
