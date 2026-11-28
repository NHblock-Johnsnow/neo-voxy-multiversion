package me.cortex.voxy.client.core.util;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * 副相机与离屏渲染通道隔离守卫（仅在客户端渲染线程访问，零 GC 分配）。
 * 用于识别 Vista TV 等模组触发的二次或离屏世界渲染，避免污染主视口 LOD 状态。
 */
public final class RenderPassGuard {
    private static int levelRenderDepth;
    private static int secondaryDepth;
    private static boolean secondarySectionList;

    private RenderPassGuard() {}

    /** 进入 LevelRenderer.renderLevel 时判定当前是否为副相机或离屏通道。 */
    public static boolean enterLevelRender(Camera camera) {
        boolean secondary = levelRenderDepth > 0
                || isSecondaryCamera(camera)
                || isOffscreenFramebufferBound();
        levelRenderDepth++;
        if (secondary) {
            secondaryDepth++;
        }
        return secondary;
    }

    /** 退出 LevelRenderer.renderLevel 时恢复嵌套计数。 */
    public static void exitLevelRender(boolean secondary) {
        if (levelRenderDepth > 0) {
            levelRenderDepth--;
        }
        if (secondary && secondaryDepth > 0) {
            secondaryDepth--;
        }
        if (levelRenderDepth == 0) {
            secondarySectionList = false;
        }
    }

    public static boolean isSecondaryPass() {
        return secondaryDepth > 0;
    }

    public static boolean isSecondaryCamera(Camera camera) {
        if (camera == null) {
            return false;
        }
        var mc = Minecraft.getInstance();
        return mc != null && mc.gameRenderer != null && camera != mc.gameRenderer.getMainCamera();
    }

    /** 检查当前绘制帧缓冲是否不是 Minecraft 主 RenderTarget。 */
    public static boolean isOffscreenFramebufferBound() {
        var mc = Minecraft.getInstance();
        if (mc == null) {
            return false;
        }
        var mainTarget = mc.getMainRenderTarget();
        if (mainTarget == null || mainTarget.frameBufferId <= 0) {
            return false;
        }
        return GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) != mainTarget.frameBufferId;
    }

    /** 判定当前是否应跳过 Voxy 视口更新与 LOD 绘制。 */
    public static boolean shouldSkipVoxyRender() {
        return secondaryDepth > 0 || (!IrisUtil.irisShaderPackEnabled() && isOffscreenFramebufferBound());
    }

    public static void setSecondarySectionList(boolean secondary) {
        secondarySectionList = secondary;
    }

    public static boolean isSecondarySectionList() {
        return secondaryDepth > 0 || secondarySectionList;
    }
}
