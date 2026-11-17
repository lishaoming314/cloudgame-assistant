package com.dsh.screenwatch;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final String BILI_SPACE = "https://space.bilibili.com/1416380260";
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_MUSIC = 1002;
    private static final int REQ_NOTIF = 1003;

    private static final int BG = 0xFFF5F7FB;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TEXT = 0xFF17202B;
    private static final int MUTED = 0xFF6B7785;
    private static final int BLUE = 0xFF2F80ED;
    private static final int RED = 0xFFE5484D;
    private static final int GREEN = 0xFF1FA55A;

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView overlayState, batteryState, notifState, musicState;
    private TextView thresholdLabel, intervalLabel, statusLine, ratioLine, runLine;
    private Button startBtn, auditionBtn;
    private SeekBar thresholdBar, intervalBar;
    private int threshold = 15;
    private int intervalSec = 60;
    private boolean autoStop = true;
    private Switch autoStopSwitch, pixelSwitch, ocrSwitch, bothSwitch;
    private SeekBar ocrBar;
    private TextView ocrLabel;
    private boolean usePixel = true;
    private boolean useOcr = false;
    private boolean requireBoth = true;
    private int ocrThreshold = 30;
    /** 0 = music, 1 = vibration, 2 = both. */
    private int alertMode = 0;
    private int firstDelaySec = 2;
    private int accent = 0xFFFF6A00;
    private TextView themePreview;
    private TextView cloudAppState;
    private TextView titleView;
    private View homeView;
    private View settingsView;
    private boolean settingsShown;
    private final Runnable themePush = new Runnable() {
        @Override
        public void run() {
            if (!ScreenWatchService.isRunning()) {
                return;
            }
            try {
                startService(new Intent(MainActivity.this, ScreenWatchService.class)
                        .setAction(ScreenWatchService.ACTION_SET_THEME)
                        .putExtra(ScreenWatchService.EXTRA_COLOR, accent));
            } catch (Throwable ignored) {
            }
        }
    };

    private MediaPlayer audition;
    private BroadcastReceiver statsReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ScreenWatchService.PREF, MODE_PRIVATE);
        threshold = Math.max(5, Math.min(50, prefs.getInt(ScreenWatchService.KEY_THRESHOLD, 15)));
        intervalSec = Math.max(5, Math.min(3600, prefs.getInt(ScreenWatchService.KEY_INTERVAL, 60)));
        autoStop = prefs.getBoolean("auto_stop", true);
        usePixel = prefs.getBoolean("use_pixel", true);
        useOcr = prefs.getBoolean("use_ocr", false);
        requireBoth = prefs.getBoolean("require_both", true);
        ocrThreshold = Math.max(10, Math.min(90, prefs.getInt("ocr_threshold", 30)));
        alertMode = Math.max(0, Math.min(2, prefs.getInt("alert_mode", 0)));
        firstDelaySec = Math.max(0, Math.min(60, prefs.getInt("first_delay_sec", 2)));
        accent = prefs.getInt("accent_color", 0xFFFF6A00);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissions();
        refreshMusic();
        refreshRunState();
        refreshCloudApp();
        if (statsReceiver == null) {
            statsReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    float ratio = intent.getFloatExtra(ScreenWatchService.EXTRA_RATIO, 0f);
                    float textRatio = intent.getFloatExtra(ScreenWatchService.EXTRA_TEXT_RATIO, 0f);
                    boolean ringing = intent.getBooleanExtra(ScreenWatchService.EXTRA_RINGING, false);
                    boolean paused = intent.getBooleanExtra(ScreenWatchService.EXTRA_PAUSED, false);
                    String status = intent.getStringExtra(ScreenWatchService.EXTRA_STATUS);
                    ratioLine.setText(useOcr
                            ? String.format(Locale.US, "上次变化：像素 %.1f%% · 文字 %.0f%%", ratio, textRatio)
                            : String.format(Locale.US, "上次变化：%.1f%%（阈值 %d%%）", ratio, threshold));
                    if (status != null) {
                        statusLine.setText(status);
                    }
                    runLine.setText(ringing ? "状态：正在响铃" : paused ? "状态：已暂停" : "状态：排队监测中");
                    startBtn.setText(ScreenWatchService.isRunning() ? "停止监测" : "开始监测");
                    styleStartButton(ScreenWatchService.isRunning());
                }
            };
            registerReceiver(statsReceiver, new IntentFilter(ScreenWatchService.ACTION_STATS));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (statsReceiver != null) {
            try {
                unregisterReceiver(statsReceiver);
            } catch (Throwable ignored) {
            }
            statsReceiver = null;
        }
        stopAudition();
    }

    @Override
    protected void onDestroy() {
        stopAudition();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- ui

    private View buildUi() {
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(BG);

        // ================= home =================
        ScrollView homeScroll = new ScrollView(this);
        homeScroll.setBackgroundColor(BG);
        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        home.setPadding(pad, dp(26), pad, dp(24));
        homeScroll.addView(home, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(homeScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        homeView = homeScroll;

        TextView title = text("云游戏助手", 26, TEXT, Typeface.BOLD);
        titleView = title;
        home.addView(title);
        TextView sub = text("排队监测 · 像素 / OCR 双识别", 13, MUTED, Typeface.NORMAL);
        sub.setPadding(0, dp(6), 0, dp(20));
        home.addView(sub);

        startBtn = new Button(this);
        startBtn.setText("开始监测");
        startBtn.setAllCaps(false);
        startBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        startBtn.setTextColor(Color.WHITE);
        startBtn.setOnClickListener(v -> {
            if (ScreenWatchService.isRunning()) {
                stopWatch();
            } else {
                beginWatch();
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        home.addView(startBtn, bl);
        styleStartButton(false);

        LinearLayout recog = card();
        recog.addView(sectionTitle("识别方式"));
        pixelSwitch = addSwitchRow(recog, "像素变化识别", usePixel, (b, checked) -> {
            usePixel = checked;
            prefs.edit().putBoolean("use_pixel", checked).apply();
        });
        ocrSwitch = addSwitchRow(recog, "OCR 文字变化识别（离线）", useOcr, (b, checked) -> {
            useOcr = checked;
            prefs.edit().putBoolean("use_ocr", checked).apply();
        });
        TextView recogTip = text("阈值与「同时满足」在更多设置里", 12, MUTED, Typeface.NORMAL);
        recogTip.setPadding(0, dp(6), 0, dp(2));
        recog.addView(recogTip);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(18);
        home.addView(recog, rlp);

        LinearLayout alert = card();
        alert.addView(sectionTitle("提醒方式"));
        RadioGroup alertGroup = new RadioGroup(this);
        alertGroup.setId(View.generateViewId());
        alertGroup.setOrientation(RadioGroup.VERTICAL);
        String[] alertLabels = {"播放音乐（响铃 3 分钟）", "连续震动（持续 3 分钟）", "音乐 + 震动（同时）"};
        for (int k = 0; k < alertLabels.length; k++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(alertLabels[k]);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            rb.setPadding(0, dp(4), 0, dp(4));
            alertGroup.addView(rb);
        }
        ((RadioButton) alertGroup.getChildAt(alertMode)).setChecked(true);
        alertGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int idx = group.indexOfChild(group.findViewById(checkedId));
            if (idx >= 0) {
                alertMode = idx;
                prefs.edit().putInt("alert_mode", idx).apply();
            }
        });
        alert.addView(alertGroup);
        home.addView(alert);

        Button more = new Button(this);
        more.setText("更多设置 ›");
        more.setAllCaps(false);
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        more.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        mlp.topMargin = dp(4);
        home.addView(more, mlp);

        // ================= settings (secondary page) =================
        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.setBackgroundColor(BG);
        settingsScroll.setVisibility(View.GONE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(18), pad, dp(28));
        settingsScroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(settingsScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        settingsView = settingsScroll;

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, 0, 0, dp(10));
        TextView back = text("‹ 返回首页", 14, BLUE, Typeface.BOLD);
        back.setPadding(0, dp(4), 0, dp(8));
        back.setOnClickListener(v -> showHome());
        header.addView(back);
        header.addView(text("设置", 22, TEXT, Typeface.BOLD));
        root.addView(header);

        // ---- permissions
        LinearLayout perm = card();
        perm.addView(sectionTitle("权限与后台运行"));
        overlayState = addCheckRow(perm, "悬浮窗权限", "在其它应用上显示倒计时悬浮球",
                v -> openOverlaySettings());
        batteryState = addCheckRow(perm, "电池优化豁免", "不被系统杀后台（Android 10 必需）",
                v -> openBatterySettings());
        if (Build.VERSION.SDK_INT >= 33) {
            notifState = addCheckRow(perm, "通知权限", "常驻通知显示运行状态",
                    v -> requestNotifPermission());
        }
        root.addView(perm);

        // ---- music
        LinearLayout music = card();
        music.addView(sectionTitle("本地音乐"));
        musicState = text("尚未选择音乐", 13, MUTED, Typeface.NORMAL);
        musicState.setPadding(0, dp(4), 0, dp(10));
        music.addView(musicState);
        LinearLayout musicRow = new LinearLayout(this);
        musicRow.setOrientation(LinearLayout.HORIZONTAL);
        Button pick = new Button(this);
        pick.setText("选择音乐文件");
        pick.setAllCaps(false);
        pick.setOnClickListener(v -> pickMusic());
        musicRow.addView(pick, new LinearLayout.LayoutParams(0, dp(44), 1f));
        auditionBtn = new Button(this);
        auditionBtn.setText("试听 6 秒");
        auditionBtn.setAllCaps(false);
        auditionBtn.setOnClickListener(v -> audition());
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0, dp(44), 1f);
        al.leftMargin = dp(8);
        musicRow.addView(auditionBtn, al);
        music.addView(musicRow);
        root.addView(music);

        // ---- home-screen widget
        LinearLayout widgetCard = card();
        widgetCard.addView(sectionTitle("桌面快捷卡片"));
        cloudAppState = text("未选择云游戏应用", 13, MUTED, Typeface.NORMAL);
        cloudAppState.setPadding(0, dp(4), 0, dp(10));
        widgetCard.addView(cloudAppState);
        Button chooseApp = new Button(this);
        chooseApp.setText("选择云游戏应用");
        chooseApp.setAllCaps(false);
        chooseApp.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        widgetCard.addView(chooseApp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        Button pinWidget = new Button(this);
        pinWidget.setText("添加到桌面");
        pinWidget.setAllCaps(false);
        pinWidget.setOnClickListener(v -> requestPinWidget());
        LinearLayout.LayoutParams pwl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        pwl.topMargin = dp(6);
        widgetCard.addView(pinWidget, pwl);
        TextView widgetTip = text("长按桌面卡片拖动边框可改大小；点按会启动选中的应用，并出现「开启」悬浮球。", 12, MUTED, Typeface.NORMAL);
        widgetTip.setPadding(0, dp(6), 0, dp(4));
        widgetCard.addView(widgetTip);
        root.addView(widgetCard);

        // ---- recognition advanced
        LinearLayout recogAdv = card();
        recogAdv.addView(sectionTitle("识别高级设置"));
        bothSwitch = addSwitchRow(recogAdv, "两个都开时：需同时满足", requireBoth, (b, checked) -> {
            requireBoth = checked;
            prefs.edit().putBoolean("require_both", checked).apply();
        });
        ocrLabel = text("文字变化阈值：" + ocrThreshold + "%", 14, TEXT, Typeface.BOLD);
        ocrLabel.setPadding(0, dp(10), 0, 0);
        recogAdv.addView(ocrLabel);
        ocrBar = new SeekBar(this);
        ocrBar.setMax((90 - 10) / 5);
        ocrBar.setProgress(Math.max(0, (ocrThreshold - 10) / 5));
        ocrBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                ocrThreshold = 10 + progress * 5;
                ocrLabel.setText("文字变化阈值：" + ocrThreshold + "%");
                prefs.edit().putInt("ocr_threshold", ocrThreshold).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        recogAdv.addView(ocrBar);
        TextView ocrTip = text("OCR 首次加载模型约 1–3 秒，每帧识别更耗电；建议截图间隔 ≥10 秒。", 12, MUTED, Typeface.NORMAL);
        ocrTip.setPadding(0, dp(6), 0, dp(4));
        recogAdv.addView(ocrTip);
        root.addView(recogAdv);

        // ---- parameters
        LinearLayout params = card();
        params.addView(sectionTitle("监测参数"));
        thresholdLabel = text("像素变化阈值：" + threshold + "%", 14, TEXT, Typeface.BOLD);
        thresholdLabel.setPadding(0, dp(6), 0, 0);
        params.addView(thresholdLabel);
        thresholdBar = new SeekBar(this);
        thresholdBar.setMax(45);
        thresholdBar.setProgress(Math.max(0, threshold - 5));
        thresholdBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                threshold = 5 + progress;
                thresholdLabel.setText("像素变化阈值：" + threshold + "%");
                prefs.edit().putInt(ScreenWatchService.KEY_THRESHOLD, threshold).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        params.addView(thresholdBar);

        intervalLabel = text("截图间隔：" + formatInterval(intervalSec), 14, TEXT, Typeface.BOLD);
        intervalLabel.setPadding(0, dp(10), 0, 0);
        params.addView(intervalLabel);
        intervalBar = new SeekBar(this);
        intervalBar.setMax((600 - 5) / 5);
        intervalBar.setProgress(Math.max(0, (intervalSec - 5) / 5));
        intervalBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                intervalSec = 5 + progress * 5;
                intervalLabel.setText("截图间隔：" + formatInterval(intervalSec));
                prefs.edit().putInt(ScreenWatchService.KEY_INTERVAL, intervalSec).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        params.addView(intervalBar);

        TextView firstDelayLabel = text("首次截图延迟：" + firstDelaySec + " 秒", 14, TEXT, Typeface.BOLD);
        firstDelayLabel.setPadding(0, dp(10), 0, 0);
        params.addView(firstDelayLabel);
        SeekBar firstDelayBar = new SeekBar(this);
        firstDelayBar.setMax(60);
        firstDelayBar.setProgress(firstDelaySec);
        firstDelayBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                firstDelaySec = progress;
                firstDelayLabel.setText("首次截图延迟：" + firstDelaySec + " 秒");
                prefs.edit().putInt("first_delay_sec", firstDelaySec).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        params.addView(firstDelayBar);
        TextView firstDelayTip = text("点开始后等这么久截第一张（只作基准），下一张才出变化百分比。", 12, MUTED, Typeface.NORMAL);
        firstDelayTip.setPadding(0, dp(2), 0, 0);
        params.addView(firstDelayTip);

        LinearLayout autoRow = new LinearLayout(this);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRow.setPadding(0, dp(10), 0, 0);
        TextView autoLabel = text("提醒后自动停止监测", 14, TEXT, Typeface.BOLD);
        autoRow.addView(autoLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        autoStopSwitch = new Switch(this);
        autoStopSwitch.setChecked(autoStop);
        autoStopSwitch.setOnCheckedChangeListener((b, checked) -> {
            autoStop = checked;
            prefs.edit().putBoolean("auto_stop", checked).apply();
        });
        autoRow.addView(autoStopSwitch);
        params.addView(autoRow);
        TextView tip = text("只保留最近两张截图：上一张 / 当前这张（缓存 snapshots/prev.jpg、cur.jpg）。", 12, MUTED, Typeface.NORMAL);
        tip.setPadding(0, dp(8), 0, 0);
        params.addView(tip);
        root.addView(params);

        // ---- live status
        LinearLayout status = card();
        status.addView(sectionTitle("运行状态"));
        statusLine = text("状态：未启动", 14, TEXT, Typeface.BOLD);
        statusLine.setPadding(0, dp(6), 0, 0);
        status.addView(statusLine);
        ratioLine = text("最近变化：—", 13, MUTED, Typeface.NORMAL);
        ratioLine.setPadding(0, dp(4), 0, 0);
        status.addView(ratioLine);
        runLine = text("状态：—", 13, MUTED, Typeface.NORMAL);
        runLine.setPadding(0, dp(2), 0, dp(10));
        status.addView(runLine);
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        Button snapNow = new Button(this);
        snapNow.setText("立即截图");
        snapNow.setAllCaps(false);
        snapNow.setOnClickListener(v -> sendControl(ScreenWatchService.ACTION_CAPTURE_NOW));
        quick.addView(snapNow, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button stopRing = new Button(this);
        stopRing.setText("停止提醒");
        stopRing.setAllCaps(false);
        stopRing.setOnClickListener(v -> sendControl(ScreenWatchService.ACTION_STOP_RING));
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(0, dp(42), 1f);
        ql.leftMargin = dp(8);
        quick.addView(stopRing, ql);
        status.addView(quick);
        root.addView(status);

        // ---- theme colour
        LinearLayout theme = card();
        theme.addView(sectionTitle("主题色"));
        TextView themeHint = text("拖动色球选颜色：悬浮球、按钮和通知会同步更新", 12, MUTED, Typeface.NORMAL);
        themeHint.setPadding(0, dp(4), 0, dp(6));
        theme.addView(themeHint);
        ColorBallView wheel = new ColorBallView(this);
        wheel.setColor(accent);
        int wheelSize = dp(190);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(wheelSize, wheelSize);
        wlp.gravity = Gravity.CENTER_HORIZONTAL;
        wlp.topMargin = dp(4);
        theme.addView(wheel, wlp);
        themePreview = text(String.format(Locale.US, "当前主题色 #%06X", accent & 0xFFFFFF),
                13, TEXT, Typeface.BOLD);
        themePreview.setGravity(Gravity.CENTER);
        themePreview.setPadding(0, dp(8), 0, dp(6));
        theme.addView(themePreview);
        Button resetColor = new Button(this);
        resetColor.setText("恢复默认");
        resetColor.setAllCaps(false);
        resetColor.setOnClickListener(v -> {
            wheel.setColor(0xFFFF6A00);
            setAccentColor(0xFFFF6A00);
        });
        theme.addView(resetColor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        wheel.setListener(this::setAccentColor);
        root.addView(theme);

        // ---- about / author
        LinearLayout about = card();
        about.addView(sectionTitle("关于"));
        TextView ver = text("云游戏助手 v2.2.1 · 作者：理烧明", 13, MUTED, Typeface.NORMAL);
        ver.setPadding(0, dp(4), 0, dp(8));
        about.addView(ver);
        Button bili = new Button(this);
        bili.setText("打开 B 站主页");
        bili.setAllCaps(false);
        bili.setOnClickListener(v -> openUrl(BILI_SPACE));
        about.addView(bili, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        root.addView(about);

        TextView notes = text("提示：\n"
                + "• 像素与 OCR 是两个独立开关，可单开也可同开；同开时可选“需同时满足”降低误报。\n"
                + "• OCR 完全离线（Tesseract 中文模型），首次识别需加载模型，会慢 1–3 秒。\n"
                + "• 首次开始会弹出「立即开始录制或投放」的屏幕捕获授权，必须允许，否则无法截图。\n"
                + "• 提醒方式可选音乐、连续震动或两者同时；震动会循环 3 分钟。\n"
                + "• 音乐走「闹钟」音量通道：请把闹钟/媒体音量调高，勿扰模式下允许闹钟。\n"
                + "• 小米/OPPO/vivo 等还需在系统设置里允许「自启动 / 后台弹出界面 / 锁屏显示」。", 12, MUTED, Typeface.NORMAL);
        notes.setPadding(0, dp(16), 0, 0);
        root.addView(notes);

        return container;
    }

    private void showSettings() {
        settingsShown = true;
        homeView.setVisibility(View.GONE);
        settingsView.setVisibility(View.VISIBLE);
        refreshPermissions();
        refreshMusic();
        refreshRunState();
        refreshCloudApp();
    }

    private void showHome() {
        settingsShown = false;
        settingsView.setVisibility(View.GONE);
        homeView.setVisibility(View.VISIBLE);
        refreshRunState();
    }

    @Override
    public void onBackPressed() {
        if (settingsShown) {
            showHome();
            return;
        }
        super.onBackPressed();
    }

    private Switch addSwitchRow(LinearLayout parent, String label, boolean checked,
                                CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(2));
        TextView t = text(label, 14, TEXT, Typeface.BOLD);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    private TextView sectionTitle(String s) {
        return text(s, 15, TEXT, Typeface.BOLD);
    }

    private TextView addCheckRow(LinearLayout parent, String name, String desc, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView n = text(name, 14, TEXT, Typeface.BOLD);
        TextView d = text(desc, 12, MUTED, Typeface.NORMAL);
        col.addView(n);
        col.addView(d);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView state = text("未授权", 12, RED, Typeface.BOLD);
        row.addView(state, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button go = new Button(this);
        go.setText("去设置");
        go.setAllCaps(false);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        go.setOnClickListener(click);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(76), dp(38));
        gl.leftMargin = dp(8);
        row.addView(go, gl);
        parent.addView(row);
        return state;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(16));
        c.setBackground(g);
        c.setElevation(dp(2));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12);
        c.setLayoutParams(p);
        return c;
    }

    private TextView text(String s, float sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private void styleStartButton(boolean running) {
        if (startBtn == null) {
            return;
        }
        GradientDrawable g = new GradientDrawable();
        g.setColor(running ? RED : accent);
        g.setCornerRadius(dp(14));
        startBtn.setBackground(g);
        startBtn.setTextColor(running ? Color.WHITE : contrastText(accent));
    }

    private int contrastText(int color) {
        double lum = 0.299 * Color.red(color) + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color);
        return lum > 170 ? 0xFF17202B : Color.WHITE;
    }

    private void setAccentColor(int color) {
        accent = color;
        prefs.edit().putInt("accent_color", color).apply();
        if (themePreview != null) {
            themePreview.setText(String.format(Locale.US, "当前主题色 #%06X", color & 0xFFFFFF));
        }
        styleStartButton(ScreenWatchService.isRunning());
        ui.removeCallbacks(themePush);
        ui.postDelayed(themePush, 300);
    }

    private String formatInterval(int sec) {
        if (sec < 60) {
            return sec + " 秒";
        }
        if (sec % 60 == 0) {
            return (sec / 60) + " 分钟";
        }
        return (sec / 60) + " 分 " + (sec % 60) + " 秒";
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            toast("没有能打开链接的应用");
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- state refresh

    private void refreshPermissions() {
        boolean overlay = Settings.canDrawOverlays(this);
        overlayState.setText(overlay ? "已授权" : "未授权");
        overlayState.setTextColor(overlay ? GREEN : RED);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryState.setText(battery ? "已豁免" : "未豁免");
        batteryState.setTextColor(battery ? GREEN : RED);

        if (Build.VERSION.SDK_INT >= 33 && notifState != null) {
            boolean n = checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
            notifState.setText(n ? "已授权" : "未授权");
            notifState.setTextColor(n ? GREEN : RED);
        }
    }

    private void refreshMusic() {
        Uri uri = musicUri();
        if (uri == null) {
            musicState.setText("尚未选择音乐（响铃需要先选一个本地音频文件）");
            musicState.setTextColor(MUTED);
            return;
        }
        musicState.setText("已选择：" + displayName(uri));
        musicState.setTextColor(GREEN);
    }

    private void refreshCloudApp() {
        if (cloudAppState == null) {
            return;
        }
        String pkg = prefs.getString("cloud_app_package", null);
        if (pkg == null) {
            cloudAppState.setText("未选择云游戏应用");
            cloudAppState.setTextColor(MUTED);
        } else {
            cloudAppState.setText("已选择：" + prefs.getString("cloud_app_label", pkg));
            cloudAppState.setTextColor(GREEN);
        }
    }

    private void requestPinWidget() {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(this);
            ComponentName provider = new ComponentName(this, CloudWidgetProvider.class);
            if (manager.isRequestPinAppWidgetSupported()) {
                manager.requestPinAppWidget(provider, null, null);
            } else {
                toast("请长按桌面空白处 → 小组件 → 云游戏助手");
            }
        } catch (Throwable t) {
            toast("请手动在桌面小组件里添加");
        }
    }

    private void refreshRunState() {
        boolean running = ScreenWatchService.isRunning();
        startBtn.setText(running ? "停止监测" : "开始监测");
        styleStartButton(running);
        statusLine.setText(running ? "状态：排队监测中" : "状态：未启动");
        float ratio = prefs.getFloat("last_ratio", -1f);
        float textRatio = prefs.getFloat("last_text_ratio", 0f);
        ratioLine.setText(ratio < 0
                ? "上次变化：—"
                : useOcr
                ? String.format(Locale.US, "上次变化：像素 %.1f%% · 文字 %.0f%%", ratio, textRatio)
                : String.format(Locale.US, "上次变化：%.1f%%（阈值 %d%%）", ratio, threshold));
    }

    // ---------------------------------------------------------------- actions

    private void pickMusic() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_MUSIC);
        } catch (Throwable t) {
            toast("找不到文件选择器：" + t.getMessage());
        }
    }

    private void audition() {
        Uri uri = musicUri();
        if (uri == null) {
            toast("请先选择一个本地音乐文件");
            return;
        }
        stopAudition();
        try {
            audition = new MediaPlayer();
            audition.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            audition.setDataSource(this, uri);
            audition.prepare();
            audition.start();
            ui.postDelayed(this::stopAudition, 6000);
        } catch (Throwable t) {
            toast("试听失败：" + t.getMessage());
            stopAudition();
        }
    }

    private void stopAudition() {
        if (audition != null) {
            try {
                audition.stop();
            } catch (Throwable ignored) {
            }
            try {
                audition.release();
            } catch (Throwable ignored) {
            }
            audition = null;
        }
    }

    private void beginWatch() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限");
            openOverlaySettings();
            return;
        }
        if (!usePixel && !useOcr) {
            toast("至少开启一种识别方式（像素或 OCR）");
            return;
        }
        if (alertMode != 1 && musicUri() == null) {
            toast("还没选音乐：触发时会改用连续震动提醒");
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Throwable t) {
            toast("无法发起屏幕捕获授权：" + t.getMessage());
        }
    }

    private void stopWatch() {
        try {
            startService(new Intent(this, ScreenWatchService.class)
                    .setAction(ScreenWatchService.ACTION_STOP));
        } catch (Throwable t) {
            toast("停止失败：" + t.getMessage());
        }
        ui.postDelayed(this::refreshRunState, 300);
    }

    private void sendControl(String action) {
        if (!ScreenWatchService.isRunning()) {
            toast("监控未运行");
            return;
        }
        try {
            startService(new Intent(this, ScreenWatchService.class).setAction(action));
        } catch (Throwable t) {
            toast(t.getMessage());
        }
    }

    private void openOverlaySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Throwable t2) {
                toast("请到系统设置 → 应用 → 显示在其他应用上层 中授权");
            }
        }
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("请到 设置 → 电池 → 电池优化 中允许本应用");
            }
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
    }

    // ---------------------------------------------------------------- results

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MUSIC) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable t) {
                    toast("无法长期保存该文件权限，重启后可能需要重新选择");
                }
                prefs.edit().putString(ScreenWatchService.KEY_MUSIC, uri.toString()).apply();
                refreshMusic();
            }
            return;
        }
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                try {
                    startForegroundService(StartIntent.build(this, resultCode, data));
                } catch (Throwable t) {
                    toast("启动服务失败：" + t.getMessage());
                }
                ui.postDelayed(this::refreshRunState, 800);
            } else {
                toast("未授权屏幕捕获，无法开始监测");
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private Uri musicUri() {
        String s = prefs.getString(ScreenWatchService.KEY_MUSIC, null);
        return (s == null || s.isEmpty()) ? null : Uri.parse(s);
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.isEmpty()) {
                        return n;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? uri.toString() : last;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
