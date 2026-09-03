package com.localmod.batterydisplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import java.util.Arrays;

import de.robv.android.xposed.XposedBridge;

/**
 * 低电量提醒：换算后电量跌破 20% / 10% / 5% / 1% 各提醒一次（普通通知，可划掉）。
 *
 * 判断规则："当前换算后电量 <= 某档位，且这个档位这一轮还没提醒过"才弹一次。
 * 已经提醒过的档位，哪怕电量在附近来回抖动（采样噪声 ±1%），也不会重复弹——
 * 只有"跨过一个新的、还没提醒过的档位"才会触发，这本身就是一种天然的滞回，
 * 不需要再单独加"回升几个百分点才清除"之类的缓冲。
 *
 * 插上电（charging 由 false 变 true 的那一刻）清空所有"已提醒"标记，
 * 下次拔电重新从头按档位提醒。
 *
 * 如果提醒开关是在电量已经很低的时候才打开，同一套判断逻辑会自然实现
 * "只补最接近当前电量的那一档，不重复补更高档位"：因为更高的档位在这次
 * 检查里会和当前最低档位一起被标记为"已提醒"，但只有最后（最紧急）那一档
 * 会真正触发一次通知。
 *
 * 通知是以 SystemUI 的进程身份发出的（Context 来自 BatteryMeterView 所在的
 * SystemUI 进程），所以通知渠道也必须用这个身份创建，不能在 App 自己进程里建；
 * 用户看到的通知应用名会显示成 "System UI"。
 */
final class LowBatteryReminder {

    private static final String TAG = "BatteryDisplayFix";
    private static final String CHANNEL_ID = "battery_display_fix_low_battery";
    private static final String CHANNEL_NAME = "低电量提醒";

    // 固定 ID：新档位触发时会替换掉旧通知的内容，不会在通知栏里堆叠出多条
    private static final int NOTIFICATION_ID = 20260830;

    // 从高到低排列；数组里最后一个满足条件的档位就是当前最紧急的那一档
    private static final int[] TIERS = {20, 10, 5, 1};

    private static final boolean[] notified = new boolean[TIERS.length];
    private static boolean lastCharging = false;
    private static volatile boolean channelCreated = false;

    private LowBatteryReminder() {
    }

    static synchronized void onBatteryUpdate(Context context, int displayLevel,
                                              boolean charging, boolean reminderEnabled) {
        boolean justPluggedIn = charging && !lastCharging;
        lastCharging = charging;

        if (justPluggedIn) {
            Arrays.fill(notified, false);
        }

        if (!reminderEnabled || charging) {
            return;
        }

        int mostUrgentIndex = -1;
        for (int i = 0; i < TIERS.length; i++) {
            if (!notified[i] && displayLevel <= TIERS[i]) {
                notified[i] = true;
                mostUrgentIndex = i;
            }
        }

        if (mostUrgentIndex >= 0) {
            sendNotification(context, TIERS[mostUrgentIndex]);
        }
    }

    private static void ensureChannel(Context context) {
        if (channelCreated) {
            return;
        }
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("电量显示重算模块的低电量提醒");
            nm.createNotificationChannel(channel);
            channelCreated = true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 创建通知渠道失败: " + t);
        }
    }

    private static void sendNotification(Context context, int tier) {
        try {
            ensureChannel(context);
            Notification notification = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                    .setContentTitle("电量低于 " + tier + "%")
                    .setContentText("请尽快充电⚡")
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .build();
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, notification);
            }
            XposedBridge.log(TAG + ": 已发送低电量提醒(" + tier + "%)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 发送低电量通知失败: " + t);
        }
    }
}
