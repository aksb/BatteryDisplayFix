# 修改电池百分比显示

由于本人红米K40S手机电池故障，20% 以下会直接关机，但是电池还很耐用舍不得换掉。所以用AI写了个LSPosed模块，干脆把 20% (关机点)当作"0%"，

另外，如果手机没有root和LSPosed，有“要校正电量”的需求，可以尝试用“KWGT”桌面小部件，添加文字，输入$mu(round, mu(max, 0, (bi(level) - 20) / 0.8))$%公式来”校正“。

LSPosed 模块：把状态栏 / 锁屏 显示的电量百分比**文字**，
按你自己填的"关机点"重新计算显示，不改真实电量、不改电量条图形；
另外支持一个可开关的低电量提醒。

```
显示值 = 原始电量 <= x ? 0 : round((原始电量 - x) / (100 - x) * 100)
```

x 是"实际电量关机点"，在 App 设置界面里填，默认 20（结果会夹在 0~100 之间）。

适用场景：电池老化，到某个百分比就会直接关机，所以干脆把这个百分比当作
"0%"，剩余可用区间重新拉伸显示。

## 目标环境

- 只在本人一台红米K40S PixelExperience Office，Android 13测试通过
- 目标进程：`com.android.systemui`
- 目标类：`com.android.systemui.statusbar.policy.BatteryMeterView`
  （或 Android 12+ 重构后的 `com.android.systemui.battery.BatteryMeterView`，
  代码里两个都会尝试）

## 安全性说明

- 本模块不修改任何系统文件，只在 System UI 进程运行期间通过 LSPosed 动态
  挂钩，不生效或者卸载后，系统状态会恢复原样。
- 如果 hook 逻辑触发异常导致 System UI 反复崩溃，可以通过 LSPosed Manager
  关闭本模块作用域，或进入安全模式（Safe Mode）卸载本模块来恢复，不会
  造成系统层面的永久性损坏。
- `ConfigProvider` 是 `exported="true"` 且不设权限校验的，因为这是给自己
  单台设备用的本地工具；如果你打算分享给别人用，上线前应该重新评估权限
  设置（比如加 `android:permission` 限制谁能读写）。
