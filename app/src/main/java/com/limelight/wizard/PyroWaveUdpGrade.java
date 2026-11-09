package com.limelight.wizard;

import java.util.Arrays;
import java.util.Locale;

/**
 * Grades one Vibepollo /pyrowave-udp-probe run (PEN-10). Pure logic, unit tested.
 * <p>
 * The host sends a 2 s burst of numbered UDP packets on 1 ms groups (or, for a pace probe, as
 * video frames sent back-to-back at a given pace) and reports how many it planned and sent. The
 * client records when each sequence number arrived. The pass rule is the one Vibepollo documents
 * for calibration (docs/pyrowave-protocol.md, "FEC-inclusive recommendations and UDP
 * calibration"): every planned packet sent, the sender within 2% of its 2 s, at most 0.1% loss
 * overall and 1% in any 100 ms window, p99 transit variation at most 4 ms and delay growth at
 * most 2 ms. Transit is measured against the sender's 1 ms schedule and taken relative to the
 * fastest packet of the run, so the two clocks never need to agree.
 * <p>
 * Frame-shaped (pace) probes are bursty on purpose, so only their loss is graded, with the same
 * limits, plus no video frame missing more than 10% of its packets.
 */
public final class PyroWaveUdpGrade {
    public static final int DURATION_MS = 2000;
    public static final int WINDOW_MS = 100;
    public static final int WINDOWS = DURATION_MS / WINDOW_MS;
    public static final int MIN_KBPS = 5000;
    public static final int MAX_KBPS = 3000000;
    /** Wire bytes Vibepollo charges for each probe packet: packet + RTP, encryption, UDP, IPv6, Ethernet. */
    public static final int WIRE_OVERHEAD_BYTES = 134;

    public static final double LOSS_LIMIT_PCT = 0.1;
    public static final double WINDOW_LOSS_LIMIT_PCT = 1.0;
    public static final double DELAY_P99_LIMIT_MS = 4.0;
    public static final double DELAY_GROWTH_LIMIT_MS = 2.0;
    public static final double FRAME_LOSS_LIMIT_PCT = 10.0;

    private PyroWaveUdpGrade() {}

    /** Packets the host plans for a probe (its own formula). */
    public static int expectedPackets(int kbps, int packetSize) {
        return (int) ((long) kbps * DURATION_MS / (8L * (packetSize + WIRE_OVERHEAD_BYTES)));
    }

    public static final class Result {
        public int kbps;
        public int fps;        // 0: capacity probe; otherwise a frame-shaped pace probe
        public int paceKbps;
        public int expected;
        public int sent;
        public int received;
        public double senderMs;
        public double lossPct = 100;
        public double worstWindowLossPct = 100;
        public double delayP99Ms = Double.NaN;
        public double delayGrowthMs = Double.NaN;
        public double worstFrameLossPct = 100;
        public String error; // the probe itself failed (host refused, timed out ...)

        public boolean senderOnTime() {
            return senderMs >= DURATION_MS * 0.98 && senderMs <= DURATION_MS * 1.02;
        }

        /** Why it failed, or null when it passed. */
        public String failure() {
            if (error != null) return error;
            if (expected <= 0) return "no probe ran";
            if (sent < expected) return String.format(Locale.US, "the host sent only %d of %d packets", sent, expected);
            if (received == 0) return "no UDP packets arrived";
            if (!senderOnTime()) return String.format(Locale.US, "the host took %.0f ms instead of %d", senderMs, DURATION_MS);
            if (lossPct > LOSS_LIMIT_PCT) return String.format(Locale.US, "%.2f%% of packets lost", lossPct);
            if (worstWindowLossPct > WINDOW_LOSS_LIMIT_PCT) return String.format(Locale.US, "%.1f%% lost in one 100 ms window", worstWindowLossPct);
            if (fps == 0) {
                if (!(delayP99Ms <= DELAY_P99_LIMIT_MS)) return String.format(Locale.US, "delivery varied by %.1f ms (p99)", delayP99Ms);
                if (!(delayGrowthMs <= DELAY_GROWTH_LIMIT_MS)) return String.format(Locale.US, "delay grew by %.1f ms", delayGrowthMs);
            } else if (worstFrameLossPct > FRAME_LOSS_LIMIT_PCT) {
                return String.format(Locale.US, "a frame lost %.0f%% of its packets", worstFrameLossPct);
            }
            return null;
        }

        public boolean passed() {
            return failure() == null;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%d kbps%s: sent %d/%d in %.0f ms, received %d, loss %.3f%%, worst 100 ms %.2f%%, p99 %.2f ms, growth %.2f ms%s -> %s",
                    kbps, fps > 0 ? String.format(Locale.US, " as %d fps frames paced at %d kbps", fps, paceKbps) : "",
                    sent, expected, senderMs, received, lossPct, worstWindowLossPct, delayP99Ms, delayGrowthMs,
                    fps > 0 ? String.format(Locale.US, ", worst frame %.1f%%", worstFrameLossPct) : "",
                    passed() ? "pass" : failure());
        }
    }

    /**
     * @param arrivalsUs arrival time of each sequence number in µs on any monotonic clock, or -1
     *                   if it never arrived (length must be expected)
     */
    public static Result grade(int kbps, int fps, int paceKbps, int expected, int sent, double senderMs, long[] arrivalsUs) {
        Result r = new Result();
        r.kbps = kbps;
        r.fps = fps;
        r.paceKbps = paceKbps;
        r.expected = expected;
        r.sent = sent;
        r.senderMs = senderMs;
        if (expected <= 0 || arrivalsUs == null) {
            return r;
        }

        double[] transit = new double[expected];
        int transitCount = 0;
        double first = 0, last = 0;
        int firstCount = 0, lastCount = 0;
        r.received = 0;
        r.worstWindowLossPct = 0;
        for (int w = 0; w < WINDOWS; w++) {
            int begin = (int) ((long) expected * w / WINDOWS);
            int end = (int) ((long) expected * (w + 1) / WINDOWS);
            int count = 0;
            for (int seq = begin; seq < end && seq < arrivalsUs.length; seq++) {
                if (arrivalsUs[seq] < 0) continue;
                count++;
                // The sender's 1 ms group for this packet
                long tick = ((long) (seq + 1) * DURATION_MS + expected - 1) / expected - 1;
                double delta = arrivalsUs[seq] / 1000.0 - tick;
                transit[transitCount++] = delta;
                if (w == 0) { first += delta; firstCount++; }
                if (w == WINDOWS - 1) { last += delta; lastCount++; }
            }
            r.received += count;
            if (end > begin) {
                r.worstWindowLossPct = Math.max(r.worstWindowLossPct, 100.0 * (end - begin - count) / (end - begin));
            }
        }
        r.lossPct = 100.0 * (expected - r.received) / expected;
        if (transitCount > 0) {
            double[] sorted = Arrays.copyOf(transit, transitCount);
            Arrays.sort(sorted);
            r.delayP99Ms = sorted[(transitCount - 1) * 99 / 100] - sorted[0];
            r.delayGrowthMs = firstCount > 0 && lastCount > 0 ? last / lastCount - first / firstCount : Double.POSITIVE_INFINITY;
        }

        if (fps > 0) {
            // The burst host splits the sequence into frames in the same way
            int frames = Math.max(1, fps * DURATION_MS / 1000);
            r.worstFrameLossPct = 0;
            for (int f = 0; f < frames; f++) {
                int begin = (int) ((long) expected * f / frames);
                int end = (int) ((long) expected * (f + 1) / frames);
                if (end <= begin) continue;
                int missing = 0;
                for (int seq = begin; seq < end; seq++) {
                    if (seq >= arrivalsUs.length || arrivalsUs[seq] < 0) missing++;
                }
                r.worstFrameLossPct = Math.max(r.worstFrameLossPct, 100.0 * missing / (end - begin));
            }
        }
        return r;
    }

    // ---------------------------------------------------------------- searches

    public interface Probe {
        /** Runs one probe at kbps (fps 0: capacity probe) and grades it. */
        Result run(int kbps, int fps, int paceKbps) throws InterruptedException;
    }

    public static final class Capacity {
        /** Highest rate that passed and then passed again at 95%, kbps; 0 when none. */
        public int kbps;
        public Result last;
        public int probes;
    }

    static int round5(double kbps) {
        return (int) (kbps / MIN_KBPS) * MIN_KBPS;
    }

    /**
     * Starts at startKbps, grows 25% while passing, then bisects the passing/failing bracket down
     * to stepKbps; the best pass is then confirmed afresh at 95% (twice, as Vibepollo's own
     * calibration does). A failed confirmation retries 20% lower. At most maxProbes runs.
     */
    public static Capacity searchCapacity(int startKbps, int capKbps, int stepKbps, int maxProbes, Probe probe)
            throws InterruptedException {
        Capacity c = new Capacity();
        int cap = Math.min(MAX_KBPS, round5(capKbps));
        if (cap < MIN_KBPS) return c;
        int low = 0, high = cap + MIN_KBPS;
        int next = Math.max(MIN_KBPS, Math.min(cap, round5(startKbps)));
        // Leave room for the confirmation runs
        int searchBudget = Math.max(1, maxProbes - 2);
        while (c.probes < searchBudget) {
            Result r = probe.run(next, 0, 0);
            c.probes++;
            c.last = r;
            if (r.error != null && r.expected == 0) {
                // The probe itself can't run (host refused, UDP blocked): searching further won't help
                return c;
            }
            if (r.passed()) {
                low = next;
                if (low >= cap || high - low <= stepKbps) break;
                next = high <= cap ? round5((low + high) / 2.0) : Math.min(cap, Math.max(low + MIN_KBPS, round5(low * 1.25)));
            } else {
                high = next;
                if (high - low <= stepKbps) break;
                next = Math.max(MIN_KBPS, round5((low + high) / 2.0));
                if (next <= low) break;
            }
        }
        if (low == 0) return c;
        int confirm = Math.max(MIN_KBPS, round5(low * 0.95));
        while (c.probes < maxProbes) {
            Result r = probe.run(confirm, 0, 0);
            c.probes++;
            c.last = r;
            if (r.passed() && c.probes < maxProbes) {
                r = probe.run(confirm, 0, 0);
                c.probes++;
                c.last = r;
            }
            if (r.passed()) {
                c.kbps = confirm;
                return c;
            }
            if (confirm == MIN_KBPS) break;
            confirm = Math.max(MIN_KBPS, round5(confirm * 0.8));
        }
        return c;
    }

    public static final class Pace {
        /** Highest pace whose frame-shaped probe passed, kbps; 0: not found (the host keeps its default). */
        public int paceKbps;
        public Result last;
        public int probes;
    }

    /**
     * Frame-shaped probes at kbps and fps, paced from the link's rate downwards in 20% steps to
     * floorKbps (a frame must still go out within its frame interval). The first pace that
     * passes twice wins.
     */
    public static Pace searchPace(int kbps, int fps, int linkKbps, int floorKbps, int maxProbes, Probe probe)
            throws InterruptedException {
        Pace p = new Pace();
        int floor = Math.max(kbps, floorKbps);
        int pace = Math.max(floor, linkKbps);
        while (p.probes < maxProbes) {
            Result r = probe.run(kbps, fps, pace);
            p.probes++;
            p.last = r;
            if (r.error != null && r.expected == 0) return p;
            if (r.passed() && p.probes < maxProbes) {
                r = probe.run(kbps, fps, pace);
                p.probes++;
                p.last = r;
                if (r.passed()) {
                    p.paceKbps = pace;
                    return p;
                }
            }
            if (pace <= floor) break;
            pace = Math.max(floor, (int) (pace * 0.8) / 1000 * 1000);
        }
        return p;
    }
}
