package com.limelight.utils;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Polls Vibepollo's /api/hardware-metrics (CPU and GPU load, encoder load, GPU temperature and
 * VRAM) for the stats overlay, on its own low-priority thread, no faster than the host samples
 * and never faster than every 2 seconds. Only hosts whose serverinfo advertises
 * HardwareMetricsVersion are asked. A refusal (metrics turned off on the host, or this client
 * not allowed to view them) stops the polling for the stream.
 */
public final class HostHardwareMetrics {
    private static final long MIN_INTERVAL_MS = 2000;
    private static final long MAX_INTERVAL_MS = 10000;
    // A reading older than this isn't shown
    private static final long STALE_MS = 15000;

    private final Context context;
    private final NvHTTP http;
    private HandlerThread thread;
    private Handler handler;
    private long intervalMs = MIN_INTERVAL_MS;
    private volatile String line;
    private volatile long lineAtMs;
    private boolean loggedFirst;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            boolean keepGoing = fetch();
            Handler current = handler;
            if (keepGoing && current != null) {
                current.postDelayed(this, intervalMs);
            }
        }
    };

    public HostHardwareMetrics(Context context, NvHTTP http) {
        this.context = context.getApplicationContext();
        this.http = http;
    }

    public synchronized void start() {
        if (thread != null) return;
        thread = new HandlerThread("Host metrics", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(poll);
    }

    public synchronized void stop() {
        if (thread == null) return;
        handler.removeCallbacks(poll);
        handler = null;
        thread.quitSafely();
        thread = null;
    }

    /** The overlay line for the latest reading, or null when there is none (or it's stale) */
    public String latestLine() {
        String current = line;
        if (current == null || android.os.SystemClock.uptimeMillis() - lineAtMs > STALE_MS) {
            return null;
        }
        return current;
    }

    // Returns false when the host refused, so polling stops
    private boolean fetch() {
        try {
            String body = http.getHardwareMetrics();
            JSONObject json = new JSONObject(body);
            int hostIntervalMs = json.optInt("interval_ms", 0);
            intervalMs = Math.min(MAX_INTERVAL_MS, Math.max(MIN_INTERVAL_MS, hostIntervalMs));
            line = format(json);
            lineAtMs = android.os.SystemClock.uptimeMillis();
            if (!loggedFirst) {
                loggedFirst = true;
                LimeLog.info("Host hardware metrics: " + line + " (polling every " + intervalMs + " ms)");
            }
            return true;
        } catch (HostHttpResponseException e) {
            // 403: turned off on the host, or this client may not view them. 404: not served.
            if (e.getErrorCode() == 403 || e.getErrorCode() == 404) {
                LimeLog.warning("Host hardware metrics refused (" + e.getErrorCode() + "): " + e.getMessage() + "; not asking again this stream");
                line = null;
                return false;
            }
            // 409 (no running video yet) and 503 (busy) are passing
            LimeLog.info("Host hardware metrics not available now (" + e.getErrorCode() + ")");
            return true;
        } catch (java.io.FileNotFoundException e) {
            LimeLog.warning("Host doesn't serve hardware metrics; not asking again this stream");
            return false;
        } catch (Exception e) {
            LimeLog.warning("Host hardware metrics failed: " + e);
            return true;
        }
    }

    private String format(JSONObject json) throws JSONException {
        StringBuilder sb = new StringBuilder(context.getString(R.string.perf_overlay_host_metrics_prefix));
        JSONObject cpu = json.optJSONObject("cpu");
        boolean any = false;
        if (cpu != null && cpu.has("cpu_usage_percent")) {
            sb.append(' ').append(context.getString(R.string.perf_overlay_host_cpu, cpu.getDouble("cpu_usage_percent")));
            any = true;
        }
        JSONArray gpus = json.optJSONArray("gpus");
        if (gpus != null && gpus.length() > 0) {
            // The GPU encoding the stream comes first; the rest rarely matter while streaming
            JSONObject metrics = gpus.getJSONObject(0).optJSONObject("metrics");
            if (metrics != null) {
                if (metrics.has("gpu_usage_percent")) {
                    sb.append(any ? " · " : " ").append(context.getString(R.string.perf_overlay_host_gpu, metrics.getDouble("gpu_usage_percent")));
                    any = true;
                }
                if (metrics.has("gpu_encoder_percent")) {
                    sb.append(any ? " · " : " ").append(context.getString(R.string.perf_overlay_host_encoder, metrics.getDouble("gpu_encoder_percent")));
                    any = true;
                }
                if (metrics.has("gpu_temperature_c")) {
                    sb.append(any ? " · " : " ").append(context.getString(R.string.perf_overlay_host_temperature, metrics.getDouble("gpu_temperature_c")));
                    any = true;
                }
                if (metrics.has("gpu_vram_used_mb")) {
                    sb.append(any ? " · " : " ").append(context.getString(R.string.perf_overlay_host_vram,
                            metrics.getDouble("gpu_vram_used_mb") / 1024.0));
                    any = true;
                }
            }
        }
        if (!any) {
            sb.append(' ').append(context.getString(R.string.perf_overlay_host_metrics_none));
        }
        return sb.toString();
    }
}
