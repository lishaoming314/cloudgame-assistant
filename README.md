# 云游戏助手 · Cloud Game Assistant

> 盯着云游戏的排队画面：用**像素对比**和/或**离线 OCR 文字对比**判断是否排到队、进入游戏，变化够大就播放本地音乐提醒，响铃 3 分钟后停止。悬浮球实时显示下次截图倒计时。

[![Release](https://img.shields.io/github/v/release/lishaoming314/cloudgame-assistant?display_name=tag&sort=semver)](https://github.com/lishaoming314/cloudgame-assistant/releases)
[![License](https://img.shields.io/github/license/lishaoming314/cloudgame-assistant)](LICENSE)
![minSdk](https://img.shields.io/badge/minSdk-26-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-29-blue)

作者：**理烧明** · [B 站主页](https://space.bilibili.com/1416380260)

---

## 功能

- **悬浮倒计时球**：60dp 渐变圆球，实时显示距下次截图的秒数；点一下展开面板（截图 / 暂停 / 停止 / 收起），可拖动到任意位置。
- **自定义主题色**：应用内有一枚 HSV 色球，拖动选色后悬浮球、按钮、通知会实时跟着变（图标保持渐变云朵）。
- **双通道识别（独立开关）**：
  - **像素变化**：每次截图与上一张按 48×48 网格比较 RGB，单通道差 > 24 记为变化格，算出变化占比。
  - **OCR 文字变化**：内置 Tesseract + 中文/英文模型，完全离线；识别两次截图的文字，按字符级编辑距离算出文字变化百分比。
  - 两个开关可单开或同开；同开时可选「需同时满足」（默认）降低误报，或「任一满足」提高灵敏度。
- **自动响铃**：像素占比 > 15%（可调）或文字变化 > 30%（可调 10%–90%）→ 闹钟音量循环播放你选的本地音乐，180 秒后自动停止。
- **提醒后自动停止**（可关）：触发后暂停截图，响铃结束即停止监测，避免进入游戏后每分钟重复响铃。
- **截图间隔 5 秒–10 分钟**：滑杆按秒调节，默认 60 秒；开启 OCR 时建议 ≥10 秒（每帧识别需要 1–3 秒）。
- **只保留两张截图**：上一张 / 当前这张（缓存 `snapshots/prev.jpg`、`cur.jpg`，每轮覆盖）。
- **完全离线**：OCR 模型随 APK 打包，不联网、不上传。
- **后台保活**：mediaProjection 前台服务 + 常驻通知 + 电池优化豁免引导 + 响铃唤醒锁。
- **减少误报**：锁屏 / 熄屏不截图，亮屏后第一张只作基准；屏幕旋转后重建镜像并重置基准。

## 工作原理

```
 MediaProjection ──► VirtualDisplay (≤480px) ──► ImageReader ──► Bitmap
                                                                  │
                                          48×48 采样 ◄────────────┘
                                                │
                          上一张 ──► PixelDiff ──► 像素占比 %  ─┐
                                                │              ├─► 触发判断 ──► MediaPlayer
                    上一帧文字 ──► TextDiff ──► 文字变化 % ──────┘     （3 分钟）
                                                │
         悬浮球倒计时 ◄── 状态                阈值可分别设置
```

- 截图与比较全部在本地完成，不上传任何画面。
- 截图通过系统录屏接口获取，**每次开始都需要你在系统弹窗里点一次「立即开始」**（Android 10 的限制）。

## 安装

1. 打开 [Releases](https://github.com/lishaoming314/cloudgame-assistant/releases)，下载最新的 `CloudGameAssistant-2.1.1.apk`（约 11 MB）。
2. 允许「安装未知来源应用」后安装。
3. 首次打开按应用内提示逐项授权。

> 系统要求：Android 8.0+（API 26+），开发与验证目标为 Android 10（API 29）。

## 使用

1. 打开「云游戏助手」。
2. **悬浮窗权限** → 允许显示在其他应用上层。
3. **电池优化豁免** → 允许（Android 10 上必须，否则后台会被冻结）。
4. **本地音乐** → 选一个音频文件，可「试听 6 秒」。
5. 设置**变化阈值**、**截图间隔**（5 秒起），在「识别方式」里选择像素 / OCR / 两者同时，并按需调整文字阈值与「需同时满足」。
6. 点「开始监测」→ 系统弹窗点 **立即开始**。
7. 悬浮球开始倒计时。排队时数字变化、画面稳定；排到队/进入游戏时画面大幅变化 → 响铃 3 分钟。

## 权限说明

| 权限 / 设置 | 用途 |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | 悬浮倒计时球与展开面板 |
| `FOREGROUND_SERVICE` + `foregroundServiceType="mediaProjection"` | Android 10 截屏必须运行在对应类型的前台服务中 |
| `WAKE_LOCK` | 响铃 3 分钟期间保持 CPU 唤醒 |
| `VIBRATE` | 未选择音乐时的震动提示 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 申请电池优化豁免（后台活动） |
| 屏幕捕获授权（系统弹窗） | 用户每次手动允许后才可截屏 |

小米 / OPPO / vivo / 华为等还需在系统设置里允许「自启动 / 后台弹出界面 / 锁屏显示」，否则后台可能被杀。

## 构建

需要：

- JDK 17 或 21
- Android SDK **platform 29**（`android.jar`）
- Android **build-tools**（`aapt2`、`zipalign`、`apksigner`；缺少 `d8` 时脚本会自动下载新版 `r8.jar`）
- 首次构建会自动下载 OCR 依赖（Tesseract4Android 约 13 MB + 中英文模型约 6 MB）

```bash
./build.sh
# 产物：build/CloudGameAssistant-2.1.1.apk（约 11 MB，已签名）
```

OCR 依赖由 `fetch-ocr.sh` 自动下载；也可以手动执行：

```bash
./fetch-ocr.sh
```

首次构建会生成 `keystore/debug.keystore`（密码 `android`），换机器构建签名不同，覆盖安装前先卸载旧版。

算法自测（无需 Android 设备）：

```bash
javac -d /tmp/pd src/com/dsh/screenwatch/PixelDiff.java
java -cp /tmp/pd com.dsh.screenwatch.PixelDiff

javac -d /tmp/td src/com/dsh/screenwatch/TextDiff.java
java -cp /tmp/td com.dsh.screenwatch.TextDiff
```

## 项目结构

```
.
├── AndroidManifest.xml
├── build.sh                      # 无 Gradle 构建脚本：aapt2 + javac + d8 + apksigner
├── fetch-ocr.sh                  # 下载 Tesseract4Android + 中英文模型
├── res/                          # 渐变自适应图标 + 通知小图标
├── assets/tessdata/              # OCR 模型（构建时下载）
└── src/com/dsh/screenwatch/
    ├── MainActivity.java         # 权限、选音乐、像素/OCR 开关、参数、作者入口
    ├── ScreenWatchService.java   # 前台服务：截图、双通道对比、响铃、自动停止
    ├── OverlayController.java    # 悬浮倒计时球 + 展开面板
    ├── ColorBallView.java        # HSV 色球控件（主题色）
    ├── OcrEngine.java            # Tesseract 离线 OCR 封装
    ├── TextDiff.java             # 文字变化占比算法（可单独测试）
    └── PixelDiff.java            # 像素变化占比算法（可单独测试）
```

## 常见问题

**为什么每次开始都要授权录屏？**
Android 10 起 MediaProjection 授权是一次性的，无法永久保存。

**为什么没有声音？**
音乐走闹钟音量通道：确认闹钟音量不为 0，勿扰模式允许闹钟；也可先点「试听 6 秒」。

**排队中会误报吗？**
排队界面一般很稳定；如果某个云游戏排队页动效很大，把像素阈值调高（如 25%–35%）、调长间隔，或开启 OCR 并选「需同时满足」。

**开启 OCR 后为什么变慢/更耗电？**
OCR 每帧要在本地跑 Tesseract 中文识别，约 1–3 秒；建议间隔 ≥10 秒。像素和 OCR 都关掉时应用不允许开始。

**会被系统杀掉吗？**
已用前台服务并引导电池优化豁免；国产 ROM 还需允许自启动 / 后台活动。

**截图保存在哪？**
应用私有缓存 `snapshots/`，只有 `prev.jpg` 和 `cur.jpg`，卸载即清除。

## 隐私

- 截图仅在设备本地处理，不上传、不联网。
- 应用没有网络权限。

## License

[MIT](LICENSE) © 2026 理烧明
