package com.limelight.wizard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PyroWaveUdpGradeTest {

    private static final int PACKET = 1392;

    /** Arrivals exactly on the sender's 1 ms schedule plus a fixed transit and optional jitter. */
    private static long[] onSchedule(int expected, double transitMs, double jitterMs, int jitterEvery) {
        long[] a = new long[expected];
        for (int seq = 0; seq < expected; seq++) {
            long tick = ((long) (seq + 1) * PyroWaveUdpGrade.DURATION_MS + expected - 1) / expected - 1;
            double ms = tick + transitMs + (jitterEvery > 0 && seq % jitterEvery == 0 ? jitterMs : 0);
            a[seq] = Math.round(ms * 1000);
        }
        return a;
    }

    @Test
    public void expectedMatchesTheHost() {
        // Vibepollo: kbps * 2000 / (8 * (packetsize + 134))
        assertEquals(16382, PyroWaveUdpGrade.expectedPackets(100000, PACKET));
        assertEquals(163826, PyroWaveUdpGrade.expectedPackets(1000000, PACKET));
    }

    @Test
    public void cleanRunPasses() {
        int expected = PyroWaveUdpGrade.expectedPackets(200000, PACKET);
        PyroWaveUdpGrade.Result r = PyroWaveUdpGrade.grade(200000, 0, 0, expected, expected, 2000.4,
                onSchedule(expected, 1.5, 0, 0));
        assertTrue(r.toString(), r.passed());
        assertEquals(0, r.lossPct, 1e-9);
        assertEquals(0, r.delayP99Ms, 0.01);
        assertEquals(0, r.delayGrowthMs, 0.01);
    }

    @Test
    public void aggregateLossLimitIsOneInAThousand() {
        int expected = 20000;
        long[] a = onSchedule(expected, 1, 0, 0);
        // 20 lost, spread out: exactly 0.1% passes
        for (int i = 0; i < 20; i++) a[i * 1000 + 7] = -1;
        PyroWaveUdpGrade.Result r = PyroWaveUdpGrade.grade(200000, 0, 0, expected, expected, 2000, a);
        assertTrue(r.toString(), r.passed());
        // 21 lost fails
        a[19999] = -1;
        r = PyroWaveUdpGrade.grade(200000, 0, 0, expected, expected, 2000, a);
        assertFalse(r.passed());
        assertTrue(r.failure().contains("lost"));
    }

    @Test
    public void burstInOneWindowFails() {
        int expected = 20000;
        long[] a = onSchedule(expected, 1, 0, 0);
        // 15 consecutive losses: 0.075% overall, but 1.5% of one 100 ms window (1000 packets)
        for (int i = 5000; i < 5015; i++) a[i] = -1;
        PyroWaveUdpGrade.Result r = PyroWaveUdpGrade.grade(200000, 0, 0, expected, expected, 2000, a);
        assertEquals(1.5, r.worstWindowLossPct, 1e-9);
        assertFalse(r.passed());
        assertTrue(r.failure(), r.failure().contains("100 ms"));
    }

    @Test
    public void transitVariationIsJudgedAgainstTheBaseline() {
        int expected = 20000;
        // 2% of the packets arrive 5 ms late: p99 is 5 ms
        PyroWaveUdpGrade.Result r = PyroWaveUdpGrade.grade(200000, 0, 0, expected, expected, 2000,
                onSchedule(expected, 1, 5, 50));
        assertEquals(5.0, r.delayP99Ms, 0.01);
        // As its own baseline it passes; against a clean baseline it's 5 ms worse
        assertTrue(r.toString(), r.passed());
        r.baselineP99Ms = 0.5;
        assertFalse(r.passed());
        assertTrue(r.failure(), r.failure().contains("p99"));
        // Within 4 ms of the baseline passes
        r.baselineP99Ms = 1.0;
        assertTrue(r.toString(), r.passed());
        // 0.5% late does not move p99
        r = PyroWaveUdpGrade.grade(200000, 0, 0, expected, expected, 2000, onSchedule(expected, 1, 5, 200));
        r.baselineP99Ms = 0;
        assertTrue(r.toString(), r.passed());
    }

    @Test
    public void busyLanBaselineJitterNoLongerFailsEveryRate() {
        // Device test on a busy LAN: 0% loss at every rate, p99 26.5 ms at 10 Mbps, 25.3 at 25,
        // 29.7 at 50, 47.5 at 100. With the 26.5 ms baseline, 25 and 50 pass and 100 fails.
        int expected = 20000;
        PyroWaveUdpGrade.Result at50 = PyroWaveUdpGrade.grade(50000, 0, 0, expected, expected, 2000,
                onSchedule(expected, 1, 29.7, 50));
        at50.baselineP99Ms = 26.5;
        assertTrue(at50.toString(), at50.passed());
        PyroWaveUdpGrade.Result at100 = PyroWaveUdpGrade.grade(100000, 0, 0, expected, expected, 2000,
                onSchedule(expected, 1, 47.5, 50));
        at100.baselineP99Ms = 26.5;
        assertFalse(at100.passed());
    }

    @Test
    public void growingDelayFails() {
        int expected = 20000;
        long[] a = onSchedule(expected, 1, 0, 0);
        // A queue building up: 4 ms more delay by the end
        for (int seq = 0; seq < expected; seq++) a[seq] += (long) (4000.0 * seq / expected);
        PyroWaveUdpGrade.Result r = PyroWaveUdpGrade.grade(200000, 0, 0, expected, expected, 2000, a);
        assertTrue(r.delayGrowthMs > PyroWaveUdpGrade.DELAY_GROWTH_LIMIT_MS);
        assertFalse(r.passed());
        assertTrue(r.failure(), r.failure().contains("grew"));
    }

    @Test
    public void hostMustSendEverythingOnTime() {
        int expected = 20000;
        long[] a = onSchedule(expected, 1, 0, 0);
        assertFalse(PyroWaveUdpGrade.grade(200000, 0, 0, expected, expected - 1, 2000, a).passed());
        assertFalse(PyroWaveUdpGrade.grade(200000, 0, 0, expected, expected, 2100, a).passed());
        assertTrue(PyroWaveUdpGrade.grade(200000, 0, 0, expected, expected, 2030, a).passed());
    }

    @Test
    public void paceProbeIgnoresTimingButNotFrameDamage() {
        int expected = 12000;
        long[] a = new long[expected];
        // Bursty on purpose: whole frames arrive at once
        for (int seq = 0; seq < expected; seq++) a[seq] = (seq / 100) * 16_000L;
        PyroWaveUdpGrade.Result r = PyroWaveUdpGrade.grade(100000, 60, 500000, expected, expected, 2000, a);
        assertTrue(r.toString(), r.passed());
        // One frame (100 packets) loses 11 of them: 0.09% overall, but more than 10% of that frame
        for (int seq = 300; seq < 311; seq++) a[seq] = -1;
        r = PyroWaveUdpGrade.grade(100000, 60, 500000, expected, expected, 2000, a);
        assertEquals(11.0, r.worstFrameLossPct, 1e-9);
        assertFalse(r.passed());
    }

    /**
     * A fake link: up to limitKbps no loss and baseJitterMs of p99 variation (the phone and the
     * LAN); above it, 2% loss and 20 ms more variation (a queue).
     */
    private static PyroWaveUdpGrade.Probe link(int limitKbps, double baseJitterMs, List<Integer> log) {
        return (kbps, fps, pace) -> {
            log.add(fps > 0 ? -pace : kbps);
            int expected = PyroWaveUdpGrade.expectedPackets(kbps, PACKET);
            boolean ok = fps > 0 ? pace <= limitKbps : kbps <= limitKbps;
            long[] a = onSchedule(expected, 1, baseJitterMs + (ok ? 0 : 20), 50);
            if (!ok) Arrays.fill(a, 0, expected / 50, -1);
            return PyroWaveUdpGrade.grade(kbps, fps, pace, expected, expected, 2000, a);
        };
    }

    /** Fails only on timing above limitKbps (no loss anywhere), like the busy LAN. */
    private static PyroWaveUdpGrade.Probe jitteryLink(int limitKbps, double baseJitterMs, List<Integer> log) {
        return (kbps, fps, pace) -> {
            log.add(kbps);
            int expected = PyroWaveUdpGrade.expectedPackets(kbps, PACKET);
            long[] a = onSchedule(expected, 1, baseJitterMs + (kbps <= limitKbps ? 0 : 20), 50);
            return PyroWaveUdpGrade.grade(kbps, fps, pace, expected, expected, 2000, a);
        };
    }

    private static PyroWaveUdpGrade.Capacity search(int start, int cap, int maxProbes, PyroWaveUdpGrade.Probe p)
            throws InterruptedException {
        return PyroWaveUdpGrade.searchCapacity(PyroWaveUdpGrade.BASELINE_KBPS, start, cap, 10000, maxProbes, p, null);
    }

    @Test
    public void capacitySearchFindsTheLimitAndConfirmsAtNinetyFivePercent() throws Exception {
        List<Integer> log = new ArrayList<>();
        PyroWaveUdpGrade.Capacity c = search(400000, 1000000, 12, link(620000, 0, log));
        assertEquals("baseline first", Integer.valueOf(PyroWaveUdpGrade.BASELINE_KBPS), log.get(0));
        assertTrue("found " + c.kbps + " " + log, c.kbps <= 620000 && c.kbps >= 540000);
        assertFalse(c.atCap);
        assertTrue(c.probes <= 12);
        // The last two runs are the confirmation, at 95% of the best pass
        assertEquals(log.get(log.size() - 1), log.get(log.size() - 2));
    }

    @Test
    public void baselineJitterDoesNotHideTheCapacity() throws Exception {
        // 25 ms of variation even at 10 Mbps: the old absolute 4 ms rule found nothing
        List<Integer> log = new ArrayList<>();
        PyroWaveUdpGrade.Capacity c = search(200000, 400000, 10, jitteryLink(150000, 25, log));
        assertEquals(25.0, c.baseline.delayP99Ms, 0.01);
        assertTrue("found " + c.kbps + " " + log, c.kbps >= 120000 && c.kbps <= 150000);
    }

    @Test
    public void capacitySearchStopsAtTheCap() throws Exception {
        List<Integer> log = new ArrayList<>();
        PyroWaveUdpGrade.Capacity c = search(300000, 572000, 12, link(5000000, 0, log));
        // round5(572000) = 570000; confirmed at 95%
        assertEquals(540000, c.kbps);
        assertTrue(c.atCap);
        for (int kbps : log) assertTrue("never above the cap: " + log, kbps <= 570000);
    }

    @Test
    public void onlyTheBaselinePassing() throws Exception {
        List<Integer> log = new ArrayList<>();
        PyroWaveUdpGrade.Capacity c = search(200000, 400000, 10, jitteryLink(12000, 25, log));
        assertEquals(PyroWaveUdpGrade.BASELINE_KBPS, c.kbps);
        assertTrue(c.probes <= 10);
    }

    @Test
    public void capacitySearchGivesNothingWhenTheBaselineLoses() throws Exception {
        List<Integer> log = new ArrayList<>();
        PyroWaveUdpGrade.Capacity c = search(400000, 1000000, 10, link(0, 0, log));
        assertEquals(0, c.kbps);
        assertEquals(1, c.probes);
        assertNotNull(c.baseline);
        assertFalse(c.last.passed());
    }

    @Test
    public void probeErrorStopsTheSearch() throws Exception {
        final int[] runs = {0};
        PyroWaveUdpGrade.Capacity c = search(400000, 1000000, 10, (kbps, fps, pace) -> {
            runs[0]++;
            PyroWaveUdpGrade.Result r = new PyroWaveUdpGrade.Result();
            r.error = "Stop streaming before testing bandwidth";
            return r;
        });
        assertEquals(1, runs[0]);
        assertEquals(0, c.kbps);
    }

    @Test
    public void paceSearchStepsDownFromTheLink() throws Exception {
        List<Integer> log = new ArrayList<>();
        PyroWaveUdpGrade.Pace p = PyroWaveUdpGrade.searchPace(200000, 60, 1000000, 250000, 6, link(700000, 0, log));
        // 1000 -> 800 fail, 640 passes twice
        assertEquals(640000, p.paceKbps);
        assertEquals(Arrays.asList(-1000000, -800000, -640000, -640000), log);
    }

    @Test
    public void paceSearchNeverGoesBelowTheFloor() throws Exception {
        List<Integer> log = new ArrayList<>();
        PyroWaveUdpGrade.Pace p = PyroWaveUdpGrade.searchPace(200000, 60, 1000000, 600000, 6, link(100000, 0, log));
        assertEquals(0, p.paceKbps);
        for (int pace : log) assertTrue(-pace >= 600000);
    }
}
