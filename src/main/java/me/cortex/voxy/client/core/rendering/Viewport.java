package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import me.cortex.voxy.client.core.rendering.util.HiZBufferAccess;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer2;
import net.minecraft.util.Mth;
import org.joml.*;

import java.lang.reflect.Field;

public abstract class Viewport <A extends Viewport<A>> {
    public final HiZBufferAccess hiZBuffer;
    public final DepthFramebuffer depthBoundingBuffer = new DepthFramebuffer();
    //Depth bounding buffer allocation: half the viewport (rounded up) when the chunk-bound mask is
    //rasterised at half resolution, else the viewport itself. Fixed for the viewport's lifetime
    //because the LOD fragment shader's mask-coordinate shift is a compile-time define - the section
    //renderer that compiled it creates the viewport from the same snapshot, so a buffer whose size
    //disagrees with the shift cannot exist, whatever the live config says.
    public final boolean chunkMaskHalfRes;
    public int chunkMaskWidth, chunkMaskHeight;

    private static final Field planesField;
    static {
        try {
            planesField = FrustumIntersection.class.getDeclaredField("planes");
            planesField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public int width;
    public int height;
    public int frameId;
    //frameId of the most recent command-list build. Feeds the SceneUniform slot the visibility
    //raster compares stamps against: with the command-list hold, frameId keeps counting on held
    //frames while the stamps in visibilityData stay at the build that wrote them, so "visible last
    //frame" means "visible at the previous build" - frameId-1 would never match after a hold.
    public int prevBuildFrameId;
    public Matrix4f vanillaProjection = new Matrix4f();
    public Matrix4f projection = new Matrix4f();
    public Matrix4f modelView = new Matrix4f();
    public final FrustumIntersection frustum = new FrustumIntersection();
    public final Vector4f[] frustumPlanes;
    public double cameraX;
    public double cameraY;
    public double cameraZ;

    public final Matrix4f MVP = new Matrix4f();
    public final NativeLodSelection lodSelection = new NativeLodSelection();
    public final Vector3i section = new Vector3i();
    public final Vector3f innerTranslation = new Vector3f();

    //Chunk-mask reuse state (experimentalChunkMaskReuse): the exact inputs the depth bounding
    //buffer's current content was rasterised with. The content is reusable only while every input
    //still matches AND nothing cleared or resized the buffer since - every such writer must call
    //invalidateChunkMask(), because the inputs alone cannot see the content being wiped.
    public final Matrix4f chunkMaskMVP = new Matrix4f();
    public double chunkMaskCamX, chunkMaskCamY, chunkMaskCamZ;
    public float chunkMaskRenderDistance;
    public int chunkMaskContentGen;
    public boolean chunkMaskValid;

    public void invalidateChunkMask() {
        this.chunkMaskValid = false;
    }

    private final RenderProperties properties;

    protected Viewport(RenderProperties properties, boolean chunkMaskHalfRes) {
        this.chunkMaskHalfRes = chunkMaskHalfRes;
        Vector4f[] planes = null;
        try {
             planes = (Vector4f[]) planesField.get(this.frustum);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        this.frustumPlanes = planes;

        this.properties = properties;
        //Read once here: the choice is fixed for this viewport's life, so flipping the flag at
        //runtime only reaches the next renderer creation and never swaps a live chain
        this.hiZBuffer = VoxyConfig.CONFIG.experimentalHiZCompute
                ? HiZBuffer2.createOrFallback(properties)
                : new HiZBuffer(properties);
    }

    public final void delete() {
        this.delete0();
    }

    protected void delete0() {
        this.hiZBuffer.free();
        this.depthBoundingBuffer.free();
    }

    public A setVanillaProjection(Matrix4fc projection) {
        this.vanillaProjection.set(projection);
        return (A) this;
    }

    public A setProjection(Matrix4f projection) {
        //Copied, not aliased: callers pass a scratch matrix they overwrite every frame
        this.projection.set(projection);
        return (A) this;
    }

    public A setModelView(Matrix4fc modelView) {
        this.modelView.set(modelView);
        return (A) this;
    }

    public A setCamera(double x, double y, double z) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
        return (A) this;
    }

    public A setScreenSize(int width, int height) {
        this.width = width;
        this.height = height;
        return (A) this;
    }

    public A update() {
        //MVP
        this.projection.mul(this.modelView, this.MVP);
        this.lodSelection.update(this.MVP, this.vanillaProjection, this.width, this.height,
                this.cameraX, this.cameraY, this.cameraZ, VoxyConfig.CONFIG.subDivisionSize,
                (net.minecraft.client.Minecraft.getInstance().options.renderDistance().get() + 2) * 16f);

        //Update the frustum
        this.frustum.set(this.MVP, false);

        //Translation vectors
        int sx = Mth.floor(this.cameraX)>>5;
        int sy = Mth.floor(this.cameraY)>>5;
        int sz = Mth.floor(this.cameraZ)>>5;
        this.section.set(sx, sy, sz);

        this.innerTranslation.set(
                (float) (this.cameraX-(sx<<5)),
                (float) (this.cameraY-(sy<<5)),
                (float) (this.cameraZ-(sz<<5)));

        this.chunkMaskWidth = this.chunkMaskHalfRes ? (this.width + 1) >> 1 : this.width;
        this.chunkMaskHeight = this.chunkMaskHalfRes ? (this.height + 1) >> 1 : this.height;
        if (this.depthBoundingBuffer.resize(this.chunkMaskWidth, this.chunkMaskHeight)) {
            this.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
            this.invalidateChunkMask();
        }

        return (A) this;
    }

    public abstract GlBuffer getRenderList();
}
