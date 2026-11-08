package com.limelight.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Penumbra loading indicator (PEN-3): a small eclipse with a cyan/violet corona arc orbiting it,
 * a counter-rotating thin ring and a breathing glow. Draws continuously while visible.
 */
public class OrbitLoaderView extends View {
    private static final int CYAN = 0xFF6FE7FF;
    private static final int VIOLET = 0xFFA98BFF;
    private static final int DEEP = 0xFF6A4BFF;

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF oval = new RectF();
    private final RectF innerOval = new RectF();
    private final long start = SystemClock.uptimeMillis();

    private SweepGradient arcShader;
    private RadialGradient glowShader;
    private float cx, cy, radius;

    public OrbitLoaderView(Context context) {
        this(context, null);
    }

    public OrbitLoaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcGlowPaint.setStyle(Paint.Style.STROKE);
        arcGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(0x33A98BFF);
        discPaint.setColor(0xFF07061A);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setColor(0x66A98BFF);
        dotPaint.setColor(Color.WHITE);
        // Blur mask filters need a software layer
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.min(w, h) / 2f;
        float stroke = Math.max(2f, radius * 0.07f);
        float arcR = radius * 0.80f;
        oval.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR);
        float innerR = radius * 0.62f;
        innerOval.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR);

        arcShader = new SweepGradient(cx, cy,
                new int[]{0x006FE7FF, 0x006FE7FF, DEEP, VIOLET, CYAN, Color.WHITE},
                new float[]{0f, 0.35f, 0.6f, 0.8f, 0.97f, 1f});
        arcPaint.setShader(arcShader);
        arcPaint.setStrokeWidth(stroke);
        arcGlowPaint.setShader(arcShader);
        arcGlowPaint.setStrokeWidth(stroke * 2.6f);
        arcGlowPaint.setMaskFilter(new BlurMaskFilter(stroke * 2.2f, BlurMaskFilter.Blur.NORMAL));
        ringPaint.setStrokeWidth(Math.max(1f, stroke * 0.35f));
        rimPaint.setStrokeWidth(Math.max(1f, stroke * 0.4f));

        glowShader = new RadialGradient(cx, cy, radius,
                new int[]{0x556A4BFF, 0x226FE7FF, 0x00000000}, new float[]{0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP);
        glowPaint.setShader(glowShader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (radius <= 0) {
            return;
        }
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        boolean animate = ValueAnimator.areAnimatorsEnabled();

        // Breathing glow
        float breathe = 0.85f + 0.15f * (float) Math.sin(t * 2.4f);
        canvas.save();
        canvas.scale(breathe, breathe, cx, cy);
        canvas.drawCircle(cx, cy, radius, glowPaint);
        canvas.restore();

        // Counter-rotating dashed-feel ring
        float ringAngle = -t * 40f;
        for (int i = 0; i < 3; i++) {
            canvas.drawArc(innerOval, ringAngle + i * 120f, 70f, false, ringPaint);
        }

        // Eclipse disc and rim
        float discR = radius * 0.48f;
        canvas.drawCircle(cx, cy, discR, discPaint);
        canvas.drawCircle(cx, cy, discR, rimPaint);

        // Orbiting corona arc: the sweep's bright head leads the rotation
        float angle = (t * 300f) % 360f;
        float sweep = 200f + 60f * (float) Math.sin(t * 1.7f);
        matrix.setRotate(angle, cx, cy);
        arcShader.setLocalMatrix(matrix);
        canvas.drawArc(oval, angle + 360f - sweep, sweep, false, arcGlowPaint);
        canvas.drawArc(oval, angle + 360f - sweep, sweep, false, arcPaint);

        // Bright head
        double rad = Math.toRadians(angle);
        float hx = cx + (float) Math.cos(rad) * oval.width() / 2f;
        float hy = cy + (float) Math.sin(rad) * oval.height() / 2f;
        canvas.drawCircle(hx, hy, arcPaint.getStrokeWidth() * 0.75f, dotPaint);

        if (animate && isShown()) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            invalidate();
        }
    }
}
