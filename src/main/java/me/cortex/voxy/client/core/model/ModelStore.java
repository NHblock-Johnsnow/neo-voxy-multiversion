package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

public class ModelStore {
    public static final int MODEL_SIZE = 64;
    final GlBuffer modelBuffer;
    GlBuffer modelColourBuffer;
    final GlTexture textures;
    public final int blockSampler = glGenSamplers();

    public ModelStore() {
        this.modelBuffer = new GlBuffer(MODEL_SIZE * (1<<16)).name("ModelData");
        this.modelColourBuffer = new GlBuffer(4L * (BiomeBlendPalette.NORMAL_COLOUR_BASE + (1 << 16))).name("ModelColour");
        this.textures = RenderResourceReuse.getOrCreateModelStoreTextureAtlas();

        //Limit the mips of the texture to match that of the terrain atlas
        int mipLvl = ((TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")))
                .mipLevel;

        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAX_LOD, mipLvl);//Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE)
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
        glDeleteSamplers(this.blockSampler);
    }


    public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, modelBindingIndex, this.modelBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, colourBindingIndex, this.modelColourBuffer.id);
        glBindTextureUnit(textureBindingIndex, this.textures.id);
        glBindSampler(textureBindingIndex, this.blockSampler);
    }
}
