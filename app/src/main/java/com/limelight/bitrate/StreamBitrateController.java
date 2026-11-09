package com.limelight.bitrate;

import android.os.Handler;
import android.os.Looper;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvHTTP;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live bitrate for a running stream (PEN-10). Vibepollo changes the encoder's bitrate through
 * GET /bitrate over paired HTTPS without a reconnect; it says so by listing "runtime_bitrate" in
 * /api/abr/capabilities. Other hosts don't answer, and the in-game Bitrate entry stays hidden.
 * <p>
 * Created on the main thread when the stream starts; callbacks come back on the main thread.
 */
public class StreamBitrateController {
    private static final String TAG = "Bitrate: ";

    public interface Listener {
        /** The host took a new bitrate (kbps). source: "menu", "auto" or "start". */
        void onBitrateApplied(int kbps, String source);
        /** A change failed; message is for the user. */
        void onBitrateFailed(String message, String source);
        /** Capability detection finished. */
        void onCapabilitiesKnown(boolean liveBitrate);
    }

    private final NvHTTP http;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "penumbra-bitrate");
        t.setDaemon(true);
        return t;
    });

    private volatile int currentKbps;
    private volatile boolean liveBitrate;
    private volatile boolean hostAdaptive;
    private volatile boolean stopped;
    private volatile boolean capabilitiesKnown;

    public StreamBitrateController(NvHTTP http, int startKbps, Listener listener) {
        this.http = http;
        this.currentKbps = startKbps;
        this.listener = listener;
    }

    /** Asks the host what it supports. Runs off the main thread; reports through the listener. */
    public void start() {
        worker.execute(() -> {
            boolean live = false;
            boolean adaptive = false;
            try {
                NvHTTP.BitrateCapabilities caps = http.getBitrateCapabilities();
                if (caps != null) {
                    LimeLog.info(TAG + "host capabilities: " + caps);
                    live = caps.runtimeBitrate();
                    adaptive = caps.hostAdaptive;
                } else {
                    LimeLog.info(TAG + "host doesn't serve /api/abr/capabilities: no live bitrate");
                }
            } catch (Exception e) {
                LimeLog.info(TAG + "capability check failed (" + e + "): no live bitrate");
            }
            final boolean fLive = live;
            final boolean fAdaptive = adaptive;
            ui.post(() -> {
                if (stopped) return;
                liveBitrate = fLive;
                hostAdaptive = fAdaptive;
                capabilitiesKnown = true;
                LimeLog.info(TAG + "live bitrate " + (fLive ? "available" : "unavailable")
                        + (fAdaptive ? ", host runs its own adaptive bitrate" : ""));
                listener.onCapabilitiesKnown(fLive);
            });
        });
    }

    public void stop() {
        stopped = true;
        worker.shutdownNow();
    }

    public boolean isLiveBitrateAvailable() {
        return liveBitrate && !stopped;
    }

    public boolean isCapabilitiesKnown() {
        return capabilitiesKnown;
    }

    /** The host said it adapts the bitrate itself (capabilities "supported": true). */
    public boolean isHostAdaptive() {
        return hostAdaptive;
    }

    public int getCurrentKbps() {
        return currentKbps;
    }

    public interface Result {
        void done(boolean ok, int appliedKbps);
    }

    /** Asks the host for kbps. The result comes back on the main thread. */
    public void request(final int kbps, final String source, final Result result) {
        if (!liveBitrate || stopped) {
            if (result != null) result.done(false, currentKbps);
            return;
        }
        worker.execute(() -> {
            int applied = 0;
            String error = null;
            long start = android.os.SystemClock.uptimeMillis();
            try {
                applied = http.setStreamBitrate(kbps);
                if (applied <= 0) error = "The host didn't apply the bitrate";
            } catch (HostHttpResponseException e) {
                error = e.getErrorCode() == 404 ? "The host has no running stream for this device"
                        : e.getErrorCode() == 403 ? "The host doesn't let this device change the bitrate (it needs the \"View stream\" permission)"
                        : e.getMessage();
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            long tookMs = android.os.SystemClock.uptimeMillis() - start;
            final int fApplied = applied;
            final String fError = error;
            ui.post(() -> {
                if (stopped) return;
                if (fError == null) {
                    currentKbps = fApplied;
                    LimeLog.info(String.format(Locale.US, TAG + "%s set %d kbps, host applied %d kbps (%d ms)",
                            source, kbps, fApplied, tookMs));
                    listener.onBitrateApplied(fApplied, source);
                    if (result != null) result.done(true, fApplied);
                } else {
                    LimeLog.warning(String.format(Locale.US, TAG + "%s set %d kbps failed: %s", source, kbps, fError));
                    listener.onBitrateFailed(fError, source);
                    if (result != null) result.done(false, currentKbps);
                }
            });
        });
    }

    /** "40.0 Mbps" */
    public static String formatMbps(int kbps) {
        return String.format(Locale.US, kbps >= 100000 ? "%.0f Mbps" : "%.1f Mbps", kbps / 1000f);
    }
}
