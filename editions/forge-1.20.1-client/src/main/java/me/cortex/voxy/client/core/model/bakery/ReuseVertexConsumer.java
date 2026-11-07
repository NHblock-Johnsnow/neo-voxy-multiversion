package me.cortex.voxy.client.core.model.bakery;


import me.cortex.voxy.common.util.MemoryBuffer;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class ReuseVertexConsumer implements VertexConsumer {
    public static final int VERTEX_FORMAT_SIZE = 28;
    private MemoryBuffer buffer = new MemoryBuffer(8192);
    private long ptr;
    private int count;
    private int defaultMeta;
    private boolean vertexAlphaOnly;

    public boolean anyShaded;
    public boolean anyDarkendTex;
    public boolean anyDiscard;

    private final int globalOrMetadata;
    private static final Map<Object, Boolean> VOXY_SPRITE_ALPHA_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    public ReuseVertexConsumer() {
        this(0);
    }
    public ReuseVertexConsumer(int globalOrMetadata) {
        this.reset();
        this.globalOrMetadata = globalOrMetadata;
    }

    public ReuseVertexConsumer setDefaultMeta(int meta) {
        this.defaultMeta = meta;
        return this;
    }

    public int getDefaultMeta() {
        return this.defaultMeta;
    }

    @Override
    public ReuseVertexConsumer vertex(
    //? if 1.20.1 {
        double x,
        double y,
        double z
    //?} else {
        /*float x,
        float y,
        float z
    *///?}
    ) {
        this.ensureCanPut();
        this.ptr += VERTEX_FORMAT_SIZE; this.count++; //Goto next vertex
        this.meta(this.defaultMeta|this.globalOrMetadata);
        //? if 1.20.1 {
        MemoryUtil.memPutFloat(this.ptr, (float) x);
        MemoryUtil.memPutFloat(this.ptr + 4, (float) y);
        MemoryUtil.memPutFloat(this.ptr + 8, (float) z);
        MemoryUtil.memPutInt(this.ptr + 24, 0xFFFFFFFF);
        //?} else {
        /*MemoryUtil.memPutFloat(this.ptr, x);
        MemoryUtil.memPutFloat(this.ptr + 4, y);
        MemoryUtil.memPutFloat(this.ptr + 8, z);
        *///?}
        return this;
    }

    public ReuseVertexConsumer meta(int metadata) {
        this.anyDiscard |= (metadata&1)!=0;
        MemoryUtil.memPutInt(this.ptr + 12, metadata);
        return this;
    }

    @Override
    public ReuseVertexConsumer color(int red, int green, int blue, int alpha) {
        return this.writeAbgr(((alpha & 0xFF) << 24) | ((blue & 0xFF) << 16)
                | ((green & 0xFF) << 8) | (red & 0xFF));
    }

    @Override
    public ReuseVertexConsumer color(int i) {
        return this.writeAbgr((i & 0xFF00FF00) | ((i >>> 16) & 0xFF) | ((i & 0xFF) << 16));
    }

    private ReuseVertexConsumer writeAbgr(int i) {
        if (this.vertexAlphaOnly) {
            i = (i & 0xFF000000) | 0x00FFFFFF;
        }
        MemoryUtil.memPutInt(this.ptr + 24, i);
        return this;
    }

    public ReuseVertexConsumer setVertexAlphaOnly(boolean vertexAlphaOnly) {
        this.vertexAlphaOnly = vertexAlphaOnly;
        return this;
    }

    @Override
    public ReuseVertexConsumer uv(float u, float v) {
        MemoryUtil.memPutFloat(this.ptr + 16, u);
        MemoryUtil.memPutFloat(this.ptr + 20, v);
        return this;
    }

    @Override
    public ReuseVertexConsumer overlayCoords(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer uv2(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer normal(float x, float y, float z) {
        return this;
    }

    //? if 1.20.1 {
    @Override
    public void defaultColor(int red, int green, int blue, int alpha) {
        return;
    }

    @Override
    public void endVertex() {
        return;
    }

    @Override
    public void unsetDefaultColor() {
        return;
    }
    //?}

    public ReuseVertexConsumer quad(BakedQuad quad, RenderType layer) {
        return this.quad(quad, false, layer);
    }

    public ReuseVertexConsumer quad(BakedQuad quad, boolean forceSolid, RenderType layer) {
        int meta = 0;
        meta |= shouldEnableAlphaDiscard(quad, forceSolid, layer) ? 1 : 0;
        meta |= quad.isTinted()?4:0;//has tinting
        return this.quad(quad, meta);
    }

    private static boolean shouldEnableAlphaDiscard(BakedQuad quad, boolean forceSolid, RenderType layer) {
        if (forceSolid || layer == RenderType.translucent()) {
            return false;
        }
        return layer != RenderType.solid() || spriteHasTransparency(quad);
    }

    /**
     * Some plant/cross models are declared as SOLID even though their atlas sprite
     * contains transparent texels. Baking those pixels without alpha discard
     * produces a dark rectangular card in coarse LODs.
     */
    private static boolean spriteHasTransparency(BakedQuad quad) {
        try {
            var contents = quad.getSprite().contents();
            Boolean cached = VOXY_SPRITE_ALPHA_CACHE.get(contents);
            if (cached != null) return cached;
            boolean transparent = false;
            int width = contents.width();
            int height = contents.height();
            try (var frames = contents.getUniqueFrames()) {
                var iterator = frames.iterator();
                while (iterator.hasNext() && !transparent) {
                    int frame = iterator.nextInt();
                    for (int y = 0; y < height && !transparent; y++) {
                        for (int x = 0; x < width; x++) {
                            if (contents.isTransparent(frame, x, y)) {
                                transparent = true;
                                break;
                            }
                        }
                    }
                }
            }
            VOXY_SPRITE_ALPHA_CACHE.put(contents, transparent);
            return transparent;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public ReuseVertexConsumer quad(BakedQuad quad, int metadata) {
        this.anyShaded |= quad.isShade();
        this.anyDarkendTex |= false;// todo: what actually goes here??
        this.ensureCanPut();
        int[] vertices = quad.getVertices();
        for (int i = 0; i < 4; i++) {
            // look at FaceBakery
            int j = i * 8;
            this.vertex(Float.intBitsToFloat(vertices[j]), Float.intBitsToFloat(vertices[j + 1]), Float.intBitsToFloat(vertices[j + 2]));
            this.writeAbgr(vertices[j + 3]);
            this.uv(Float.intBitsToFloat(vertices[j + 4]), Float.intBitsToFloat(vertices[j + 5]));

            this.meta(metadata|this.globalOrMetadata);
        }
        return this;
    }

    private void ensureCanPut() {
        if ((long) (this.count + 5) * VERTEX_FORMAT_SIZE < this.buffer.size) {
            return;
        }
        long offset = this.ptr-this.buffer.address;
        //1.5x the size
        var newBuffer = new MemoryBuffer((((int)(this.buffer.size*2)+VERTEX_FORMAT_SIZE-1)/VERTEX_FORMAT_SIZE)*VERTEX_FORMAT_SIZE);
        this.buffer.cpyTo(newBuffer.address);
        this.buffer.free();
        this.buffer = newBuffer;
        this.ptr = offset + newBuffer.address;
    }

    public ReuseVertexConsumer reset() {
        this.anyShaded = false;
        this.anyDarkendTex = false;
        this.anyDiscard = false;
        this.defaultMeta = 0;//RESET THE DEFAULT META
        this.vertexAlphaOnly = false;
        this.count = 0;
        this.ptr = this.buffer.address - VERTEX_FORMAT_SIZE;//the thing is first time this gets incremented by FORMAT_STRIDE
        return this;
    }

    public void free() {
        this.ptr = 0;
        this.count = 0;
        this.buffer.free();
        this.buffer = null;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    public int quadCount() {
        if (this.count%4 != 0) throw new IllegalStateException();
        return this.count/4;
    }

    public long getAddress() {
        return this.buffer.address;
    }
}
