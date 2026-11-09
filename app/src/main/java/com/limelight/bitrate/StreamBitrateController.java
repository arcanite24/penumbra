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
        /** The host took a new bitrate (kbps). source: "menu", "auto-down" or "auto-up". */
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
                maybeStartAuto();
                listener.onCapabilitiesKnown(fLive);
            });
        });
    }

    public void stop() {
        stopped = true;
        ui.removeCallbacks(autoTick);
        worker.shutdownNow();
    }

    // ---------------------------------------------------------------- automatic bitrate

    /** The stream's counters: MediaCodecDecoderRenderer.getProbeCounters() layout. */
    public interface StatsSource {
        /** {decodeTimeMs, framesReceived, framesRendered, framesTotal, framesLost, windowFlipUptimeMs} */
        long[] counters();
        int rttMs();
        long videoBytesReceived();
    }

    /** Where what the controller learns is kept. */
    public interface LimitStore {
        void remember(int limitKbps, long upHoldMs);
    }

    private AutoBitrateController auto;
    private StatsSource stats;
    private LimitStore store;
    private long[] lastCounters;
    private long lastBytes = -1;
    private long lastSampleAt;
    private int lastLoggedLimit = -1;

    private final Runnable autoTick = new Runnable() {
        @Override
        public void run() {
            if (stopped || auto == null) return;
            try {
                autoStep();
            } catch (Exception e) {
                LimeLog.warning(TAG + "auto: " + e);
            }
            ui.postDelayed(this, 250);
        }
    };

    /**
     * Runs the automatic bitrate controller once the host's capabilities are known. It doesn't
     * start when the host can't change the bitrate live, or when it reports its own adaptive
     * bitrate (two controllers fighting over the encoder would oscillate).
     */
    public void enableAuto(AutoBitrateController controller, StatsSource source, LimitStore limitStore) {
        this.auto = controller;
        this.stats = source;
        this.store = limitStore;
    }

    public boolean isAutoRunning() {
        return auto != null && autoRunning;
    }

    public AutoBitrateController getAuto() {
        return auto;
    }

    private boolean autoRunning;

    /** Called on the main thread when capabilities are known. */
    private void maybeStartAuto() {
        if (auto == null || autoRunning) return;
        if (!liveBitrate) {
            LimeLog.info(TAG + "auto: not started, the host can't change the bitrate live");
            auto = null;
            return;
        }
        if (hostAdaptive) {
            LimeLog.info(TAG + "auto: not started, the host runs its own adaptive bitrate");
            auto = null;
            return;
        }
        autoRunning = true;
        LimeLog.info(String.format(Locale.US, TAG + "auto: watching at %d kbps (ceiling %d, floor %d, step-up wait %d s)",
                auto.getCurrentKbps(), auto.getCeilingKbps(), auto.getFloorKbps(), auto.getUpHoldMs() / 1000));
        ui.postDelayed(autoTick, 1000);
    }

    private void autoStep() {
        long[] c = stats.counters();
        if (c == null) return;
        if (lastCounters == null || c[5] <= 0) {
            lastCounters = c;
            lastBytes = stats.videoBytesReceived();
            lastSampleAt = c[5];
            return;
        }
        if (c[5] == lastSampleAt) return; // the renderer hasn't closed another stats window yet
        long dtMs = c[5] - lastSampleAt;
        long bytes = stats.videoBytesReceived();
        long received = c[1] - lastCounters[1];
        long rendered = c[2] - lastCounters[2];
        long total = c[3] - lastCounters[3];
        long lost = c[4] - lastCounters[4];
        long dBytes = lastBytes >= 0 && bytes >= lastBytes ? bytes - lastBytes : 0;
        lastCounters = c;
        lastBytes = bytes;
        lastSampleAt = c[5];
        if (dtMs <= 0 || dtMs > 5000 || total < 0) return;

        float seconds = dtMs / 1000f;
        float lossPct = total > 0 ? lost * 100f / total : 0f;
        int carried = (int) (dBytes * 8 / dtMs); // bits per ms = kbps
        AutoBitrateController.Sample s = new AutoBitrateController.Sample((int) (total / seconds), lossPct,
                received / seconds, rendered / seconds, stats.rttMs(), carried);
        final long now = android.os.SystemClock.uptimeMillis();
        final AutoBitrateController.Decision d = auto.onSample(s, now);
        if (auto.getLastHealth() == AutoBitrateController.Health.TROUBLE
                || auto.getLastHealth() == AutoBitrateController.Health.STRAINED) {
            LimeLog.info(String.format(Locale.US, TAG + "auto: %s, %.1f%% frames lost, rtt %d ms, %.0f/%.0f fps received/decoded, %d kbps arrived",
                    auto.getLastHealth(), lossPct, s.rttMs, s.receivedFps, s.decodedFps, carried));
        }
        if (d == null) return;
        LimeLog.info(TAG + "auto: " + d);
        request(d.kbps, d.up ? "auto-up" : "auto-down", (ok, applied) -> {
            long at = android.os.SystemClock.uptimeMillis();
            if (auto == null) return;
            if (ok) {
                auto.onApplied(d, applied, at);
                int limit = auto.getLimitKbps();
                if (store != null) store.remember(limit, auto.getUpHoldMs());
                if (limit != lastLoggedLimit) {
                    lastLoggedLimit = limit;
                    LimeLog.info(TAG + "auto: limit for this network now " + (limit > 0 ? limit + " kbps" : "none (at the ceiling)"));
                }
            } else {
                auto.onApplyFailed(at);
            }
        });
    }

    /** "32.0 Mbps (auto)" for the stats overlay. */
    public String overlayLabel() {
        String label = formatMbps(currentKbps);
        if (isAutoRunning()) {
            int limit = auto.getLimitKbps();
            label += limit > 0 ? " (auto, ceiling " + formatMbps(auto.getCeilingKbps()) + ")" : " (auto)";
        } else if (capabilitiesKnown && !liveBitrate) {
            label += " (fixed)";
        }
        return label;
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
                    if ("menu".equals(source) && auto != null) {
                        // A hand-picked bitrate is the new ceiling for automatic bitrate
                        auto.onManualChange(fApplied, android.os.SystemClock.uptimeMillis());
                        if (store != null) store.remember(0, auto.getUpHoldMs());
                    }
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
