package com.dsh.screenwatch;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Draggable floating status card (TYPE_APPLICATION_OVERLAY).
 * Shows the countdown, the last change ratio and quick controls.
 */
final class OverlayController {

    interface Actions {
        void captureNow();

        void togglePause();

        void stopAll();
    }

    private final Context ctx;
    private final Actions actions;
    private final WindowManager wm;

    private LinearLayout root;
    private TextView statusText;
    private TextView detailText;
    private TextView pauseBtn;
    private WindowManager.LayoutParams lp;
    private boolean shown;

    OverlayController(Context ctx, Actions actions) {
        this.ctx = ctx;
        this.actions = actions;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean show() {
        if (shown) {
            return true;
        }
        if (!Settings.canDrawOverlays(ctx)) {
            return false;
        }
        try {
            root = buildView();
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(10);
            lp.y = dp(90);
            lp.windowAnimations = 0;
            attachDrag(root);
            wm.addView(root, lp);
            shown = true;
            return true;
        } catch (Throwable t) {
            android.util.Log.w("ScreenWatch", "overlay show failed", t);
            root = null;
            return false;
        }
    }

    void hide() {
        if (!shown || root == null) {
            return;
        }
        try {
            wm.removeView(root);
        } catch (Throwable ignored) {
        }
        root = null;
        shown = false;
    }

    void update(String status, String detail) {
        if (statusText != null) {
            statusText.setText(status);
        }
        if (detailText != null) {
            detailText.setText(detail);
        }
    }

    void setPaused(boolean paused) {
        if (pauseBtn != null) {
            pauseBtn.setText(paused ? "继续" : "暂停");
        }
    }

    // ------------------------------------------------------------ view building

    private LinearLayout buildView() {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6182230);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x552F80ED);
        card.setBackground(bg);
        card.setElevation(dp(6));

        TextView title = new TextView(ctx);
        title.setText("盯屏提醒");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setTextColor(0xFF8AB4F8);
        title.setGravity(Gravity.START);
        card.addView(title);

        statusText = new TextView(ctx);
        statusText.setText("● 监控中");
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusText.setTextColor(0xFFFFFFFF);
        statusText.setPadding(0, dp(2), 0, 0);
        card.addView(statusText);

        detailText = new TextView(ctx);
        detailText.setText("变化 0.0% · 阈值 15%");
        detailText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        detailText.setTextColor(0xFFA7B4C4);
        detailText.setPadding(0, dp(2), 0, dp(8));
        card.addView(detailText);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        TextView snap = pill("截图");
        snap.setOnClickListener(v -> actions.captureNow());
        bar.addView(snap);

        pauseBtn = pill("暂停");
        pauseBtn.setOnClickListener(v -> actions.togglePause());
        bar.addView(pauseBtn);

        TextView stop = pill("关闭");
        stop.setOnClickListener(v -> actions.stopAll());
        bar.addView(stop);

        card.addView(bar);
        return card;
    }

    private TextView pill(String label) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        int gap = dp(8);
        int v = dp(5);
        t.setPadding(dp(12), v, dp(12), v);
        t.setMinHeight(dp(30));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x33FFFFFF);
        g.setCornerRadius(dp(9));
        t.setBackground(g);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.rightMargin = gap;
        t.setLayoutParams(p);
        return t;
    }

    private void attachDrag(View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (!moved && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6))) {
                            moved = true;
                        }
                        if (moved) {
                            lp.x = startX + dx;
                            lp.y = startY + dy;
                            try {
                                wm.updateViewLayout(root, lp);
                            } catch (Throwable ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
