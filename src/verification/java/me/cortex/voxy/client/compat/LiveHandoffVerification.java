package me.cortex.voxy.client.compat;

/** 验证交接时不会在同帧改变归属，或在短暂就绪后提前让出 LOD。 */
public final class LiveHandoffVerification {
    private static int checks;

    public static void main(String[] args) {
        var state = new LiveHandoffState();
        expect(!state.update(1, 0, false), "未就绪时由 LOD 绘制");
        expect(!state.update(1, 500_000_000L, true), "同帧后续绘制不能改变归属");
        expect(!state.update(2, 10_000_000L, true), "首次恢复先等待");
        expect(!state.update(3, 259_999_999L, true), "等待不足 250 ms");
        expect(!state.update(3, 260_000_000L, true), "同帧越过时间阈值也不能改变归属");
        expect(state.update(4, 260_000_000L, true), "下一帧稳定恢复");
        expect(state.update(4, 270_000_000L, false), "同帧多个渲染入口共用结果");
        expect(!state.update(5, 280_000_000L, false), "下一帧失效立即交回 LOD");
        expect(!state.update(6, 300_000_000L, true), "重新等待");
        expect(!state.update(7, 400_000_000L, false), "短暂失效打断等待");
        expect(!state.update(8, 550_000_000L, true), "不能沿用被打断的计时");
        expect(!state.update(9, 799_999_999L, true), "新的等待必须完整");
        expect(state.update(10, 800_000_000L, true), "连续就绪后恢复");
        expect(!state.reacquiring(), "接管后退出重获状态");
        expect(state.evaluated(10) && !state.evaluated(11), "帧缓存仅命中当前帧");

        var firstLive = new LiveHandoffState();
        expect(firstLive.update(1, -1_000_000_000L, true), "初始已就绪保持原版");
        expect(!firstLive.update(2, -900_000_000L, false), "时钟可为负值");
        expect(!firstLive.update(3, 0, true), "零时刻也可以开始等待");
        expect(firstLive.update(4, 250_000_000L, true), "零时刻不是未初始化标记");
        System.out.println("Passed " + checks + " live handoff checks");
    }

    private static void expect(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }
}
