package me.cortex.voxy.client.mixin.seedgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(remap = false, targets = {
        "mobius.voxyseedgen.config.VoxySeedGenConfig",
        "mobius.voxyseedgen.voxy.CoarseRings",
        "mobius.voxyseedgen.voxy.LadderWriter",
        "mobius.voxyseedgen.voxy.RenderReach",
        "mobius.voxyseedgen.voxy.SeedFill",
        "mobius.voxyseedgen.voxy.VoxyChunkWriter"
})
public abstract class SeedGenTargets {}
