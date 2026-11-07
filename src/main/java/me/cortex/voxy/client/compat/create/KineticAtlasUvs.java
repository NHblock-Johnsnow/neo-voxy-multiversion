package me.cortex.voxy.client.compat.create;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** 为卸载后的原始顶点保留纹理身份；不持有旧图像或世界。 */
final class KineticAtlasUvs {
    private static final int GRID = 64;
    // float[] 使用身份相等；值不能反向引用顶点数组。
    private static final Map<float[], Raw> CAPTURES = new WeakHashMap<>();
    private static WeakReference<TextureAtlasSprite> generation = new WeakReference<>(null);
    private static Layout current;

    private KineticAtlasUvs() {}

    static void remember(float[] vertices) {
        if (vertices == null || CAPTURES.containsKey(vertices)) return;
        if (vertices.length % 36 != 0) throw new IllegalArgumentException("Incomplete kinetic atlas quad");
        Layout layout = layout();
        ResourceLocation[] sprites = new ResourceLocation[vertices.length / 36];
        for (int q = 0; q < sprites.length; q++) {
            Bounds sprite = layout.find(vertices, q * 36);
            if (sprite != null) sprites[q] = sprite.name;
        }
        CAPTURES.put(vertices, new Raw(layout, sprites));
    }

    static void onResourceReload() {
        if (CAPTURES.isEmpty()) return;
        Layout next = layout();
        for (var entry : CAPTURES.entrySet()) {
            float[] vertices = entry.getKey();
            Raw raw = entry.getValue();
            if (vertices == null || raw.layout == next) continue;
            for (int q = 0; q < raw.sprites.length; q++) {
                ResourceLocation name = raw.sprites[q];
                Bounds before = raw.layout.get(name), after = next.get(name);
                if (name != null && before.sameUv(after)) continue;
                for (int i = q * 36; i < (q + 1) * 36; i += 9) {
                    // 歧义、跨纹理或非有限 UV 没有可恢复的纹理身份，明确显示 missing。
                    vertices[i + 3] = name == null ? (after.u0 + after.u1) * 0.5f
                            : after.u0 + (vertices[i + 3] - before.u0) / (before.u1 - before.u0) * (after.u1 - after.u0);
                    vertices[i + 4] = name == null ? (after.v0 + after.v1) * 0.5f
                            : after.v0 + (vertices[i + 4] - before.v0) / (before.v1 - before.v0) * (after.v1 - after.v0);
                }
            }
            // 保留原名称；资源暂时缺失后重新出现时，仍可从 missing 的局部 UV 恢复。
            raw.layout = next;
        }
    }

    static void clear() {
        CAPTURES.clear();
        current = null;
        generation.clear();
    }

    private static Layout layout() {
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite missing = atlas.getSprite(MissingTextureAtlasSprite.getLocation());
        if (current == null || generation.get() != missing) {
            current = new Layout(atlas.getTextures(), missing);
            generation = new WeakReference<>(missing);
        }
        return current;
    }

    private static final class Raw {
        Layout layout;
        final ResourceLocation[] sprites;

        Raw(Layout layout, ResourceLocation[] sprites) {
            this.layout = layout;
            this.sprites = sprites;
        }
    }

    private record Bounds(ResourceLocation name, float u0, float u1, float v0, float v1) {
        Bounds(TextureAtlasSprite sprite) {
            this(sprite.contents().name(), sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1());
        }

        boolean sameUv(Bounds other) {
            return this.u0 == other.u0 && this.u1 == other.u1 && this.v0 == other.v0 && this.v1 == other.v1;
        }
    }

    private static final class Layout {
        final Map<ResourceLocation, Bounds> sprites = new HashMap<>();
        final ArrayList<Bounds>[] cells;
        final Bounds missing;

        @SuppressWarnings("unchecked")
        Layout(Map<ResourceLocation, TextureAtlasSprite> textures, TextureAtlasSprite missing) {
            this.missing = new Bounds(missing);
            this.cells = (ArrayList<Bounds>[]) new ArrayList<?>[GRID * GRID];
            for (var entry : textures.entrySet()) {
                Bounds b = new Bounds(entry.getValue());
                if (!(b.u0 >= 0 && b.u1 <= 1 && b.u0 < b.u1 && b.v0 >= 0 && b.v1 <= 1 && b.v0 < b.v1)) continue;
                this.sprites.put(entry.getKey(), b);
                // 包含边界格，以便退化 UV 也能识别并拒绝相邻纹理的歧义。
                for (int y = cell(b.v0); y <= cell(b.v1); y++) {
                    for (int x = cell(b.u0); x <= cell(b.u1); x++) {
                        int index = y * GRID + x;
                        if (this.cells[index] == null) this.cells[index] = new ArrayList<>();
                        this.cells[index].add(b);
                    }
                }
            }
        }

        Bounds get(ResourceLocation name) {
            return this.sprites.getOrDefault(name, this.missing);
        }

        Bounds find(float[] vertices, int offset) {
            float minU = Float.POSITIVE_INFINITY, minV = Float.POSITIVE_INFINITY;
            float maxU = Float.NEGATIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
            for (int i = offset; i < offset + 36; i += 9) {
                float u = vertices[i + 3], v = vertices[i + 4];
                if (!Float.isFinite(u) || !Float.isFinite(v)) return null;
                minU = Math.min(minU, u); maxU = Math.max(maxU, u);
                minV = Math.min(minV, v); maxV = Math.max(maxV, v);
            }
            if (minU < 0 || minV < 0 || maxU > 1 || maxV > 1) return null;
            var candidates = this.cells[cell((minV + maxV) * 0.5f) * GRID + cell((minU + maxU) * 0.5f)];
            if (candidates == null) return null;
            Bounds found = null;
            for (Bounds b : candidates) {
                if (minU < b.u0 || maxU > b.u1 || minV < b.v0 || maxV > b.v1) continue;
                if (found != null) return null;
                found = b;
            }
            return found;
        }

        private static int cell(float uv) {
            return Math.min(GRID - 1, (int) (uv * GRID));
        }
    }
}
