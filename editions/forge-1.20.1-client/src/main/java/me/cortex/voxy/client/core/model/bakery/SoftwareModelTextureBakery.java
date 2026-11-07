package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureImage;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12.GL_PACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

/** 1.20.1 Forge 的离线模型投影器；输出布局与渲染器读取顺序保持一致。 */
public class SoftwareModelTextureBakery {
    public static final int FLAG_CENTERED_GROUND_CROSS = 1 << 4;
    // Note: the first bit of metadata is if alpha discard is enabled
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final ReuseVertexConsumer opaqueVC = new ReuseVertexConsumer();
    // Translucent cube faces must keep normal culling/blending semantics. Marking
    // every translucent quad as alpha-cutout makes front/back/edge layers fold
    // into dark cards during the six-direction offline projection.
    private final ReuseVertexConsumer translucentVC = new ReuseVertexConsumer();
    private final SoftwareRasterizer rasterizer = new SoftwareRasterizer(ModelFactory.MODEL_TEXTURE_SIZE);


    public SoftwareModelTextureBakery() {
    }

    public void setupTexture() {
        var texture = Minecraft.getInstance().getTextureManager().getTexture(new ResourceLocation("minecraft", "textures/atlas/blocks.png"));

        int textureId = texture.getId();

        if (!RenderSystem.isOnRenderThread()) {
            CompletableFuture<Void> future = new CompletableFuture<>();

            RenderSystem.recordRenderCall(() -> {
                try {
                    doSetupTexture(textureId);
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            future.join();
        } else {
            doSetupTexture(textureId);
        }
    }

    /** 在读取图集前清空像素缓冲绑定，完成后恢复 OpenGL 状态。 */
    private void doSetupTexture(int glId) {
        int texture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int pbo = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int[] packNames = {GL_PACK_ROW_LENGTH, GL_PACK_IMAGE_HEIGHT, GL_PACK_SKIP_ROWS,
                GL_PACK_SKIP_PIXELS, GL_PACK_SKIP_IMAGES, GL_PACK_ALIGNMENT};
        int[] packValues = new int[packNames.length];
        for (int i = 0; i < packNames.length; i++) packValues[i] = glGetInteger(packNames[i]);
        try {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            for (int name : packNames) glPixelStorei(name, name == GL_PACK_ALIGNMENT ? 4 : 0);
            glBindTexture(GL_TEXTURE_2D, glId);
            int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
            int height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
            int[] pixels = new int[Math.multiplyExact(width, height)];
            glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            this.rasterizer.setSamplerTexture(pixels, width, height);
        } finally {
            glBindTexture(GL_TEXTURE_2D, texture);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
            for (int i = 0; i < packNames.length; i++) glPixelStorei(packNames[i], packValues[i]);
        }
    }

    private boolean bakeBlockModel(BlockState state, RenderType layer) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return false;
        }
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        // Only FAST intentionally turns leaf cards into opaque geometry.  The
        // previous 1.20.1 path forced every leaf mode solid here, disabling
        // alpha discard and baking the black RGB hidden in transparent texels
        // into the LOD atlas for some modded leaf textures.
        boolean forceSolidLeaf = ModelFactory.isLeafBlockState(state)
                && VoxyConfig.CONFIG.getLeafLodMode() == VoxyConfig.LeafLodMode.FAST;
        // Forge baked models can render on a different layer from the legacy
        // ItemBlockRenderTypes mapping. TFC connected grass is one such model:
        // asking it for SOLID quads returns no faces at all. Use the model's
        // advertised layers, as Forge's normal chunk renderer does.
        List<RenderType> modelLayers = new ArrayList<>();
        for (RenderType modelLayer : model.getRenderTypes(state, new SingleThreadedRandomSource(42L), ModelData.EMPTY)) {
            modelLayers.add(modelLayer);
        }
        if (modelLayers.isEmpty()) modelLayers.add(layer);
        boolean crossCandidate = true;
        int diagonalFamilies = 0;
        int unculledQuads = 0;
        for (RenderType modelLayer : modelLayers) {
            for (Direction direction : new Direction[] { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
                    Direction.WEST, Direction.EAST, null }) {
                var quads = model.getQuads(state, direction, new SingleThreadedRandomSource(42L),
                        ModelData.EMPTY, modelLayer);
                if (direction != null && !quads.isEmpty()) crossCandidate = false;
                for (var quad : quads) {
                    if (direction == null && crossCandidate) {
                        int family = classifyGroundCrossQuad(quad.getVertices());
                        if (family == 0) crossCandidate = false;
                        else {
                            diagonalFamilies |= family;
                            unculledQuads++;
                        }
                    }
                    (modelLayer == RenderType.translucent() ? this.translucentVC : this.opaqueVC)
                            .quad(quad, forceSolidLeaf, modelLayer);
                }
            }
        }
        return crossCandidate && unculledQuads >= 2 && diagonalFamilies == 0b11;
    }

    private static int classifyGroundCrossQuad(int[] vertices) {
        if (vertices.length < 16 || (vertices.length & 3) != 0) return 0;
        int stride = vertices.length / 4;
        float x0 = Float.intBitsToFloat(vertices[0]);
        float y0 = Float.intBitsToFloat(vertices[1]);
        float z0 = Float.intBitsToFloat(vertices[2]);
        float x1 = Float.intBitsToFloat(vertices[stride]);
        float y1 = Float.intBitsToFloat(vertices[stride + 1]);
        float z1 = Float.intBitsToFloat(vertices[stride + 2]);
        float x2 = Float.intBitsToFloat(vertices[stride * 2]);
        float y2 = Float.intBitsToFloat(vertices[stride * 2 + 1]);
        float z2 = Float.intBitsToFloat(vertices[stride * 2 + 2]);
        float x3 = Float.intBitsToFloat(vertices[stride * 3]);
        float y3 = Float.intBitsToFloat(vertices[stride * 3 + 1]);
        float z3 = Float.intBitsToFloat(vertices[stride * 3 + 2]);

        float ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
        float bx = x2 - x0, by = y2 - y0, bz = z2 - z0;
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float lengthSq = nx * nx + ny * ny + nz * nz;
        if (lengthSq < 1.0e-8f) return 0;
        float length = (float) Math.sqrt(lengthSq);
        float absX = Math.abs(nx), absY = Math.abs(ny), absZ = Math.abs(nz);
        if (absY > length * 0.12f) return 0;
        float major = Math.max(absX, absZ), minor = Math.min(absX, absZ);
        if (major < 1.0e-5f || minor < major * 0.55f) return 0;

        float centerX = (x0 + x1 + x2 + x3) * 0.25f - 0.5f;
        float centerY = (y0 + y1 + y2 + y3) * 0.25f - 0.5f;
        float centerZ = (z0 + z1 + z2 + z3) * 0.25f - 0.5f;
        float planeDistance = Math.abs(nx * centerX + ny * centerY + nz * centerZ) / length;
        if (planeDistance > 0.0625f) return 0;
        return nx * nz >= 0.0f ? 0b01 : 0b10;
    }

    private void bakeFluidState(BlockState state, int face, RenderType layer) {
        BlockAndTintGetter getter = new BlockAndTintGetter() {
            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }
            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                //This is such a stupid and bad hack, we can inject tinting state here since this is called
                // before the quad is added
                //TODO: need to make a quad once tinting thing
                translucentVC.setDefaultMeta(translucentVC.getDefaultMeta()|4);//Tinting
                opaqueVC.setDefaultMeta(opaqueVC.getDefaultMeta()|4);//Tinting
                translucentVC.setVertexAlphaOnly(true);
                opaqueVC.setVertexAlphaOnly(true);
                return -1;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }

                //Fixme:
                // This makes it so that the top face of water is always air, if this is commented out
                //  the up block will be a liquid state which makes the sides full
                // if this is uncommented, that issue is fixed but e.g. stacking water layers ontop of eachother
                //  doesnt fill the side of the block

                //if (pos.getY() == 1) {
                //    return Blocks.AIR.getDefaultState();
                //}
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }

                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }

            @Override
            public float getShade(Direction direction, boolean bl) {
                return getVanillaLikeFluidShade(direction);
            }
        
        };
        
        VertexConsumer vc = this.opaqueVC;;

        if (layer == RenderType.translucent()) vc = this.translucentVC;
        if (layer == RenderType.cutout()) {
            this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta()|1);//set discard
        } else {
            this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta()&~1);//remove discard
        }
        try {
            Minecraft.getInstance().getBlockRenderer().renderLiquid(BlockPos.ZERO, getter, vc, state, state.getFluidState());
        } finally {
            this.translucentVC.setVertexAlphaOnly(false);
            this.opaqueVC.setVertexAlphaOnly(false);
            this.translucentVC.setDefaultMeta(0);//Reset default meta
            this.opaqueVC.setDefaultMeta(0);//Reset default meta
        }
    }

    private static float getVanillaLikeFluidShade(Direction direction) {
        if (direction == null) {
            return 1.0f;
        }
        return switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getNormal();
        int dot = fv.getX() * pos.getX() + fv.getY() * pos.getY() + fv.getZ() * pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.opaqueVC.free();
        this.translucentVC.free();
    }

    private static final long SINGLE_FACE_OUTPUT_SIZE = (ModelFactory.MODEL_TEXTURE_SIZE
            * ModelFactory.MODEL_TEXTURE_SIZE) * 8;
    // The outputBuffer layout is different from the non software rasterized
    // ModelTextureBakery
    // in this version the values are simply appended
    // (0,0),(1,0),(2,0),(0,1),(1,1),(2,1)

    public int renderToOutput(BlockState state, long outputBuffer) {
        MemoryUtil.memSet(outputBuffer, 0, 16 * 16 * 8 * 6);

        boolean isBlock = true;
        if (state.getBlock() instanceof LiquidBlock) {
            isBlock = false;
        }

        RenderType blockRenderLayer = null;
        if (state.getBlock() instanceof LiquidBlock) {
            blockRenderLayer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
        } else {
            if (ModelFactory.isLeafBlockState(state)) {
                blockRenderLayer = VoxyConfig.CONFIG.getLeafLodMode() == VoxyConfig.LeafLodMode.FAST
                        ? RenderType.solid()
                        : RenderType.cutout();
            } else {
                blockRenderLayer = ItemBlockRenderTypes.getChunkRenderType(state);
            }
        }

        // TODO: support block model entities
        // BakedBlockEntityModel bbem = null;
        if (state.hasBlockEntity()) {
            // bbem = BakedBlockEntityModel.bake(state);
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        boolean anyTranslucent = false;
        boolean anyDiscard = false;
        boolean centeredGroundCross = false;
        if (isBlock) {
            this.opaqueVC.reset();
            this.translucentVC.reset();
            centeredGroundCross = this.bakeBlockModel(state, blockRenderLayer);
            isAnyShaded |= this.opaqueVC.anyShaded | this.translucentVC.anyShaded;
            isAnyDarkend |= this.opaqueVC.anyDarkendTex | this.translucentVC.anyDarkendTex;
            anyTranslucent |= !this.translucentVC.isEmpty();
            anyDiscard |= this.opaqueVC.anyDiscard;
            if (!(this.opaqueVC.isEmpty() && this.translucentVC.isEmpty())) {// only render if there... is shit to
                                                                             // render
                for (int i = 0; i < VIEWS.length; i++) {
                    this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);
                    this.rasterizer.clear();
                    this.rasterizer.setBlending(false);
                    this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                    this.rasterizer.setBlending(true);
                    this.rasterizer.raster(VIEWS[i], this.translucentVC);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(),
                            outputBuffer + (SINGLE_FACE_OUTPUT_SIZE * i));
                }
            }
        } else {// Is fluid, slow path :(

            if (!(state.getBlock() instanceof LiquidBlock))
                throw new IllegalStateException();
            for (int i = 0; i < VIEWS.length; i++) {
                this.opaqueVC.reset();
                this.translucentVC.reset();
                this.bakeFluidState(state, i, blockRenderLayer);
                if (this.opaqueVC.isEmpty() && this.translucentVC.isEmpty())
                    continue;
                isAnyShaded |= this.opaqueVC.anyShaded | this.translucentVC.anyShaded;
                isAnyDarkend |= this.opaqueVC.anyDarkendTex | this.translucentVC.anyDarkendTex;
                anyTranslucent |= !this.translucentVC.isEmpty();
                anyDiscard |= this.opaqueVC.anyDiscard;

                this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);

                // The projection matrix
                this.rasterizer.clear();
                this.rasterizer.setBlending(false);
                this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                this.rasterizer.setBlending(true);
                this.rasterizer.raster(VIEWS[i], this.translucentVC);
                UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer + (SINGLE_FACE_OUTPUT_SIZE * i));
            }
        }

        return (isAnyShaded ? 1 : 0) | (isAnyDarkend ? 2 : 0) | (anyTranslucent ? 4 : 0)
                | (anyDiscard ? 8 : 0) | (centeredGroundCross ? FLAG_CENTERED_GROUND_CROSS : 0);
    }

    static {
        // the face/direction is the face (e.g. down is the down face)
        addView(0, -90, 0, 0, 0);// Direction.DOWN
        addView(1, 90, 0, 0, 0b100);// Direction.UP

        addView(2, 0, 180, 0, 0b001);// Direction.NORTH
        addView(3, 0, 0, 0, 0);// Direction.SOUTH

        addView(4, 0, 90, 270, 0b100);// Direction.WEST
        addView(5, 0, 270, 270, 0);// Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f, 0.5f, 0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 0, 1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1, 0, 0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 1, 0), yaw));
        stack./*? if 1.20.1 { */mulPoseMatrix/*? } else { *//*mulPose*//*? } */(
            new Matrix4f().scale(
                1 - 2 * (flip & 1),
                1 - (flip & 2),
                1 - ((flip >> 1) & 2)
            )
        );
        stack.translate(-0.5f, -0.5f, -0.5f);
        var mat = new Matrix4f(stack.last().pose());

        mat = new Matrix4f().set(
                2, 0, 0, 0,
                0, 2, 0, 0,
                0, 0, -2, 0,
                -1, -1, 1, 1)
                .mul(mat);
        VIEWS[i] = mat;
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1 / Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
