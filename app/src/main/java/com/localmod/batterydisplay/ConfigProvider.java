package com.localmod.batterydisplay;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * 跨进程配置提供者。
 *
 * App 的设置界面（MainActivity）和被 Hook 的 SystemUI 是两个完全独立的进程，
 * 普通 SharedPreferences 不能跨进程共享，所以用 ContentProvider 做这层桥接：
 * SystemUI 进程里的 Hook 代码通过 ContentResolver.query() 主动来读配置，
 * 不依赖文件权限，比 XSharedPreferences 更稳（不用担心 SELinux）。
 *
 * 单行配置表，一次 query 就能拿到全部配置项：
 * - shutdown_point:   实际电量的关机点 x(%)，用于换算公式，默认 20
 * - display_enabled:  是否把换算后的电量显示在状态栏/锁屏文字上，默认开启
 * - reminder_enabled: 是否开启低电量提醒(固定档位 20/10/5/1)，默认关闭
 *
 * 换算这件事本身，和"要不要把换算结果显示出来"是两回事：只要填了关机点，
 * 换算值就始终在后台被计算；display_enabled 只决定要不要显示在文字上；
 * 低电量提醒永远按换算值判断，不受显示开关影响。
 */
public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.localmod.batterydisplay.config";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/settings");

    public static final String COL_SHUTDOWN_POINT = "shutdown_point";
    public static final String COL_DISPLAY_ENABLED = "display_enabled";
    public static final String COL_REMINDER_ENABLED = "reminder_enabled";

    private static final String PREFS_NAME = "battery_display_config";

    static final int DEFAULT_SHUTDOWN_POINT = 20;
    static final boolean DEFAULT_DISPLAY_ENABLED = true;
    static final boolean DEFAULT_REMINDER_ENABLED = false;

    @Override
    public boolean onCreate() {
        return true;
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                         String[] selectionArgs, String sortOrder) {
        SharedPreferences p = prefs();
        MatrixCursor cursor = new MatrixCursor(
                new String[]{COL_SHUTDOWN_POINT, COL_DISPLAY_ENABLED, COL_REMINDER_ENABLED});
        cursor.addRow(new Object[]{
                p.getInt(COL_SHUTDOWN_POINT, DEFAULT_SHUTDOWN_POINT),
                p.getBoolean(COL_DISPLAY_ENABLED, DEFAULT_DISPLAY_ENABLED) ? 1 : 0,
                p.getBoolean(COL_REMINDER_ENABLED, DEFAULT_REMINDER_ENABLED) ? 1 : 0
        });
        return cursor;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        SharedPreferences.Editor editor = prefs().edit();
        int count = 0;

        if (values.containsKey(COL_SHUTDOWN_POINT)) {
            editor.putInt(COL_SHUTDOWN_POINT, values.getAsInteger(COL_SHUTDOWN_POINT));
            count++;
        }
        if (values.containsKey(COL_DISPLAY_ENABLED)) {
            editor.putBoolean(COL_DISPLAY_ENABLED, values.getAsInteger(COL_DISPLAY_ENABLED) != 0);
            count++;
        }
        if (values.containsKey(COL_REMINDER_ENABLED)) {
            editor.putBoolean(COL_REMINDER_ENABLED, values.getAsInteger(COL_REMINDER_ENABLED) != 0);
            count++;
        }

        editor.apply();

        if (count > 0 && getContext() != null) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd." + AUTHORITY + ".settings";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // 单行配置表，插入等价于更新
        update(uri, values, null, null);
        return uri;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0; // 不支持删除，配置项固定为单行
    }
}
