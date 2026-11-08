package com.limelight.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.ui.EclipseBackgroundView;
import com.limelight.ui.LoaderProgressView;

/**
 * Penumbra (PEN-3) loaders. Same API as Moonlight's ProgressDialog wrapper, but drawn as a glass
 * panel with an orbiting eclipse, or as a full-screen launch screen while a stream connects.
 */
public class SpinnerDialog implements Runnable, OnCancelListener {
    private final String title;
    private final String message;
    private final Activity activity;
    private final boolean finish;

    // Stream launch screen extras (null for the compact loader)
    private final String subtitle;
    private final File coverFile;

    private Dialog progress;
    private TextView messageView;
    private View contentRoot;
    private boolean closing;

    private static final ArrayList<SpinnerDialog> rundownDialogs = new ArrayList<>();

    private SpinnerDialog(Activity activity, String title, String message, boolean finish,
                          String subtitle, File coverFile) {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.finish = finish;
        this.subtitle = subtitle;
        this.coverFile = coverFile;
    }

    public static SpinnerDialog displayDialog(Activity activity, String title, String message, boolean finish) {
        SpinnerDialog spinner = new SpinnerDialog(activity, title, message, finish, null, null);
        activity.runOnUiThread(spinner);
        return spinner;
    }

    /** Full-screen launch screen shown while a stream connects. */
    public static SpinnerDialog displayStreamLoader(Activity activity, String appName, String pcName,
                                                    File coverFile, String message) {
        String sub = pcName != null ? activity.getString(R.string.loader_on_host, pcName) : "";
        SpinnerDialog spinner = new SpinnerDialog(activity, appName != null ? appName : "", message, true,
                sub, coverFile);
        activity.runOnUiThread(spinner);
        return spinner;
    }

    private boolean isStreamLoader() {
        return subtitle != null;
    }

    public static void closeDialogs(Activity activity) {
        synchronized (rundownDialogs) {
            Iterator<SpinnerDialog> i = rundownDialogs.iterator();
            while (i.hasNext()) {
                SpinnerDialog dialog = i.next();
                if (dialog.activity == activity) {
                    i.remove();
                    if (dialog.progress != null && dialog.progress.isShowing()) {
                        dialog.progress.dismiss();
                    }
                }
            }
        }
    }

    public void dismiss() {
        // Running again with progress != null will destroy it
        activity.runOnUiThread(this);
    }

    public void setMessage(final String message) {
        activity.runOnUiThread(() -> {
            if (messageView == null) {
                return;
            }
            // Crossfade status changes instead of snapping
            messageView.animate().cancel();
            messageView.animate().alpha(0f).translationY(-6 * density()).setDuration(110).withEndAction(() -> {
                messageView.setText(message);
                messageView.setTranslationY(6 * density());
                messageView.animate().alpha(1f).translationY(0f).setDuration(180)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
            }).start();
        });
    }

    private float density() {
        return activity.getResources().getDisplayMetrics().density;
    }

    @Override
    public void run() {
        // If we're dying, don't bother doing anything
        if (activity.isFinishing()) {
            return;
        }

        if (progress == null) {
            progress = isStreamLoader() ? createStreamLoader() : createCompact();
            progress.setOnCancelListener(this);

            // If we want to finish the activity when this is killed, make it cancellable
            if (finish) {
                progress.setCancelable(true);
                progress.setCanceledOnTouchOutside(false);
            } else {
                progress.setCancelable(false);
            }

            synchronized (rundownDialogs) {
                rundownDialogs.add(this);
                progress.show();
            }
            animateIn();
        } else if (!closing) {
            closing = true;
            boolean wasShowing;
            synchronized (rundownDialogs) {
                wasShowing = rundownDialogs.remove(this) && progress.isShowing();
            }
            if (!wasShowing) {
                return;
            }
            if (contentRoot == null) {
                progress.dismiss();
                return;
            }
            if (isStreamLoader()) {
                LoaderProgressView bar = contentRoot.findViewById(R.id.loaderProgress);
                bar.complete();
                messageView.setText(R.string.loader_connected);
            }
            contentRoot.animate().alpha(0f).scaleX(isStreamLoader() ? 1.04f : 0.96f)
                    .scaleY(isStreamLoader() ? 1.04f : 0.96f)
                    .setStartDelay(isStreamLoader() ? 220 : 0).setDuration(isStreamLoader() ? 320 : 160)
                    .withEndAction(() -> {
                        if (progress.isShowing() && !activity.isFinishing()) {
                            progress.dismiss();
                        }
                    }).start();
        }
    }

    private Dialog createCompact() {
        Dialog d = new Dialog(activity, R.style.PenumbraSpinner);
        d.setContentView(R.layout.penumbra_spinner);
        ((TextView) d.findViewById(R.id.spinnerTitle)).setText(title);
        messageView = d.findViewById(R.id.spinnerMessage);
        messageView.setText(message);
        contentRoot = d.findViewById(R.id.spinnerPanel);
        // Keep the bars hidden while the loader has focus
        UiHelper.applyImmersive(d.getWindow());
        return d;
    }

    private Dialog createStreamLoader() {
        Dialog d = new Dialog(activity, R.style.PenumbraStreamLoader);
        d.setContentView(R.layout.stream_loader);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                w.getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }

        contentRoot = d.findViewById(R.id.loaderRoot);
        EclipseBackgroundView eclipse = d.findViewById(R.id.loaderEclipse);
        eclipse.setEclipse(0.82f, 0.30f, 0.20f);
        ((TextView) d.findViewById(R.id.loaderTitle)).setText(title);
        ((TextView) d.findViewById(R.id.loaderSubtitle)).setText(subtitle);
        messageView = d.findViewById(R.id.loaderStatus);
        messageView.setText(message);
        d.findViewById(R.id.loaderHints).setVisibility(finish ? View.VISIBLE : View.GONE);

        if (coverFile != null) {
            final ImageView cover = d.findViewById(R.id.loaderCover);
            final ImageView backdrop = d.findViewById(R.id.loaderBackdrop);
            final View card = d.findViewById(R.id.loaderCoverCard);
            new Thread(() -> {
                Bitmap bmp = null;
                try {
                    if (coverFile.exists()) {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 2;
                        bmp = BitmapFactory.decodeFile(coverFile.getAbsolutePath(), opts);
                    }
                } catch (Exception ignored) {
                }
                final Bitmap art = bmp;
                if (art == null) {
                    return;
                }
                activity.runOnUiThread(() -> {
                    if (closing) {
                        return;
                    }
                    cover.setImageBitmap(art);
                    card.setVisibility(View.VISIBLE);
                    card.setAlpha(0f);
                    card.setScaleX(0.9f);
                    card.setScaleY(0.9f);
                    card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380)
                            .setInterpolator(new DecelerateInterpolator(2f)).start();
                    backdrop.setImageBitmap(art);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        backdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                                40 * density(), 40 * density(), android.graphics.Shader.TileMode.CLAMP));
                        backdrop.animate().alpha(0.35f).setDuration(600).start();
                    } else {
                        backdrop.animate().alpha(0.15f).setDuration(600).start();
                    }
                });
            }, "LoaderCover").start();
        }
        return d;
    }

    private void animateIn() {
        if (contentRoot == null) {
            return;
        }
        if (isStreamLoader()) {
            View content = contentRoot.findViewById(R.id.loaderContent);
            content.setAlpha(0f);
            content.setTranslationY(16 * density());
            content.animate().alpha(1f).translationY(0f).setDuration(420)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        } else {
            contentRoot.setAlpha(0f);
            contentRoot.setScaleX(0.94f);
            contentRoot.setScaleY(0.94f);
            contentRoot.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        synchronized (rundownDialogs) {
            rundownDialogs.remove(this);
        }

        // This will only be called if finish was true, so we don't need to check again
        activity.finish();
    }
}
