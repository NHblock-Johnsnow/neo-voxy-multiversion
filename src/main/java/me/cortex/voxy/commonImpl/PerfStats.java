package me.cortex.voxy.commonImpl;

import java.util.concurrent.atomic.LongAdder;

public final class PerfStats {
    private PerfStats() {}
    public static final LongAdder sectionArrayPoolMiss = new LongAdder();
    public static final LongAdder sectionArrayPoolOverflow = new LongAdder();

    //--- ingest hot path ---
    //Biome id resolution: a hit skipped a ResourceLocation.toString() + registry lookup (64x/section)
    public static final LongAdder biomeCacheHit = new LongAdder();
    public static final LongAdder biomeCacheMiss = new LongAdder();
    //Copycat material key: a hit skipped writeBlockState + toString for that block
    public static final LongAdder copycatKeyHit = new LongAdder();
    public static final LongAdder copycatKeyMiss = new LongAdder();

    //--- create client ---
    //Kinetic leave-behind snapshots dropped by the distance bound (each freed a VAO/VBO + heap verts)
    public static final LongAdder kineticSnapshotEvicted = new LongAdder();
    public static final LongAdder contraptionSnapshotEvicted = new LongAdder();
    //Per-tick 64KB re-bakes avoided for contraptions that resolved to no drawable mesh
    public static final LongAdder contraptionRebakeSkipped = new LongAdder();
    //Traversal "request already in flight" warns suppressed after the first few
    public static final LongAdder nodeWarnSuppressed = new LongAdder();

    //--- distant train server ---
    //Carriage pose reuse across players in a dimension: a hit skipped a buildBogeyPoses + trig
    public static final LongAdder trainPoseCacheHit = new LongAdder();
    public static final LongAdder trainPoseCacheMiss = new LongAdder();
    //Heavy Contraption.fromNBT shape builds pushed to a later tick by the per-round budget
    public static final LongAdder trainShapeBuildDeferred = new LongAdder();

    //--- world section uniform mode ---
    //Sections that stayed uniform for their whole life (each saved a 256KiB alloc + memset)
    public static final LongAdder sectionUniformKept = new LongAdder();
    //Sections that had to allocate a real array (the denominator for the uniform hit rate)
    public static final LongAdder sectionMaterialized = new LongAdder();

    public static final LongAdder sectionIngestedChunkless = new LongAdder();
    public static final LongAdder sectionIngestedWithChunk = new LongAdder();
    //Materialise calls that found another thread had already done it (contention, but no wasted work)
    public static final LongAdder sectionMaterializeContended = new LongAdder();
    //Neighbour face slices filled from a uniform value instead of copied out of an array
    public static final LongAdder neighborFaceUniformFill = new LongAdder();
    //Ingest writes whose values all matched the uniform value, so the section stayed uniform
    public static final LongAdder sectionUniformWriteSkipped = new LongAdder();
    //Sections whose translucent or double-sided quad bucket filled up and had quads dropped. Non-zero
    //means geometry is missing from those sections - see the guard in RenderDataFactory.
    public static final LongAdder quadBucketOverflow = new LongAdder();
    public static final LongAdder quadBucketDropped = new LongAdder();

    //--- section saving ---
    //Batched section writes: sections/commits is the headline (>1 means batching is working at all)
    public static final LongAdder saveBatchCommits = new LongAdder();
    public static final LongAdder saveBatchSections = new LongAdder();

    private static String ratio(String name, LongAdder hit, LongAdder miss) {
        long h = hit.sum();
        long m = miss.sum();
        long total = h + m;
        double pct = total == 0 ? 0.0 : (100.0 * h / total);
        return String.format("  %-22s hit=%,d miss=%,d (%.2f%% hit)", name, h, m, pct);
    }

    public static String report() {
        StringBuilder sb = new StringBuilder("Voxy optimization stats:\n");
        sb.append(ratio("biome-id cache", biomeCacheHit, biomeCacheMiss)).append('\n');
        sb.append(ratio("copycat material cache", copycatKeyHit, copycatKeyMiss)).append('\n');
        sb.append(ratio("train pose reuse", trainPoseCacheHit, trainPoseCacheMiss)).append('\n');
        sb.append(String.format("  %-22s %,d", "kinetic snapshots evicted", kineticSnapshotEvicted.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "contraption snaps evicted", contraptionSnapshotEvicted.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "contraption rebakes skipped", contraptionRebakeSkipped.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "train shapes deferred", trainShapeBuildDeferred.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "node warns suppressed", nodeWarnSuppressed.sum())).append('\n');
        long commits = saveBatchCommits.sum();
        long batched = saveBatchSections.sum();
        sb.append(String.format("  %-22s %,d sections in %,d commits (avg %.1f/commit)",
                "save batching", batched, commits, commits == 0 ? 0.0 : (double) batched / commits)).append('\n');
        long uniform = sectionUniformKept.sum();
        long materialized = sectionMaterialized.sum();
        long totalSections = uniform + materialized;
        sb.append(String.format("  %-22s uniform=%,d materialized=%,d (%.1f%% uniform, %,d MiB saved)",
                "section uniform mode", uniform, materialized,
                totalSections == 0 ? 0.0 : (100.0 * uniform / totalSections),
                (uniform * 256L) / 1024)).append('\n');
        sb.append(String.format("  %-22s %,d (contended %,d)",
                "neighbour uniform fill", neighborFaceUniformFill.sum(), sectionMaterializeContended.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "uniform writes skipped", sectionUniformWriteSkipped.sum())).append('\n');
        sb.append(String.format("  %-22s from server=%,d from this client=%,d",
                "section ingest source", sectionIngestedChunkless.sum(), sectionIngestedWithChunk.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "quad bucket overflows", quadBucketOverflow.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "quads dropped", quadBucketDropped.sum()));
        return sb.toString();
    }

    public static void reset() {
        for (LongAdder a : new LongAdder[]{biomeCacheHit, biomeCacheMiss, copycatKeyHit, copycatKeyMiss,
                kineticSnapshotEvicted, contraptionSnapshotEvicted, contraptionRebakeSkipped, nodeWarnSuppressed,
                trainPoseCacheHit, trainPoseCacheMiss, trainShapeBuildDeferred,
                saveBatchCommits, saveBatchSections,
                sectionUniformKept, sectionMaterialized, sectionMaterializeContended, neighborFaceUniformFill, sectionUniformWriteSkipped,
                sectionIngestedChunkless, sectionIngestedWithChunk, quadBucketOverflow, quadBucketDropped}) {
            a.reset();
        }
    }
}
