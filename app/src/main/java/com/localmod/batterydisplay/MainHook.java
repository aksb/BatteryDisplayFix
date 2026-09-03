package com.localmod.batterydisplay;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.BatteryManager;
import android.view.View;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 电量显示重算 + 低电量提醒。
 *
 * 只改状态栏 / 锁屏 / 快捷设置面板里显示的电量【文字】，不动电量条图形、不动系统上报的真实电量值。
 *
 * 原理：AOSP SystemUI 里状态栏、锁屏、快捷设置面板顶部的电量文字，
 * 通常共用同一个类 BatteryMeterView（同一份布局被 inflate 到不同位置），
 * 每次电量变化时会调用它的 updatePercentText() 方法刷新文字，本模块就挂在
 * 这一个 Hook 点上，同时驱动"改文字"和"发低电量提醒"两件事——早期版本
 * 还单独 Hook 了 onBatteryLevelChanged() 来拿充电状态，但这个方法在不同
 * ROM 上名字/签名不统一（PixelExperience 上验证会报 NoSuchMethodError），
 * 所以改成直接查询安卓标准的电池粘性广播（ACTION_BATTERY_CHANGED）来判断
 * 是否在充电，这是公开稳定的系统 API，不依赖 ROM 怎么改 SystemUI 内部实现。
 *
 * 配置来源：ConfigProvider（跨进程 ContentProvider，由 MainActivity 写入，
 * Hook 代码这边只负责读，不负责写）。
 *
 * 如果这台设备的类名/方法名/字段名和这里写的不一致，
 * 本模块会在 LSPosed 日志里打印具体报错，可据此调整（见 README）。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "BatteryDisplayFix";
    private static final String TARGET_PACKAGE = "com.android.systemui";

    // Android 12 前后 AOSP 对 BatteryMeterView 做过模块化重构，类路径发生过变化，
    // 这里按"新路径优先"的顺序依次尝试，命中哪个用哪个。
    private static final String[] BATTERY_VIEW_CLASS_CANDIDATES = {
            "com.android.systemui.battery.BatteryMeterView",          // Android 12+ 重构后路径
            "com.android.systemui.statusbar.policy.BatteryMeterView"  // 较早 AOSP 路径
    };

    // 触发文字刷新的方法名，AOSP 原生里通常叫这个名字；电量变化时会调用它，
    // 低电量提醒也挂在这个 Hook 点上一起处理。
    private static final String UPDATE_TEXT_METHOD = "updatePercentText";

    // BatteryMeterView 内部保存"当前电量"的字段名
    private static final String LEVEL_FIELD = "mLevel";

    // BatteryMeterView 内部持有的百分比 TextView 字段名
    private static final String PERCENT_VIEW_FIELD = "mBatteryPercentView";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        Class<?> batteryMeterViewClass = null;
        String matchedClassName = null;

        for (String candidate : BATTERY_VIEW_CLASS_CANDIDATES) {
            try {
                batteryMeterViewClass = XposedHelpers.findClass(candidate, lpparam.classLoader);
                matchedClassName = candidate;
                break;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": 候选类未命中 " + candidate + " -> " + t);
            }
        }

        if (batteryMeterViewClass == null) {
            XposedBridge.log(TAG + ": 所有候选类均未命中，需要反编译 SystemUI 确认真实类名");
            return;
        }

        hookUpdatePercentText(batteryMeterViewClass, matchedClassName);
    }

    private void hookUpdatePercentText(Class<?> batteryMeterViewClass, String matchedClassName) {
        try {
            XposedHelpers.findAndHookMethod(
                    batteryMeterViewClass,
                    UPDATE_TEXT_METHOD,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onBatteryTextRefresh(param.thisObject);
                        }
                    });

            XposedBridge.log(TAG + ": hook 安装成功 -> " + matchedClassName + "#" + UPDATE_TEXT_METHOD);
        } catch (Throwable t) {
            // 类找到了，但方法名不对：常见于该类被重写过，字段/方法名也一起变了。
            XposedBridge.log(TAG + ": 类命中(" + matchedClassName
                    + ")但方法未找到(" + UPDATE_TEXT_METHOD + ")，请检查方法名是否需要调整: " + t);
        }
    }

    /**
     * updatePercentText 每次触发（电量变化时）都会进这里：
     * 读原始电量 -> 换算 -> 按开关决定是否覆盖显示文字 -> 顺带跑一次低电量提醒判断。
     */
    private void onBatteryTextRefresh(Object batteryMeterView) {
        try {
            if (!(batteryMeterView instanceof View)) {
                return;
            }
            Context context = ((View) batteryMeterView).getContext().getApplicationContext();
            Config config = readConfig(context);

            int rawLevel = XposedHelpers.getIntField(batteryMeterView, LEVEL_FIELD);
            int displayLevel = BatteryCalc.calcDisplayLevel(rawLevel, config.shutdownPoint);

            if (config.displayEnabled) {
                Object percentViewObj = XposedHelpers.getObjectField(batteryMeterView, PERCENT_VIEW_FIELD);
                if (percentViewObj instanceof TextView) {
                    ((TextView) percentViewObj).setText(displayLevel + "%");
                }
            } // 开关关闭时保留系统原始文字，不覆盖

            handleLowBatteryReminder(context, displayLevel, config);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 设置文本失败(字段名可能需要调整): " + t);
        }
    }

    private void handleLowBatteryReminder(Context context, int displayLevel, Config config) {
        try {
            boolean charging = isCharging(context);

            // 低电量提醒依赖"状态栏电量显示修改"：提醒文案报的是换算后的电量，
            // 如果显示是关的，状态栏看到的还是系统原始电量，两个数字会对不上。
            // 正常情况下 App 界面已经保证不会出现 display=false 且 reminder=true
            // 的组合，这里再夹一层防御，避免配置被 UI 之外的方式改坏。
            boolean effectiveReminderEnabled = config.displayEnabled && config.reminderEnabled;

            LowBatteryReminder.onBatteryUpdate(context, displayLevel, charging, effectiveReminderEnabled);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 低电量提醒处理失败: " + t);
        }
    }

    /**
     * 查询安卓标准的电池粘性广播判断是否在充电，不依赖 ROM 私有实现。
     * BATTERY_STATUS_FULL 也算作"充电中"（已插着电，不会继续掉电）。
     */
    private static boolean isCharging(Context context) {
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, filter);
            if (batteryStatus == null) {
                return false;
            }
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 读取充电状态失败: " + t);
            return false;
        }
    }

    // 换算逻辑抽在 BatteryCalc 里，和 MainActivity 的换算对照表共用同一份算法，
    // 避免"App 里预览的表"和"状态栏实际显示的数字"两边跑偏。

    private static Config readConfig(Context context) {
        Config config = new Config();
        try {
            Cursor cursor = context.getContentResolver().query(
                    ConfigProvider.CONTENT_URI, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        config.shutdownPoint = cursor.getInt(
                                cursor.getColumnIndexOrThrow(ConfigProvider.COL_SHUTDOWN_POINT));
                        config.displayEnabled = cursor.getInt(
                                cursor.getColumnIndexOrThrow(ConfigProvider.COL_DISPLAY_ENABLED)) != 0;
                        config.reminderEnabled = cursor.getInt(
                                cursor.getColumnIndexOrThrow(ConfigProvider.COL_REMINDER_ENABLED)) != 0;
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 读取配置失败，使用默认值: " + t);
        }
        return config;
    }

    private static final class Config {
        int shutdownPoint = 20;
        boolean displayEnabled = true;
        boolean reminderEnabled = false;
    }
}
