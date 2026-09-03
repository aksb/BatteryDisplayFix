package com.localmod.batterydisplay;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置界面。
 *
 * 关机点数值是文本输入，需要一个独立的"保存"按钮确认（放在输入框右边，
 * 填完立刻点）；两个开关则是"拨动即生效"，不需要额外保存——拨动的瞬间
 * 就直接写入 ConfigProvider。
 *
 * "低电量提醒"依赖"状态栏电量显示修改"：提醒文案里报的是换算后的电量，
 * 如果显示开关是关的，状态栏显示的还是系统原始电量，两个数字对不上会
 * 让人以为出了问题。所以关掉显示开关时，提醒开关会强制摆回"关"（视觉
 * 上灰掉禁用 + 底层配置一起清成关闭，不是只灰不改状态）；重新打开显示
 * 开关后，提醒开关变回可点，但默认仍是关的，需要用户手动再开一次，
 * 不做"自动恢复成之前开着的状态"这种隐式行为。
 *
 * 界面上方的换算对照表不是静态示例：打开界面时按当前已保存的关机点计算，
 * 点"保存"成功后按刚保存的新数值重新计算；换算逻辑调用 BatteryCalc，
 * 和 MainHook 里状态栏实际显示用的是同一份算法，不会出现两边对不上的情况。
 * 如果保存时输入校验没通过，表格维持上一次成功保存的状态，不会被清空。
 */
public class MainActivity extends Activity {

    // 换算对照表左列（系统原始电量）的固定参照值，从高到低，和布局里的行顺序一一对应
    private static final int[] TABLE_ORIGINAL_LEVELS = {100, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0};

    private EditText editShutdownPoint;
    private Switch switchDisplayEnabled;
    private Switch switchReminderEnabled;
    private TextView[] tableResultViews;

    // 加载配置、以及联动强制关闭提醒开关时，用这个标记跳过监听器，
    // 避免程序化 setChecked() 触发一次多余/错误的写入
    private boolean suppressListener = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editShutdownPoint = findViewById(R.id.edit_shutdown_point);
        switchDisplayEnabled = findViewById(R.id.switch_display_enabled);
        switchReminderEnabled = findViewById(R.id.switch_reminder_enabled);
        Button buttonSaveShutdownPoint = findViewById(R.id.button_save_shutdown_point);

        tableResultViews = new TextView[]{
                findViewById(R.id.result_100),
                findViewById(R.id.result_90),
                findViewById(R.id.result_80),
                findViewById(R.id.result_70),
                findViewById(R.id.result_60),
                findViewById(R.id.result_50),
                findViewById(R.id.result_40),
                findViewById(R.id.result_30),
                findViewById(R.id.result_20),
                findViewById(R.id.result_10),
                findViewById(R.id.result_0),
        };

        buttonSaveShutdownPoint.setOnClickListener(v -> saveShutdownPoint());

        // 底部链接：标题行和网址行点任意一个都跳转到同一个链接
        bindLinkClick(R.id.link_project_home, R.string.link_project_url);
        bindLinkClick(R.id.link_project_url, R.string.link_project_url);
        bindLinkClick(R.id.link_author_home, R.string.link_author_url);
        bindLinkClick(R.id.link_author_url, R.string.link_author_url);

        switchDisplayEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressListener) {
                return;
            }
            writeBoolean(ConfigProvider.COL_DISPLAY_ENABLED, isChecked);

            if (!isChecked) {
                // 显示开关关闭：低电量提醒强制摆回"关"，视觉 + 配置一起处理
                suppressListener = true;
                switchReminderEnabled.setChecked(false);
                suppressListener = false;
                writeBoolean(ConfigProvider.COL_REMINDER_ENABLED, false);
                switchReminderEnabled.setEnabled(false);
            } else {
                // 重新打开显示开关：提醒开关恢复可点，但保持关闭状态，不自动恢复
                switchReminderEnabled.setEnabled(true);
            }
        });

        switchReminderEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressListener) {
                return;
            }
            writeBoolean(ConfigProvider.COL_REMINDER_ENABLED, isChecked);
        });

        loadConfig();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadConfig();
    }

    private void loadConfig() {
        try (Cursor cursor = getContentResolver().query(
                ConfigProvider.CONTENT_URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int shutdownPoint = cursor.getInt(
                        cursor.getColumnIndexOrThrow(ConfigProvider.COL_SHUTDOWN_POINT));
                boolean displayEnabled = cursor.getInt(
                        cursor.getColumnIndexOrThrow(ConfigProvider.COL_DISPLAY_ENABLED)) != 0;
                boolean reminderEnabled = cursor.getInt(
                        cursor.getColumnIndexOrThrow(ConfigProvider.COL_REMINDER_ENABLED)) != 0;

                editShutdownPoint.setText(String.valueOf(shutdownPoint));

                suppressListener = true;
                switchDisplayEnabled.setChecked(displayEnabled);
                // 防御性处理：正常情况下 display=false 时 reminder 一定已经是 false
                // （由联动逻辑保证），这里再夹一层，避免配置被外部直接改坏时界面显示不一致
                switchReminderEnabled.setChecked(displayEnabled && reminderEnabled);
                switchReminderEnabled.setEnabled(displayEnabled);
                suppressListener = false;

                refreshTable(shutdownPoint);
            }
        } catch (Exception e) {
            Toast.makeText(this, "读取配置失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveShutdownPoint() {
        String text = editShutdownPoint.getText().toString().trim();
        int shutdownPoint;
        try {
            shutdownPoint = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "关机点请输入 0~99 之间的数字", Toast.LENGTH_SHORT).show();
            return; // 校验不通过：不写入配置，表格也维持上一次成功保存的状态
        }
        if (shutdownPoint < 0 || shutdownPoint > 99) {
            Toast.makeText(this, "关机点请输入 0~99 之间的数字", Toast.LENGTH_SHORT).show();
            return;
        }

        ContentValues values = new ContentValues();
        values.put(ConfigProvider.COL_SHUTDOWN_POINT, shutdownPoint);
        int count = getContentResolver().update(ConfigProvider.CONTENT_URI, values, null, null);
        if (count > 0) {
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
            refreshTable(shutdownPoint); // 保存成功，按刚保存的新数值重新计算表格
        } else {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 按给定的关机点重算换算对照表的右列（更改显示后），调用和 MainHook 相同的 BatteryCalc。 */
    private void refreshTable(int shutdownPoint) {
        for (int i = 0; i < TABLE_ORIGINAL_LEVELS.length; i++) {
            int displayLevel = BatteryCalc.calcDisplayLevel(TABLE_ORIGINAL_LEVELS[i], shutdownPoint);
            tableResultViews[i].setText(displayLevel + "%");
        }
    }

    /** 给指定 View 绑定点击事件：点击后用系统默认浏览器打开 urlResId 对应的链接。 */
    private void bindLinkClick(int viewId, int urlResId) {
        View view = findViewById(viewId);
        view.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(urlResId)));
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.toast_open_link_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void writeBoolean(String column, boolean value) {
        ContentValues values = new ContentValues();
        values.put(column, value ? 1 : 0);
        int count = getContentResolver().update(ConfigProvider.CONTENT_URI, values, null, null);
        if (count <= 0) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }
}
