# 盯屏提醒 · ScreenWatch

> Android 10 悬浮窗 + 每分钟截图对比。画面像素变化超过阈值时，播放你选择的本地音乐，响铃 3 分钟后自动停止。

[![Release](https://img.shields.io/github/v/release/lishaoming314/screenwatch-android?display_name=tag&sort=semver)](https://github.com/lishaoming314/screenwatch-android/releases)
[![License](https://img.shields.io/github/license/lishaoming314/screenwatch-android)](LICENSE)
![minSdk](https://img.shields.io/badge/minSdk-26-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-29-blue)

---

## 功能

- **悬浮窗**：可拖动的小卡片，显示倒计时、上次变化百分比，带「截图 / 暂停 / 关闭」按钮。
- **定时截图**：MediaProjection 屏幕捕获，默认每 1 分钟一帧（可调 1–10 分钟）。
- **只保留两张截图**：内存里仅保留「上一帧 / 当前帧」采样；缓存目录 `snapshots/prev.jpg`、`snapshots/cur.jpg` 每轮覆盖，永远只有两张。
- **像素变化占比**：截图缩到最长边 480px，再采样成 48×48 网格，逐格比较 RGB；单通道差 > 24 记为「变化」，`变化格数 / 总格数 × 100%` 即占比。
- **自动响铃**：占比 > 阈值（默认 15%，可调 5%–50%）→ 用**闹钟音量**循环播放本地音乐，180 秒后自动停止；悬浮卡片或通知里可提前停铃。
- **后台保活**：mediaProjection 前台服务 + 常驻通知 + 电池优化豁免引导 + 响铃期间唤醒锁。
- **减少误报**：锁屏 / 熄屏不截图，亮屏后第一帧只作基准；屏幕旋转后重建镜像并重置基准。

## 工作原理

```
 MediaProjection ──► VirtualDisplay (≤480px) ──► ImageReader ──► Bitmap
                                                                  │
                                          48×48 采样 ◄────────────┘
                                                │
                             上一帧 ──► PixelDiff ──► 变化占比 %
                                                │
              悬浮窗 / 通知 ◄── 状态           > 阈值? ──► MediaPlayer（3 分钟）
```

- 截图与比较全部在本地完成，不上传任何画面。
- 截图通过系统录屏接口获取，**每次开始都需要你在系统弹窗里点一次「立即开始」**（Android 10 的硬限制）。

## 安装

1. 打开 [Releases](https://github.com/lishaoming314/screenwatch-android/releases)，下载 `ScreenWatch-1.0.apk`。
2. 在手机上允许「安装未知来源应用」，安装即可。
3. 首次打开按应用内提示逐项授权（见下）。

> 系统要求：Android 8.0+（API 26+），在 Android 10（API 29）上开发与验证目标。

## 首次使用

1. 打开「盯屏提醒」。
2. **悬浮窗权限** → 去设置 → 允许显示在其他应用上层。
3. **电池优化豁免** → 去设置 → 允许（Android 10 上必须，否则后台会被冻结）。
4. **本地音乐** → 选择音频文件（支持试听 6 秒）。
5. 按需调整阈值 / 截图间隔，点「开始监控」。
6. 系统弹出「立即开始录制或投放屏幕？」→ 点 **立即开始**。
7. 出现悬浮卡片即开始工作：第一分钟建立基准，第二分钟起给出变化百分比。

## 权限说明

| 权限 / 设置 | 用途 |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | 悬浮状态卡片 |
| `FOREGROUND_SERVICE` + `android:foregroundServiceType="mediaProjection"` | Android 10 截屏必须运行在对应类型的前台服务中 |
| `WAKE_LOCK` | 响铃 3 分钟期间保持 CPU 唤醒 |
| `VIBRATE` | 未选择音乐时的震动提示 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 申请电池优化豁免（后台活动） |
| 屏幕捕获授权（系统弹窗） | 用户每次手动允许后才可截屏 |

小米 / OPPO / vivo / 华为等还需要在系统设置里允许「自启动 / 后台弹出界面 / 锁屏显示」，否则后台可能被杀。

## 构建

需要：

- JDK 17 或 21
- Android SDK **platform 29**（`android.jar`）
- Android **build-tools**（`aapt2`、`zipalign`、`apksigner`；若缺少 `d8`，脚本会自动从 Google Maven 下载 `r8.jar`）

```bash
./build.sh
# 产物：build/ScreenWatch-1.0.apk（已签名，可直接安装）
```

首次构建会生成 `keystore/debug.keystore`（密码 `android`）。换机器构建会得到不同签名，覆盖安装前需要先卸载旧版。

算法自测（纯 Java，无需 Android 设备）：

```bash
javac -d /tmp/pd src/com/dsh/screenwatch/PixelDiff.java
java -cp /tmp/pd com.dsh.screenwatch.PixelDiff
```

## 项目结构

```
.
├── AndroidManifest.xml
├── build.sh                      # 无 Gradle 构建脚本：aapt2 + javac + d8 + apksigner
├── res/                          # 自适应图标
└── src/com/dsh/screenwatch/
    ├── MainActivity.java         # 权限检查、选音乐、参数、开始/停止
    ├── ScreenWatchService.java   # 前台服务：截图、对比、响铃、通知、悬浮窗
    ├── OverlayController.java    # 可拖动悬浮卡片
    └── PixelDiff.java            # 像素变化占比算法（可单独测试）
```

## 常见问题

**为什么每次开始都要授权录屏？**
Android 10 起，MediaProjection 授权是一次性的，无法永久保存；应用只能每次由用户确认。

**为什么没有声音？**
音乐走闹钟音量通道：请确认闹钟音量不为 0，勿扰模式下允许闹钟；也可以在应用里先点「试听 6 秒」。

**会被系统杀掉吗？**
已使用前台服务并引导电池优化豁免；国产 ROM 还需手动允许自启动 / 后台活动。

**截图保存在哪？**
应用私有缓存 `snapshots/` 下，只有 `prev.jpg` 和 `cur.jpg` 两张，卸载应用即清除。

## 隐私

- 截图仅在设备本地处理，不上传、不联网。
- 应用没有网络权限。

## License

[MIT](LICENSE) © 2026 lishaoming314
