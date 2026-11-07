package me.cortex.voxy.client.core.model.bakery;


import me.cortex.voxy.common.util.MemoryBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.VertexConsumer;

public final class ReuseVertexConsumer implements VertexConsumer {
    public static final int VERTEX_FORMAT_SIZE = 28;
    public static final int META_ALPHA_DISCARD = 1;
    public static final int META_NO_FACE_CULL = 1 << 3;
    private MemoryBuffer buffer = new MemoryBuffer(8192);
    private long ptr;
    private int count;
    private int defaultMeta;
    private boolean vertexAlphaOnly;
    private int fallbackTintColour = -1;
    private int forcedTintColour = -1;

    public boolean anyShaded;
    public boolean anyDiscard;

    private final int globalOrMetadata;
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

    public ReuseVertexConsumer setVertexAlphaOnly(boolean vertexAlphaOnly) {
        this.vertexAlphaOnly = vertexAlphaOnly;
        return this;
    }

    public ReuseVertexConsumer setFallbackTintColour(int colour) {
        this.fallbackTintColour = colour;
        return this;
    }

    public ReuseVertexConsumer setForcedTintColour(int colour) {
        this.forcedTintColour = colour;
        return this;
    }


    @Override
    public ReuseVertexConsumer addVertex(float x, float y, float z) {
        this.ensureCanPut();
        this.ptr += VERTEX_FORMAT_SIZE; this.count++; //Goto next vertex
        this.meta(this.defaultMeta|this.globalOrMetadata);
        MemoryUtil.memPutFloat(this.ptr, x);
        MemoryUtil.memPutFloat(this.ptr + 4, y);
        MemoryUtil.memPutFloat(this.ptr + 8, z);
        MemoryUtil.memPutInt(this.ptr + 24, 0xFFFFFFFF);
        return this;
    }

    public ReuseVertexConsumer meta(int metadata) {
        this.anyDiscard |= (metadata&1)!=0;
        MemoryUtil.memPutInt(this.ptr + 12, metadata);
        return this;
    }

    @Override
    public ReuseVertexConsumer setColor(int red, int green, int blue, int alpha) {
        return this.writeAbgr(packAbgr(red, green, blue, alpha));
    }

    @Override
    public ReuseVertexConsumer setColor(int i) {
        return this.writeAbgr((i & 0xFF00FF00) | ((i >>> 16) & 0xFF) | ((i & 0xFF) << 16));
    }

    private ReuseVertexConsumer writeAbgr(int colour) {
        if (this.vertexAlphaOnly) {
            colour = (colour & 0xFF000000) | 0x00FFFFFF;
        }
        MemoryUtil.memPutInt(this.ptr + 24, colour);
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv(float u, float v) {
        MemoryUtil.memPutFloat(this.ptr + 16, u);
        MemoryUtil.memPutFloat(this.ptr + 20, v);
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer setNormal(float x, float y, float z) {
        return this;
    }

    public ReuseVertexConsumer quad(BakedQuad quad, RenderType layer) {
        return this.quad(quad, false, layer, null);
    }

    public ReuseVertexConsumer quad(BakedQuad quad, boolean forceSolid, RenderType layer) {
        return this.quad(quad, forceSolid, layer, null);
    }

    public ReuseVertexConsumer quad(BakedQuad quad, boolean forceSolid, RenderType layer, BlockState state) {
        return this.quad(quad, forceSolid, layer, state, false);
    }

    public ReuseVertexConsumer quad(BakedQuad quad, boolean forceSolid, RenderType layer,
                                    BlockState state, boolean disableFaceCulling) {
        int meta = 0;
        meta |= shouldEnableAlphaDiscard(quad, forceSolid, layer) ? META_ALPHA_DISCARD : 0;
        meta |= disableFaceCulling ? META_NO_FACE_CULL : 0;

        int tintColour = this.forcedTintColour;
        if (tintColour == -1 && quad.isTinted()) {
            int tintIndex = quad.getTintIndex();
            tintColour = captureTintColour(state, tintIndex);
            if (tintColour == -1 && tintIndex > 0xFF) {
                tintColour = this.fallbackTintColour;
            }
            if (tintColour == -1) {
                meta |= 4;
            }
        }
        return this.quad(quad, meta, tintColour);
    }

    public ReuseVertexConsumer quad(BakedQuad quad, int metadata) {
        return this.quad(quad, metadata, -1);
    }

    public ReuseVertexConsumer quad(BakedQuad quad, int metadata, int tintColour) {
        this.anyShaded |= quad.isShade();
        this.ensureCanPut();
        int[] vertices = quad.getVertices();
        for (int i = 0; i < 4; i++) {
            // look at FaceBakery
            int j = i * 8;
            int colour = vertices[j + 3];
            if (tintColour != -1) {
                colour = multiplyAbgr(colour, tintColour);
            }
            this.addVertex(Float.intBitsToFloat(vertices[j]), Float.intBitsToFloat(vertices[j + 1]), Float.intBitsToFloat(vertices[j + 2]));
            this.writeAbgr(colour);
            this.setUv(Float.intBitsToFloat(vertices[j + 4]), Float.intBitsToFloat(vertices[j + 5]));

            this.meta(metadata|this.globalOrMetadata);
        }
        return this;
    }

    private static final Map<Object, Boolean> VOXY_SPRITE_ALPHA_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean shouldEnableAlphaDiscard(BakedQuad quad, boolean forceSolid, RenderType layer) {
        if (forceSolid) {
            return false;
        }
        if (layer == RenderType.translucent()) {
            return false;
        }
        if (layer != RenderType.solid()) {
            return true;
        }
        return voxy$quadSpriteHasTransparency(quad);
    }

    private static boolean voxy$quadSpriteHasTransparency(BakedQuad quad) {
        try {
            Object sprite = voxy$invokeNoArg(quad, "getSprite");
            if (sprite == null) {
                sprite = voxy$invokeNoArg(quad, "sprite");
            }
            if (sprite == null) {
                sprite = voxy$getField(quad, "sprite");
            }
            if (sprite == null) {
                return false;
            }

            Object contents = voxy$invokeNoArg(sprite, "contents");
            if (contents == null) {
                contents = voxy$invokeNoArg(sprite, "getContents");
            }
            if (contents == null) {
                contents = voxy$getField(sprite, "contents");
            }
            if (contents == null) {
                contents = sprite;
            }

            Boolean cached;
            synchronized (VOXY_SPRITE_ALPHA_CACHE) {
                cached = VOXY_SPRITE_ALPHA_CACHE.get(contents);
            }
            if (cached != null) {
                return cached;
            }

            boolean result = voxy$computeSpriteTransparency(contents);
            synchronized (VOXY_SPRITE_ALPHA_CACHE) {
                VOXY_SPRITE_ALPHA_CACHE.put(contents, result);
            }
            return result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean voxy$computeSpriteTransparency(Object contents) throws ReflectiveOperationException {
        Method widthMethod = voxy$findMethod(contents.getClass(), "width");
        if (widthMethod == null) {
            widthMethod = voxy$findMethod(contents.getClass(), "getWidth");
        }
        Method heightMethod = voxy$findMethod(contents.getClass(), "height");
        if (heightMethod == null) {
            heightMethod = voxy$findMethod(contents.getClass(), "getHeight");
        }
        if (widthMethod == null || heightMethod == null) {
            return false;
        }

        int width = ((Number) widthMethod.invoke(contents)).intValue();
        int height = ((Number) heightMethod.invoke(contents)).intValue();
        if (width <= 0 || height <= 0) {
            return false;
        }

        Method alphaMethod = voxy$findBooleanMethod(contents.getClass(), "isTransparent", int.class, int.class, int.class);
        boolean hasFrameArgument = true;
        if (alphaMethod == null) {
            alphaMethod = voxy$findBooleanMethod(contents.getClass(), "isPixelTransparent", int.class, int.class, int.class);
        }
        if (alphaMethod == null) {
            alphaMethod = voxy$findBooleanMethod(contents.getClass(), "isTransparent", int.class, int.class);
            hasFrameArgument = false;
        }
        if (alphaMethod == null) {
            alphaMethod = voxy$findBooleanMethod(contents.getClass(), "isPixelTransparent", int.class, int.class);
            hasFrameArgument = false;
        }
        if (alphaMethod == null) {
            return false;
        }

        Object uniqueFrames = hasFrameArgument ? voxy$invokeNoArg(contents, "getUniqueFrames") : null;
        // 动画只检查实际使用的帧，非零起始帧也不能漏掉透明像素。
        try (java.util.stream.IntStream frames = uniqueFrames instanceof java.util.stream.IntStream stream
                ? stream : java.util.stream.IntStream.of(0)) {
            var iterator = frames.iterator();
            while (iterator.hasNext()) {
                int frame = iterator.nextInt();
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        boolean transparent = hasFrameArgument
                                ? (Boolean) alphaMethod.invoke(contents, frame, x, y)
                                : (Boolean) alphaMethod.invoke(contents, x, y);
                        if (transparent) return true;
                    }
                }
            }
        }
        return false;
    }

    private static Object voxy$invokeNoArg(Object target, String methodName) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = voxy$findMethod(target.getClass(), methodName);
        return method == null ? null : method.invoke(target);
    }

    private static Object voxy$getField(Object target, String fieldName) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Method voxy$findBooleanMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Method method = voxy$findMethod(type, name, parameterTypes);
        if (method == null) {
            return null;
        }
        Class<?> returnType = method.getReturnType();
        return returnType == boolean.class || returnType == Boolean.class ? method : null;
    }

    private static Method voxy$findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
            return null;
        }

        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                }
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static int captureTintColour(BlockState state, int tintIndex) {
        if (state == null || tintIndex <= 0xFF) {
            return -1;
        }
        try {
            int colour = Minecraft.getInstance().getBlockColors().getColor(state, null, BlockPos.ZERO, tintIndex);
            if (colour == -1) {
                return -1;
            }
            return rgbToAbgr(colour);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int packAbgr(int red, int green, int blue, int alpha) {
        return ((alpha & 0xFF) << 24) | ((blue & 0xFF) << 16) | ((green & 0xFF) << 8) | (red & 0xFF);
    }

    private static int rgbToAbgr(int rgb) {
        return 0xFF000000 | ((rgb & 0x0000FF) << 16) | (rgb & 0x00FF00) | ((rgb >>> 16) & 0xFF);
    }

    private static int multiplyAbgr(int base, int tint) {
        int a = (((base >>> 24) & 0xFF) * ((tint >>> 24) & 0xFF)) / 255;
        int b = (((base >>> 16) & 0xFF) * ((tint >>> 16) & 0xFF)) / 255;
        int g = (((base >>> 8) & 0xFF) * ((tint >>> 8) & 0xFF)) / 255;
        int r = ((base & 0xFF) * (tint & 0xFF)) / 255;
        return (a << 24) | (b << 16) | (g << 8) | r;
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
        this.anyDiscard = false;
        this.defaultMeta = 0;//RESET THE DEFAULT META
        this.vertexAlphaOnly = false;
        this.fallbackTintColour = -1;
        this.forcedTintColour = -1;
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
