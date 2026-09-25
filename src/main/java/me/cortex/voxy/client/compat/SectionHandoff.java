package me.cortex.voxy.client.compat;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.rendering.LodBoundaryFade;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;

/** 静态远景模型仅向本帧参与地形绘制的区段交接。 */
public final class SectionHandoff {
    private static long frameId;
    private static long frameTimeNanos;

    private SectionHandoff() {}

    public static void beginFrame() {
        frameId++;
        frameTimeNanos = System.nanoTime();
    }

    public static long frameId() { return frameId; }

    public static long frameTimeNanos() { return frameTimeNanos; }

    public static double distanceSquared() {
        var boundary = LodBoundaryFade.getDistances();
        double distance = boundary.enabled() ? boundary.fadeStart()
                : Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        return distance * distance;
    }

    public static boolean vanillaOwns(int sectionX, int sectionY, int sectionZ) {
        var renderer = Minecraft.getInstance().levelRenderer;
        if (renderer == null || VoxyClient.disableSodiumChunkRender()) return false;
        var system = ((IGetVoxyRenderSystem) renderer).voxy$getRenderSystem();
        return system != null && system.chunkBoundRenderer.containsVisibleSection(
                SectionPos.asLong(sectionX, sectionY, sectionZ));
    }
}
