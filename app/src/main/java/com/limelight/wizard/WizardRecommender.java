package com.limelight.wizard;

import android.content.SharedPreferences;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link WizardReport} into stream settings (PEN-8). Each setting carries the
 * reason it was picked so the results screen can explain itself.
 */
public final class WizardRecommender {

    public static final String KEY_RESOLUTION = "list_resolution";
    public static final String KEY_FPS = "list_fps";
    public static final String KEY_BITRATE = "seekbar_bitrate_kbps";
    public static final String KEY_CODEC = "video_format";
    public static final String KEY_HDR = "checkbox_enable_hdr";
    public static final String KEY_PACING = "frame_pacing";
    public static final String KEY_VDISPLAY = "checkbox_use_virtual_display";
    public static final String KEY_ULL = "checkbox_ultra_low_latency";
    public static final String KEY_REDUCE_REFRESH = "checkbox_reduce_refresh_rate";

    private static final int[][] RESOLUTIONS = {
            {3840, 2160}, {2560, 1440}, {1920, 1080}, {1280, 720}, {854, 480}};

    public static class Plan {
        public int width;
        public int height;
        public int fps;
        public int bitrateKbps;
        public String codec;
        public boolean hdr;
        public String pacing;
        public boolean virtualDisplay;
        public boolean ultraLowLatency;
        public Boolean reduceRefresh; // null: leave as is
        public final Map<String, String> reasons = new LinkedHashMap<>();
        public final List<String> notes = new ArrayList<>();
    }

    public static class Rec {
        public final String key;
        public final String title;
        public final Object value;
        public final String display;
        public final String current;
        public final String reason;

        Rec(String key, String title, Object value, String display, String current, String reason) {
            this.key = key;
            this.title = title;
            this.value = value;
            this.display = display;
            this.current = current;
            this.reason = reason;
        }

        public boolean changes() {
            return current == null || !current.equals(display);
        }
    }

    private WizardRecommender() {}

    // ---------------------------------------------------------------- base plan

    public static Plan plan(WizardReport r) {
        Plan p = new Plan();

        // Resolution: the largest standard mode that fits the panel
        int[] res = RESOLUTIONS[RESOLUTIONS.length - 1];
        for (int[] c : RESOLUTIONS) {
            if (c[0] <= Math.max(r.panelWidth, 854) && c[1] <= Math.max(r.panelHeight, 480)) {
                res = c;
                break;
            }
        }
        String resReason = "Matches your " + r.panelWidth + "×" + r.panelHeight + " screen pixel for pixel.";
        if (res[0] != r.panelWidth || res[1] != r.panelHeight) {
            resReason = "Largest standard mode that fits your " + r.panelWidth + "×" + r.panelHeight + " screen.";
        }
        if (r.power == WizardReport.POWER_BATTERY && res[0] > 1920) {
            res = new int[] {1920, 1080};
            resReason = "1080p decodes with much less power than your native resolution, for longer battery life.";
        }
        p.width = res[0];
        p.height = res[1];
        p.reasons.put(KEY_RESOLUTION, resReason);

        // Frame rate: the fastest rate the panel can show natively
        int fps = 60;
        for (int c : new int[] {120, 90}) {
            if (panelSupports(r, c)) {
                fps = c;
                break;
            }
        }
        String fpsReason = fps > 60 ? "Your screen runs at " + fps + " Hz, so every frame you see is a fresh one."
                : "Your screen tops out at 60 Hz.";
        if (fps > 60) {
            if (r.power == WizardReport.POWER_BATTERY) {
                fps = 60;
                fpsReason = "60 fps halves decode and radio work on battery.";
            } else if (r.isWifi24()) {
                fps = 60;
                fpsReason = "2.4 GHz Wi-Fi can't deliver " + 120 + " fps without drops. Switch to 5 GHz to unlock it.";
            } else if (r.link == WizardReport.LINK_WIFI && r.wifiRssi < -72) {
                fps = 60;
                fpsReason = "Weak Wi-Fi signal (" + r.wifiRssi + " dBm): 60 fps leaves room for retries.";
            } else if (r.place == WizardReport.PLACE_REMOTE || (r.pingSamples > 0 && !r.hostOnLan)) {
                fps = 60;
                fpsReason = "Over the internet, 60 fps is far more stable.";
            } else if (r.hevcMaxFps1080 > 0 && r.hevcMaxFps1080 < fps && p.height <= 1080) {
                fps = 60;
                fpsReason = "Your decoder reports " + r.hevcMaxFps1080 + " fps at 1080p.";
            }
        }
        p.fps = fps;
        p.reasons.put(KEY_FPS, fpsReason);

        // Codec
        boolean hevc = r.hevcDecoder != null && r.hostHevc();
        if (hevc) {
            p.codec = "forceh265";
            p.reasons.put(KEY_CODEC, "Hardware HEVC on both ends: same quality as H.264 at about 40% less bitrate"
                    + (r.av1Decoder != null && r.hostAv1() ? ". AV1 also works but usually decodes slower on handhelds." : "."));
        } else if (r.av1Decoder != null && r.hostAv1()) {
            p.codec = "forceav1";
            p.reasons.put(KEY_CODEC, "AV1 is the best codec both devices share.");
        } else {
            p.codec = "neverh265";
            p.reasons.put(KEY_CODEC, "H.264 is the only codec both devices share in hardware.");
        }

        // HDR
        boolean hdrPossible = r.hdrDisplay && r.hevcMain10 && r.hostHevc10() && hevc;
        p.hdr = r.wantHdr && hdrPossible;
        if (p.hdr) {
            p.reasons.put(KEY_HDR, "Your screen, decoder and host all handle 10-bit HDR.");
        } else if (!r.wantHdr) {
            p.reasons.put(KEY_HDR, "You asked to keep SDR.");
        } else if (!r.hdrDisplay) {
            p.reasons.put(KEY_HDR, "This screen doesn't report HDR10.");
        } else if (!r.hevcMain10) {
            p.reasons.put(KEY_HDR, "The decoder can't do 10-bit HEVC.");
        } else {
            p.reasons.put(KEY_HDR, "The host doesn't offer 10-bit encoding.");
        }

        // Bitrate
        p.bitrateKbps = estimateBitrate(r, p);

        // Frame pacing
        switch (r.priority) {
            case WizardReport.PRIORITY_LATENCY:
                p.pacing = "latency";
                p.reasons.put(KEY_PACING, "Shows each frame the moment it's decoded, for the lowest delay.");
                break;
            case WizardReport.PRIORITY_SMOOTH:
                p.pacing = "smoothness";
                p.reasons.put(KEY_PACING, "Buffers a frame to even out motion. Adds about one frame of delay.");
                break;
            default:
                p.pacing = "balanced";
                p.reasons.put(KEY_PACING, "Paces frames to the screen without building up a queue.");
                break;
        }

        // Virtual display on the host
        p.virtualDisplay = r.hostVirtualDisplay && r.hostVirtualDisplayReady;
        p.reasons.put(KEY_VDISPLAY, p.virtualDisplay
                ? "The host draws at exactly " + p.width + "×" + p.height + " @ " + p.fps + " Hz instead of scaling its own screen."
                : r.hostVirtualDisplay ? "The host's virtual display driver isn't ready." : "The host doesn't support it.");

        // Vendor low-latency decoder options
        String dec = r.hevcDecoder != null ? r.hevcDecoder.toLowerCase(Locale.ROOT) : "";
        boolean vendorLowLatency = dec.contains("qti") || dec.contains("qcom") || dec.contains("mtk")
                || dec.contains("mediatek") || dec.contains("exynos");
        p.ultraLowLatency = vendorLowLatency && r.priority != WizardReport.PRIORITY_SMOOTH;
        p.reasons.put(KEY_ULL, vendorLowLatency
                ? (p.ultraLowLatency ? "Your decoder has a vendor low-latency mode. Turning it on saves a few ms per frame."
                : "You picked smooth motion, so the vendor low-latency mode stays off.")
                : "Your decoder has no known vendor low-latency mode.");

        if (r.power == WizardReport.POWER_BATTERY) {
            p.reduceRefresh = true;
            p.reasons.put(KEY_REDUCE_REFRESH, "Lets the screen drop to the stream's frame rate to save power.");
        }

        if (r.thermalStatus >= 2) {
            p.notes.add("The device is already hot. Expect throttling on long sessions.");
        }
        if (r.isWifi24()) {
            p.notes.add("You're on 2.4 GHz Wi-Fi. A 5 GHz network is the biggest upgrade available.");
        }
        return p;
    }

    private static boolean panelSupports(WizardReport r, int fps) {
        for (float hz : r.refreshRates) {
            if (Math.abs(hz - fps) < 1.5f) {
                return true;
            }
        }
        return false;
    }

    static int estimateBitrate(WizardReport r, Plan p) {
        double pixels = (double) p.width * p.height / (1920.0 * 1080.0);
        double kbps = 20000 * pixels * Math.pow(p.fps / 60.0, 0.6);
        StringBuilder why = new StringBuilder(String.format(Locale.US, "Sized for %d×%d at %d fps",
                p.width, p.height, p.fps));
        switch (r.priority) {
            case WizardReport.PRIORITY_LATENCY: kbps *= 0.85; why.append(", trimmed for latency"); break;
            case WizardReport.PRIORITY_IMAGE: kbps *= 1.4; why.append(", raised for image quality"); break;
            default: break;
        }
        if (p.hdr) kbps *= 1.15;
        if ("neverh265".equals(p.codec)) kbps *= 1.4;
        if (r.power == WizardReport.POWER_BATTERY) kbps *= 0.85;

        double cap = Double.MAX_VALUE;
        String capWhy = null;
        if (r.link == WizardReport.LINK_WIFI && r.wifiLinkMbps > 0) {
            cap = r.wifiLinkMbps * 1000 * 0.3;
            capWhy = "a third of your " + r.wifiLinkMbps + " Mbps Wi-Fi link";
        }
        if (r.link == WizardReport.LINK_WIFI && r.wifiRssi < -67) {
            kbps *= 0.75;
            why.append(", reduced for the weak signal");
        }
        if (r.isWifi24() && cap > 15000) {
            cap = 15000;
            capWhy = "what 2.4 GHz Wi-Fi carries reliably";
        }
        if ((r.place == WizardReport.PLACE_REMOTE || r.link == WizardReport.LINK_CELLULAR
                || (r.pingSamples > 0 && !r.hostOnLan)) && cap > 20000) {
            cap = 20000;
            capWhy = "a typical upload limit for remote play";
        }
        if (kbps > cap) {
            kbps = cap;
            why.append(", capped at ").append(capWhy);
        }
        int result = (int) Math.max(5000, Math.min(150000, Math.round(kbps / 1000.0) * 1000));
        why.append('.');
        p.reasons.put(KEY_BITRATE, why.toString());
        return result;
    }

    // ---------------------------------------------------------------- trials

    public static List<WizardReport.Trial> buildTrials(Plan p) {
        List<WizardReport.Trial> trials = new ArrayList<>();
        int b = p.bitrateKbps;
        int low = Math.max(5000, Math.round(b * 0.6f / 1000f) * 1000);
        int high = Math.min(150000, Math.round(b * 1.6f / 1000f) * 1000);
        trials.add(new WizardReport.Trial("Bitrate " + WizardProbes.mbps(low), p.width, p.height, p.fps, low, "latency", p.codec, p.hdr));
        trials.add(new WizardReport.Trial("Bitrate " + WizardProbes.mbps(b), p.width, p.height, p.fps, b, "latency", p.codec, p.hdr));
        trials.add(new WizardReport.Trial("Bitrate " + WizardProbes.mbps(high), p.width, p.height, p.fps, high, "latency", p.codec, p.hdr));
        if (!"latency".equals(p.pacing)) {
            trials.add(new WizardReport.Trial("Pacing: " + pacingName(p.pacing), p.width, p.height, p.fps, b, p.pacing, p.codec, p.hdr));
        }
        return trials;
    }

    /** A 60 fps fallback trial when every high-frame-rate trial lost frames on the network. */
    public static WizardReport.Trial followUpTrial(Plan p, WizardReport r) {
        if (p.fps <= 60) {
            return null;
        }
        for (WizardReport.Trial t : r.trials) {
            if (t.fps == p.fps && t.networkClean()) {
                return null;
            }
        }
        for (WizardReport.Trial t : r.trials) {
            if (t.fps == 60) {
                return null;
            }
        }
        boolean anyDone = false;
        for (WizardReport.Trial t : r.trials) {
            anyDone |= t.done && !t.failed;
        }
        if (!anyDone) {
            return null;
        }
        int b = Math.max(5000, Math.round(p.bitrateKbps * 0.7f / 1000f) * 1000);
        return new WizardReport.Trial("60 fps fallback", p.width, p.height, 60, b, "latency", p.codec, p.hdr);
    }

    public static Bundle overridesFor(WizardReport.Trial t, Plan p) {
        Bundle b = new Bundle();
        b.putString(KEY_RESOLUTION, t.width + "x" + t.height);
        b.putString(KEY_FPS, String.valueOf(t.fps));
        b.putInt(KEY_BITRATE, t.bitrateKbps);
        b.putString(KEY_CODEC, t.codec);
        b.putBoolean(KEY_HDR, t.hdr);
        b.putString(KEY_PACING, t.pacing);
        b.putBoolean(KEY_VDISPLAY, p.virtualDisplay);
        b.putBoolean(KEY_ULL, p.ultraLowLatency);
        b.putBoolean("checkbox_enable_perf_overlay", true);
        b.putBoolean("checkbox_enable_post_stream_toast", false);
        return b;
    }

    /** Folds trial measurements back into the plan. */
    public static void refine(Plan p, WizardReport r) {
        List<WizardReport.Trial> ladder = new ArrayList<>();
        WizardReport.Trial pacingTrial = null;
        WizardReport.Trial fallback = null;
        for (WizardReport.Trial t : r.trials) {
            if (!t.done || t.failed) continue;
            if (t.fps != p.fps) {
                fallback = t;
            } else if ("latency".equals(t.pacing) && t.label.startsWith("Bitrate")) {
                ladder.add(t);
            } else {
                pacingTrial = t;
            }
        }
        if (ladder.isEmpty() && fallback == null) {
            return;
        }

        if (!ladder.isEmpty()) {
            WizardReport.Trial best = null;
            WizardReport.Trial bestLoaded = null;
            float maxCarried = 0;
            for (WizardReport.Trial t : ladder) {
                if (t.networkClean() && (best == null || t.bitrateKbps > best.bitrateKbps)) {
                    best = t;
                }
                if (t.networkClean() && t.loadedLink() && (bestLoaded == null || t.bitrateKbps > bestLoaded.bitrateKbps)) {
                    bestLoaded = t;
                }
                if (!Float.isNaN(t.carriedMbps)) maxCarried = Math.max(maxCarried, t.carriedMbps);
            }
            if (best != null && !best.loadedLink() && !Float.isNaN(best.carriedMbps)) {
                // Low-motion test content never filled the pipe: a clean result at a high target
                // proves nothing about the link, so keep the estimate (or a loaded clean result)
                int keep = Math.max(p.bitrateKbps, bestLoaded != null ? bestLoaded.bitrateKbps : 0);
                p.bitrateKbps = Math.min(keep, best.bitrateKbps);
                p.reasons.put(KEY_BITRATE, p.reasons.get(KEY_BITRATE) + String.format(Locale.US,
                        " Test streams arrived clean (%.0f of %d fps, %.1f%% lost), but the test scene was calm and "
                                + "used at most %.1f Mbps, so this keeps the estimate instead of the highest test.",
                        best.receivedFps, best.fps, best.lossPct, maxCarried));
            } else if (best != null) {
                p.bitrateKbps = best.bitrateKbps;
                p.reasons.put(KEY_BITRATE, String.format(Locale.US,
                        "Highest tested bitrate that arrived clean: %.0f of %d fps received, %.1f%% lost, %.1f ms decode.",
                        best.receivedFps, best.fps, best.lossPct, best.decodeMs));
            } else if (fallback != null && fallback.networkClean()) {
                p.fps = 60;
                p.bitrateKbps = fallback.bitrateKbps;
                p.reasons.put(KEY_FPS, String.format(Locale.US,
                        "At %d fps the network dropped frames at every bitrate; 60 fps arrived clean (%.1f%% lost).",
                        ladder.get(0).fps, fallback.lossPct));
                p.reasons.put(KEY_BITRATE, "Tested clean at 60 fps.");
                p.reasons.put(KEY_VDISPLAY, p.virtualDisplay
                        ? "The host draws at exactly " + p.width + "×" + p.height + " @ 60 Hz instead of scaling its own screen."
                        : p.reasons.get(KEY_VDISPLAY));
            } else {
                WizardReport.Trial lowest = ladder.get(0);
                for (WizardReport.Trial t : ladder) {
                    if (t.bitrateKbps < lowest.bitrateKbps) lowest = t;
                }
                p.bitrateKbps = Math.max(5000, Math.round(lowest.bitrateKbps * 0.7f / 1000f) * 1000);
                p.reasons.put(KEY_BITRATE, String.format(Locale.US,
                        "Even %s lost %.1f%% of frames, so this goes lower still. Check the Wi-Fi.",
                        WizardProbes.mbps(lowest.bitrateKbps), lowest.lossPct));
                p.notes.add("Every trial stream lost frames on the network. Move closer to the router or use 5 GHz.");
            }

            float worstDecode = 0;
            for (WizardReport.Trial t : ladder) worstDecode = Math.max(worstDecode, t.decodeMs);
            if (worstDecode > 1000f / p.fps * 0.8f) {
                p.notes.add(String.format(Locale.US,
                        "Decoding takes up to %.1f ms, close to the %.1f ms frame budget. A lower resolution would help.",
                        worstDecode, 1000f / p.fps));
            }
        }

        if (pacingTrial != null && !ladder.isEmpty()) {
            WizardReport.Trial reference = null;
            for (WizardReport.Trial t : ladder) {
                if (t.bitrateKbps == pacingTrial.bitrateKbps) reference = t;
            }
            if (reference != null && !pacingTrial.renderClean() && reference.renderClean()) {
                String chosen = pacingName(p.pacing);
                p.pacing = "latency";
                p.reasons.put(KEY_PACING, String.format(Locale.US,
                        "%s showed %.0f of %.0f received fps; lowest latency showed %.0f of %.0f, so it's smoother here too.",
                        chosen, pacingTrial.renderedFps, pacingTrial.receivedFps,
                        reference.renderedFps, reference.receivedFps));
            } else if (pacingTrial.renderClean()) {
                p.reasons.put(KEY_PACING, p.reasons.get(KEY_PACING) + String.format(Locale.US,
                        " Tested: %.0f of %.0f fps shown.", pacingTrial.renderedFps, pacingTrial.receivedFps));
            }
        }
    }

    // ---------------------------------------------------------------- output

    public static List<Rec> recommendations(Plan p, SharedPreferences current) {
        List<Rec> recs = new ArrayList<>();
        recs.add(new Rec(KEY_RESOLUTION, "Resolution", p.width + "x" + p.height, p.width + "×" + p.height,
                current.getString(KEY_RESOLUTION, "1280x720").replace('x', '×'), p.reasons.get(KEY_RESOLUTION)));
        recs.add(new Rec(KEY_FPS, "Frame rate", String.valueOf(p.fps), p.fps + " fps",
                current.getString(KEY_FPS, "60") + " fps", p.reasons.get(KEY_FPS)));
        recs.add(new Rec(KEY_BITRATE, "Bitrate", p.bitrateKbps, WizardProbes.mbps(p.bitrateKbps),
                WizardProbes.mbps(current.getInt(KEY_BITRATE, 0)), p.reasons.get(KEY_BITRATE)));
        recs.add(new Rec(KEY_CODEC, "Codec", p.codec, codecName(p.codec),
                codecName(current.getString(KEY_CODEC, "auto")), p.reasons.get(KEY_CODEC)));
        recs.add(new Rec(KEY_HDR, "HDR", p.hdr, onOff(p.hdr),
                onOff(current.getBoolean(KEY_HDR, false)), p.reasons.get(KEY_HDR)));
        recs.add(new Rec(KEY_PACING, "Frame pacing", p.pacing, pacingName(p.pacing),
                pacingName(current.getString(KEY_PACING, "latency")), p.reasons.get(KEY_PACING)));
        recs.add(new Rec(KEY_VDISPLAY, "Host virtual display", p.virtualDisplay, onOff(p.virtualDisplay),
                onOff(current.getBoolean(KEY_VDISPLAY, false)), p.reasons.get(KEY_VDISPLAY)));
        recs.add(new Rec(KEY_ULL, "Decoder low-latency mode", p.ultraLowLatency, onOff(p.ultraLowLatency),
                onOff(current.getBoolean(KEY_ULL, false)), p.reasons.get(KEY_ULL)));
        if (p.reduceRefresh != null) {
            recs.add(new Rec(KEY_REDUCE_REFRESH, "Lower screen refresh", p.reduceRefresh, onOff(p.reduceRefresh),
                    onOff(current.getBoolean(KEY_REDUCE_REFRESH, false)), p.reasons.get(KEY_REDUCE_REFRESH)));
        }
        return recs;
    }

    static String onOff(boolean b) {
        return b ? "On" : "Off";
    }

    static String codecName(String v) {
        switch (v) {
            case "forceh265": return "HEVC";
            case "forceav1": return "AV1";
            case "neverh265": return "H.264";
            default: return "Automatic";
        }
    }

    static String pacingName(String v) {
        switch (v) {
            case "latency": return "Lowest latency";
            case "balanced": return "Balanced";
            case "cap-fps": return "Balanced, FPS cap";
            case "smoothness": return "Smoothest";
            case "warp": return "Warp";
            case "warp2": return "Warp 2";
            default: return v;
        }
    }
}
