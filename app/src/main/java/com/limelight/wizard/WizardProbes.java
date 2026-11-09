package com.limelight.wizard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Range;
import android.view.Display;

import com.limelight.LimeLog;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.utils.ServerHelper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Device, host and network probes for the setup wizard (PEN-8). */
public final class WizardProbes {

    public static final int GOOD = 0;
    public static final int OK = 1;
    public static final int WARN = 2;
    public static final int BAD = 3;
    public static final int INFO = 4;

    public static class Fact {
        public final String label;
        public final String value;
        public final String detail;
        public final int status;

        Fact(String label, String value, String detail, int status) {
            this.label = label;
            this.value = value;
            this.detail = detail;
            this.status = status;
        }
    }

    private WizardProbes() {}

    // ---------------------------------------------------------------- device

    @SuppressLint("DefaultLocale")
    public static List<Fact> probeDevice(Activity activity, WizardReport r) {
        List<Fact> facts = new ArrayList<>();

        String soc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Build.SOC_MODEL : Build.HARDWARE;
        ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mem);
        facts.add(new Fact("Device", Build.MODEL,
                String.format("Android %s · %s · %.0f GB RAM", Build.VERSION.RELEASE, soc,
                        Math.ceil(mem.totalMem / 1024f / 1024f / 1024f)), INFO));

        probeDisplay(activity, r, facts);
        probeDecoders(r, facts);
        probePower(activity, r, facts);
        return facts;
    }

    @SuppressLint("DefaultLocale")
    private static void probeDisplay(Activity activity, WizardReport r, List<Fact> facts) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        r.panelWidth = Math.max(size.x, size.y);
        r.panelHeight = Math.min(size.x, size.y);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode current = display.getMode();
            for (Display.Mode mode : display.getSupportedModes()) {
                if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                        && mode.getPhysicalHeight() == current.getPhysicalHeight()) {
                    float hz = Math.round(mode.getRefreshRate() * 100) / 100f;
                    if (!r.refreshRates.contains(hz)) {
                        r.refreshRates.add(hz);
                    }
                }
            }
        }
        if (r.refreshRates.isEmpty()) {
            r.refreshRates.add(display.getRefreshRate());
        }
        Collections.sort(r.refreshRates);
        r.maxRefresh = r.refreshRates.get(r.refreshRates.size() - 1);

        StringBuilder rates = new StringBuilder();
        for (float hz : r.refreshRates) {
            if (rates.length() > 0) rates.append(" / ");
            rates.append(Math.round(hz));
        }
        facts.add(new Fact("Screen", String.format("%d×%d", r.panelWidth, r.panelHeight),
                "Refresh modes: " + rates + " Hz",
                r.maxRefresh >= 119 ? GOOD : r.maxRefresh >= 89 ? OK : INFO));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Display.HdrCapabilities caps = display.getHdrCapabilities();
            if (caps != null) {
                for (int type : caps.getSupportedHdrTypes()) {
                    if (type == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                        r.hdrDisplay = true;
                    }
                }
                r.hdrMaxNits = caps.getDesiredMaxLuminance();
            }
        }
        facts.add(new Fact("HDR", r.hdrDisplay ? "HDR10" : "Not supported",
                r.hdrDisplay && r.hdrMaxNits > 0 ? String.format("Peak about %.0f nits", r.hdrMaxNits) : null,
                r.hdrDisplay ? GOOD : INFO));
    }

    @SuppressLint("DefaultLocale")
    private static void probeDecoders(WizardReport r, List<Fact> facts) {
        MediaCodecInfo hevc = safeDecoder("video/hevc");
        MediaCodecInfo av1 = safeDecoder("video/av01");
        MediaCodecInfo avc = safeDecoder("video/avc");

        if (hevc != null) {
            r.hevcDecoder = hevc.getName();
            MediaCodecInfo.CodecCapabilities caps = capabilities(hevc, "video/hevc");
            if (caps != null) {
                for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                    if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                            || pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) {
                        r.hevcMain10 = true;
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    r.hevcLowLatency = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
                }
                r.hevcMaxFps1080 = maxFps(caps, 1920, 1080);
            }
            facts.add(new Fact("HEVC decoder", r.hevcDecoder,
                    joinNonEmpty(r.hevcMain10 ? "10-bit" : "8-bit only",
                            r.hevcLowLatency ? "low-latency mode" : null,
                            r.hevcMaxFps1080 > 0 ? "up to " + r.hevcMaxFps1080 + " fps at 1080p" : null),
                    GOOD));
        } else {
            facts.add(new Fact("HEVC decoder", "None", "Falls back to H.264", WARN));
        }

        if (av1 != null) {
            r.av1Decoder = av1.getName();
            MediaCodecInfo.CodecCapabilities caps = capabilities(av1, "video/av01");
            if (caps != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                    if (pl.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                            || pl.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10) {
                        r.av1Main10 = true;
                    }
                }
            }
            facts.add(new Fact("AV1 decoder", r.av1Decoder, r.av1Main10 ? "10-bit" : "8-bit", GOOD));
        } else {
            facts.add(new Fact("AV1 decoder", "None", null, INFO));
        }

        if (avc != null) {
            r.avcDecoder = avc.getName();
        }

        r.pyrowaveDecoder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && com.limelight.binding.video.VulkanRendererBridge.isPyrowaveSupported();
        facts.add(new Fact("PyroWave decoder", r.pyrowaveDecoder ? "Vulkan" : "Not supported",
                r.pyrowaveDecoder ? null : "Needs 64-bit Android 10+ with Vulkan 1.1", r.pyrowaveDecoder ? GOOD : INFO));
    }

    /** The hardware decoder the stream would use: low-latency variants first, software never. */
    private static MediaCodecInfo safeDecoder(String mime) {
        MediaCodecInfo first = null;
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder() || isSoftware(info)) {
                    continue;
                }
                boolean supports = false;
                for (String type : info.getSupportedTypes()) {
                    supports |= type.equalsIgnoreCase(mime);
                }
                if (!supports) {
                    continue;
                }
                if (info.getName().toLowerCase(Locale.ROOT).contains("low_latency")) {
                    return info;
                }
                if (first == null) {
                    first = info;
                }
            }
        } catch (Exception ignored) {
        }
        return first;
    }

    private static boolean isSoftware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isSoftwareOnly();
        }
        String name = info.getName().toLowerCase(Locale.ROOT);
        return name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw.");
    }

    private static MediaCodecInfo.CodecCapabilities capabilities(MediaCodecInfo info, String mime) {
        try {
            return info.getCapabilitiesForType(mime);
        } catch (Exception e) {
            return null;
        }
    }

    private static int maxFps(MediaCodecInfo.CodecCapabilities caps, int w, int h) {
        try {
            MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
            if (video == null || !video.isSizeSupported(w, h)) {
                return 0;
            }
            Range<Double> rates = video.getSupportedFrameRatesFor(w, h);
            return (int) Math.floor(rates.getUpper());
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressLint("DefaultLocale")
    private static void probePower(Activity activity, WizardReport r, List<Fact> facts) {
        PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        r.powerSaveMode = pm.isPowerSaveMode();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            r.thermalStatus = pm.getCurrentThermalStatus();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            r.thermalHeadroom = pm.getThermalHeadroom(10);
        }
        String thermalText;
        int thermalState;
        switch (r.thermalStatus) {
            case PowerManager.THERMAL_STATUS_NONE: thermalText = "Cool"; thermalState = GOOD; break;
            case PowerManager.THERMAL_STATUS_LIGHT: thermalText = "Warm"; thermalState = OK; break;
            case PowerManager.THERMAL_STATUS_MODERATE: thermalText = "Hot"; thermalState = WARN; break;
            case -1: thermalText = "Unknown"; thermalState = INFO; break;
            default: thermalText = "Throttling"; thermalState = BAD; break;
        }
        String headroom = Float.isNaN(r.thermalHeadroom) || r.thermalHeadroom <= 0 ? null
                : String.format("%.0f%% of the throttle limit used", r.thermalHeadroom * 100);
        facts.add(new Fact("Thermals", thermalText, headroom, thermalState));

        Intent battery = activity.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            r.batteryPct = level >= 0 && scale > 0 ? level * 100 / scale : -1;
            r.charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }
        facts.add(new Fact("Battery", r.batteryPct >= 0 ? r.batteryPct + "%" : "Unknown",
                joinNonEmpty(r.charging ? "Charging" : "On battery", r.powerSaveMode ? "Battery saver on" : null),
                r.powerSaveMode ? WARN : INFO));
    }

    // ---------------------------------------------------------------- link

    @SuppressLint({"DefaultLocale", "MissingPermission"})
    public static List<Fact> probeLink(Context context, WizardReport r) {
        List<Fact> facts = new ArrayList<>();
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        WifiInfo wifi = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            NetworkCapabilities caps = network != null ? cm.getNetworkCapabilities(network) : null;
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    r.link = WizardReport.LINK_ETHERNET;
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    r.link = WizardReport.LINK_WIFI;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            && caps.getTransportInfo() instanceof WifiInfo) {
                        wifi = (WifiInfo) caps.getTransportInfo();
                    }
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    r.link = WizardReport.LINK_CELLULAR;
                } else {
                    r.link = WizardReport.LINK_OTHER;
                }
            }
        }
        if (r.link == WizardReport.LINK_WIFI || r.link == WizardReport.LINK_NONE) {
            if (wifi == null) {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifi = wm.getConnectionInfo();
                }
            }
            if (wifi != null && wifi.getNetworkId() != -1 || wifi != null && wifi.getFrequency() > 0) {
                r.link = WizardReport.LINK_WIFI;
                r.wifiFreqMhz = wifi.getFrequency();
                r.wifiRssi = wifi.getRssi();
                r.wifiLinkMbps = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && wifi.getRxLinkSpeedMbps() > 0
                        ? wifi.getRxLinkSpeedMbps() : wifi.getLinkSpeed();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    r.wifiStandard = wifi.getWifiStandard();
                }
            }
        }

        switch (r.link) {
            case WizardReport.LINK_ETHERNET:
                facts.add(new Fact("Connection", "Ethernet", "Best case for streaming", GOOD));
                break;
            case WizardReport.LINK_WIFI: {
                String band = r.wifiFreqMhz >= 5925 ? "6 GHz" : r.wifiFreqMhz >= 4900 ? "5 GHz"
                        : r.wifiFreqMhz > 0 ? "2.4 GHz" : "Wi-Fi";
                String std = r.wifiStandard >= 4 ? "Wi-Fi " + r.wifiStandard + " · " : "";
                int state = r.isWifi24() ? BAD : r.wifiRssi < -70 ? WARN : r.wifiRssi < -60 ? OK : GOOD;
                String detail = r.isWifi24()
                        ? "2.4 GHz is crowded and jittery. Switch to 5 GHz for high frame rates."
                        : null;
                facts.add(new Fact("Connection", std + band, detail, state));
                facts.add(new Fact("Signal", r.wifiRssi + " dBm",
                        r.wifiRssi >= -55 ? "Excellent" : r.wifiRssi >= -65 ? "Good"
                                : r.wifiRssi >= -72 ? "Fair: expect occasional drops" : "Weak: move closer to the router",
                        r.wifiRssi >= -60 ? GOOD : r.wifiRssi >= -70 ? OK : WARN));
                if (r.wifiLinkMbps > 0) {
                    facts.add(new Fact("Link speed", r.wifiLinkMbps + " Mbps",
                            r.pyrowaveDecoder && r.wifiLinkMbps >= 600
                                    ? "About a third of this suits HEVC; PyroWave can use about two thirds"
                                    : "Usable streaming bitrate is about a third of this",
                            r.wifiLinkMbps >= 400 ? GOOD : r.wifiLinkMbps >= 150 ? OK : WARN));
                }
                break;
            }
            case WizardReport.LINK_CELLULAR:
                facts.add(new Fact("Connection", "Mobile data", "Bitrate will be capped", WARN));
                break;
            default:
                facts.add(new Fact("Connection", "Unknown", null, INFO));
                break;
        }
        return facts;
    }

    // ---------------------------------------------------------------- host

    @SuppressLint("DefaultLocale")
    public static List<Fact> probeHost(Context context, ComputerDetails computer,
                                       ComputerManagerService.ComputerManagerBinder binder,
                                       WizardReport r) {
        List<Fact> facts = new ArrayList<>();
        r.hostName = computer.name;
        try {
            NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                    binder.getUniqueId(), computer.serverCert, PlatformBinding.getCryptoProvider(context));
            String info = http.getServerInfo(true);
            r.hostReachable = true;
            r.hostGpu = http.getGpuType(info);
            r.hostVersion = http.getServerVersion(info);
            r.hostCodecModes = http.getServerCodecModeSupport(info);
            r.hostVirtualDisplay = http.getServerSupportsVDisplay(info);
            r.hostVirtualDisplayReady = http.getServerVDisplayDriverReady(info);
            r.hostRunningApp = http.getCurrentGame(info) != 0;
            // Vibepollo's UDP burst calibration (only in paired serverinfo, with PyroWave enabled)
            r.hostUdpProbeVersion = (int) NvHTTP.getServerInfoLong(info, "PyroWaveUdpProbeVersion", 0);
            r.hostUdpHandshake = NvHTTP.getServerInfoLong(info, "PyroWaveUdpHandshakeVersion", 0) >= 1;
            r.hostUdpBurst = NvHTTP.getServerInfoLong(info, "PyroWaveUdpProbeBurstVersion", 0) >= 1;
            r.hostLinkMbps = (int) NvHTTP.getServerInfoLong(info, "PyroWaveHostLinkMbps", 0);

            facts.add(new Fact("Host", computer.name,
                    joinNonEmpty(r.hostVersion != null ? "Server " + r.hostVersion : null,
                            r.hostGpu != null && !r.hostGpu.isEmpty() ? r.hostGpu : null), GOOD));

            List<String> codecs = new ArrayList<>();
            if ((r.hostCodecModes & WizardReport.SCM_H264) != 0) codecs.add("H.264");
            if (r.hostHevc()) codecs.add(r.hostHevc10() ? "HEVC 10-bit" : "HEVC");
            if (r.hostAv1()) codecs.add((r.hostCodecModes & WizardReport.SCM_AV1_MAIN10) != 0 ? "AV1 10-bit" : "AV1");
            if (r.hostPyrowave()) codecs.add(r.hostPyrowave10() ? "PyroWave 10-bit" : "PyroWave");
            facts.add(new Fact("Host encoders", android.text.TextUtils.join(" · ", codecs), null,
                    r.hostHevc() ? GOOD : WARN));

            facts.add(new Fact("HDR from host", r.hostHevc10() ? "Available" : "Not available",
                    r.hostHevc10() ? "10-bit HEVC encode" : null, r.hostHevc10() ? GOOD : INFO));

            facts.add(new Fact("Virtual display", r.hostVirtualDisplay
                    ? (r.hostVirtualDisplayReady ? "Ready" : "Driver not ready") : "Not supported",
                    r.hostVirtualDisplay ? "Host renders at your exact resolution and refresh rate" : null,
                    r.hostVirtualDisplay && r.hostVirtualDisplayReady ? GOOD : r.hostVirtualDisplay ? WARN : INFO));

            try {
                List<NvApp> apps = http.getAppList();
                NvApp pick = null;
                for (NvApp app : apps) {
                    if (app.getAppName().equalsIgnoreCase("Desktop")) {
                        pick = app;
                        break;
                    }
                }
                if (pick == null && !apps.isEmpty()) {
                    pick = apps.get(0);
                }
                r.testApp = pick;
                facts.add(new Fact("Test app", pick != null ? pick.getAppName() : "None",
                        r.hostRunningApp ? "Something is running on the host now; stream tests will replace it" : null,
                        pick == null ? BAD : r.hostRunningApp ? WARN : GOOD));
            } catch (Exception e) {
                facts.add(new Fact("Test app", "Unavailable", e.getMessage(), WARN));
            }
        } catch (Exception e) {
            r.hostReachable = false;
            facts.add(new Fact("Host", computer.name, "Not reachable: " + e.getMessage(), BAD));
        }
        return facts;
    }

    // ---------------------------------------------------------------- network

    public interface PingListener {
        void onSample(int index, int total, float ms);
    }

    /** Times repeated TCP connects to the host's HTTP port: a ping that needs no extra permissions. */
    @SuppressLint("DefaultLocale")
    public static List<Fact> probeNetwork(Context context, ComputerDetails computer, WizardReport r, int samples, PingListener listener) {
        List<Fact> facts = new ArrayList<>();
        ComputerDetails.AddressTuple addr = computer.activeAddress;
        if (addr == null) {
            facts.add(new Fact("Latency", "No address", null, BAD));
            return facts;
        }
        List<Float> times = new ArrayList<>();
        int lost = 0;
        // Same Wi-Fi low-latency lock the stream holds, so power saving doesn't inflate the numbers
        WifiManager.WifiLock lock = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Penumbra wizard probe");
                lock.setReferenceCounted(false);
                lock.acquire();
            } catch (Exception e) {
                lock = null;
            }
        }
        try {
            InetAddress ip = InetAddress.getByName(addr.address);
            // Warm-up connects (ARP, radio wake) aren't counted
            for (int i = 0; i < 3; i++) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(ip, addr.port), 1000);
                } catch (Exception ignored) {
                }
            }
            r.hostOnLan = ip.isSiteLocalAddress() || ip.isLinkLocalAddress() || ip.isLoopbackAddress();
            for (int i = 0; i < samples; i++) {
                long start = SystemClock.elapsedRealtimeNanos();
                try (Socket s = new Socket()) {
                    s.setTcpNoDelay(true);
                    s.connect(new InetSocketAddress(ip, addr.port), 1000);
                    float ms = (SystemClock.elapsedRealtimeNanos() - start) / 1e6f;
                    times.add(ms);
                    if (listener != null) listener.onSample(i, samples, ms);
                } catch (Exception e) {
                    lost++;
                    if (listener != null) listener.onSample(i, samples, -1);
                }
                SystemClock.sleep(40);
            }
        } catch (Exception e) {
            facts.add(new Fact("Latency", "Failed", e.getMessage(), BAD));
            return facts;
        } finally {
            if (lock != null) {
                lock.release();
            }
        }

        r.pingSamples = samples;
        r.pingLost = lost;
        if (!times.isEmpty()) {
            float jitter = 0;
            for (int i = 1; i < times.size(); i++) {
                jitter += Math.abs(times.get(i) - times.get(i - 1));
            }
            r.pingJitterMs = times.size() > 1 ? jitter / (times.size() - 1) : 0;
            List<Float> sorted = new ArrayList<>(times);
            Collections.sort(sorted);
            r.pingMedianMs = sorted.get(sorted.size() / 2);
            r.pingP95Ms = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1));
        }

        facts.add(new Fact("Route", r.hostOnLan ? "Local network" : "Remote / VPN", addr.address,
                r.hostOnLan ? GOOD : OK));
        if (Float.isNaN(r.pingMedianMs)) {
            facts.add(new Fact("Latency", "No replies", null, BAD));
        } else {
            facts.add(new Fact("Latency", String.format("%.1f ms", r.pingMedianMs),
                    String.format("95%% of samples under %.1f ms", r.pingP95Ms),
                    r.pingMedianMs < 5 ? GOOD : r.pingMedianMs < 15 ? OK : r.pingMedianMs < 40 ? WARN : BAD));
            facts.add(new Fact("Jitter", String.format("%.1f ms", r.pingJitterMs),
                    r.pingJitterMs < 2 ? "Steady" : r.pingJitterMs < 6 ? "Some variation" : "Unsteady: frames will arrive late",
                    r.pingJitterMs < 2 ? GOOD : r.pingJitterMs < 6 ? OK : WARN));
        }
        facts.add(new Fact("Dropped probes", lost + " of " + samples, null,
                lost == 0 ? GOOD : lost <= samples / 20 ? WARN : BAD));
        return facts;
    }

    // ---------------------------------------------------------------- UDP burst calibration (PEN-10)

    public interface ProgressListener {
        void onProgress(String text);
    }

    /** The host offers Vibepollo's /pyrowave-udp-probe and PyroWave could be picked on this link. */
    public static boolean canProbeUdp(WizardReport r) {
        return r.hostReachable && r.hostUdpProbeVersion >= 1 && r.hostPyrowave() && r.pyrowaveDecoder
                && r.hostOnLan && r.place != WizardReport.PLACE_REMOTE
                && (r.link == WizardReport.LINK_ETHERNET || r.link == WizardReport.LINK_WIFI);
    }

    /** The fastest the link could go, kbps: the client's link speed, bounded by the host's wired link. */
    static int udpLinkKbps(WizardReport r) {
        int mbps = r.link == WizardReport.LINK_ETHERNET ? 1000 : Math.max(0, r.wifiLinkMbps);
        if (r.hostLinkMbps > 0) mbps = mbps > 0 ? Math.min(mbps, r.hostLinkMbps) : r.hostLinkMbps;
        return (int) Math.min(PyroWaveUdpGrade.MAX_KBPS, mbps * 1000L);
    }

    /**
     * Measures how much UDP the host can burst to this device with Vibepollo's probe, then which
     * frame pace still delivers whole frames. Sets r.udp*; takes 20-40 s. The host refuses while
     * it streams, so this runs before the trial streams.
     */
    @SuppressLint("DefaultLocale")
    public static List<Fact> probeUdp(Context context, ComputerDetails computer,
                                      ComputerManagerService.ComputerManagerBinder binder,
                                      WizardReport r, final ProgressListener listener) {
        List<Fact> facts = new ArrayList<>();
        int linkKbps = udpLinkKbps(r);
        if (linkKbps < PyroWaveUdpGrade.MIN_KBPS) {
            facts.add(new Fact("UDP burst test", "Skipped", "The link speed is unknown", INFO));
            return facts;
        }
        WifiManager.WifiLock lock = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Penumbra wizard UDP probe");
                lock.setReferenceCounted(false);
                lock.acquire();
            } catch (Exception e) {
                lock = null;
            }
        }
        try {
            NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                    binder.getUniqueId(), computer.serverCert, PlatformBinding.getCryptoProvider(context));
            final PyroWaveUdpProbe udp = new PyroWaveUdpProbe(http, r.hostUdpHandshake);
            final int[] count = {0};
            PyroWaveUdpGrade.Probe probe = (kbps, fps, paceKbps) -> {
                count[0]++;
                if (listener != null) {
                    listener.onProgress(fps > 0
                            ? String.format(Locale.US, "UDP test %d · %s frames at %s pace…", count[0], mbps(kbps), mbps(paceKbps))
                            : String.format(Locale.US, "UDP test %d · %s…", count[0], mbps(kbps)));
                }
                PyroWaveUdpGrade.Result result = udp.run(kbps, fps, paceKbps);
                if (listener != null) {
                    listener.onProgress(String.format(Locale.US, "UDP test %d · %s · %s", count[0], mbps(kbps),
                            result.passed() ? "clean" : result.failure()));
                }
                return result;
            };

            int start = (int) (linkKbps * (r.link == WizardReport.LINK_ETHERNET ? 0.6 : 0.4));
            PyroWaveUdpGrade.Capacity cap = PyroWaveUdpGrade.searchCapacity(start, linkKbps, 10000, 10, probe);
            r.udpProbeRan = cap.last != null && (cap.last.expected > 0 || cap.kbps > 0);
            r.udpCapacityKbps = cap.kbps;
            if (cap.kbps <= 0) {
                r.udpNote = cap.last != null ? cap.last.failure() : "no probe ran";
            }
            LimeLog.info("PyroWave UDP calibration: " + (cap.kbps > 0 ? cap.kbps + " kbps" : "no rate (" + r.udpNote + ")")
                    + " after " + cap.probes + " probes, link " + linkKbps + " kbps");
            if (!r.udpProbeRan) {
                facts.add(new Fact("UDP burst test", "Failed", r.udpNote, WARN));
                return facts;
            }
            facts.add(new Fact("UDP burst capacity", cap.kbps > 0 ? mbps(cap.kbps) : "No clean rate",
                    cap.kbps > 0
                            ? String.format(Locale.US, "Passed twice at this rate: ≤0.1%% loss, ≤1%% in any 100 ms, ≤4 ms variation · %d tests", cap.probes)
                            : r.udpNote,
                    cap.kbps >= 300000 ? GOOD : cap.kbps >= 150000 ? OK : cap.kbps > 0 ? WARN : BAD));

            if (cap.kbps > 0 && r.hostUdpBurst) {
                WizardRecommender.Plan plan = WizardRecommender.plan(r);
                int fps = Math.max(10, Math.min(500, plan.fps));
                int image = (int) Math.round(WizardRecommender.pyrowaveKbps(plan.width, plan.height, fps, false));
                int kbps = Math.max(PyroWaveUdpGrade.MIN_KBPS,
                        PyroWaveUdpGrade.round5(Math.min(cap.kbps * 0.8, image)));
                // A frame must leave well within its frame interval
                int floor = (int) (kbps * 1.25);
                PyroWaveUdpGrade.Pace pace = PyroWaveUdpGrade.searchPace(kbps, fps, linkKbps, floor, 6, probe);
                r.udpPaceKbps = pace.paceKbps;
                LimeLog.info("PyroWave UDP pace: " + (pace.paceKbps > 0 ? pace.paceKbps + " kbps" : "none passed")
                        + " for " + kbps + " kbps at " + fps + " fps after " + pace.probes + " probes");
                facts.add(new Fact("PyroWave frame pace", pace.paceKbps > 0 ? mbps(pace.paceKbps) : "Host default",
                        pace.paceKbps > 0
                                ? String.format(Locale.US, "Whole %d fps frames arrived intact when sent at this rate", fps)
                                : (pace.last != null ? pace.last.failure() : null),
                        pace.paceKbps > 0 ? GOOD : INFO));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            facts.add(new Fact("UDP burst test", "Failed", e.getMessage(), WARN));
        } finally {
            if (lock != null) {
                lock.release();
            }
        }
        return facts;
    }

    static String joinNonEmpty(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    static String mbps(int kbps) {
        return String.format(Locale.US, kbps % 1000 == 0 ? "%.0f Mbps" : "%.1f Mbps", kbps / 1000f);
    }
}
