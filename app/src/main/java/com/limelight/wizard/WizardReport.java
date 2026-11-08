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
