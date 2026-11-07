package me.cortex.voxy.client.core.compat.eclipticseasons;

import me.cortex.voxy.client.core.model.bakery.ReuseVertexConsumer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.SurfaceWorldView;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

//Indirection so the core classes never link against EclipticSeasons: every method body that
//touches its classes lives behind this interface, and the field is only assigned when the mod is
//present (see the registration in VoxyClient).
public final class SeasonalLod {
    public static volatile View view = null;

    private SeasonalLod() {}

    public static void disarm(LinkageError error) {
        if (view == null) return;
        view = null;
        me.cortex.voxy.common.Logger.error(
                "Seasonal LOD disabled: EclipticSeasons symbol missing at mesh time", error);
    }

    public record SeasonalBakedModel(BakedModel model, boolean replace) { }

    public interface View {
        long[] substituteSection(WorldEngine world, WorldSection section, long[] source);

        default long[] substituteSection(WorldEngine world, WorldSection section, long[] source,
                                         SurfaceWorldView surface) {
            return this.substituteSection(world, section, source);
        }

        void substituteRegion(WorldEngine world, SurfaceWorldView surface, long[] data,
                              int offset, int width, int height, int depth,
                              int rowStride, int layerStride, int x, int y, int z);

        //In-place seasonal pass over the four lateral neighbour face slices already pulled into
        //the mesh worker's private scratch, so same-id face culling at section borders sees the
        //same substitution on both sides.
        void substituteLateralSlices(WorldEngine world, WorldSection section,
                                     long[] neighborFaces, int neighborMsk);

        default void substituteLateralSlices(WorldEngine world, WorldSection section,
                                             long[] neighborFaces, int neighborMsk, SurfaceWorldView surface) {
            this.substituteLateralSlices(world, section, neighborFaces, neighborMsk);
        }

        boolean isSeasonalConstantTint(BlockState state, Object colourProvider);

        SeasonalBakedModel resolveSeasonalModel(BlockState state, ResourceLocation modelId);

        void renderSnowOverlay(BlockState state, RenderType layer,
                               ReuseVertexConsumer translucentVC, ReuseVertexConsumer opaqueVC);

        // Called before Voxy rebuilds after a resource reload. Implementations must not retain
        // judgements derived from EclipticSeasons' data-driven snow/model definitions.
        void clearCaches();
    }
}
