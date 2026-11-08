package com.limelight.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * An ImageView that keeps a 3:4 portrait cover shape: its width follows its height.
 */
public class CoverImageView extends ImageView {
    private static final float ASPECT = 3f / 4f;

    public CoverImageView(Context context) {
        super(context);
    }

    public CoverImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CoverImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int width = Math.round(height * ASPECT);
        setMeasuredDimension(width, height);
    }
}
