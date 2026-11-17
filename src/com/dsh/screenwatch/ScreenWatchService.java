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
import android.os.VibrationEffect;
import android.os.Vibrator;
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
    public static final String ACTION_SET_THEME = "com.dsh.screenwatch.action.SET_THEME";

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_THRESHOLD = "threshold";
    public static final String EXTRA_INTERVAL_SEC = "intervalSec";
    public static final String EXTRA_AUTO_STOP = "autoStop";
    public static final String EXTRA_USE_PIXEL = "usePixel";
    public static final String EXTRA_USE_OCR = "useOcr";
    public static final String EXTRA_OCR_THRESHOLD = "ocrThreshold";
    public static final String EXTRA_REQUIRE_BOTH = "requireBoth";
    public static final String EXTRA_MUSIC = "music";
    public static final String EXTRA_RING_SECONDS = "ringSeconds";
    public static final String EXTRA_COLOR = "accentColor";
    public static final String EXTRA_ALERT_MODE = "alertMode";
    public static final String EXTRA_FIRST_DELAY_SEC = "firstDelaySec";

    public static final String ACTION_STATS = "com.dsh.screenwatch.action.STATS";
    public static final String EXTRA_RATIO = "ratio";
    public static final String EXTRA_RINGING = "ringing";
    public static final String EXTRA_PAUSED = "paused";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_TEXT_RATIO = "textRatio";

    public static final String PREF = "watch";
    public static final String KEY_MUSIC = "music_uri";
    public static final String KEY_THRESHOLD = "threshold";
    public static final String KEY_INTERVAL = "interval_sec";

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
    private long firstDelayMs = 2000L;
    private boolean autoStopAfterAlert = true;
    private boolean usePixel = true;
    private boolean useOcr = false;
    private boolean requireBoth = true;
    private int ocrThreshold = 30;
    private float maxCaptureSide = MAX_CAPTURE_SIDE;

    private float lastTextRatio;
    private String prevText;
    private boolean hasTextBaseline;
    private OcrEngine ocrEngine;
    private int ringSeconds = 180;
    private int accent = 0xFFFF6A00;
    /** 0 = music, 1 = vibration, 2 = both. */
    private int alertMode = 0;
    private Vibrator vibrator;
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
            case ACTION_SET_THEME:
                handleTheme(intent);
                if (!running) {
                    stopSelf();
                }
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
        int intervalSec = clamp(intent.getIntExtra(EXTRA_INTERVAL_SEC, 60), 5, 3600);
        intervalMs = intervalSec * 1000L;
        autoStopAfterAlert = intent.getBooleanExtra(EXTRA_AUTO_STOP, true);
        usePixel = intent.getBooleanExtra(EXTRA_USE_PIXEL, true);
        useOcr = intent.getBooleanExtra(EXTRA_USE_OCR, false);
        requireBoth = intent.getBooleanExtra(EXTRA_REQUIRE_BOTH, true);
        ocrThreshold = clamp(intent.getIntExtra(EXTRA_OCR_THRESHOLD, 30), 5, 100);
        maxCaptureSide = useOcr ? 960f : MAX_CAPTURE_SIDE;
        ringSeconds = clamp(intent.getIntExtra(EXTRA_RING_SECONDS, 180), 5, 3600);
        alertMode = clamp(intent.getIntExtra(EXTRA_ALERT_MODE, 0), 0, 2);
        int firstDelaySec = clamp(intent.getIntExtra(EXTRA_FIRST_DELAY_SEC, 2), 0, 60);
        firstDelayMs = Math.max(100L, firstDelaySec * 1000L);
        accent = intent.getIntExtra(EXTRA_COLOR,
                getSharedPreferences(PREF, MODE_PRIVATE).getInt("accent_color", 0xFFFF6A00));
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
        hasTextBaseline = false;
        prevText = null;
        lastTextRatio = 0f;
        if (ocrEngine != null && !useOcr) {
            ocrEngine.release();
            ocrEngine = null;
        }

        synchronized (captureLock) {
            setupVirtualDisplay();
        }
        if (!running) {
            return;
        }
        overlay.show();
        overlay.setAccent(accent);
        overlay.setPaused(false);
        main.removeCallbacks(tick);
        main.post(tick);
        scheduleCapture(firstDelayMs);
        refreshUiText();
        String mode = usePixel && useOcr ? (requireBoth ? "像素+文字（同时满足）" : "像素+文字（任一满足）")
                : usePixel ? "仅像素" : "仅文字 OCR";
        toast(String.format(Locale.US, "云游戏助手已开始（%s），每 %d 秒检测一次", mode, intervalMs / 1000));
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
        if (ocrEngine != null) {
            ocrEngine.release();
            ocrEngine = null;
        }
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
        float scale = Math.min(1f, maxCaptureSide / (float) Math.max(sw, sh));
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
            float pixelRatio = 0f;
            boolean pixelHit = false;
            if (usePixel) {
                int[] sample = new int[GRID * GRID];
                Bitmap small = Bitmap.createScaledBitmap(frame, GRID, GRID, true);
                small.getPixels(sample, 0, GRID, 0, 0, GRID, GRID);
                small.recycle();
                if (hasBaseline && prevSample != null) {
                    pixelRatio = PixelDiff.changeRatio(prevSample, sample, CELL_TOLERANCE);
                }
                lastRatio = pixelRatio;
                pixelHit = hasBaseline && prevSample != null && pixelRatio > threshold;
                if (prevSample == null) {
                    prevSample = new int[sample.length];
                }
                System.arraycopy(sample, 0, prevSample, 0, sample.length);
                hasBaseline = true;
            } else {
                lastRatio = 0f;
            }

            float textRatio = 0f;
            boolean ocrHit = false;
            if (useOcr) {
                if (ocrEngine == null) {
                    ocrEngine = new OcrEngine();
                }
                if (!ocrEngine.ensureReady(this)) {
                    useOcr = false;
                    main.post(() -> toast("OCR 初始化失败，已切换为仅像素识别"));
                } else if (usePixel && pixelRatio < 1.0f) {
                    // Screen did not change at all: no need to spend seconds on OCR.
                    textRatio = 0f;
                } else {
                    String text = ocrEngine.recognize(frame);
                    if (text != null) {
                        if (hasTextBaseline) {
                            textRatio = TextDiff.changeRatio(prevText, text);
                            boolean noText = TextDiff.normalize(prevText).isEmpty()
                                    && TextDiff.normalize(text).isEmpty();
                            // In AND mode, a screen without any recognized text must not
                            // block an obvious pixel change.
                            ocrHit = (noText && usePixel && requireBoth) || textRatio > ocrThreshold;
                        }
                        lastTextRatio = textRatio;
                        prevText = text;
                        hasTextBaseline = true;
                    }
                }
            } else {
                lastTextRatio = 0f;
            }

            saveSnapshots(frame);

            boolean trigger;
            if (usePixel && useOcr) {
                trigger = requireBoth ? (pixelHit && ocrHit) : (pixelHit || ocrHit);
            } else if (usePixel) {
                trigger = pixelHit;
            } else {
                trigger = ocrHit;
            }

            Log.i(TAG, String.format(Locale.US,
                    "capture pixel=%.2f%% text=%.2f%% pixelHit=%s ocrHit=%s ringing=%s",
                    lastRatio, lastTextRatio, pixelHit, ocrHit, ringing));
            if (trigger) {
                triggerRing(lastRatio, lastTextRatio);
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

    private void triggerRing(float pixelRatio, float textRatio) {
        if (ringing) {
            return;
        }
        boolean hasMusic = musicUri != null && !musicUri.isEmpty();
        boolean wantMusic = alertMode != 1 && hasMusic;
        boolean wantVibrate = alertMode != 0;
        if (alertMode != 1 && !hasMusic) {
            wantVibrate = true;
            toast(String.format(Locale.US,
                    "检测到变化（像素 %.1f%% / 文字 %.0f%%），未选音乐，改用连续震动",
                    pixelRatio, textRatio));
        }
        if (!wantMusic && !wantVibrate) {
            return;
        }
        ringing = true;
        ringEndAt = SystemClock.elapsedRealtime() + ringSeconds * 1000L;
        if (wantMusic && !startMusic()) {
            wantVibrate = true;
        }
        if (wantVibrate) {
            startVibration();
        }
        if (autoStopAfterAlert) {
            paused = true;
            if (captureHandler != null) {
                captureHandler.removeCallbacks(captureRunnable);
            }
        }
        acquireWakeLock(ringSeconds * 1000L + 15_000L);
        if (captureHandler != null) {
            captureHandler.removeCallbacks(ringStopRunnable);
            captureHandler.postDelayed(ringStopRunnable, ringSeconds * 1000L);
        }
        main.post(this::refreshUiText);
        publishStats();
        Log.i(TAG, "alert started mode=" + alertMode + " music=" + wantMusic
                + " vibrate=" + wantVibrate + " for " + ringSeconds + "s");
    }

    private void startVibration() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) {
                Log.w(TAG, "no vibrator");
                return;
            }
            long[] pattern = new long[]{0, 700, 300};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                v.vibrate(pattern, 0);
            }
            vibrator = v;
        } catch (Throwable t) {
            Log.w(TAG, "vibrate failed: " + t);
        }
    }

    private void stopVibration() {
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Throwable ignored) {
            }
            vibrator = null;
        }
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
            if (autoStopAfterAlert) {
                main.post(() -> stopEverything("排队结束，已提醒"));
            } else {
                main.post(ScreenWatchService.this::refreshUiText);
            }
        }
    };

    private void handlerStopRing() {
        if (captureHandler != null) {
            captureHandler.removeCallbacks(ringStopRunnable);
        }
        stopRingingInternal();
        if (autoStopAfterAlert) {
            main.post(() -> stopEverything("排队结束，已提醒"));
        } else {
            main.post(this::refreshUiText);
        }
    }

    private void stopRingingInternal() {
        if (!ringing && player == null && vibrator == null) {
            releaseWakeLock();
            return;
        }
        ringing = false;
        releasePlayer();
        stopVibration();
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
        if (!running || ringing) {
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
        if (!running || ringing) {
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

    private void handleTheme(Intent intent) {
        int color = intent.getIntExtra(EXTRA_COLOR, accent);
        accent = color;
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt("accent_color", color).apply();
        if (overlay != null) {
            overlay.setAccent(color);
        }
        lastNotifText = "";
        refreshUiText();
    }

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
            overlay.setBallText(ballText());
            overlay.setMode(ballMode());
        }
        String text = notificationText();
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
            String what = alertMode == 1 ? "震动提醒中"
                    : alertMode == 2 ? "音乐+震动中" : "响铃中";
            return String.format(Locale.US, "🔔 %s · %d:%02d 后停止",
                    what, left / 60000, (left / 1000) % 60);
        }
        if (paused) {
            return "⏸ 已暂停";
        }
        if (screenOff) {
            return "💤 屏幕已关闭 · 暂停截图";
        }
        long left = Math.max(0, nextCaptureAt - SystemClock.uptimeMillis());
        return String.format(Locale.US, "● 排队监测中 · %ds 后截图", Math.round(left / 1000.0));
    }

    /** Coarse text for the notification: it must not change every second. */
    private String notificationText() {
        if (ringing) {
            return statusLine();
        }
        if (paused) {
            return "已暂停";
        }
        if (screenOff) {
            return "屏幕已关闭，暂停截图";
        }
        if (useOcr) {
            return String.format(Locale.US, "排队监测中 · 像素 %.1f%% · 文字 %.0f%%",
                    lastRatio, lastTextRatio);
        }
        return String.format(Locale.US, "排队监测中 · 像素 %.1f%%", lastRatio);
    }

    /** Text inside the floating ball: seconds until the next capture. */
    private String ballText() {
        if (!running) {
            return "—";
        }
        if (ringing) {
            return "!";
        }
        if (paused) {
            return "II";
        }
        if (screenOff) {
            return "zZ";
        }
        long left = Math.max(0, nextCaptureAt - SystemClock.uptimeMillis());
        return String.valueOf((left + 999) / 1000);
    }

    private int ballMode() {
        if (ringing) {
            return OverlayController.MODE_RINGING;
        }
        if (!running) {
            return OverlayController.MODE_OFF;
        }
        if (paused) {
            return OverlayController.MODE_PAUSED;
        }
        if (screenOff) {
            return OverlayController.MODE_OFF;
        }
        return OverlayController.MODE_NORMAL;
    }

    private String detailLine() {
        StringBuilder b = new StringBuilder();
        if (usePixel) {
            b.append(String.format(Locale.US, "像素 %.1f%%", lastRatio));
        } else {
            b.append("像素关");
        }
        if (useOcr) {
            b.append(String.format(Locale.US, " · 文字 %.0f%%", lastTextRatio));
        } else {
            b.append(" · 文字关");
        }
        b.append(String.format(Locale.US, " · 阈值 %d%%", threshold));
        if (useOcr) {
            b.append("/").append(ocrThreshold).append("%");
        }
        return b.toString();
    }

    private void publishStats() {
        SharedPreferences.Editor e = getSharedPreferences(PREF, MODE_PRIVATE).edit();
        e.putFloat("last_ratio", lastRatio)
                .putFloat("last_text_ratio", lastTextRatio)
                .putLong("last_capture_at", lastCaptureAt)
                .putBoolean("running", running)
                .putBoolean("ringing", ringing)
                .putBoolean("paused", paused)
                .apply();
        Intent i = new Intent(ACTION_STATS).setPackage(getPackageName());
        i.putExtra(EXTRA_RATIO, lastRatio)
                .putExtra(EXTRA_RINGING, ringing)
                .putExtra(EXTRA_PAUSED, paused)
                .putExtra(EXTRA_STATUS, statusLine())
                .putExtra(EXTRA_TEXT_RATIO, lastTextRatio);
        sendBroadcast(i);
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL, "云游戏监控",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("排队画面监测的运行状态");
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
                .setSmallIcon(R.drawable.ic_stat_cloud)
                .setContentTitle("云游戏助手")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pi)
                .setColor(accent);
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,
                paused ? "继续" : "暂停", servicePending(ACTION_TOGGLE_PAUSE, 11)).build());
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_camera,
                "立即截图", servicePending(ACTION_CAPTURE_NOW, 12)).build());
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_delete,
                ringing ? "停止提醒" : "停止",
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
