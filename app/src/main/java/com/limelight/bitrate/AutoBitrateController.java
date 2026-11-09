package com.limelight.bitrate;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Client automatic bitrate (PEN-10), pure logic so it can be unit tested. Once a second the
 * stream's health is judged from frame loss, round-trip time and received vs. decoded frame rate:
 * <ul>
 * <li>TROUBLE: 2% or more of the frames were lost, or frames were lost while the RTT rose well
 * above its floor. Three trouble seconds in a row step the bitrate down to 80% of what it was
 * (or of what actually arrived, if less), never below the floor.</li>
 * <li>DEVICE: frames arrive but the decoder shows fewer of them, with no loss. That's the device,
 * not the network: the bitrate is left alone.</li>
 * <li>IDLE: almost no frames (a still desktop). Not judged.</li>
 * <li>STRAINED: a little loss or a rising RTT. Counts against a step-up.</li>
 * </ul>
 * After a clean minute (57 of the last 60 judged seconds good, none in trouble) and at least
 * the step-up wait since the last change, the bitrate goes up 20%, never above the ceiling (what
 * the user picked). A step-up that runs into trouble within a minute goes back to where it was
 * and doubles the wait before the next one (1, 2, 4 ... 16 min).
 */
public final class AutoBitrateController {

    public static final int TROUBLE_SECONDS = 3;
    public static final int WINDOW_SECONDS = 60;
    public static final int WINDOW_GOOD = 57;
    public static final double STEP_DOWN = 0.8;
    public static final double STEP_UP = 1.2;
    public static final float TROUBLE_LOSS_PCT = 2.0f;
    public static final long UP_HOLD_MS = 60_000L;
    public static final long UP_HOLD_MAX_MS = 16 * 60_000L;
    public static final long UP_PROVE_MS = 60_000L;
    public static final long SETTLE_MS = 3_000L;

    public enum Health { GOOD, STRAINED, TROUBLE, DEVICE, IDLE }

    /** One second of measurements. */
    public static final class Sample {
        public final int frames;          // frames the host sent this second (received + lost)
        public final float lossPct;       // frames lost / frames, %
        public final float receivedFps;
        public final float decodedFps;    // frames the decoder presented
        public final int rttMs;           // 0 unknown
        public final int carriedKbps;     // video payload that arrived, 0 unknown

        public Sample(int frames, float lossPct, float receivedFps, float decodedFps, int rttMs, int carriedKbps) {
            this.frames = frames;
            this.lossPct = lossPct;
            this.receivedFps = receivedFps;
            this.decodedFps = decodedFps;
            this.rttMs = rttMs;
            this.carriedKbps = carriedKbps;
        }
    }

    /** A bitrate the caller should apply, then report with {@link #onApplied} or {@link #onApplyFailed}. */
    public static final class Decision {
        public final int kbps;
        public final boolean up;
        public final String reason;

        Decision(int kbps, boolean up, String reason) {
            this.kbps = kbps;
            this.up = up;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return (up ? "up to " : "down to ") + kbps + " kbps: " + reason;
        }
    }

    private int ceilingKbps;
    private final int floorKbps;
    private final float targetFps;
    private float maxPresentFps;

    private int currentKbps;
    private long upHoldMs;
    private int upFails;

    private long startedAt = -1;
    private long settleUntil;
    private long lastChangeAt;
    private long lastUpAt;
    private int lastUpFrom;
    private boolean pending;

    private int troubleRun;
    private int troubleCarriedSum;
    private int troubleCarriedCount;
    private int rttFloor = Integer.MAX_VALUE;
    private final ArrayDeque<Health> window = new ArrayDeque<>();
    private Health lastHealth = Health.IDLE;

    /**
     * @param ceilingKbps the bitrate the user picked: never streamed above it
     * @param startKbps   where the stream starts (a limit remembered for this network, or the ceiling)
     * @param floorKbps   never stepped below it
     * @param targetFps   the stream's frame rate
     * @param upHoldMs    the wait before a step-up (remembered with the limit)
     */
    public AutoBitrateController(int ceilingKbps, int startKbps, int floorKbps, float targetFps, long upHoldMs) {
        this.ceilingKbps = ceilingKbps;
        this.floorKbps = Math.min(floorKbps, ceilingKbps);
        this.targetFps = targetFps > 0 ? targetFps : 60;
        this.currentKbps = Math.max(this.floorKbps, Math.min(ceilingKbps, startKbps));
        this.upHoldMs = Math.max(UP_HOLD_MS, Math.min(UP_HOLD_MAX_MS, upHoldMs));
    }

    /** The bitrate for a stream of this size: about 5 Mbps at 720p60 and 10 at 1080p60, 3 to 20 Mbps. */
    public static int floorKbps(int width, int height, float fps) {
        double load = (double) width * height * fps / (1920.0 * 1080.0 * 60.0);
        if (load < 0.3) return 3000;
        return (int) Math.max(5000, Math.min(20000, Math.floor(10000 * load / 1000) * 1000));
    }

    /** The screen's refresh rate: frames beyond it are dropped by pacing, not lost by the device. 0: unknown. */
    public void setMaxPresentFps(float fps) {
        maxPresentFps = fps;
    }

    public Health judge(Sample s) {
        if (s.frames < Math.max(5, targetFps * 0.2f)) {
            return Health.IDLE;
        }
        if (s.rttMs > 0 && s.rttMs < rttFloor) {
            rttFloor = s.rttMs;
        }
        boolean rttRising = s.rttMs > 0 && rttFloor != Integer.MAX_VALUE
                && s.rttMs > rttFloor + Math.max(15, rttFloor);
        if (s.lossPct >= TROUBLE_LOSS_PCT || (s.lossPct > 0 && rttRising)) {
            return Health.TROUBLE;
        }
        // Frames dropped on purpose because the screen refreshes slower than the stream aren't the device's fault
        float presentable = maxPresentFps > 0 ? Math.min(s.receivedFps, maxPresentFps) : s.receivedFps;
        if (s.lossPct == 0 && presentable > 0 && s.decodedFps < presentable * 0.85f) {
            return Health.DEVICE;
        }
        if (s.lossPct > 0 || rttRising) {
            return Health.STRAINED;
        }
        return Health.GOOD;
    }

    /** Feed one second. Returns a change to apply, or null. */
    public Decision onSample(Sample s, long nowMs) {
        if (startedAt < 0) {
            startedAt = nowMs;
            settleUntil = nowMs + SETTLE_MS;
        }
        Health h = judge(s);
        lastHealth = h;
        if (pending || nowMs < settleUntil || h == Health.IDLE) {
            return null;
        }

        if (h == Health.TROUBLE) {
            troubleRun++;
            if (s.carriedKbps > 0) {
                troubleCarriedSum += s.carriedKbps;
                troubleCarriedCount++;
            }
        } else {
            troubleRun = 0;
            troubleCarriedSum = 0;
            troubleCarriedCount = 0;
        }
        window.addLast(h);
        while (window.size() > WINDOW_SECONDS) {
            window.removeFirst();
        }

        if (troubleRun >= TROUBLE_SECONDS) {
            return stepDown(s, nowMs);
        }

        if (lastUpAt != 0 && nowMs - lastUpAt >= UP_PROVE_MS) {
            lastUpAt = 0; // the step-up held
        }
        if (currentKbps >= ceilingKbps) {
            return null;
        }
        long since = nowMs - Math.max(lastChangeAt, startedAt);
        if (since < upHoldMs || window.size() < WINDOW_SECONDS) {
            return null;
        }
        int good = 0;
        for (Health w : window) {
            if (w == Health.TROUBLE) return null;
            if (w == Health.GOOD) good++;
        }
        if (good < WINDOW_GOOD) {
            return null;
        }
        int to = Math.min(ceilingKbps, Math.max(currentKbps + 1000, roundDown((int) (currentKbps * STEP_UP))));
        pending = true;
        return new Decision(to, true, String.format(Locale.US, "%d of %d s clean at %d kbps", good, window.size(), currentKbps));
    }

    private Decision stepDown(Sample s, long nowMs) {
        int carried = troubleCarriedCount > 0 ? troubleCarriedSum / troubleCarriedCount : 0;
        troubleRun = 0;
        troubleCarriedSum = 0;
        troubleCarriedCount = 0;
        window.clear();

        int to = (int) (currentKbps * STEP_DOWN);
        if (carried > 0 && carried < currentKbps) {
            to = Math.min(to, (int) (carried * STEP_DOWN));
        }
        String reason = String.format(Locale.US, "%d s of trouble (%.1f%% frames lost, rtt %d ms, %d kbps arrived)",
                TROUBLE_SECONDS, s.lossPct, s.rttMs, carried);
        if (lastUpAt != 0 && nowMs - lastUpAt < UP_PROVE_MS && lastUpFrom < currentKbps) {
            // The step-up didn't hold: back to where it was, and wait twice as long next time
            to = lastUpFrom;
            upHoldMs = Math.min(UP_HOLD_MAX_MS, upHoldMs * 2);
            upFails++;
            lastUpAt = 0;
            reason += String.format(Locale.US, "; step-up from %d kbps failed, next wait %d s", lastUpFrom, upHoldMs / 1000);
        }
        to = Math.max(floorKbps, roundDown(to));
        if (to >= currentKbps) {
            // Already at the floor: something other than the bitrate is losing packets
            settleUntil = nowMs + SETTLE_MS;
            return null;
        }
        pending = true;
        return new Decision(to, false, reason);
    }

    /** The host took the change. */
    public void onApplied(Decision d, int appliedKbps, long nowMs) {
        pending = false;
        int from = currentKbps;
        currentKbps = appliedKbps;
        lastChangeAt = nowMs;
        settleUntil = nowMs + SETTLE_MS;
        window.clear();
        troubleRun = 0;
        if (d != null && d.up && appliedKbps > from) {
            lastUpAt = nowMs;
            lastUpFrom = from;
        }
        if (d != null && d.up && appliedKbps < d.kbps) {
            // The host capped it: that's as high as this stream goes
            ceilingKbps = Math.max(floorKbps, appliedKbps);
        }
    }

    /** The change didn't reach the host; try again after the settle time. */
    public void onApplyFailed(long nowMs) {
        pending = false;
        settleUntil = nowMs + SETTLE_MS;
    }

    /** The user picked a bitrate by hand: it becomes the ceiling and the current rate. */
    public void onManualChange(int kbps, long nowMs) {
        ceilingKbps = Math.max(kbps, floorKbps);
        currentKbps = kbps;
        lastChangeAt = nowMs;
        settleUntil = nowMs + SETTLE_MS;
        lastUpAt = 0;
        window.clear();
        troubleRun = 0;
    }

    private static int roundDown(int kbps) {
        return kbps / 500 * 500;
    }

    public int getCurrentKbps() {
        return currentKbps;
    }

    public int getCeilingKbps() {
        return ceilingKbps;
    }

    public int getFloorKbps() {
        return floorKbps;
    }

    public long getUpHoldMs() {
        return upHoldMs;
    }

    public int getUpFails() {
        return upFails;
    }

    public Health getLastHealth() {
        return lastHealth;
    }

    /** The limit to remember for this network: 0 when the stream runs at its ceiling. */
    public int getLimitKbps() {
        return currentKbps < ceilingKbps ? currentKbps : 0;
    }
}
