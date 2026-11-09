package com.limelight.utils;

/**
 * Keeps the start times of network stalls over the last 20 minutes and finds a steady rhythm in
 * them (see PeriodicStalls), the signature of background Wi-Fi scans. A stall is a one-second
 * sample with at least 2% of frames lost, or a round-trip variation above a frame interval
 * (8 ms at the least), as in Nightjar's stream health.
 */
public final class StallRhythm {
    public static final long HISTORY_MS = 20 * 60_000;
    private static final int MAX_STALLS = 64;

    private final long[] stallStarts = new long[MAX_STALLS];
    private int stallCount;
    private long lastStallMs = Long.MIN_VALUE / 2;
    private long periodMs;

    /**
     * Adds a sample, about once a second. Returns true when this sample made a rhythm appear
     * (or its period change), so the caller can tell the user once.
     *
     * @param nowMs a monotonic time
     * @param lossPercent frames lost in the sample, or a negative value if unknown
     * @param rttVarianceMs round-trip time variation, or a negative value if unknown
     * @param fps the stream's frame rate
     */
    public synchronized boolean add(long nowMs, float lossPercent, float rttVarianceMs, float fps) {
        long before = periodMs;
        expire(nowMs);
        float budgetMs = fps > 0 ? 1000f / fps : 16.7f;
        boolean stall = lossPercent >= 2 || rttVarianceMs >= Math.max(8, budgetMs);
        if (stall) {
            if (nowMs - lastStallMs > PeriodicStalls.BURST_GAP_MS) {
                if (stallCount == MAX_STALLS) {
                    System.arraycopy(stallStarts, 1, stallStarts, 0, --stallCount);
                }
                stallStarts[stallCount++] = nowMs;
                periodMs = PeriodicStalls.periodMs(stallStarts, stallCount);
            }
            lastStallMs = nowMs;
        }
        return periodMs > 0 && Math.abs(periodMs - before) > PeriodicStalls.BURST_GAP_MS;
    }

    private void expire(long nowMs) {
        int old = 0;
        while (old < stallCount && nowMs - stallStarts[old] > HISTORY_MS) {
            old++;
        }
        if (old > 0) {
            System.arraycopy(stallStarts, old, stallStarts, 0, stallCount - old);
            stallCount -= old;
            periodMs = PeriodicStalls.periodMs(stallStarts, stallCount);
        }
    }

    /** The rhythm of recent network stalls in ms, or 0 */
    public synchronized long periodMs() {
        return periodMs;
    }

    /** Network stalls in the last 20 minutes */
    public synchronized int stalls() {
        return stallCount;
    }
}
