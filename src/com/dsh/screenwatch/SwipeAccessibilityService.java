package com.dsh.screenwatch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

/**
 * Accessibility service that can inject swipe gestures. Used by the anti-idle feature:
 * every N minutes it performs a few small swipes on the screen.
 */
public class SwipeAccessibilityService extends AccessibilityService {

    static volatile SwipeAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Whether our accessibility service is enabled in system settings. */
    public static boolean isEnabled(Context context) {
        try {
            AccessibilityManager am =
                    (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) {
                return false;
            }
            for (AccessibilityServiceInfo info :
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                String id = info.getId();
                if (id != null && id.startsWith(context.getPackageName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** direction: 0 = vertical, 1 = horizontal, 2 = alternate. */
    void swipe(int direction, int count) {
        handler.removeCallbacksAndMessages(null);
        int n = Math.max(1, Math.min(5, count));
        for (int i = 0; i < n; i++) {
            final int index = i;
            handler.postDelayed(() -> dispatchOne(direction, index), i * 700L);
        }
    }

    private void dispatchOne(int direction, int index) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        boolean horizontal = direction == 1 || (direction == 2 && index % 2 == 1);

        Path path = new Path();
        float x1;
        float y1;
        float x2;
        float y2;
        if (horizontal) {
            x1 = w * 0.78f;
            x2 = w * 0.28f;
            y1 = y2 = h * 0.58f;
        } else {
            x1 = x2 = w * 0.5f;
            y1 = h * 0.72f;
            y2 = h * 0.42f;
        }
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 260);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        try {
            dispatchGesture(gesture, null, null);
        } catch (Throwable ignored) {
        }
    }
}
