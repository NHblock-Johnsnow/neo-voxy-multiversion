package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL45;

public class ModelStore {
   public static final int MODEL_SIZE = 64;
   final GlBuffer modelBuffer;
   GlBuffer modelColourBuffer;
   final GlTexture textures;
   public final int blockSampler = GL33.glGenSamplers();

   public ModelStore() {
      this.modelBuffer = new GlBuffer(4194304L).name("ModelData");
      this.modelColourBuffer = new GlBuffer(4L * (BiomeBlendPalette.NORMAL_COLOUR_BASE + (1 << 16))).name("ModelColour");
      this.textures = RenderResourceReuse.getOrCreateModelStoreTextureAtlas();
      int mipLvl = ((TextureAtlas)Minecraft.getInstance()
            .getTextureManager()
            .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")))
         .maxMipLevel;
      GL33C.glSamplerParameteri(this.blockSampler, 10241, 9986);
      GL33C.glSamplerParameteri(this.blockSampler, 10240, 9728);
      GL33C.glSamplerParameteri(this.blockSampler, 33082, 0);
      GL33C.glSamplerParameteri(this.blockSampler, 33083, mipLvl);
   }

    /** 调色板位于缓冲开头；普通颜色表增长时保留全部旧索引。 */
    void ensureBiomeColourCapacity(long entries) {
        long required = Math.multiplyExact(4L, BiomeBlendPalette.NORMAL_COLOUR_BASE + entries);
        if (required <= this.modelColourBuffer.size()) return;
        long limit = org.lwjgl.opengl.GL32.glGetInteger64(org.lwjgl.opengl.GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        if (required > limit) throw new IllegalStateException("Biome colours exceed the GPU buffer limit: " + required);
        var replacement = new GlBuffer(Math.max(required, Math.min(limit, this.modelColourBuffer.size() * 2))).name("ModelColour");
        try {
            // 已排队的上传必须先落到旧缓冲，之后再整体复制。
            me.cortex.voxy.client.core.rendering.util.UploadStream.INSTANCE.commit();
            org.lwjgl.opengl.GL45.glCopyNamedBufferSubData(this.modelColourBuffer.id, replacement.id,
                    0, 0, this.modelColourBuffer.size());
        } catch (RuntimeException | Error error) {
            replacement.free();
            throw error;
        }
        this.modelColourBuffer.free();
        this.modelColourBuffer = replacement;
    }

   public void free() {
      this.modelBuffer.free();
      this.modelColourBuffer.free();
      RenderResourceReuse.giveBackModelStoreTextureAtlas(this.textures);
      GL33.glDeleteSamplers(this.blockSampler);
   }

   public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
      GL30.glBindBufferBase(37074, modelBindingIndex, this.modelBuffer.id);
      GL30.glBindBufferBase(37074, colourBindingIndex, this.modelColourBuffer.id);
      GL45.glBindTextureUnit(textureBindingIndex, this.textures.id);
      GL33.glBindSampler(textureBindingIndex, this.blockSampler);
   }
}
