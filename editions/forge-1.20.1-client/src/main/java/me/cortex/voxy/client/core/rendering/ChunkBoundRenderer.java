package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.mixin.sodium.AccessorSodiumWorldRenderer;
import me.cortex.voxy.common.Logger;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBDirectStateAccess.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance;

//This is a render subsystem, its very simple in what it does
// it renders an AABB around loaded chunks, thats it
public class ChunkBoundRenderer {
    private static final int INIT_MAX_CHUNK_COUNT = 1<<12;
    private GlBuffer chunkPosBuffer = new GlBuffer(INIT_MAX_CHUNK_COUNT*8);//Stored as ivec2
    private GlBuffer visiblePosBuffer = new GlBuffer(INIT_MAX_CHUNK_COUNT*8L);
    private final GlBuffer uniformBuffer = new GlBuffer(128);
    private final Long2IntOpenHashMap chunk2idx = new Long2IntOpenHashMap(INIT_MAX_CHUNK_COUNT);
    private long[] idx2chunk = new long[INIT_MAX_CHUNK_COUNT];
    private int[] visiblePositions = new int[INIT_MAX_CHUNK_COUNT*2];
    private int[] pendingVisiblePositions = new int[INIT_MAX_CHUNK_COUNT*2];
    private int visibleSectionCount;
    private int pendingVisibleSectionCount;
    private Object pendingVisibleRenderLists;
    private boolean hasPendingVisibleSections;
    private final LongOpenHashSet pendingVisibleSet = new LongOpenHashSet(INIT_MAX_CHUNK_COUNT);
    private GlBuffer boundPositionBuffer;
    private boolean visibleListFailureLogged;
    private final Shader rasterShader;
    private final RenderProperties properties;
    private final Matrix4f cameraRelativeMvp = new Matrix4f();

    private final LongOpenHashSet addQueue = new LongOpenHashSet();
    private final LongOpenHashSet remQueue = new LongOpenHashSet();

    private final AbstractRenderPipeline pipeline;
    public ChunkBoundRenderer(AbstractRenderPipeline pipeline) {
        this.chunk2idx.defaultReturnValue(-1);
        this.properties = pipeline.properties;

        String vert = ShaderLoader.parse("voxy:chunkoutline/outline.vsh");
        String taa = pipeline.taaFunction("getTAA");
        if (taa != null) {
            this.pipeline = pipeline;
            vert = vert+"\n\n\n"+taa;
        } else {
            this.pipeline = null;
        }

        this.rasterShader = Shader.makeAuto()
                .addSource(ShaderType.VERTEX, vert)
                .defineIf("TAA", taa != null)
                .add(ShaderType.FRAGMENT, "voxy:chunkoutline/outline.fsh")
                .apply(this.properties::apply)
                .compile()
                .ubo(0, this.uniformBuffer)
                .ssbo(1, this.chunkPosBuffer);
        this.boundPositionBuffer = this.chunkPosBuffer;
    }

    public void addSection(long pos) {
        if (!this.remQueue.remove(pos)) {
            this.addQueue.add(pos);
        }
    }

    public void removeSection(long pos) {
        if (!this.addQueue.remove(pos)) {
            this.remQueue.add(pos);
        }
    }

    //Bind and render, changing as little gl state as possible so that the caller may configure how it wants to render
    public void render(Viewport<?> viewport) {
        if (!this.remQueue.isEmpty()) {
            boolean wasEmpty = this.chunk2idx.isEmpty();
            this.remQueue.forEach(this::_remPos);//TODO: REPLACE WITH SCATTER COMPUTE
            this.remQueue.clear();
            if (this.chunk2idx.isEmpty()&&!wasEmpty) {//When going from stuff to nothing need to clear the depth buffer
                viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
            }
        }

        boolean useVisibleSections = this.refreshVisibleSections();
        int count = useVisibleSections ? this.visibleSectionCount : this.chunk2idx.size();
        if (count == 0) {
            viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
            this.flushAddQueue();
            return;
        }

        viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());

        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        long matPtr = ptr; ptr += 4*4*4;

        final float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance()*16;//In blocks

        {//This is recomputed to be in chunk section space not worldsection

            //Camera block pos
            int bx = (int)Math.floor(viewport.cameraX);
            int by = (int)Math.floor(viewport.cameraY);
            int bz = (int)Math.floor(viewport.cameraZ);
            MemoryUtil.memPutInt(ptr, bx); ptr += 4;
            MemoryUtil.memPutInt(ptr, by); ptr += 4;
            MemoryUtil.memPutInt(ptr, bz); ptr += 4;
            MemoryUtil.memPutInt(ptr, 0);  ptr += 4;

            float innerX = (float) (viewport.cameraX - bx);
            float innerY = (float) (viewport.cameraY - by);
            float innerZ = (float) (viewport.cameraZ - bz);
            MemoryUtil.memPutFloat(ptr, innerX); ptr += 4;
            MemoryUtil.memPutFloat(ptr, innerY); ptr += 4;
            MemoryUtil.memPutFloat(ptr, innerZ); ptr += 4;

            this.cameraRelativeMvp.set(viewport.MVP)
                    .translate(-innerX, -innerY, -innerZ)
                    .getToAddress(matPtr);
            MemoryUtil.memPutFloat(ptr, renderDistance); ptr += 4;
        }
        UploadStream.INSTANCE.commit();


        {
            //need to reverse the winding order since we want the back faces of the AABB, not the front

            glFrontFace(GL_CW);//Reverse winding order

            //"reverse depth buffer" it goes from 0->1 where 1 is far away
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(this.properties.furtherDepthCompare());
        }

        glBindVertexArray(GlVertexArray.STATIC_VAO);
        viewport.depthBoundingBuffer.bind();
        this.rasterShader.bind();
        GlBuffer positionBuffer = useVisibleSections ? this.visiblePosBuffer : this.chunkPosBuffer;
        if (this.boundPositionBuffer != positionBuffer) {
            ((AutoBindingShader)this.rasterShader).ssbo(1, positionBuffer);
            this.boundPositionBuffer = positionBuffer;
        }
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE_BB_BYTE.id());
        if (this.pipeline != null) this.pipeline.bindUniforms();//shader TAA

        //Batch the draws into groups of size 32
        if (count >= 32) {
            glDrawElementsInstanced(GL_TRIANGLES, 6 * 2 * 3 * 32, GL_UNSIGNED_BYTE, 0, count/32);
        }
        if (count%32 != 0) {
            glDrawElementsInstancedBaseInstance(GL_TRIANGLES, 6 * 2 * 3 * (count%32), GL_UNSIGNED_BYTE, 0, 1, (count/32)*32);
        }

        {
            glFrontFace(GL_CCW);//Restore winding order

            glDepthFunc(this.properties.closerEqualDepthCompare());

            //TODO: check this is correct
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
        }


        this.flushAddQueue();
    }

    private boolean refreshVisibleSections() {
        try {
            var sodium = SodiumWorldRenderer.instanceNullable();
            if (sodium == null) return false;
            var manager = ((AccessorSodiumWorldRenderer)(Object)sodium).getRenderSectionManager();
            if (manager == null) return false;
            var renderLists = manager.getRenderLists();

            // Embeddium publishes its visibility graph before every terrain pass has
            // completed. Activating that graph immediately can remove LOD one frame
            // before vanilla terrain (especially translucent water) reaches the
            // framebuffer. Commit the graph observed on the previous render instead.
            // This is a one-frame hand-off barrier, not a timed scan: unchanged graphs
            // have no traversal or upload cost.
            if (this.hasPendingVisibleSections) {
                int[] swap = this.visiblePositions;
                this.visiblePositions = this.pendingVisiblePositions;
                this.pendingVisiblePositions = swap;
                this.visibleSectionCount = this.pendingVisibleSectionCount;
                this.uploadVisibleSections();
                this.hasPendingVisibleSections = false;
            }

            if (renderLists == this.pendingVisibleRenderLists) {
                return true;
            }
            this.pendingVisibleRenderLists = renderLists;
            this.pendingVisibleSectionCount = 0;
            this.pendingVisibleSet.clear();

            var lists = renderLists.iterator();
            while (lists.hasNext()) {
                var list = lists.next();
                var sections = list.sectionsWithGeometryIterator(false);
                if (sections == null) continue;
                var region = list.getRegion();
                int baseX = region.getChunkX();
                int baseY = region.getChunkY();
                int baseZ = region.getChunkZ();
                while (sections.hasNext()) {
                    int localIndex = sections.nextByteAsInt();
                    this.ensurePendingVisibleCapacity(this.pendingVisibleSectionCount + 1);
                    long pos = SectionPos.asLong(
                            baseX + LocalSectionIndex.unpackX(localIndex),
                            baseY + LocalSectionIndex.unpackY(localIndex),
                            baseZ + LocalSectionIndex.unpackZ(localIndex));
                    this.pendingVisibleSet.add(pos);
                    int outputIndex = this.pendingVisibleSectionCount++ << 1;
                    this.pendingVisiblePositions[outputIndex] = (int)pos;
                    this.pendingVisiblePositions[outputIndex + 1] = (int)(pos >>> 32);
                }
            }
            this.retainCurrentlyVisibleSections();
            this.hasPendingVisibleSections = true;
            return true;
        } catch (Throwable failure) {
            if (!this.visibleListFailureLogged) {
                this.visibleListFailureLogged = true;
                Logger.warn("Unable to use Embeddium visible-section bounds; using built-section bounds instead: " + failure);
            }
            this.pendingVisibleRenderLists = null;
            this.hasPendingVisibleSections = false;
            return false;
        }
    }

    private void retainCurrentlyVisibleSections() {
        int retained = 0;
        for (int index = 0; index < this.visibleSectionCount; index++) {
            int inputIndex = index << 1;
            long pos = ((long)this.visiblePositions[inputIndex + 1] << 32)
                    | (this.visiblePositions[inputIndex] & 0xFFFFFFFFL);
            if (!this.pendingVisibleSet.contains(pos)) continue;

            int outputIndex = retained++ << 1;
            this.visiblePositions[outputIndex] = this.visiblePositions[inputIndex];
            this.visiblePositions[outputIndex + 1] = this.visiblePositions[inputIndex + 1];
        }
        if (retained != this.visibleSectionCount) {
            this.visibleSectionCount = retained;
            this.uploadVisibleSections();
        }
    }

    private void ensurePendingVisibleCapacity(int sectionCount) {
        int requiredInts = sectionCount << 1;
        if (requiredInts <= this.pendingVisiblePositions.length) return;
        int newLength = Math.max(requiredInts, this.pendingVisiblePositions.length + (this.pendingVisiblePositions.length >> 1));
        int[] replacement = new int[newLength];
        System.arraycopy(this.pendingVisiblePositions, 0, replacement, 0, this.pendingVisibleSectionCount << 1);
        this.pendingVisiblePositions = replacement;
    }

    private void uploadVisibleSections() {
        long requiredBytes = this.visibleSectionCount * 8L;
        if (requiredBytes > this.visiblePosBuffer.size()) {
            UploadStream.INSTANCE.commit();
            this.visiblePosBuffer.free();
            this.visiblePosBuffer = new GlBuffer(Math.max(requiredBytes, (long)(requiredBytes * 1.25)));
            if (this.boundPositionBuffer != this.chunkPosBuffer) this.boundPositionBuffer = null;
        }
        if (requiredBytes == 0) return;

        long ptr = UploadStream.INSTANCE.upload(this.visiblePosBuffer, 0, requiredBytes);
        int intCount = this.visibleSectionCount << 1;
        for (int index = 0; index < intCount; index++) {
            MemoryUtil.memPutInt(ptr + index * 4L, this.visiblePositions[index]);
        }
        UploadStream.INSTANCE.commit();
    }

    private void flushAddQueue() {
        if (!this.addQueue.isEmpty()) {
            this.addQueue.forEach(this::_addPos);
            this.addQueue.clear();
            UploadStream.INSTANCE.commit();
        }
    }

    private void _remPos(long pos) {
        int idx = this.chunk2idx.remove(pos);
        if (idx == -1) {
            Logger.warn("Chunk not in map: " + pos);
            return;
        }
        if (idx == this.chunk2idx.size()) {
            //Dont need to do anything as heap is already compact
            return;
        }
        if (this.idx2chunk[idx] != pos) {
            throw new IllegalStateException();
        }

        //Move last entry on heap to this index
        long ePos = this.idx2chunk[this.chunk2idx.size()];// since is already removed size is correct end idx
        if (this.chunk2idx.put(ePos, idx) == -1) {
            throw new IllegalStateException();
        }
        this.idx2chunk[idx] = ePos;

        //Put the end pos into the new idx
        this.put(idx, ePos);
    }

    private void _addPos(long pos) {
        if (this.chunk2idx.containsKey(pos)) {
            Logger.warn("Chunk already in map: " + pos);
            return;
        }
        this.ensureSize1();//Resize if needed

        int idx = this.chunk2idx.size();
        this.chunk2idx.put(pos, idx);
        this.idx2chunk[idx] = pos;

        this.put(idx, pos);
    }

    private void ensureSize1() {
        if (this.chunk2idx.size() < this.idx2chunk.length) return;
        //Commit any copies, ensures is synced to new buffer
        UploadStream.INSTANCE.commit();

        int size = (int) (this.idx2chunk.length*1.5);
        Logger.info("Resizing chunk position buffer to: " + size);
        //Need to resize
        var old = this.chunkPosBuffer;
        this.chunkPosBuffer = new GlBuffer(size * 8L);
        glCopyNamedBufferSubData(old.id, this.chunkPosBuffer.id, 0, 0, old.size());
        old.free();
        var old2 = this.idx2chunk;
        this.idx2chunk = new long[size];
        System.arraycopy(old2, 0, this.idx2chunk, 0, old2.length);
        //Replace the old buffer with the new one
        ((AutoBindingShader)this.rasterShader).ssbo(1, this.chunkPosBuffer);
    }

    private void put(int idx, long pos) {
        long ptr2 = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 8L*idx, 8);
        //Need to do it in 2 parts because ivec2 is 2 parts
        MemoryUtil.memPutInt(ptr2, (int)(pos&0xFFFFFFFFL)); ptr2 += 4;
        MemoryUtil.memPutInt(ptr2, (int)((pos>>>32)&0xFFFFFFFFL));
    }

    public void reset() {
        this.chunk2idx.clear();
        this.visibleSectionCount = 0;
        this.pendingVisibleSectionCount = 0;
        this.pendingVisibleRenderLists = null;
        this.hasPendingVisibleSections = false;
        this.pendingVisibleSet.clear();
    }

    public void free() {
        this.rasterShader.free();
        this.uniformBuffer.free();
        this.chunkPosBuffer.free();
        this.visiblePosBuffer.free();
    }
}
