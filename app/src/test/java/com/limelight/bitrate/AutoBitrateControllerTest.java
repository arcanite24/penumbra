package com.limelight.bitrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoBitrateControllerTest {

    private static final AutoBitrateController.Sample GOOD =
            new AutoBitrateController.Sample(60, 0f, 60f, 60f, 4, 30000);
    private static final AutoBitrateController.Sample LOSSY =
            new AutoBitrateController.Sample(60, 5f, 57f, 57f, 5, 28000);
    private static final AutoBitrateController.Sample IDLE =
            new AutoBitrateController.Sample(2, 50f, 1f, 1f, 4, 100);

    private long now = 1_000_000L;

    private AutoBitrateController.Decision feed(AutoBitrateController c, AutoBitrateController.Sample s, int seconds) {
        AutoBitrateController.Decision d = null;
        for (int i = 0; i < seconds && d == null; i++) {
            now += 1000;
            d = c.onSample(s, now);
        }
        return d;
    }

    private AutoBitrateController controller(int ceiling, int start) {
        AutoBitrateController c = new AutoBitrateController(ceiling, start, 5000, 60, AutoBitrateController.UP_HOLD_MS);
        // Get past the settle period
        assertNull(feed(c, GOOD, 4));
        return c;
    }

    @Test
    public void stepsDownAfterThreeTroubleSeconds() {
        AutoBitrateController c = controller(40000, 40000);
        assertNull(c.onSample(LOSSY, now += 1000));
        assertNull(c.onSample(LOSSY, now += 1000));
        AutoBitrateController.Decision d = c.onSample(LOSSY, now += 1000);
        assertNotNull(d);
        assertFalse(d.up);
        // 80% of what arrived (28 Mbps) is below 80% of the bitrate
        assertEquals(22000, d.kbps);
    }

    @Test
    public void troubleMustBeConsecutive() {
        AutoBitrateController c = controller(40000, 40000);
        for (int i = 0; i < 10; i++) {
            assertNull(c.onSample(LOSSY, now += 1000));
            assertNull(c.onSample(LOSSY, now += 1000));
            assertNull(c.onSample(GOOD, now += 1000));
        }
    }

    @Test
    public void ignoresTheFirstSecondsAndIdleSeconds() {
        AutoBitrateController c = new AutoBitrateController(40000, 40000, 5000, 60, AutoBitrateController.UP_HOLD_MS);
        // Settle period: trouble isn't counted
        assertNull(feed(c, LOSSY, 3));
        // A still picture with a lost frame says nothing about the link
        assertNull(feed(c, IDLE, 20));
        assertEquals(AutoBitrateController.Health.IDLE, c.getLastHealth());
    }

    @Test
    public void deviceBoundIsNotTrouble() {
        AutoBitrateController c = controller(40000, 40000);
        AutoBitrateController.Sample slowDecoder = new AutoBitrateController.Sample(60, 0f, 60f, 40f, 4, 30000);
        assertNull(feed(c, slowDecoder, 30));
        assertEquals(AutoBitrateController.Health.DEVICE, c.getLastHealth());
    }

    @Test
    public void pacingDropsAboveTheRefreshRateAreNotTheDevice() {
        AutoBitrateController c = controller(40000, 40000);
        c.setMaxPresentFps(60);
        AutoBitrateController.Sample fast = new AutoBitrateController.Sample(120, 0f, 120f, 60f, 4, 30000);
        assertEquals(AutoBitrateController.Health.GOOD, c.judge(fast));
    }

    @Test
    public void risingRttWithLossIsTrouble() {
        AutoBitrateController c = controller(40000, 40000);
        AutoBitrateController.Sample laggy = new AutoBitrateController.Sample(60, 0.5f, 59f, 59f, 60, 30000);
        assertEquals(AutoBitrateController.Health.TROUBLE, c.judge(laggy));
        AutoBitrateController.Sample laggyNoLoss = new AutoBitrateController.Sample(60, 0f, 60f, 60f, 60, 30000);
        assertEquals(AutoBitrateController.Health.STRAINED, c.judge(laggyNoLoss));
    }

    @Test
    public void stepsUpTwentyPercentAfterACleanMinute() {
        AutoBitrateController c = controller(40000, 20000);
        // Not before a minute of judged seconds
        assertNull(feed(c, GOOD, 50));
        AutoBitrateController.Decision d = feed(c, GOOD, 20);
        assertNotNull(d);
        assertTrue(d.up);
        assertEquals(24000, d.kbps);
    }

    @Test
    public void neverAboveTheCeilingOrBelowTheFloor() {
        AutoBitrateController c = controller(22000, 20000);
        AutoBitrateController.Decision up = feed(c, GOOD, 70);
        assertNotNull(up);
        assertEquals(22000, up.kbps);
        c.onApplied(up, up.kbps, now);
        // At the ceiling: no more step-ups
        assertNull(feed(c, GOOD, 200));

        AutoBitrateController low = controller(40000, 6000);
        AutoBitrateController.Decision down = feed(low, LOSSY, 3);
        assertNotNull(down);
        assertEquals(5000, down.kbps);
        low.onApplied(down, down.kbps, now);
        // Already at the floor
        assertNull(feed(low, LOSSY, 30));
    }

    @Test
    public void failedStepUpGoesBackAndDoublesTheWait() {
        AutoBitrateController c = controller(40000, 20000);
        AutoBitrateController.Decision up = feed(c, GOOD, 70);
        assertNotNull(up);
        c.onApplied(up, up.kbps, now);
        assertEquals(24000, c.getCurrentKbps());

        // Trouble soon after the step-up
        assertNull(feed(c, GOOD, 4));
        AutoBitrateController.Decision down = feed(c, LOSSY, 3);
        assertNotNull(down);
        assertEquals(20000, down.kbps);
        assertEquals(2 * AutoBitrateController.UP_HOLD_MS, c.getUpHoldMs());
        assertEquals(1, c.getUpFails());
        c.onApplied(down, down.kbps, now);

        // The next step-up waits two minutes
        assertNull(feed(c, GOOD, 100));
        AutoBitrateController.Decision again = feed(c, GOOD, 40);
        assertNotNull(again);
        assertTrue(again.up);
    }

    @Test
    public void heldStepUpKeepsTheWait() {
        AutoBitrateController c = controller(40000, 20000);
        AutoBitrateController.Decision up = feed(c, GOOD, 70);
        c.onApplied(up, up.kbps, now);
        // Trouble long after: an ordinary step-down, not a failed step-up
        assertNull(feed(c, GOOD, 61));
        AutoBitrateController.Decision down = feed(c, LOSSY, 3);
        assertNotNull(down);
        assertEquals(AutoBitrateController.UP_HOLD_MS, c.getUpHoldMs());
        assertEquals(0, c.getUpFails());
    }

    @Test
    public void hostCapBecomesTheCeiling() {
        AutoBitrateController c = controller(40000, 20000);
        AutoBitrateController.Decision up = feed(c, GOOD, 70);
        c.onApplied(up, 21000, now);
        assertEquals(21000, c.getCeilingKbps());
        assertEquals(0, c.getLimitKbps());
    }

    @Test
    public void manualChangeIsTheNewCeiling() {
        AutoBitrateController c = controller(40000, 20000);
        c.onManualChange(15000, now);
        assertEquals(15000, c.getCeilingKbps());
        assertNull(feed(c, GOOD, 200));
    }

    @Test
    public void floorScalesWithTheStream() {
        assertEquals(3000, AutoBitrateController.floorKbps(1280, 720, 30));
        assertEquals(5000, AutoBitrateController.floorKbps(1280, 720, 60));
        assertEquals(10000, AutoBitrateController.floorKbps(1920, 1080, 60));
        assertEquals(20000, AutoBitrateController.floorKbps(3840, 2160, 60));
    }

    @Test
    public void pendingChangeBlocksNewDecisions() {
        AutoBitrateController c = controller(40000, 40000);
        AutoBitrateController.Decision d = feed(c, LOSSY, 3);
        assertNotNull(d);
        // Until it's applied or failed, nothing else is decided
        assertNull(feed(c, LOSSY, 10));
        c.onApplyFailed(now);
        assertNull(feed(c, LOSSY, 3)); // settling
        assertNotNull(feed(c, LOSSY, 3));
    }
}
