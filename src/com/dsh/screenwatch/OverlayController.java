package com.dsh.screenwatch;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
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
 * Floating countdown ball (TYPE_APPLICATION_OVERLAY).
 *
 * Collapsed: a 60dp gradient ball showing the seconds left until the next capture.
 * Tap:       expands a small panel with status + 截图 / 暂停 / 停止 / 收起.
 * Drag:      move it anywhere.
 */
final class OverlayController {

    interface Actions {
        void captureNow();

        void togglePause();

        void stopAll();

        void requestStart();
    }

    static final int MODE_NORMAL = 0;
    static final int MODE_PAUSED = 1;
    static final int MODE_RINGING = 2;
    static final int MODE_OFF = 3;

    private final Context ctx;
    private final Actions actions;
    private final WindowManager wm;

    private LinearLayout root;
    private LinearLayout panel;
    private TextView ball;
    private TextView statusText;
    private TextView detailText;
    private TextView pauseBtn;
    private GradientDrawable ballBg;
    private ObjectAnimator pulse;
    private WindowManager.LayoutParams lp;
    private boolean shown;
    private int accent = 0xFFFF6A00;
    private int mode = MODE_NORMAL;
    private boolean idle;
    private GradientDrawable panelBg;
    private TextView titleText;

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
            lp.y = dp(120);
            lp.windowAnimations = 0;
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
        stopPulse();
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

    void setBallText(CharSequence text) {
        if (ball != null) {
            ball.setText(text);
        }
    }

    void setMode(int mode) {
        this.mode = mode;
        applyModeColors();
        if (mode == MODE_RINGING) {
            startPulse();
        } else {
            stopPulse();
        }
    }

    void setAccent(int color) {
        accent = color;
        if (panelBg != null) {
            panelBg.setStroke(dp(1), Color.argb(0x99, Color.red(color),
                    Color.green(color), Color.blue(color)));
        }
        if (titleText != null) {
            titleText.setTextColor(shade(color, 0.35f));
        }
        applyModeColors();
    }

    private void applyModeColors() {
        if (ballBg == null) {
            return;
        }
        switch (mode) {
            case MODE_RINGING:
                ballBg.setColors(new int[]{0xFFFF3B30, 0xFFFF9500});
                break;
            case MODE_PAUSED:
                ballBg.setColors(new int[]{0xFF6B7785, 0xFF3D4652});
                break;
            case MODE_OFF:
                ballBg.setColors(new int[]{0xFF2B3240, 0xFF1B2029});
                break;
            default:
                ballBg.setColors(new int[]{shade(accent, 0.22f), shade(accent, -0.22f)});
                break;
        }
    }

    /** factor &gt; 0 lightens toward white, &lt; 0 darkens toward black. */
    private static int shade(int color, float factor) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        if (factor >= 0) {
            r += (255 - r) * factor;
            g += (255 - g) * factor;
            b += (255 - b) * factor;
        } else {
            float k = 1f + factor;
            r *= k;
            g *= k;
            b *= k;
        }
        return Color.rgb(Math.min(255, Math.round(r)), Math.min(255, Math.round(g)),
                Math.min(255, Math.round(b)));
    }

    void setIdle(boolean idle) {
        this.idle = idle;
    }

    void setPaused(boolean paused) {
        if (pauseBtn != null) {
            pauseBtn.setText(paused ? "继续" : "暂停");
        }
    }

    void togglePanel() {
        if (panel == null) {
            return;
        }
        boolean show = panel.getVisibility() != View.VISIBLE;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------ view building

    private LinearLayout buildView() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ball = new TextView(ctx);
        ball.setGravity(Gravity.CENTER);
        ball.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        ball.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ball.setTextColor(0xFFFFFFFF);
        ball.setText("60");
        ball.setShadowLayer(dp(4), 0, dp(1), 0x66000000);
        ballBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{shade(accent, 0.22f), shade(accent, -0.22f)});
        ballBg.setShape(GradientDrawable.OVAL);
        ballBg.setStroke(dp(2), 0x55FFFFFF);
        ball.setBackground(ballBg);
        ball.setElevation(dp(8));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(60), dp(60));
        row.addView(ball, blp);

        panel = buildPanel();
        panel.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = dp(8);
        row.addView(panel, plp);

        attachTouch(ball);
        return row;
    }

    private LinearLayout buildPanel() {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0141B2B);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.argb(0x99, Color.red(accent), Color.green(accent), Color.blue(accent)));
        card.setBackground(bg);
        panelBg = bg;
        card.setElevation(dp(10));

        TextView title = new TextView(ctx);
        title.setText("云游戏助手");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setTextColor(shade(accent, 0.35f));
        card.addView(title);
        titleText = title;

        statusText = new TextView(ctx);
        statusText.setText("● 排队监测中");
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusText.setTextColor(0xFFFFFFFF);
        statusText.setPadding(0, dp(3), 0, 0);
        card.addView(statusText);

        detailText = new TextView(ctx);
        detailText.setText("上次变化 0.0% · 阈值 15%");
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

        TextView stop = pill("停止");
        stop.setOnClickListener(v -> actions.stopAll());
        bar.addView(stop);

        TextView close = pill("收起");
        close.setOnClickListener(v -> togglePanel());
        bar.addView(close);

        card.addView(bar);
        return card;
    }

    private TextView pill(String label) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(11), dp(5), dp(11), dp(5));
        t.setMinHeight(dp(30));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x33FFFFFF);
        g.setCornerRadius(dp(9));
        t.setBackground(g);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(8);
        t.setLayoutParams(p);
        return t;
    }

    private void attachTouch(View view) {
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
                        if (!moved) {
                            if (idle) {
                                actions.requestStart();
                            } else {
                                togglePanel();
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void startPulse() {
        if (pulse != null) {
            return;
        }
        pulse = ObjectAnimator.ofFloat(ball, View.ALPHA, 1f, 0.55f);
        pulse.setDuration(420);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (ball != null) {
            ball.setAlpha(1f);
        }
    }

    private int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
