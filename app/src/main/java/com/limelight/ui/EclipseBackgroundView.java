package com.limelight.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Penumbra's animated backdrop: slowly drifting nebula light, a breathing eclipse with a
 * rotating corona, and a field of twinkling stars. Runs at about 30 fps, pauses when the view
 * is hidden, and stays still when the system has animations turned off.
 */
public class EclipseBackgroundView extends View {
    private static final long FRAME_MS = 33;
    private static final int STAR_COUNT = 90;

    private final Paint basePaint = new Paint();
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coronaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();

    // Nebula blobs: colour, path periods (s), path amplitude and base position (fractions)
    private static final int[] BLOB_COLORS = {0x5C6A4BFF, 0x3848C8FF, 0x30B04BFF, 0x2A2F6BFF};
    private static final float[][] BLOB_PATH = {
            // baseX, baseY, ampX, ampY, periodX, periodY, radius
            {0.78f, 0.10f, 0.16f, 0.12f, 47f, 61f, 0.75f},
            {0.15f, 0.85f, 0.18f, 0.10f, 53f, 41f, 0.70f},
            {0.45f, 0.45f, 0.25f, 0.20f, 71f, 59f, 0.55f},
            {0.05f, 0.15f, 0.10f, 0.15f, 67f, 79f, 0.60f},
    };
    private final RadialGradient[] blobShaders = new RadialGradient[BLOB_COLORS.length];

    private float eclipseX = 0.86f, eclipseY = 0.14f, eclipseR = 0.17f;
    private RadialGradient glowShader;
    private SweepGradient coronaShader;

    private final float[] starX = new float[STAR_COUNT];
    private final float[] starY = new float[STAR_COUNT];
    private final float[] starSize = new float[STAR_COUNT];
    private final float[] starPhase = new float[STAR_COUNT];
    private final float[] starSpeed = new float[STAR_COUNT];

    private final long startTime = SystemClock.uptimeMillis();
    private boolean animate = true;
    private float intensity = 1f;

    public EclipseBackgroundView(Context context) {
        super(context);
        init();
    }

    public EclipseBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EclipseBackgroundView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        Random r = new Random(0x50E);
        for (int i = 0; i < STAR_COUNT; i++) {
            starX[i] = r.nextFloat();
            starY[i] = r.nextFloat();
            starSize[i] = 0.6f + r.nextFloat() * r.nextFloat() * 1.8f;
            starPhase[i] = r.nextFloat() * 6.283f;
            starSpeed[i] = 0.4f + r.nextFloat() * 1.4f;
        }
        starPaint.setColor(Color.WHITE);
        discPaint.setColor(0xFF050418);
        rimPaint.setStyle(Paint.Style.STROKE);
        coronaPaint.setStyle(Paint.Style.STROKE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            animate = ValueAnimator.areAnimatorsEnabled();
        }
    }

    /** Places the eclipse; x/y are fractions of the width/height, radius a fraction of the height. */
    public void setEclipse(float x, float y, float radius) {
        eclipseX = x;
        eclipseY = y;
        eclipseR = radius;
        buildShaders(getWidth(), getHeight());
        invalidate();
    }

    /** 0..1, dims the nebula and corona for busy screens. */
    public void setIntensity(float intensity) {
        this.intensity = intensity;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildShaders(w, h);
    }

    private void buildShaders(int w, int h) {
        if (w == 0 || h == 0) {
            return;
        }
        basePaint.setShader(new LinearGradient(0, 0, w * 0.3f, h,
                new int[]{0xFF0A0726, 0xFF050418, 0xFF07051F}, null, Shader.TileMode.CLAMP));

        float size = Math.max(w, h);
        for (int i = 0; i < blobShaders.length; i++) {
            float radius = size * BLOB_PATH[i][6];
            int c = BLOB_COLORS[i];
            // Shader centred on the origin; moved with a local matrix every frame
            blobShaders[i] = new RadialGradient(0, 0, radius,
                    new int[]{c, (c & 0x00FFFFFF) | ((Color.alpha(c) / 3) << 24), c & 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        }

        float er = h * eclipseR;
        glowShader = new RadialGradient(0, 0, er * 2.6f,
                new int[]{0x996A4BFF, 0x5548C8FF, 0x1A6A4BFF, 0x00050418},
                new float[]{0.30f, 0.42f, 0.65f, 1f}, Shader.TileMode.CLAMP);
        coronaShader = new SweepGradient(0, 0,
                new int[]{0x006FE7FF, 0xCC6FE7FF, 0xFFE8E6FF, 0xCCA98BFF, 0x00A98BFF, 0x006FE7FF},
                new float[]{0f, 0.18f, 0.25f, 0.34f, 0.55f, 1f});
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || glowShader == null) {
            return;
        }
        float t = animate ? (SystemClock.uptimeMillis() - startTime) / 1000f : 12f;

        canvas.drawRect(0, 0, w, h, basePaint);

        // Nebula light
        int blobAlpha = Math.round(255 * intensity);
        for (int i = 0; i < blobShaders.length; i++) {
            float[] p = BLOB_PATH[i];
            float cx = w * (p[0] + p[2] * (float) Math.sin(t * 6.283f / p[4] + i));
            float cy = h * (p[1] + p[3] * (float) Math.cos(t * 6.283f / p[5] + i * 2));
            matrix.setTranslate(cx, cy);
            blobShaders[i].setLocalMatrix(matrix);
            blobPaint.setShader(blobShaders[i]);
            blobPaint.setAlpha(blobAlpha);
            canvas.drawRect(0, 0, w, h, blobPaint);
        }

        // Stars, twinkling and drifting slowly left
        for (int i = 0; i < STAR_COUNT; i++) {
            float tw = 0.5f + 0.5f * (float) Math.sin(t * starSpeed[i] + starPhase[i]);
            float x = ((starX[i] - t * 0.0025f * starSpeed[i]) % 1f + 1f) % 1f;
            starPaint.setAlpha((int) (40 + 170 * tw * tw));
            canvas.drawCircle(x * w, starY[i] * h, starSize[i] * getResources().getDisplayMetrics().density * 0.6f, starPaint);
        }

        // Eclipse: breathing glow, rotating corona, dark disc with a thin rim
        float ex = w * eclipseX, ey = h * eclipseY, er = h * eclipseR;
        float breathe = 0.85f + 0.15f * (float) Math.sin(t * 6.283f / 7f);
        matrix.setScale(breathe, breathe);
        matrix.postTranslate(ex, ey);
        glowShader.setLocalMatrix(matrix);
        glowPaint.setShader(glowShader);
        glowPaint.setAlpha(Math.round(255 * intensity));
        canvas.drawCircle(ex, ey, er * 2.6f * breathe, glowPaint);

        matrix.setRotate(t * 18f);
        matrix.postTranslate(ex, ey);
        coronaShader.setLocalMatrix(matrix);
        coronaPaint.setShader(coronaShader);
        // Wide soft halo, then a thin bright ring
        coronaPaint.setStrokeWidth(er * 0.30f);
        coronaPaint.setAlpha(Math.round(70 * intensity));
        canvas.drawCircle(ex, ey, er * 1.08f, coronaPaint);
        coronaPaint.setStrokeWidth(er * 0.05f);
        coronaPaint.setAlpha(Math.round(255 * intensity));
        canvas.drawCircle(ex, ey, er * 1.01f, coronaPaint);

        // Penumbra: the moon slightly offset, so the corona shows as a crescent
        float off = er * (0.15f + 0.04f * (float) Math.sin(t * 6.283f / 23f));
        canvas.drawCircle(ex - off, ey + off * 0.6f, er, discPaint);
        rimPaint.setStrokeWidth(Math.max(1f, er * 0.012f));
        rimPaint.setColor(0x3399E9FF);
        canvas.drawCircle(ex - off, ey + off * 0.6f, er, rimPaint);

        if (animate && isShown()) {
            postInvalidateDelayed(FRAME_MS);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            invalidate();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            invalidate();
        }
    }
}
