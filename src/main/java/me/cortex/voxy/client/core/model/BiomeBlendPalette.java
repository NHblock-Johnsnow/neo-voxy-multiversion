package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

// Palette of box-blended biome colours for LOD quads. The blended result needs an address a quad
// can carry, so blends live in a palette addressed through the quad's unused bits.
public final class BiomeBlendPalette {
    // Must match the base used by quad_util.glsl.
    public static final int PALETTE_BASE = 0;
    public static final int PALETTE_CAPACITY = 8192;
    // 流体将四个形状位保留给高度，颜色只使用独立的 9 位索引。
    public static final int FLUID_PALETTE_BASE = PALETTE_CAPACITY;
    public static final int FLUID_PALETTE_CAPACITY = 512;
    public static final int NORMAL_COLOUR_BASE = FLUID_PALETTE_BASE + FLUID_PALETTE_CAPACITY;
    private final ConcurrentHashMap<Integer, Integer> fluidIndices = new ConcurrentHashMap<>();
    private final AtomicInteger nextFluidIndex = new AtomicInteger();
    private volatile boolean fluidFullWarned;

    public int indexForFluid(int abgr) {
        if (this.disabled) return -1;
        return this.fluidIndices.computeIfAbsent(abgr & 0xF8F8F8, ignored -> {
            int index = this.nextFluidIndex.getAndIncrement();
            if (index >= FLUID_PALETTE_CAPACITY) {
                if (!this.fluidFullWarned) {
                    this.fluidFullWarned = true;
                    me.cortex.voxy.common.Logger.warn("Fluid blend palette full (512 entries); further colours keep biome edges");
                }
                return -1;
            }
            this.pendingUploads.add(new int[]{FLUID_PALETTE_BASE + index, abgr | 0xFF000000});
            return index;
        });
    }


    public record Snapshot(int[] colours, int[] rowBaseByModelId, int stride) {
        static final Snapshot EMPTY = new Snapshot(new int[0], new int[0], 0);

        // 返回 RGB，使有效的纯白色与缺失值 -1 分开。
        public int colourOf(int modelId, int biomeId) {
            if (modelId < 0 || modelId >= this.rowBaseByModelId.length
                    || biomeId < 0 || biomeId >= this.stride) {
                return -1;
            }
            int base = this.rowBaseByModelId[modelId];
            if (base < 0) {
                return -1;
            }
            int idx = base + biomeId;
            return idx < this.colours.length ? this.colours[idx] & 0xFFFFFF : -1;
        }
    }

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private final ConcurrentHashMap<Integer, Integer> quantisedToIndex = new ConcurrentHashMap<>();
    private final AtomicInteger nextIndex = new AtomicInteger();
    private final ConcurrentLinkedQueue<int[]> pendingUploads = new ConcurrentLinkedQueue<>();
    private volatile boolean fullWarned;
    private volatile boolean disabled;

    public Snapshot snapshot() {
        return this.snapshot;
    }

    public void disable() {
        this.disabled = true;
    }

    public int indexFor(int abgr) {
        if (this.disabled) {
            return -1;
        }
        int key = abgr & 0xF8F8F8;
        return this.quantisedToIndex.computeIfAbsent(key, ignored -> {
            int idx = this.nextIndex.getAndIncrement();
            if (idx >= PALETTE_CAPACITY) {
                if (!this.fullWarned) {
                    this.fullWarned = true;
                    me.cortex.voxy.common.Logger.warn("Biome blend palette full ("
                            + PALETTE_CAPACITY + " entries); further transitions keep hard edges");
                }
                return -1;
            }
            this.pendingUploads.add(new int[]{idx, abgr | 0xFF000000});
            return idx;
        });
    }

    public boolean drainUploads(GlBuffer modelColourBuffer) {
        boolean wrote = false;
        int[] entry;
        while ((entry = this.pendingUploads.poll()) != null) {
            long ptr = UploadStream.INSTANCE.upload(modelColourBuffer,
                    (PALETTE_BASE + entry[0]) * 4L, 4);
            MemoryUtil.memPutInt(ptr, entry[1]);
            wrote = true;
        }
        return wrote;
    }

    public void mirrorRow(int modelId, int rowBase, long rowAddress, int entryCount) {
        var old = this.snapshot;
        int[] rowBases = growRowBases(old.rowBaseByModelId(), modelId + 1);
        int[] colours = old.colours();
        if (colours.length < rowBase + entryCount) {
            colours = java.util.Arrays.copyOf(colours, rowBase + entryCount);
        } else {
            colours = colours.clone();
        }
        rowBases[modelId] = rowBase;
        for (int i = 0; i < entryCount; i++) {
            colours[rowBase + i] = MemoryUtil.memGetInt(rowAddress + i * 4L);
        }
        this.snapshot = new Snapshot(colours, rowBases, entryCount);
    }

    public void mirrorRebuild(long pairsAddress, int modelCount, long coloursAddress,
                              int colourCount, int stride) {
        int maxModelId = 0;
        for (int i = 0; i < modelCount; i++) {
            maxModelId = Math.max(maxModelId,
                    (int) (MemoryUtil.memGetLong(pairsAddress + i * 8L) & 0xFFFFFFFFL));
        }
        int[] rowBases = growRowBases(new int[0],
                Math.max(this.snapshot.rowBaseByModelId().length, maxModelId + 1));
        for (int i = 0; i < modelCount; i++) {
            long pair = MemoryUtil.memGetLong(pairsAddress + i * 8L);
            rowBases[(int) (pair & 0xFFFFFFFFL)] = (int) (pair >>> 32);
        }
        int[] colours = new int[colourCount];
        for (int i = 0; i < colourCount; i++) {
            colours[i] = MemoryUtil.memGetInt(coloursAddress + i * 4L);
        }
        this.snapshot = new Snapshot(colours, rowBases, stride);
    }

    private static int[] growRowBases(int[] old, int minLen) {
        int len = Math.max(old.length, minLen);
        int[] fresh = java.util.Arrays.copyOf(old, len);
        java.util.Arrays.fill(fresh, old.length, len, -1);
        return fresh;
    }
}
