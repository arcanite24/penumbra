package com.limelight.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Penumbra indeterminate progress line (PEN-3): a soft track with a cyan/violet comet
 * sliding across it. Call {@link #complete()} to fill it when the work is done.
 */
public class LoaderProgressView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final long start = SystemClock.uptimeMillis();
    private float fill = -1f;

    public LoaderProgressView(Context context) {
        this(context, null);
    }

    public LoaderProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        trackPaint.setColor(0x1FFFFFFF);
    }

    /** Animates the bar to full width. */
    public void complete() {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(260);
        anim.addUpdateListener(a -> {
            fill = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), r = h / 2f;
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, trackPaint);

        if (fill >= 0f) {
            barPaint.setShader(new LinearGradient(0, 0, w, 0, 0xFF6A4BFF, 0xFF6FE7FF, Shader.TileMode.CLAMP));
            rect.set(0, 0, w * Math.max(0.35f, fill), h);
            canvas.drawRoundRect(rect, r, r, barPaint);
            return;
        }

        float t = ((SystemClock.uptimeMillis() - start) % 1600L) / 1600f;
        // Ease in-out across the track
        float eased = (float) (0.5 - 0.5 * Math.cos(Math.PI * t));
        float seg = w * 0.38f;
        float x = -seg + (w + seg) * eased;
        barPaint.setShader(new LinearGradient(x, 0, x + seg, 0,
                new int[]{0x006A4BFF, 0xFFA98BFF, 0xFF6FE7FF}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        rect.set(Math.max(0, x), 0, Math.min(w, x + seg), h);
        if (rect.width() > 0) {
            canvas.drawRoundRect(rect, r, r, barPaint);
        }
        if (ValueAnimator.areAnimatorsEnabled() && isShown()) {
            postInvalidateOnAnimation();
        }
    }
}
