package com.limelight;

import android.os.SystemClock;

import java.util.Locale;

/**
 * Automatic recovery of an interrupted stream: after a transient failure of a connection whose
 * video started, the stream reconnects to the same running game, up to five times, after 1, 2,
 * 4, 8 and 12 seconds, within one minute. A connection that stays up for 30 seconds earns a fresh
 * budget. A recovering stream only ever resumes: it never launches or quits an app (see
 * StreamConfiguration.setResumeOnly()).
 *
 * The budget outlives the stream activity, which is recreated for each attempt, so it's kept here
 * for the process, keyed by the computer and app. Rules from Nightjar's StreamRecoveryPolicy
 * (RamazanKara/nightjar, GPL-3.0). Times are SystemClock.elapsedRealtime().
 */
final class StreamRecovery {
    static final int MAX_ATTEMPTS = 5;
    static final long WINDOW_MS = 60000;
    static final long STABLE_MS = 30000;
    private static final long[] DELAYS_MS = {1000, 2000, 4000, 8000, 12000};

    private static String key;
    private static int attempts;
    private static long startedAt;
    // An attempt is due when the next stream activity starts
    private static boolean pending;

    private StreamRecovery() {
    }

    private static String keyFor(String computerUuid, int appId) {
        return computerUuid + "/" + appId;
    }

    /**
     * The delay before the next attempt for this stream, spending it, or -1 when the budget is
     * spent. connectedAt is when the connection that failed started, or -1 if it never did.
     */
    static synchronized long nextDelay(String computerUuid, int appId, long connectedAt) {
        long now = SystemClock.elapsedRealtime();
        String streamKey = keyFor(computerUuid, appId);
        if (!streamKey.equals(key)) {
            key = streamKey;
            attempts = 0;
            startedAt = 0;
        }
        // Briefly starting a stream must not give a flapping connection an unlimited budget
        if (connectedAt >= 0 && now - connectedAt >= STABLE_MS) {
            attempts = 0;
            startedAt = 0;
        }
        if (attempts == 0) {
            startedAt = now;
        }
        if (attempts >= MAX_ATTEMPTS || now < startedAt ||
                now - startedAt + DELAYS_MS[attempts] >= WINDOW_MS) {
            return -1;
        }
        return DELAYS_MS[attempts++];
    }

    // The attempt being made, from 1, for "attempt 2 of 5"
    static synchronized int attempt() {
        return attempts;
    }

    // The next stream activity for this computer and app is a recovery attempt
    static synchronized void markPending(String computerUuid, int appId) {
        key = keyFor(computerUuid, appId);
        pending = true;
    }

    // Whether this stream activity is a recovery attempt; consumes the mark
    static synchronized boolean takePending(String computerUuid, int appId) {
        boolean recovering = pending && keyFor(computerUuid, appId).equals(key);
        pending = false;
        return recovering;
    }

    // Stops recovering this stream: the user left, or it ended for good
    static synchronized void reset() {
        key = null;
        attempts = 0;
        startedAt = 0;
        pending = false;
    }

    static synchronized String describe() {
        return String.format(Locale.ROOT, "attempt %d of %d, %d ms into the budget", attempts, MAX_ATTEMPTS,
                startedAt == 0 ? 0 : SystemClock.elapsedRealtime() - startedAt);
    }

    /**
     * Whether a stream that ended with this error reconnects on its own: native media timeouts and
     * transport errors. A graceful end, protected content, frame conversion errors and unknown
     * host errors never retry.
     */
    static boolean transientTermination(int code) {
        if (code == -100 || code == -101 || code == -102) return true;
        switch (code) {
            case -1: // An ENet transport failure has no platform errno
            case 32: case 100: case 101: case 102: case 103: case 104:
            case 107: case 110: case 111: case 112: case 113:
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether a failure to start a recovering stream is worth another attempt: network failures
     * reaching the host, not a host refusal (pairing, permissions, a different app now running).
     */
    static boolean transientStartup(String stage, int portFlags, int error) {
        // NvConnection reports Java transport failures with the two HTTP port flags and error 0
        // (or -408 after its own retries)
        if (portFlags != 0 && (error == 0 || error == -408)) return true;
        // Native RTSP/control startup failures carry positive errno values
        return portFlags != 0 && (error < 0 || transientTermination(error)) && stage != null &&
                !stage.toLowerCase(Locale.ROOT).contains("video");
    }
}
