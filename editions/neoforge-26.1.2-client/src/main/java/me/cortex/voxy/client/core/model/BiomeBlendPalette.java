package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class BiomeBlendPalette {
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
            this.pending.add(new int[]{FLUID_PALETTE_BASE + index, abgr | 0xFF000000});
            return index;
        });
    }

   public record Snapshot(int[] colours, int[] rowBaseByModelId, int stride) {
      static final Snapshot EMPTY = new Snapshot(new int[0], new int[0], 0);
      // 返回 RGB，使有效的纯白色与缺失值 -1 分开。
        public int colourOf(int modelId, int biomeId) {
         if (modelId < 0 || modelId >= rowBaseByModelId.length || biomeId < 0 || biomeId >= stride) return -1;
         int base = rowBaseByModelId[modelId], index = base + biomeId;
         return base < 0 || index >= colours.length ? -1 : colours[index] & 0xFFFFFF;
      }
   }
   private volatile Snapshot snapshot = Snapshot.EMPTY;
   private final ConcurrentHashMap<Integer, Integer> indices = new ConcurrentHashMap<>();
   private final AtomicInteger nextIndex = new AtomicInteger();
   private final ConcurrentLinkedQueue<int[]> pending = new ConcurrentLinkedQueue<>();
   private volatile boolean disabled;
   public Snapshot snapshot() { return snapshot; }
   public void disable() { disabled = true; }
   public int indexFor(int abgr) {
      if (disabled) return -1;
      return indices.computeIfAbsent(abgr & 0xF8F8F8, ignored -> {
         int index = nextIndex.getAndIncrement();
         if (index >= PALETTE_CAPACITY) return -1;
         pending.add(new int[]{index, abgr | 0xFF000000});
         return index;
      });
   }
   public boolean drainUploads(GlBuffer buffer) {
      boolean wrote = false; int[] entry;
      while ((entry = pending.poll()) != null) {
         MemoryUtil.memPutInt(UploadStream.INSTANCE.upload(buffer, (PALETTE_BASE + entry[0]) * 4L, 4), entry[1]);
         wrote = true;
      }
      return wrote;
   }
   public void mirrorRow(int modelId, int base, long address, int count) {
      Snapshot old = snapshot;
      int[] rows = grow(old.rowBaseByModelId(), modelId + 1);
      int[] colours = Arrays.copyOf(old.colours(), Math.max(old.colours().length, base + count));
      rows[modelId] = base;
      for (int i = 0; i < count; i++) colours[base + i] = MemoryUtil.memGetInt(address + i * 4L);
      snapshot = new Snapshot(colours, rows, count);
   }
   public void mirrorRebuild(long pairs, int modelCount, long address, int colourCount, int stride) {
      int max = 0;
      for (int i = 0; i < modelCount; i++) max = Math.max(max, (int)MemoryUtil.memGetLong(pairs + i * 8L));
      int[] rows = grow(new int[0], Math.max(snapshot.rowBaseByModelId().length, max + 1));
      for (int i = 0; i < modelCount; i++) { long pair = MemoryUtil.memGetLong(pairs + i * 8L); rows[(int)pair] = (int)(pair >>> 32); }
      int[] colours = new int[colourCount];
      for (int i = 0; i < colourCount; i++) colours[i] = MemoryUtil.memGetInt(address + i * 4L);
      snapshot = new Snapshot(colours, rows, stride);
   }
   private static int[] grow(int[] old, int length) {
      int[] result = Arrays.copyOf(old, Math.max(old.length, length));
      Arrays.fill(result, old.length, result.length, -1);
      return result;
   }
}
