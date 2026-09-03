package com.localmod.batterydisplay;

/**
 * 电量换算公共逻辑。
 *
 * MainHook（SystemUI 进程里改状态栏文字）和 MainActivity（App 里的换算对照表预览）
 * 必须用同一套算法，否则容易出现"App 里预览的换算表"和"状态栏实际显示的数字"对不上
 * 的情况——所以两边都只调用这一个方法，不各自维护一份。
 */
final class BatteryCalc {

    private BatteryCalc() {
    }

    /**
     * 换算规则（关机点 x 由用户在设置界面里填写，默认 20）：
     * - 原始电量 <= x：显示 0%
     * - 原始电量 > x：显示 (原始电量 - x) / (100 - x) * 100，四舍五入，并夹在 [0, 100] 之间
     * x = 20 时正好退化成最初写死的 (level - 20) * 1.25 公式。
     */
    static int calcDisplayLevel(int rawLevel, int shutdownPoint) {
        int x = shutdownPoint;
        if (x < 0) {
            x = 0;
        } else if (x > 99) {
            x = 99; // 关机点不可能是 100%，这里防一下除以 0
        }

        if (rawLevel <= x) {
            return 0;
        }

        float value = (rawLevel - x) / (float) (100 - x) * 100f;
        int result = Math.round(value);
        if (result < 0) {
            result = 0;
        } else if (result > 100) {
            result = 100;
        }
        return result;
    }
}
