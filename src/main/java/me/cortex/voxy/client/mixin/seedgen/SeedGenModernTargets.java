package me.cortex.voxy.client.mixin.seedgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(remap = false, targets = {
        "mobius.voxyseedgen.voxy.CoarseFill",
        "mobius.voxyseedgen.voxy.ExistingChunks",
        "mobius.voxyseedgen.voxy.LodLevels",
        "mobius.voxyseedgen.voxy.SectionChildren",
        "mobius.voxyseedgen.voxy.VoxyBuilds"
})
public abstract class SeedGenModernTargets {}
