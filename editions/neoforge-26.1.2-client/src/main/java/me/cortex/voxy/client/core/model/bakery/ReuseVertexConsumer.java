package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.vertex.VertexConsumer;
import me.cortex.voxy.common.util.MemoryBuffer;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class ReuseVertexConsumer implements VertexConsumer {
   public static final int VERTEX_FORMAT_SIZE = 24;
   private MemoryBuffer buffer = new MemoryBuffer(8192L);
   private long ptr;
   private int count;
   private int defaultMeta;
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

   public ReuseVertexConsumer addVertex(float x, float y, float z) {
      this.ensureCanPut();
      this.ptr += 24L;
      this.count++;
      this.meta(this.defaultMeta | this.globalOrMetadata);
      MemoryUtil.memPutFloat(this.ptr, x);
      MemoryUtil.memPutFloat(this.ptr + 4L, y);
      MemoryUtil.memPutFloat(this.ptr + 8L, z);
      return this;
   }

   public ReuseVertexConsumer meta(int metadata) {
      this.anyDiscard |= (metadata & 1) != 0;
      MemoryUtil.memPutInt(this.ptr + 12L, metadata);
      return this;
   }

   public ReuseVertexConsumer setColor(int red, int green, int blue, int alpha) {
      return this;
   }

   public VertexConsumer setColor(int i) {
      return this;
   }

   public ReuseVertexConsumer setUv(float u, float v) {
      MemoryUtil.memPutFloat(this.ptr + 16L, u);
      MemoryUtil.memPutFloat(this.ptr + 20L, v);
      return this;
   }

   public ReuseVertexConsumer setUv1(int u, int v) {
      return this;
   }

   public ReuseVertexConsumer setUv2(int u, int v) {
      return this;
   }

   public ReuseVertexConsumer setNormal(float x, float y, float z) {
      return this;
   }

   public VertexConsumer setLineWidth(float f) {
      return null;
   }

   public ReuseVertexConsumer quad(BakedQuad quad) {
      return this.quad(quad, false);
   }

   public ReuseVertexConsumer quad(BakedQuad quad, boolean forceSolid) {
      int meta = 0;
      meta |= forceSolid ? 0 : (quad.materialInfo().layer() != ChunkSectionLayer.SOLID || spriteHasTransparency(quad) ? 1 : 0);
      meta |= quad.materialInfo().isTinted() ? 4 : 0;
      return this.quad(quad, meta);
   }

   /**
    * Some plant/cross models are declared as SOLID even though their atlas sprite
    * contains transparent texels.  Baking those pixels without alpha discard
    * produces a dark rectangular card in coarse LODs (vines and modded plants
    * are common examples).
    */
   private static boolean spriteHasTransparency(BakedQuad quad) {
      try {
         var contents = quad.materialInfo().sprite().contents();
         Boolean cached = VOXY_SPRITE_ALPHA_CACHE.get(contents);
         if (cached != null) return cached;
         boolean transparent = false;
         int width = contents.width();
         int height = contents.height();
         var frames = contents.getUniqueFrames();
         for (int frameIndex = 0; frameIndex < frames.size() && !transparent; frameIndex++) {
            int frame = frames.getInt(frameIndex);
            for (int y = 0; y < height && !transparent; y++) {
               for (int x = 0; x < width; x++) {
                  if (contents.isTransparent(frame, x, y)) {
                     transparent = true;
                     break;
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
      this.anyShaded = this.anyShaded | quad.materialInfo().shade();
      this.anyDarkendTex = this.anyDarkendTex | quad.materialInfo().sprite().contents().mipmapStrategy == MipmapStrategy.DARK_CUTOUT;
      this.ensureCanPut();

      for (int i = 0; i < 4; i++) {
         Vector3fc pos = quad.position(i);
         this.addVertex(pos.x(), pos.y(), pos.z());
         long puv = quad.packedUV(i);
         this.setUv(UVPair.unpackU(puv), UVPair.unpackV(puv));
         this.meta(metadata | this.globalOrMetadata);
      }

      return this;
   }

   private void ensureCanPut() {
      if ((this.count + 5) * 24L >= this.buffer.size) {
         long offset = this.ptr - this.buffer.address;
         MemoryBuffer newBuffer = new MemoryBuffer(((int)(this.buffer.size * 2L) + 24 - 1) / 24 * 24);
         this.buffer.cpyTo(newBuffer.address);
         this.buffer.free();
         this.buffer = newBuffer;
         this.ptr = offset + newBuffer.address;
      }
   }

   public ReuseVertexConsumer reset() {
      this.anyShaded = false;
      this.anyDarkendTex = false;
      this.anyDiscard = false;
      this.defaultMeta = 0;
      this.count = 0;
      this.ptr = this.buffer.address - 24L;
      return this;
   }

   public void free() {
      this.ptr = 0L;
      this.count = 0;
      this.buffer.free();
      this.buffer = null;
   }

   public boolean isEmpty() {
      return this.count == 0;
   }

   public int quadCount() {
      if (this.count % 4 != 0) {
         throw new IllegalStateException();
      } else {
         return this.count / 4;
      }
   }

   public long getAddress() {
      return this.buffer.address;
   }
}
