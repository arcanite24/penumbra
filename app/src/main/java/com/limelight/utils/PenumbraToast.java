package com.limelight.utils;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Penumbra (PEN-3) toasts: a glass pill with a cyan dot instead of the system grey bubble.
 * Drop-in for {@link Toast#makeText}; the returned Toast must not be given new text with setText().
 */
public final class PenumbraToast {
    private PenumbraToast() {
    }

    public static Toast makeText(Context context, int resId, int duration) {
        return makeText(context, context.getResources().getText(resId), duration);
    }

    @SuppressWarnings("deprecation")
    public static Toast makeText(Context context, CharSequence text, int duration) {
        // Custom toast views only show while our app is in the foreground, so keep the
        // system toast for services and other non-activity callers.
        if (!(unwrap(context) instanceof Activity)) {
            return Toast.makeText(context, text, duration);
        }
        Context ctx = context;
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(Math.round(16 * d), Math.round(11 * d), Math.round(20 * d), Math.round(11 * d));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xF21A1640, 0xF20B0926});
        bg.setCornerRadius(999 * d);
        bg.setStroke(Math.max(1, Math.round(d)), 0x666FE7FF);
        pill.setBackground(bg);
        pill.setElevation(8 * d);

        TextView dot = new TextView(ctx);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(0xFF6FE7FF);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(Math.round(8 * d), Math.round(8 * d));
        dotParams.setMarginEnd(Math.round(12 * d));
        pill.addView(dot, dotParams);

        TextView label = new TextView(ctx);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(14);
        label.setMaxWidth(Math.round(520 * d));
        pill.addView(label);

        Toast toast = new Toast(ctx);
        toast.setView(pill);
        toast.setDuration(duration);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, Math.round(36 * d));
        return toast;
    }

    private static Context unwrap(Context context) {
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == null || base == context) {
                break;
            }
            context = base;
        }
        return context;
    }
}
