package me.cortex.voxy.client.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBogeyStyles;
import me.cortex.voxy.client.compat.distant.DistantMesh;
import me.cortex.voxy.client.compat.distant.DistantVertexCapture;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class DistantBogeyMeshes {
    private record Key(ResourceLocation style, ResourceLocation size, CompoundTag data) {}
    private static final Map<Key, DistantMesh> CACHE = new HashMap<>();
    private static final Set<Key> CAPTURING = new HashSet<>();
    private static boolean errored;
    private static int generation;

    private DistantBogeyMeshes() {}

    static DistantMesh get(ResourceLocation styleId, ResourceLocation sizeId, CompoundTag data) {
        var key = new Key(styleId, sizeId, data.copy());
        if (CACHE.containsKey(key)) return CACHE.get(key);
        // 样式回调可重入；临时空结果不能进入负缓存。
        if (!CAPTURING.add(key)) return null;
        int captureGeneration = generation;
        DistantMesh mesh = null;
        try {
            var style = AllBogeyStyles.BOGEY_STYLES.get(styleId);
            if (style != null) {
                var size = style.validSizes().stream().filter(candidate -> candidate.id().equals(sizeId))
                        .findFirst().orElseGet(() -> style.validSizes().stream().findFirst().orElse(null));
                if (size != null) {
                    var builder = new DistantMesh.Builder();
                    try {
                        var capture = new DistantVertexCapture(builder, false);
                        style.render(size, 0.0f, new PoseStack(), capture, LightTexture.FULL_BRIGHT,
                                OverlayTexture.NO_OVERLAY, 0.0f, data.copy(), true);
                        mesh = builder.build();
                    } catch (Throwable t) {
                        builder.discard();
                        throw t;
                    }
                }
            }
        } catch (Throwable t) {
            if (captureGeneration == generation && !errored) {
                errored = true;
                Logger.error("Distant bogey capture failed for " + key, t);
            }
        } finally {
            CAPTURING.remove(key);
        }
        if (captureGeneration != generation) {
            if (mesh != null) mesh.free();
            return null;
        }
        CACHE.put(key, mesh);
        return mesh;
    }

    static boolean isCapturing() {
        return !CAPTURING.isEmpty();
    }

    static int generation() {
        return generation;
    }

    static void clear() {
        // 保留进行中的门禁，直到旧样式回调退出。
        generation++;
        for (var mesh : CACHE.values()) {
            if (mesh != null) mesh.free();
        }
        CACHE.clear();
        errored = false;
    }
}
