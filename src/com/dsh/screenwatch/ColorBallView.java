package com.dsh.screenwatch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * A draggable HSV colour ball. Touch anywhere in the wheel to pick a theme colour;
 * the marker shows the current hue/saturation.
 */
public class ColorBallView extends View {

    interface Listener {
        void onColorChanged(int color);
    }

    private final Paint wheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap wheel;
    private int color = 0xFFFF6A00;
    private float hue;
    private float sat = 1f;
    private float cx;
    private float cy;
    private float radius;
    private Listener listener;

    public ColorBallView(Context context) {
        super(context);
        init();
    }

    public ColorBallView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(3));
        ringPaint.setColor(0xFFFFFFFF);
        dotPaint.setStyle(Paint.Style.FILL);
        setColor(color);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    int getColor() {
        return color;
    }

    void setColor(int newColor) {
        color = newColor;
        float[] hsv = new float[3];
        Color.colorToHSV(newColor, hsv);
        hue = hsv[0];
        sat = hsv[1];
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        radius = Math.min(w, h) / 2f - dp(6);
        cx = w / 2f;
        cy = h / 2f;
        if (radius <= 4) {
            return;
        }
        int d = Math.round(radius * 2);
        wheel = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(wheel);
        float r = radius;
        int[] hues = new int[361];
        for (int i = 0; i <= 360; i++) {
            hues[i] = Color.HSVToColor(new float[]{i % 360, 1f, 1f});
        }
        SweepGradient sweep = new SweepGradient(r, r, hues, null);
        RadialGradient white = new RadialGradient(r, r, r,
                new int[]{0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        wheelPaint.setShader(new ComposeShader(sweep, white, PorterDuff.Mode.SRC_OVER));
        c.drawCircle(r, r, r, wheelPaint);
        wheelPaint.setShader(null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (wheel != null) {
            canvas.drawBitmap(wheel, cx - radius, cy - radius, null);
        }
        if (radius <= 4) {
            return;
        }
        double angle = Math.toRadians(hue);
        float px = cx + (float) Math.cos(angle) * sat * radius;
        float py = cy + (float) Math.sin(angle) * sat * radius;
        canvas.drawCircle(px, py, dp(9), ringPaint);
        dotPaint.setColor(color);
        canvas.drawCircle(px, py, dp(6), dotPaint);
        dotPaint.setColor(0xCCFFFFFF);
        canvas.drawCircle(px - dp(1.6f), py - dp(1.6f), dp(1.6f), dotPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (wheel == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - cx;
                float dy = event.getY() - cy;
                float dist = (float) Math.hypot(dx, dy);
                sat = Math.min(1f, dist / radius);
                hue = (float) Math.toDegrees(Math.atan2(dy, dx));
                if (hue < 0) {
                    hue += 360f;
                }
                color = Color.HSVToColor(new float[]{hue, sat, 1f});
                invalidate();
                if (listener != null) {
                    listener.onColorChanged(color);
                }
                return true;
            default:
                return true;
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
