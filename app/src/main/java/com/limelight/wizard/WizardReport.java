package com.limelight.wizard;

import com.limelight.nvstream.http.NvApp;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the setup wizard learned: survey answers, device and host probes,
 * network samples and trial-stream measurements. The recommender reads only this.
 */
public class WizardReport {

    // Survey
    public static final int PRIORITY_LATENCY = 0;
    public static final int PRIORITY_BALANCED = 1;
    public static final int PRIORITY_SMOOTH = 2;
    public static final int PRIORITY_IMAGE = 3;
    public static final int POWER_PLUGGED = 0;
    public static final int POWER_BATTERY = 1;
    public static final int POWER_MIXED = 2;
    public static final int PLACE_NEAR = 0;
    public static final int PLACE_HOME = 1;
    public static final int PLACE_REMOTE = 2;

    public int priority = PRIORITY_BALANCED;
    public int power = POWER_MIXED;
    public int place = PLACE_NEAR;
    public boolean wantHdr = true;

    // Display
    public int panelWidth;
    public int panelHeight;
    public float maxRefresh = 60f;
    public final List<Float> refreshRates = new ArrayList<>();
    public boolean hdrDisplay;
    public float hdrMaxNits;

    // Decoders
    public String hevcDecoder;
    public boolean hevcMain10;
    public boolean hevcLowLatency;
    public String av1Decoder;
    public boolean av1Main10;
    public String avcDecoder;
    public int hevcMaxFps1080;
    /** The device can decode PyroWave (64-bit, Android 10+, Vulkan 1.1; PEN-9). */
    public boolean pyrowaveDecoder;

    // Network link
    public static final int LINK_NONE = 0;
    public static final int LINK_WIFI = 1;
    public static final int LINK_ETHERNET = 2;
    public static final int LINK_CELLULAR = 3;
    public static final int LINK_OTHER = 4;
    public int link = LINK_NONE;
    public int wifiFreqMhz;
    public int wifiRssi;
    public int wifiLinkMbps;
    public int wifiStandard;

    // Thermals and power
    public int thermalStatus = -1;
    public float thermalHeadroom = Float.NaN;
    public int batteryPct = -1;
    public boolean charging;
    public boolean powerSaveMode;

    // Host
    public boolean hostReachable;
    public String hostName;
    public String hostGpu;
    public String hostVersion;
    public long hostCodecModes;
    public boolean hostVirtualDisplay;
    public boolean hostVirtualDisplayReady;
    public boolean hostRunningApp;
    public NvApp testApp;

    // Network samples (TCP connect time to the host)
    public int pingSamples;
    public int pingLost;
    public float pingMedianMs = Float.NaN;
    public float pingP95Ms = Float.NaN;
    public float pingJitterMs = Float.NaN;
    public boolean hostOnLan;

    // Trial streams
    public final List<Trial> trials = new ArrayList<>();

    public static final long SCM_H264 = 0x00000001;
    public static final long SCM_HEVC = 0x00000100;
    public static final long SCM_HEVC_MAIN10 = 0x00000200;
    public static final long SCM_AV1_MAIN8 = 0x00010000;
    public static final long SCM_AV1_MAIN10 = 0x00020000;
    // Vibepollo's PyroWave bits (docs/pyrowave-protocol.md)
    public static final long SCM_PYROWAVE = 0x00800000;
    public static final long SCM_PYROWAVE_10BIT = 0x02000000;

    public boolean hostPyrowave() {
        return (hostCodecModes & SCM_PYROWAVE) != 0;
    }

    public boolean hostPyrowave10() {
        return (hostCodecModes & SCM_PYROWAVE_10BIT) != 0;
    }

    // Vibepollo's UDP burst calibration (PEN-10), advertised in paired serverinfo
    public int hostUdpProbeVersion;
    public boolean hostUdpHandshake;
    public boolean hostUdpBurst;
    /** The host's routed wired link, Mbps; 0 when wireless, virtual or unknown. */
    public int hostLinkMbps;
    /** The UDP probe ran to the end (passed or not). */
    public boolean udpProbeRan;
    /** Highest rate that passed the UDP probe twice, kbps; 0 when none did. */
    public int udpCapacityKbps;
    /** Highest frame pace that passed, kbps; 0 when not measured (the host keeps its default). */
    public int udpPaceKbps;
    /** Why the probe gave no rate, or the last result's detail. */
    public String udpNote;

    /** The UDP probe measured a rate: it replaces the link-speed guess. */
    public boolean udpMeasured() {
        return udpProbeRan && udpCapacityKbps > 0;
    }

    /**
     * The most a PyroWave stream should use, kbps: 90% of the measured UDP capacity when the probe
     * found a clean rate, otherwise (no probe, or no clean rate) two thirds of the link speed.
     * 0 when PyroWave shouldn't be used.
     */
    public int pyrowaveBudgetKbps() {
        if (udpMeasured()) {
            return (int) (udpCapacityKbps * 0.9);
        }
        return (int) (pyrowaveLinkMbps() * 1000 * 0.65);
    }

    /**
     * Megabits per second the link to the host can carry for a PyroWave stream, or 0 when it's too
     * slow or unknown. Ethernet links are assumed to be gigabit. A UDP probe result wins.
     */
    public int pyrowaveLinkMbps() {
        if (!hostOnLan || place == PLACE_REMOTE) return 0;
        // A probe that found no clean rate doesn't disqualify PyroWave: the link-speed rule applies
        if (udpMeasured()) return udpCapacityKbps / 1000;
        if (link == LINK_ETHERNET) return 1000;
        if (link == LINK_WIFI && wifiFreqMhz >= 4900 && wifiLinkMbps >= 600 && wifiRssi >= -62) return wifiLinkMbps;
        return 0;
    }

    public boolean hostHevc() {
        // Old GFE builds don't report modes but always support HEVC
        return hostCodecModes == 0 || (hostCodecModes & SCM_HEVC) != 0;
    }

    public boolean hostHevc10() {
        return (hostCodecModes & SCM_HEVC_MAIN10) != 0;
    }

    public boolean hostAv1() {
        return (hostCodecModes & SCM_AV1_MAIN8) != 0;
    }

    public boolean isWifi24() {
        return link == LINK_WIFI && wifiFreqMhz > 0 && wifiFreqMhz < 3000;
    }

    public static class Trial {
        public final String label;
        public final int width;
        public final int height;
        public final int fps;
        public final int bitrateKbps;
        public final String pacing;
        public final String codec;
        public final boolean hdr;

        public boolean done;
        public boolean failed;
        public boolean retried;
        public String error;
        public float receivedFps;
        public float renderedFps;
        public float totalFps;
        public float decodeMs;
        public float lossPct;
        public float rttMs = Float.NaN;
        public float rttVarMs = Float.NaN;
        public float carriedMbps = Float.NaN;
        public String decoder;
        public boolean hdrActive;

        public Trial(String label, int width, int height, int fps, int bitrateKbps,
                     String pacing, String codec, boolean hdr) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrateKbps = bitrateKbps;
            this.pacing = pacing;
            this.codec = codec;
            this.hdr = hdr;
        }

        /** The stream arrived intact: almost every frame received and few lost. */
        public boolean networkClean() {
            return done && !failed && receivedFps >= fps * 0.95f && lossPct <= 1.0f;
        }

        /** The stream actually used most of its bitrate, so a clean result says something about the link. */
        public boolean loadedLink() {
            return !Float.isNaN(carriedMbps) && carriedMbps * 1000f >= bitrateKbps * 0.6f;
        }

        /** The device presented (nearly) every frame it received. */
        public boolean renderClean() {
            return done && !failed && renderedFps >= Math.min(receivedFps, fps) * 0.97f;
        }
    }
}
