package com.dsh.screenwatch;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Latency to cloud providers + per-app network usage. */
public class NetworkActivity extends Activity {

    private static final int BG = 0xFFF5F7FB;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TEXT = 0xFF17202B;
    private static final int MUTED = 0xFF6B7785;
    private static final int BLUE = 0xFF2F80ED;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final TextView[] latencyValues = new TextView[NetProbe.ENDPOINTS.length];

    private SharedPreferences prefs;
    private Switch ballSwitch;
    private Spinner targetSpinner;
    private TextView latencyStatus;
    private TextView usageState;
    private TextView usageSummary;
    private TextView usageRealtime;
    private LinearLayout appRows;
    private long lastTotalBytes;
    private long lastTotalAt;
    private long realtimeBps;
    private Button usageButton;

    private boolean latencyBusy;
    private boolean resumed;

    private final Runnable refreshLoop = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            measureLatency();
            loadUsage();
            sampleTraffic();
            ui.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ScreenWatchService.PREF, MODE_PRIVATE);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshPermissionState();
        measureLatency();
        loadUsage();
        ui.postDelayed(refreshLoop, 5000);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(refreshLoop);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- ui

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(18), pad, dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView back = text("‹ 返回", 14, BLUE, Typeface.BOLD);
        back.setPadding(0, dp(4), 0, dp(10));
        back.setOnClickListener(v -> finish());
        root.addView(back);
        root.addView(text("网络情况", 22, TEXT, Typeface.BOLD));

        // ---- latency
        LinearLayout lat = card();
        lat.addView(sectionTitle("云服务实时延迟"));
        TextView latHint = text("访问云服务首页测一次往返时间；开启后显示在悬浮球上", 12, MUTED, Typeface.NORMAL);
        latHint.setPadding(0, dp(2), 0, dp(8));
        lat.addView(latHint);

        ballSwitch = new Switch(this);
        ballSwitch.setText("在悬浮球显示实时延迟");
        ballSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        ballSwitch.setTextColor(TEXT);
        ballSwitch.setChecked(prefs.getBoolean("ball_latency", false));
        ballSwitch.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("ball_latency", checked).apply();
            pushConfigToService();
        });
        lat.addView(ballSwitch);

        LinearLayout targetRow = new LinearLayout(this);
        targetRow.setOrientation(LinearLayout.HORIZONTAL);
        targetRow.setGravity(Gravity.CENTER_VERTICAL);
        targetRow.setPadding(0, dp(8), 0, 0);
        TextView targetLabel = text("悬浮球显示的云服务：", 13, MUTED, Typeface.NORMAL);
        targetRow.addView(targetLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        targetSpinner = new Spinner(this);
        String[] names = new String[NetProbe.ENDPOINTS.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = NetProbe.ENDPOINTS[i].name;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetSpinner.setAdapter(adapter);
        String saved = prefs.getString("latency_target", "aliyun");
        for (int i = 0; i < NetProbe.ENDPOINTS.length; i++) {
            if (NetProbe.ENDPOINTS[i].key.equals(saved)) {
                targetSpinner.setSelection(i);
            }
        }
        targetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putString("latency_target",
                        NetProbe.ENDPOINTS[position].key).apply();
                pushConfigToService();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        targetRow.addView(targetSpinner);
        lat.addView(targetRow);

        for (int i = 0; i < NetProbe.ENDPOINTS.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(8), 0, 0);
            TextView name = text(NetProbe.ENDPOINTS[i].name, 14, TEXT, Typeface.BOLD);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView value = text("测速中…", 14, MUTED, Typeface.BOLD);
            row.addView(value);
            latencyValues[i] = value;
            lat.addView(row);
        }

        latencyStatus = text("", 11, MUTED, Typeface.NORMAL);
        latencyStatus.setPadding(0, dp(8), 0, dp(2));
        lat.addView(latencyStatus);

        Button refreshLat = new Button(this);
        refreshLat.setText("重新测速");
        refreshLat.setAllCaps(false);
        refreshLat.setOnClickListener(v -> measureLatency());
        lat.addView(refreshLat);
        root.addView(lat);

        // ---- per-app usage
        LinearLayout usage = card();
        usage.addView(sectionTitle("应用网络占用（近 1 小时）"));
        TextView usageHint = text("读取系统 NetworkStats 汇总各应用收发流量；需要「使用情况访问」权限。系统按统计周期汇总，可能有几分钟延迟。", 12, MUTED, Typeface.NORMAL);
        usageHint.setPadding(0, dp(2), 0, dp(8));
        usage.addView(usageHint);
        usageState = text("", 13, MUTED, Typeface.NORMAL);
        usageState.setPadding(0, dp(2), 0, dp(6));
        usage.addView(usageState);
        usageButton = new Button(this);
        usageButton.setText("授予使用情况访问权限");
        usageButton.setAllCaps(false);
        usageButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Throwable t) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        usage.addView(usageButton);
        usageSummary = text("", 13, TEXT, Typeface.BOLD);
        usageSummary.setPadding(0, dp(8), 0, dp(2));
        usage.addView(usageSummary);
        usageRealtime = text("本机实时吞吐：—", 12, MUTED, Typeface.NORMAL);
        usageRealtime.setPadding(0, dp(2), 0, dp(4));
        usage.addView(usageRealtime);
        appRows = new LinearLayout(this);
        appRows.setOrientation(LinearLayout.VERTICAL);
        usage.addView(appRows);

        Button refreshUsage = new Button(this);
        refreshUsage.setText("刷新占用");
        refreshUsage.setAllCaps(false);
        refreshUsage.setOnClickListener(v -> loadUsage());
        usage.addView(refreshUsage);

        TextView note = text("说明：系统流量统计有数秒延迟，且部分系统应用可能不显示；悬浮球延迟每 10 秒更新一次。", 11, MUTED, Typeface.NORMAL);
        note.setPadding(0, dp(6), 0, dp(2));
        usage.addView(note);
        root.addView(usage);

        return scroll;
    }

    private void pushConfigToService() {
        if (!ScreenWatchService.isRunning()) {
            return;
        }
        try {
            startService(new Intent(this, ScreenWatchService.class)
                    .setAction(ScreenWatchService.ACTION_NETWORK_CONFIG)
                    .putExtra(ScreenWatchService.EXTRA_SHOW_LATENCY,
                            prefs.getBoolean("ball_latency", false))
                    .putExtra(ScreenWatchService.EXTRA_LATENCY_TARGET,
                            prefs.getString("latency_target", "aliyun")));
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- latency

    private void measureLatency() {
        if (latencyBusy) {
            return;
        }
        latencyBusy = true;
        io.execute(() -> {
            int[] values = new int[NetProbe.ENDPOINTS.length];
            for (int i = 0; i < NetProbe.ENDPOINTS.length; i++) {
                values[i] = NetProbe.latencyMs(NetProbe.ENDPOINTS[i].url);
            }
            ui.post(() -> {
                latencyBusy = false;
                for (int i = 0; i < values.length; i++) {
                    TextView v = latencyValues[i];
                    if (v == null) {
                        continue;
                    }
                    if (values[i] > 0) {
                        v.setText(values[i] + " ms");
                        v.setTextColor(values[i] < 120 ? 0xFF1FA55A
                                : values[i] < 300 ? 0xFFD98A00 : 0xFFE5484D);
                    } else {
                        v.setText("超时");
                        v.setTextColor(0xFFE5484D);
                    }
                }
                String time = new SimpleDateFormat("HH:mm:ss", Locale.US)
                        .format(new Date());
                latencyStatus.setText("更新于 " + time);
            });
        });
    }

    // ---------------------------------------------------------------- usage

    private boolean usageGranted() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshPermissionState() {
        boolean granted = usageGranted();
        usageButton.setVisibility(granted ? View.GONE : View.VISIBLE);
        usageState.setText(granted ? "已授权，读取系统流量统计"
                : "未授权：只能看到总流量，无法按应用拆分");
    }

    private void loadUsage() {
        if (!usageGranted()) {
            refreshPermissionState();
            return;
        }
        io.execute(() -> {
            long total = 0;
            Map<Integer, Long> byUid = new TreeMap<>();
            try {
                NetworkStatsManager nsm =
                        (NetworkStatsManager) getSystemService(Context.NETWORK_STATS_SERVICE);
                long end = System.currentTimeMillis();
                long start = end - 3600_000L;
                int[] types = {ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE};
                for (int type : types) {
                    try {
                        NetworkStats stats = nsm.querySummary(type, null, start, end);
                        NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                        while (stats.hasNextBucket()) {
                            stats.getNextBucket(bucket);
                            long bytes = bucket.getRxBytes() + bucket.getTxBytes();
                            if (bytes <= 0) {
                                continue;
                            }
                            int uid = bucket.getUid();
                            Long old = byUid.get(uid);
                            byUid.put(uid, old == null ? bytes : old + bytes);
                            total += bytes;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            List<Map.Entry<Integer, Long>> sorted = new ArrayList<>(byUid.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            long finalTotal = total;
            ui.post(() -> renderUsage(finalTotal, sorted));
        });
    }

    private void renderUsage(long totalBytes, List<Map.Entry<Integer, Long>> sorted) {
        usageSummary.setText(String.format(Locale.US, "近 1 小时总流量 %s", formatBytes(totalBytes)));
        appRows.removeAllViews();
        int shown = 0;
        for (Map.Entry<Integer, Long> e : sorted) {
            if (shown >= 8) {
                break;
            }
            String label = labelForUid(e.getKey());
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(7), 0, 0);
            TextView name = text(label, 13, TEXT, Typeface.NORMAL);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            int pct = totalBytes > 0 ? (int) Math.round(e.getValue() * 100.0 / totalBytes) : 0;
            TextView value = text(String.format(Locale.US, "%s · %d%%",
                    formatBytes(e.getValue()), pct), 12, MUTED, Typeface.BOLD);
            row.addView(value);
            appRows.addView(row);
            shown++;
        }
        if (shown == 0) {
            TextView empty = text("近一小时没有捕获到应用流量", 12, MUTED, Typeface.NORMAL);
            empty.setPadding(0, dp(6), 0, 0);
            appRows.addView(empty);
        }
    }

    private void sampleTraffic() {
        long total = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
        long now = SystemClock.elapsedRealtime();
        if (lastTotalAt > 0 && total >= lastTotalBytes) {
            long dt = now - lastTotalAt;
            if (dt > 0) {
                realtimeBps = (total - lastTotalBytes) * 1000L / dt;
            }
        }
        lastTotalBytes = total;
        lastTotalAt = now;
        if (usageRealtime != null) {
            usageRealtime.setText("本机实时吞吐：" + formatRate(realtimeBps));
        }
    }

    private String formatRate(long bytesPerSec) {
        if (bytesPerSec < 1024) {
            return bytesPerSec + " B/s";
        }
        double kb = bytesPerSec / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB/s", kb);
        }
        return String.format(Locale.US, "%.1f MB/s", kb / 1024.0);
    }

    private String labelForUid(int uid) {
        try {
            String[] pkgs = getPackageManager().getPackagesForUid(uid);
            if (pkgs != null && pkgs.length > 0) {
                return getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(pkgs[0], 0)).toString();
            }
        } catch (Throwable ignored) {
        }
        return "UID " + uid;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        return String.format(Locale.US, "%.1f MB", kb / 1024.0);
    }

    // ---------------------------------------------------------------- helpers

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(10));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
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

    private TextView sectionTitle(String s) {
        return text(s, 15, TEXT, Typeface.BOLD);
    }

    private TextView text(String s, float sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
