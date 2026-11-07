package me.cortex.voxy.client.compat.create;

public final class CreateMemoryReport {
    private CreateMemoryReport() {}

    private static final int FLOAT = 4;
    //Object header plus the array length word, near enough for a report meant to be read in orders of
    //magnitude rather than compared byte for byte
    private static final int ARRAY_OVERHEAD = 16;

    public static String dump() {
        var sb = new StringBuilder("distant Create memory:").append('\n');

        long kineticGpu = 0, kineticSource = 0, kineticGeneric = 0;
        int buckets = 0, snaps = 0, withGeneric = 0, hollowSnaps = 0;
        for (var bucket : KineticSnapshots.sections().values()) {
            buckets++;
            if (bucket.mesh != null) {
                kineticGpu += bucket.mesh.gpuByteSize();
            }
            for (var snap : bucket.geoms.values()) {
                if (snap.hollow()) {
                    hollowSnaps++;
                    continue;
                }
                snaps++;
                long own = 48;
                if (snap.generic() != null) {
                    withGeneric++;
                    long g = (long) snap.generic().length * FLOAT + ARRAY_OVERHEAD;
                    kineticGeneric += g;
                    own += g;
                }
                if (snap.bnbChain() != null) {
                    own += (long) snap.bnbChain().length * FLOAT + ARRAY_OVERHEAD;
                }
                if (snap.bnbLights() != null) {
                    own += (long) snap.bnbLights().length * Integer.BYTES + ARRAY_OVERHEAD;
                }
                if (snap.chains() != null) {
                    own += ARRAY_OVERHEAD;
                    for (float[] row : snap.chains()) {
                        if (row != null) {
                            own += (long) row.length * FLOAT + ARRAY_OVERHEAD;
                        }
                    }
                }
                kineticSource += own;
            }
        }

        long contraptionGpu = 0, contraptionSource = 0;
        int contraptions = 0, withSource = 0, blocks = 0, dormant = 0;
        for (var snap : DistantContraptionManager.snapshots().values()) {
            contraptions++;
            if (snap.mesh() != null) {
                contraptionGpu += snap.mesh().mesh.gpuByteSize();
            } else if (snap.source() != null) {
                //Knows what it is made of, holds no vertex memory - either evicted by the budget or read
                //off disk and not yet rebuilt
                dormant++;
            }
            var source = snap.source();
            if (source != null) {
                withSource++;
                blocks += source.blockCount();
                //Per ShapeBlock: three bytes of position plus a reference to a shared BlockState, which
                //is interned by the registry and so is not charged here
                contraptionSource += (long) source.blockCount() * 24L + ARRAY_OVERHEAD;
                if (source.modelData() != null) {
                    contraptionSource += (long) source.modelData().size() * 48L;
                }
            }
        }

        sb.append(String.format("  kinetics    buckets=%d snaps=%d (generic=%d, skipMarkers=%d)%n", buckets, snaps, withGeneric, hollowSnaps));
        sb.append(String.format("              gpu=%s  source=%s  (of which generic %s)%n",
                mib(kineticGpu), mib(kineticSource), mib(kineticGeneric)));
        sb.append(String.format("              source/gpu = %s%n", ratio(kineticSource, kineticGpu)));
        sb.append(String.format("  contraption count=%d (with source=%d, dormant=%d, %d blocks)%n",
                contraptions, withSource, dormant, blocks));
        sb.append(String.format("              budget=%d MiB, resident=%s%n",
                me.cortex.voxy.client.config.VoxyConfig.CONFIG.distantContraptionGpuBudgetMiB,
                mib(DistantContraptionManager.residentGpuBytes())));
        sb.append(String.format("              gpu=%s  source=%s%n", mib(contraptionGpu), contraptionSource == 0 ? "0" : mib(contraptionSource)));
        sb.append(String.format("              source/gpu = %s%n", ratio(contraptionSource, contraptionGpu)));
        sb.append(String.format("  total gpu   %s", mib(kineticGpu + contraptionGpu)));
        return sb.toString();
    }

    //A subsystem whose source is a small fraction of its mesh is worth persisting; one whose source
    //approaches or exceeds it is not.
    private static String ratio(long source, long gpu) {
        if (gpu == 0) {
            return source == 0 ? "n/a" : "no gpu residency";
        }
        return String.format("%.2fx", (double) source / gpu);
    }

    private static String mib(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KiB", bytes / 1024.0);
        }
        return String.format("%.2f MiB", bytes / (1024.0 * 1024.0));
    }
}
