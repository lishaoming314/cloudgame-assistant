package com.dsh.screenwatch;

import android.app.Activity;
import android.content.BroadcastReceiver;
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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

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
    private int intervalMin = 1;

    private MediaPlayer audition;
    private BroadcastReceiver statsReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ScreenWatchService.PREF, MODE_PRIVATE);
        threshold = Math.max(5, Math.min(50, prefs.getInt(ScreenWatchService.KEY_THRESHOLD, 15)));
        intervalMin = Math.max(1, Math.min(10, prefs.getInt(ScreenWatchService.KEY_INTERVAL, 1)));
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissions();
        refreshMusic();
        refreshRunState();
        if (statsReceiver == null) {
            statsReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    float ratio = intent.getFloatExtra(ScreenWatchService.EXTRA_RATIO, 0f);
                    boolean ringing = intent.getBooleanExtra(ScreenWatchService.EXTRA_RINGING, false);
                    boolean paused = intent.getBooleanExtra(ScreenWatchService.EXTRA_PAUSED, false);
                    String status = intent.getStringExtra(ScreenWatchService.EXTRA_STATUS);
                    ratioLine.setText(String.format(Locale.US, "最近变化：%.1f%%（阈值 %d%%）", ratio, threshold));
                    if (status != null) {
                        statusLine.setText(status);
                    }
                    runLine.setText(ringing ? "状态：正在响铃" : paused ? "状态：已暂停" : "状态：监控中");
                    startBtn.setText(ScreenWatchService.isRunning() ? "停止监控" : "开始监控");
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
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("盯屏提醒", 24, TEXT, Typeface.BOLD);
        root.addView(title);

        TextView sub = text("每分钟截一次屏，与上一分钟对比像素变化；超过阈值就播放你选的本地音乐，响铃 3 分钟后自动停止。", 13, MUTED, Typeface.NORMAL);
        sub.setPadding(0, dp(6), 0, dp(16));
        root.addView(sub);

        // ---- permissions
        LinearLayout perm = card();
        perm.addView(sectionTitle("权限与后台运行"));
        overlayState = addCheckRow(perm, "悬浮窗权限", "在其它应用上显示监控卡片",
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

        // ---- parameters
        LinearLayout params = card();
        params.addView(sectionTitle("对比参数"));
        thresholdLabel = text("变化阈值：15%", 14, TEXT, Typeface.BOLD);
        thresholdLabel.setPadding(0, dp(6), 0, 0);
        params.addView(thresholdLabel);
        thresholdBar = new SeekBar(this);
        thresholdBar.setMax(45);
        thresholdBar.setProgress(Math.max(0, threshold - 5));
        thresholdBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                threshold = 5 + progress;
                thresholdLabel.setText("变化阈值：" + threshold + "%");
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

        intervalLabel = text("截图间隔：1 分钟", 14, TEXT, Typeface.BOLD);
        intervalLabel.setPadding(0, dp(10), 0, 0);
        params.addView(intervalLabel);
        intervalBar = new SeekBar(this);
        intervalBar.setMax(9);
        intervalBar.setProgress(Math.max(0, intervalMin - 1));
        intervalBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                intervalMin = 1 + progress;
                intervalLabel.setText("截图间隔：" + intervalMin + " 分钟");
                prefs.edit().putInt(ScreenWatchService.KEY_INTERVAL, intervalMin).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        params.addView(intervalBar);
        TextView tip = text("每张截图只保留最近两张：上一分钟 / 当前这一分钟（缓存目录 snapshots/prev.jpg、cur.jpg）。", 12, MUTED, Typeface.NORMAL);
        tip.setPadding(0, dp(8), 0, 0);
        params.addView(tip);
        root.addView(params);

        // ---- start
        startBtn = new Button(this);
        startBtn.setText("开始监控");
        startBtn.setAllCaps(false);
        startBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        startBtn.setTextColor(Color.WHITE);
        startBtn.setOnClickListener(v -> {
            if (ScreenWatchService.isRunning()) {
                stopWatch();
            } else {
                beginWatch();
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        bl.topMargin = dp(6);
        root.addView(startBtn, bl);
        styleStartButton(false);

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
        stopRing.setText("停止响铃");
        stopRing.setAllCaps(false);
        stopRing.setOnClickListener(v -> sendControl(ScreenWatchService.ACTION_STOP_RING));
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(0, dp(42), 1f);
        ql.leftMargin = dp(8);
        quick.addView(stopRing, ql);
        status.addView(quick);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(12);
        root.addView(status, sl);

        TextView notes = text("提示：\n"
                + "• 首次开始会弹出「立即开始录制或投放」的屏幕捕获授权，必须允许，否则无法截图。\n"
                + "• 锁屏或熄屏时不截图，亮屏后重新开始计时，避免黑屏被误判为巨大变化。\n"
                + "• 音乐走「闹钟」音量通道：请把闹钟/媒体音量调高，勿扰模式下允许闹钟。\n"
                + "• 小米/OPPO/vivo 等还需在系统设置里允许「自启动 / 后台弹出界面 / 锁屏显示」，否则后台可能被杀。", 12, MUTED, Typeface.NORMAL);
        notes.setPadding(0, dp(16), 0, 0);
        root.addView(notes);

        return scroll;
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
        g.setColor(running ? RED : BLUE);
        g.setCornerRadius(dp(14));
        startBtn.setBackground(g);
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

    private void refreshRunState() {
        boolean running = ScreenWatchService.isRunning();
        startBtn.setText(running ? "停止监控" : "开始监控");
        styleStartButton(running);
        statusLine.setText(running ? "状态：运行中" : "状态：未启动");
        float ratio = prefs.getFloat("last_ratio", -1f);
        ratioLine.setText(ratio < 0
                ? "最近变化：—"
                : String.format(Locale.US, "最近变化：%.1f%%（阈值 %d%%）", ratio, threshold));
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
        if (musicUri() == null) {
            toast("还没选音乐：可以继续，但触发时只会震动提示");
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
                Intent i = new Intent(this, ScreenWatchService.class)
                        .setAction(ScreenWatchService.ACTION_START)
                        .putExtra(ScreenWatchService.EXTRA_CODE, resultCode)
                        .putExtra(ScreenWatchService.EXTRA_DATA, data)
                        .putExtra(ScreenWatchService.EXTRA_THRESHOLD, threshold)
                        .putExtra(ScreenWatchService.EXTRA_INTERVAL_MIN, intervalMin)
                        .putExtra(ScreenWatchService.EXTRA_RING_SECONDS, 180);
                Uri music = musicUri();
                if (music != null) {
                    i.putExtra(ScreenWatchService.EXTRA_MUSIC, music.toString());
                }
                try {
                    startForegroundService(i);
                } catch (Throwable t) {
                    toast("启动服务失败：" + t.getMessage());
                }
                ui.postDelayed(this::refreshRunState, 800);
            } else {
                toast("未授权屏幕捕获，无法开始监控");
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
