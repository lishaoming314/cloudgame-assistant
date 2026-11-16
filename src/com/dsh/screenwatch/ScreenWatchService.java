package com.dsh.screenwatch;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Foreground service that owns the MediaProjection, captures the screen once per interval,
 * compares it with the previous capture and rings when the change ratio crosses the threshold.
 *
 * Only two screenshots are ever kept: the previous one and the current one (in memory and as
 * cache/snapshots/prev.jpg + cur.jpg).
 */
public class ScreenWatchService extends Service implements OverlayController.Actions {

    private static final String TAG = "ScreenWatch";

    public static final String ACTION_START = "com.dsh.screenwatch.action.START";
    public static final String ACTION_STOP = "com.dsh.screenwatch.action.STOP";
    public static final String ACTION_TOGGLE_PAUSE = "com.dsh.screenwatch.action.TOGGLE_PAUSE";
    public static final String ACTION_CAPTURE_NOW = "com.dsh.screenwatch.action.CAPTURE_NOW";
    public static final String ACTION_STOP_RING = "com.dsh.screenwatch.action.STOP_RING";

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_THRESHOLD = "threshold";
    public static final String EXTRA_INTERVAL_MIN = "intervalMin";
    public static final String EXTRA_MUSIC = "music";
    public static final String EXTRA_RING_SECONDS = "ringSeconds";

    public static final String ACTION_STATS = "com.dsh.screenwatch.action.STATS";
    public static final String EXTRA_RATIO = "ratio";
    public static final String EXTRA_RINGING = "ringing";
    public static final String EXTRA_PAUSED = "paused";
    public static final String EXTRA_STATUS = "status";

    public static final String PREF = "watch";
    public static final String KEY_MUSIC = "music_uri";
    public static final String KEY_THRESHOLD = "threshold";
    public static final String KEY_INTERVAL = "interval_min";

    static final String CHANNEL = "watch";
    static final int NOTIF_ID = 42;
    /** One sample per grid cell: 48x48 = 2304 cells. */
    static final int GRID = 48;
    /** Per-channel tolerance below which a cell is considered unchanged (jpeg/AA noise). */
    static final int CELL_TOLERANCE = 24;
    /** Longest side of the captured bitmap, in pixels. */
    static final float MAX_CAPTURE_SIDE = 480f;

    private static volatile ScreenWatchService instance;

    public static boolean isRunning() {
        return instance != null && instance.running;
    }

    public static ScreenWatchService get() {
        return instance;
    }

    private final Object captureLock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());

    private HandlerThread captureThread;
    private Handler captureHandler;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Bitmap captureBitmap;
    private int captureW;
    private int captureH;
    private int rotation = -1;

    private int[] prevSample;
    private boolean hasBaseline;

    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean screenOff;
    private volatile boolean ringing;
    private volatile boolean stopping;
    /** Retry the pending capture quickly (frame not ready yet / display just rebuilt). */
    private volatile boolean retrySoon;

    private float lastRatio;
    private long lastCaptureAt;
    private long nextCaptureAt;
    private long ringEndAt;

    private int threshold = 15;
    private long intervalMs = 60_000L;
    private int ringSeconds = 180;
    private String musicUri;

    private MediaPlayer player;
    private PowerManager.WakeLock wakeLock;

    private OverlayController overlay;
    private BroadcastReceiver screenReceiver;
    private String lastNotifText = "";

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        captureThread = new HandlerThread("capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        overlay = new OverlayController(this, this);
        registerScreenReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        switch (action) {
            case ACTION_START:
                handleStart(intent);
                break;
            case ACTION_STOP:
                stopEverything("用户停止");
                break;
            case ACTION_TOGGLE_PAUSE:
                togglePause();
                break;
            case ACTION_CAPTURE_NOW:
                captureNow();
                break;
            case ACTION_STOP_RING:
                handlerStopRing();
                break;
            default:
                stopSelf();
                break;
        }
        return START_NOT_STICKY;
    }

    private void handleStart(Intent intent) {
        // startForegroundService() requires startForeground() within 5s, so do it before
        // validating the extras (and before getMediaProjection(), which is mandatory on Q).
        startForegroundCompat("正在启动屏幕捕获…");
        int code = intent.getIntExtra(EXTRA_CODE, android.app.Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        threshold = clamp(intent.getIntExtra(EXTRA_THRESHOLD, 15), 1, 100);
        int intervalMin = clamp(intent.getIntExtra(EXTRA_INTERVAL_MIN, 1), 1, 60);
        intervalMs = intervalMin * 60_000L;
        ringSeconds = clamp(intent.getIntExtra(EXTRA_RING_SECONDS, 180), 5, 3600);
        musicUri = intent.getStringExtra(EXTRA_MUSIC);
        if (musicUri == null) {
            musicUri = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_MUSIC, null);
        }
        if (code != android.app.Activity.RESULT_OK || data == null) {
            Log.e(TAG, "missing projection result");
            toast("屏幕捕获授权无效，请重新开始");
            stopEverything("授权无效");
            return;
        }
        if (running) {
            stopProjectionOnly();
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Throwable t) {
            Log.e(TAG, "getMediaProjection failed", t);
            toast("无法获取屏幕捕获权限：" + t.getClass().getSimpleName());
            stopEverything("获取屏幕捕获权限失败");
            return;
        }
        if (projection == null) {
            toast("屏幕捕获授权失败，请重试");
            stopEverything("授权失败");
            return;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.w(TAG, "MediaProjection stopped externally");
                main.post(() -> {
                    if (!stopping) stopEverything("屏幕共享已结束");
                });
            }
        }, main);

        running = true;
        stopping = false;
        paused = false;
        hasBaseline = false;
        prevSample = null;
        lastRatio = 0f;
        lastCaptureAt = 0;

        synchronized (captureLock) {
            setupVirtualDisplay();
        }
        if (!running) {
            return;
        }
        overlay.show();
        overlay.setPaused(false);
        main.removeCallbacks(tick);
        main.post(tick);
        refreshUiText();
        scheduleCapture(1500);
        toast("盯屏提醒已开始，接下来每分钟截图对比一次");
    }

    @Override
    public void onDestroy() {
        stopping = true;
        running = false;
        main.removeCallbacksAndMessages(null);
        synchronized (captureLock) {
            releaseVirtualDisplay();
        }
        stopRingingInternal();
        if (projection != null) {
            try {
                projection.stop();
            } catch (Throwable ignored) {
            }
            projection = null;
        }
        if (overlay != null) {
            overlay.hide();
        }
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Throwable ignored) {
            }
            screenReceiver = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
        instance = null;
        SharedPreferences.Editor e = getSharedPreferences(PREF, MODE_PRIVATE).edit();
        e.putBoolean("running", false).apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- projection

    private void setupVirtualDisplay() {
        releaseVirtualDisplay();
        Display display = getDisplay();
        if (display == null) {
            return;
        }
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        rotation = display.getRotation();
        int sw = Math.max(dm.widthPixels, 8);
        int sh = Math.max(dm.heightPixels, 8);
        float scale = Math.min(1f, MAX_CAPTURE_SIDE / (float) Math.max(sw, sh));
        captureW = Math.max(8, (Math.round(sw * scale) / 8) * 8);
        captureH = Math.max(8, (Math.round(sh * scale) / 8) * 8);
        try {
            captureBitmap = Bitmap.createBitmap(captureW, captureH, Bitmap.Config.ARGB_8888);
            imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2);
            virtualDisplay = projection.createVirtualDisplay("ScreenWatch", captureW, captureH,
                    Math.max(dm.densityDpi, 1),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, captureHandler);
        } catch (Throwable t) {
            Log.e(TAG, "createVirtualDisplay failed", t);
            toast("创建屏幕镜像失败：" + t.getClass().getSimpleName());
            stopEverything("屏幕镜像失败");
            return;
        }
        hasBaseline = false;
        prevSample = null;
        lastRatio = 0f;
        Log.i(TAG, "virtual display " + captureW + "x" + captureH + " rotation=" + rotation);
    }

    private void releaseVirtualDisplay() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Throwable ignored) {
            }
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Throwable ignored) {
            }
            imageReader = null;
        }
        if (captureBitmap != null) {
            captureBitmap.recycle();
            captureBitmap = null;
        }
    }

    private Display getDisplay() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        return dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
    }

    // ---------------------------------------------------------------- capture loop

    private final Runnable captureRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                captureOnce();
            } catch (Throwable t) {
                Log.e(TAG, "capture failed", t);
            }
            if (running) {
                boolean retry = retrySoon;
                retrySoon = false;
                scheduleCapture(retry ? 2000 : intervalMs);
            }
        }
    };

    private void scheduleCapture(long delayMs) {
        if (captureHandler == null) {
            return;
        }
        captureHandler.removeCallbacks(captureRunnable);
        nextCaptureAt = SystemClock.uptimeMillis() + delayMs;
        captureHandler.postDelayed(captureRunnable, Math.max(50, delayMs));
    }

    private void captureOnce() {
        retrySoon = false;
        if (paused) {
            return;
        }
        KeyguardManager kg = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (screenOff || (kg != null && kg.isKeyguardLocked())) {
            return;
        }
        Display display = getDisplay();
        if (display != null && display.getRotation() != rotation) {
            Log.i(TAG, "rotation changed, rebuilding virtual display");
            synchronized (captureLock) {
                setupVirtualDisplay();
            }
            retrySoon = true;
            return;
        }
        synchronized (captureLock) {
            if (imageReader == null || captureBitmap == null || projection == null || !running) {
                return;
            }
            Image image = null;
            try {
                image = imageReader.acquireLatestImage();
            } catch (Throwable t) {
                Log.w(TAG, "acquireLatestImage: " + t);
            }
            if (image == null) {
                retrySoon = true;
                return;
            }
            try {
                fillBitmap(image, captureBitmap);
                processFrame(captureBitmap);
            } finally {
                try {
                    image.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void processFrame(Bitmap frame) {
        long now = SystemClock.uptimeMillis();
        lastCaptureAt = now;
        try {
            int[] sample = new int[GRID * GRID];
            Bitmap small = Bitmap.createScaledBitmap(frame, GRID, GRID, true);
            small.getPixels(sample, 0, GRID, 0, 0, GRID, GRID);
            small.recycle();

            float ratio = 0f;
            if (hasBaseline && prevSample != null) {
                ratio = PixelDiff.changeRatio(prevSample, sample, CELL_TOLERANCE);
            }
            lastRatio = ratio;
            saveSnapshots(frame);

            boolean crossed = hasBaseline && ratio > threshold;
            if (prevSample == null) {
                prevSample = new int[sample.length];
            }
            System.arraycopy(sample, 0, prevSample, 0, sample.length);
            hasBaseline = true;

            Log.i(TAG, String.format(Locale.US, "capture ratio=%.2f%% threshold=%d%% ringing=%s",
                    ratio, threshold, ringing));
            if (crossed) {
                triggerRing(ratio);
            }
            publishStats();
        } catch (Throwable t) {
            Log.e(TAG, "processFrame failed", t);
        }
    }

    private void fillBitmap(Image image, Bitmap out) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int w = image.getWidth();
        int h = image.getHeight();

        if (pixelStride == 4 && rowStride == w * 4) {
            buffer.rewind();
            out.copyPixelsFromBuffer(buffer);
            return;
        }
        int[] row = new int[w];
        byte[] bytes = new byte[rowStride];
        for (int y = 0; y < h; y++) {
            buffer.position(y * rowStride);
            int avail = Math.min(rowStride, buffer.remaining());
            buffer.get(bytes, 0, avail);
            for (int x = 0; x < w; x++) {
                int p = x * pixelStride;
                int r = bytes[p] & 0xFF;
                int g = bytes[p + 1] & 0xFF;
                int b = bytes[p + 2] & 0xFF;
                int a = bytes[p + 3] & 0xFF;
                row[x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            out.setPixels(row, 0, w, 0, y, w, 1);
        }
    }

    /** Keeps exactly two files: the previous screenshot and the current one. */
    private void saveSnapshots(Bitmap frame) {
        try {
            File dir = new File(getCacheDir(), "snapshots");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File cur = new File(dir, "cur.jpg");
            File prev = new File(dir, "prev.jpg");
            if (cur.exists()) {
                if (prev.exists() && !prev.delete()) {
                    Log.w(TAG, "cannot delete prev.jpg");
                }
                if (!cur.renameTo(prev)) {
                    Log.w(TAG, "cannot rotate cur.jpg");
                }
            }
            FileOutputStream fos = new FileOutputStream(cur);
            frame.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            fos.flush();
            fos.close();
        } catch (Throwable t) {
            Log.w(TAG, "saveSnapshots: " + t);
        }
    }

    // ---------------------------------------------------------------- ringing

    private void triggerRing(float ratio) {
        if (ringing) {
            return;
        }
        if (musicUri == null || musicUri.isEmpty()) {
            vibrate();
            toast(String.format(Locale.US, "画面变化 %.1f%%，但还没选择音乐", ratio));
            return;
        }
        ringing = true;
        ringEndAt = SystemClock.elapsedRealtime() + ringSeconds * 1000L;
        boolean ok = startMusic();
        if (!ok) {
            ringing = false;
            return;
        }
        acquireWakeLock(ringSeconds * 1000L + 15_000L);
        if (captureHandler != null) {
            captureHandler.removeCallbacks(ringStopRunnable);
            captureHandler.postDelayed(ringStopRunnable, ringSeconds * 1000L);
        }
        main.post(this::refreshUiText);
        publishStats();
        Log.i(TAG, "ring started for " + ringSeconds + "s");
    }

    private boolean startMusic() {
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setLooping(true);
            player.setDataSource(this, Uri.parse(musicUri));
            player.prepare();
            player.start();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "cannot play music " + musicUri, t);
            releasePlayer();
            vibrate();
            toast("音乐无法播放，请重新选择本地音乐");
            return false;
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.stop();
            } catch (Throwable ignored) {
            }
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
    }

    private final Runnable ringStopRunnable = new Runnable() {
        @Override
        public void run() {
            stopRingingInternal();
            main.post(ScreenWatchService.this::refreshUiText);
        }
    };

    private void handlerStopRing() {
        if (captureHandler != null) {
            captureHandler.removeCallbacks(ringStopRunnable);
        }
        stopRingingInternal();
        main.post(this::refreshUiText);
    }

    private void stopRingingInternal() {
        if (!ringing && player == null) {
            releaseWakeLock();
            return;
        }
        ringing = false;
        releasePlayer();
        releaseWakeLock();
        Log.i(TAG, "ring stopped");
    }

    private void acquireWakeLock(long timeoutMs) {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenWatch:ring");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(timeoutMs);
        } catch (Throwable t) {
            Log.w(TAG, "wake lock: " + t);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private void vibrate() {
        try {
            android.os.Vibrator v =
                    (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                v.vibrate(new long[]{0, 400, 200, 400}, -1);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- controls

    @Override
    public void captureNow() {
        if (!running) {
            return;
        }
        if (paused) {
            paused = false;
            overlay.setPaused(false);
        }
        scheduleCapture(150);
        refreshUiText();
    }

    @Override
    public void togglePause() {
        if (!running) {
            return;
        }
        paused = !paused;
        overlay.setPaused(paused);
        if (paused) {
            if (captureHandler != null) {
                captureHandler.removeCallbacks(captureRunnable);
            }
        } else {
            scheduleCapture(intervalMs);
        }
        refreshUiText();
        publishStats();
    }

    @Override
    public void stopAll() {
        stopEverything("用户停止");
    }

    private void togglePauseFromNotification() {
        togglePause();
    }

    private void stopEverything(String reason) {
        if (stopping) {
            return;
        }
        stopping = true;
        running = false;
        Log.i(TAG, "stopping: " + reason);
        if (overlay != null) {
            overlay.hide();
        }
        main.removeCallbacks(tick);
        stopRingingInternal();
        if (captureHandler != null) {
            captureHandler.removeCallbacksAndMessages(null);
        }
        synchronized (captureLock) {
            releaseVirtualDisplay();
        }
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            try {
                p.stop();
            } catch (Throwable ignored) {
            }
        }
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean("running", false).apply();
        publishStats();
        stopForeground(true);
        stopSelf();
    }

    /** Used when re-starting while an old projection is still alive. */
    private void stopProjectionOnly() {
        stopRingingInternal();
        if (captureHandler != null) {
            captureHandler.removeCallbacksAndMessages(null);
        }
        synchronized (captureLock) {
            releaseVirtualDisplay();
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (Throwable ignored) {
            }
            projection = null;
        }
    }

    // ---------------------------------------------------------------- ui / notification

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            refreshUiText();
            main.postDelayed(this, 1000);
        }
    };

    private void refreshUiText() {
        if (overlay != null) {
            overlay.update(statusLine(), detailLine());
        }
        String text = statusLine();
        if (!text.equals(lastNotifText)) {
            lastNotifText = text;
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && running) {
                try {
                    nm.notify(NOTIF_ID, buildNotification(text));
                } catch (Throwable t) {
                    Log.w(TAG, "notify: " + t);
                }
            }
        }
    }

    private String statusLine() {
        if (!running) {
            return "已停止";
        }
        if (ringing) {
            long left = Math.max(0, ringEndAt - SystemClock.elapsedRealtime());
            return String.format(Locale.US, "🔔 响铃中 · %d:%02d 后停止",
                    left / 60000, (left / 1000) % 60);
        }
        if (paused) {
            return "⏸ 已暂停";
        }
        if (screenOff) {
            return "💤 屏幕已关闭 · 暂停截图";
        }
        long left = Math.max(0, nextCaptureAt - SystemClock.uptimeMillis());
        return String.format(Locale.US, "● 监控中 · %ds 后截图", Math.round(left / 1000.0));
    }

    private String detailLine() {
        String music = (musicUri == null) ? "未选音乐" : "音乐已选";
        return String.format(Locale.US, "变化 %.1f%% · 阈值 %d%% · %s",
                lastRatio, threshold, music);
    }

    private void publishStats() {
        SharedPreferences.Editor e = getSharedPreferences(PREF, MODE_PRIVATE).edit();
        e.putFloat("last_ratio", lastRatio)
                .putLong("last_capture_at", lastCaptureAt)
                .putBoolean("running", running)
                .putBoolean("ringing", ringing)
                .putBoolean("paused", paused)
                .apply();
        Intent i = new Intent(ACTION_STATS).setPackage(getPackageName());
        i.putExtra(EXTRA_RATIO, lastRatio)
                .putExtra(EXTRA_RINGING, ringing)
                .putExtra(EXTRA_PAUSED, paused)
                .putExtra(EXTRA_STATUS, statusLine());
        sendBroadcast(i);
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL, "盯屏监控",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("每分钟截图对比的运行状态");
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent servicePending(String action, int code) {
        Intent i = new Intent(this, ScreenWatchService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("盯屏提醒")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pi);
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,
                paused ? "继续" : "暂停", servicePending(ACTION_TOGGLE_PAUSE, 11)).build());
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_camera,
                "立即截图", servicePending(ACTION_CAPTURE_NOW, 12)).build());
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_delete,
                ringing ? "停铃" : "停止",
                servicePending(ringing ? ACTION_STOP_RING : ACTION_STOP, 13)).build());
        return b.build();
    }

    private void startForegroundCompat(String text) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    // ---------------------------------------------------------------- screen state

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    screenOff = true;
                    if (captureHandler != null) {
                        captureHandler.removeCallbacks(captureRunnable);
                    }
                    main.post(ScreenWatchService.this::refreshUiText);
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    screenOff = false;
                    hasBaseline = false;
                    prevSample = null;
                    lastRatio = 0f;
                    scheduleCapture(3000);
                    main.post(ScreenWatchService.this::refreshUiText);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);
    }

    private void toast(String msg) {
        main.post(() -> android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show());
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
