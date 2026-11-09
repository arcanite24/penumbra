package com.limelight.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import com.limelight.LimeLog;
import com.limelight.R;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Reads the active network's Wi-Fi link on its own low-priority thread, every few seconds, so the
 * stats overlay only reads a cached snapshot. Needs no permission beyond network and Wi-Fi state;
 * the network name is never read because Android ties it to location access.
 * Ported from Nightjar (RamazanKara/nightjar, GPL-3.0).
 */
public final class WifiDiagnostics {
    private static final long INTERVAL_MS = 5_000;
    private final Context context;
    private HandlerThread thread;
    private Handler handler;
    private volatile WifiFacts latest;
    private WifiFacts logged;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            WifiFacts facts = read(context);
            latest = facts;
            logChange(facts);
            Handler current = handler;
            if (current != null) current.postDelayed(this, INTERVAL_MS);
        }
    };

    public WifiDiagnostics(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start() {
        if (thread != null) return;
        thread = new HandlerThread("Wi-Fi diagnostics", Process.THREAD_PRIORITY_BACKGROUND);
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

    /** The last reading; null before the first one or when Android gave no answer. */
    public WifiFacts latest() {
        return latest;
    }

    // Logs the link when its band, channel or standard changes, or the signal moves a grade
    private void logChange(WifiFacts facts) {
        if (facts == null) return;
        WifiFacts last = logged;
        if (last != null && last.link == facts.link && last.frequencyMhz == facts.frequencyMhz &&
                last.generation == facts.generation && last.signal() == facts.signal()) {
            return;
        }
        logged = facts;
        if (facts.onWifi()) {
            LimeLog.info("Wi-Fi link: " + facts.frequencyMhz + " MHz (channel " + facts.channel() + ", width " +
                    (facts.widthMhz == WifiFacts.UNKNOWN ? "?" : facts.widthMhz) + " MHz), RSSI " +
                    (facts.rssiDbm == WifiFacts.UNKNOWN ? "?" : facts.rssiDbm) + " dBm, tx/rx " +
                    facts.txMbps + "/" + facts.rxMbps + " Mbps, link " + facts.linkMbps + " Mbps, Wi-Fi " +
                    (facts.generation == WifiFacts.UNKNOWN ? "?" : facts.generation));
        }
        else {
            LimeLog.info("Stream network link: " + facts.link);
        }
    }

    /** One blocking read. Returns null when Android refuses or fails to answer. */
    @SuppressWarnings("deprecation")
    public static WifiFacts read(Context context) {
        try {
            ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (connectivity == null) return null;
            WifiFacts.Link link;
            WifiInfo info = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = connectivity.getActiveNetwork();
                NetworkCapabilities caps = network != null ? connectivity.getNetworkCapabilities(network) : null;
                if (caps == null) return network == null ? WifiFacts.notWifi(WifiFacts.Link.NONE) : null;
                link = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? WifiFacts.Link.WIFI :
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? WifiFacts.Link.ETHERNET :
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? WifiFacts.Link.CELLULAR :
                        WifiFacts.Link.OTHER;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && caps.getTransportInfo() instanceof WifiInfo) {
                    info = (WifiInfo) caps.getTransportInfo();
                }
            } else {
                NetworkInfo active = connectivity.getActiveNetworkInfo();
                if (active == null) return WifiFacts.notWifi(WifiFacts.Link.NONE);
                switch (active.getType()) {
                    case ConnectivityManager.TYPE_WIFI: link = WifiFacts.Link.WIFI; break;
                    case ConnectivityManager.TYPE_ETHERNET: link = WifiFacts.Link.ETHERNET; break;
                    case ConnectivityManager.TYPE_MOBILE: link = WifiFacts.Link.CELLULAR; break;
                    default: link = WifiFacts.Link.OTHER; break;
                }
            }
            if (link != WifiFacts.Link.WIFI && link != WifiFacts.Link.OTHER) return WifiFacts.notWifi(link);
            if (info == null && wifiManager != null) info = wifiManager.getConnectionInfo();
            if (info == null) return link == WifiFacts.Link.WIFI ? null : WifiFacts.notWifi(link);
            // A VPN such as Tailscale hides its carrier; a live Wi-Fi association most likely carries the stream.
            if (link == WifiFacts.Link.OTHER && info.getFrequency() <= 0) return WifiFacts.notWifi(link);
            int tx = WifiFacts.UNKNOWN, rx = WifiFacts.UNKNOWN, generation = WifiFacts.UNKNOWN;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tx = info.getTxLinkSpeedMbps();
                rx = info.getRxLinkSpeedMbps();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) generation = WifiFacts.generationOf(info.getWifiStandard());
            return new WifiFacts(WifiFacts.Link.WIFI, info.getRssi(), info.getFrequency(), width(info),
                    tx, rx, info.getLinkSpeed(), generation);
        } catch (RuntimeException e) {
            LimeLog.warning("Wi-Fi diagnostics unavailable: " + e);
            return null;
        }
    }

    // The access point's own announcement of its channel; Android has no width field for the connection.
    @SuppressLint("NewApi")
    private static int width(WifiInfo info) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return WifiFacts.UNKNOWN;
        List<ScanResult.InformationElement> elements = info.getInformationElements();
        if (elements == null) return WifiFacts.UNKNOWN;
        int width = WifiFacts.UNKNOWN;
        for (ScanResult.InformationElement element : elements) {
            ByteBuffer buffer = element.getBytes().asReadOnlyBuffer();
            byte[] body = new byte[buffer.remaining()];
            buffer.get(body);
            width = Math.max(width, WifiFacts.widthFromElement(element.getId(), element.getIdExt(), body));
        }
        return width;
    }

    /**
     * One line for the stats overlay: band, channel and width, signal, link speeds, standard and
     * whether the low-latency lock is held, then warnings and the stall rhythm when there is one.
     * Unknown values read "?" rather than vanish.
     */
    public static String describe(Context context, WifiFacts facts, boolean lowLatencyLockHeld, long stallPeriodMs) {
        StringBuilder text = new StringBuilder();
        if (facts == null) {
            text.append(context.getString(R.string.wifi_unavailable));
        } else if (facts.link == null || facts.link == WifiFacts.Link.NONE) {
            text.append(context.getString(R.string.wifi_link_none));
        } else if (facts.link == WifiFacts.Link.ETHERNET) {
            text.append(context.getString(R.string.wifi_link_ethernet));
        } else if (facts.link == WifiFacts.Link.CELLULAR) {
            text.append(context.getString(R.string.wifi_link_cellular));
        } else if (facts.link == WifiFacts.Link.OTHER) {
            text.append(context.getString(R.string.wifi_link_other));
        } else {
            WifiFacts.Band band = facts.band();
            String bandText = band == null ? "?" : context.getString(band == WifiFacts.Band.GHZ_2_4 ?
                    R.string.wifi_band_24 : band == WifiFacts.Band.GHZ_5 ? R.string.wifi_band_5 : R.string.wifi_band_6);
            int channel = facts.channel();
            text.append(context.getString(R.string.wifi_line_band, bandText,
                    channel == WifiFacts.UNKNOWN ? "?" : String.valueOf(channel),
                    facts.widthMhz == WifiFacts.UNKNOWN ? "?" : String.valueOf(facts.widthMhz)));

            WifiFacts.Signal signal = facts.signal();
            text.append(", ");
            if (signal == null) {
                text.append(context.getString(R.string.wifi_signal_unknown));
            } else {
                text.append(context.getString(R.string.wifi_line_signal, facts.rssiDbm,
                        context.getString(signal == WifiFacts.Signal.EXCELLENT ? R.string.wifi_signal_excellent :
                                signal == WifiFacts.Signal.GOOD ? R.string.wifi_signal_good :
                                signal == WifiFacts.Signal.FAIR ? R.string.wifi_signal_fair : R.string.wifi_signal_weak)));
            }

            text.append(", ");
            if (facts.txMbps != WifiFacts.UNKNOWN || facts.rxMbps != WifiFacts.UNKNOWN) {
                text.append(context.getString(R.string.wifi_line_speed, mbps(facts.txMbps), mbps(facts.rxMbps)));
            } else {
                text.append(context.getString(R.string.wifi_line_speed_single, mbps(facts.linkMbps)));
            }

            if (facts.generation != WifiFacts.UNKNOWN) {
                text.append(", ").append(facts.generation == 0 ?
                        context.getString(R.string.wifi_standard_legacy) :
                        facts.generation == 6 && band == WifiFacts.Band.GHZ_6 ?
                                context.getString(R.string.wifi_standard_6e) :
                                context.getString(R.string.wifi_standard, facts.generation));
            }
            if (lowLatencyLockHeld) {
                text.append(", ").append(context.getString(R.string.wifi_lock_held));
            }
        }
        if (facts != null && facts.slowBand()) text.append('\n').append(context.getString(R.string.wifi_warn_24));
        if (facts != null && facts.weakSignal()) text.append('\n').append(context.getString(R.string.wifi_warn_weak));
        if (stallPeriodMs > 0) {
            text.append('\n').append(context.getString(R.string.wifi_line_rhythm, period(context, stallPeriodMs)));
        }
        return text.toString();
    }

    private static String mbps(int value) {
        return value == WifiFacts.UNKNOWN ? "?" : String.valueOf(value);
    }

    /** "45 s" below 90 seconds, otherwise minutes to the nearest half. */
    public static String period(Context context, long periodMs) {
        long seconds = Math.round(periodMs / 1000.0);
        if (seconds < 90) return context.getString(R.string.wifi_period_seconds, (int) seconds);
        double minutes = Math.round(seconds / 30.0) / 2.0;
        return context.getString(R.string.wifi_period_minutes,
                minutes == Math.rint(minutes) ? String.format(java.util.Locale.ROOT, "%d", (long) minutes) :
                        String.format(java.util.Locale.ROOT, "%.1f", minutes));
    }
}
