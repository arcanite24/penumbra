package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DecoderInputBufferSizingTest {

    @Test
    public void resolutionEstimate() {
        // 1080p HEVC: 1920*1080*3/4 = 1.55 MB, below the 2 MB HEVC minimum
        assertEquals(2 * 1024 * 1024, DecoderInputBufferSizing.recommendedInputSize("video/hevc", 1920, 1080, 0, 0));
        // 4K HEVC: 3840*2160*3/4
        assertEquals(3840 * 2160 * 3 / 4, DecoderInputBufferSizing.recommendedInputSize("video/hevc", 3840, 2160, 0, 0));
        // H.264 rounds up to whole macroblocks: 1920x1088
        assertEquals(1920 * 1088 * 3 / 4, DecoderInputBufferSizing.recommendedInputSize("video/avc", 1920, 1080, 0, 0));
        assertEquals(0, DecoderInputBufferSizing.recommendedInputSize("video/x-vnd.on2.vp9", 1920, 1080, 0, 0));
        assertEquals(0, DecoderInputBufferSizing.recommendedInputSize("video/hevc", 0, 1080, 0, 0));
    }

    @Test
    public void highBitrateRaisesTheEstimate() {
        // 1 Gbps at 60 fps: 2.08 MB a frame, doubled to 4.17 MB
        int size = DecoderInputBufferSizing.recommendedInputSize("video/hevc", 1920, 1080, 1000000, 60);
        assertEquals(1000000L * 1000 / 8 / 60 * 2, size);
        // 500 Mbps: two frames are 2,083,332 bytes, just under the 2 MiB HEVC minimum
        assertEquals(2 * 1024 * 1024, DecoderInputBufferSizing.recommendedInputSize("video/hevc", 1920, 1080, 500000, 60));
        // H.264 has a 1 MB minimum, so 500 Mbps raises it
        assertEquals(500000L * 1000 / 8 / 60 * 2, DecoderInputBufferSizing.recommendedInputSize("video/avc", 1280, 720, 500000, 60));
        // 20 Mbps changes nothing
        assertEquals(2 * 1024 * 1024, DecoderInputBufferSizing.recommendedInputSize("video/hevc", 1920, 1080, 20000, 60));
    }

    @Test
    public void autoLeavesBigEnoughDefaultsAlone() {
        int rec = DecoderInputBufferSizing.recommendedInputSize("video/hevc", 3840, 2160, 0, 0);
        assertEquals(0, DecoderInputBufferSizing.requestedInputSize(DecoderInputBufferSizing.MODE_AUTO,
                "video/hevc", 3840, 2160, 0, 0, rec));
        assertEquals(rec, DecoderInputBufferSizing.requestedInputSize(DecoderInputBufferSizing.MODE_AUTO,
                "video/hevc", 3840, 2160, 0, 0, rec - 1));
        assertEquals(rec, DecoderInputBufferSizing.requestedInputSize(DecoderInputBufferSizing.MODE_AUTO,
                "video/hevc", 3840, 2160, 0, 0, 0));
    }

    @Test
    public void forceAndOff() {
        int rec = DecoderInputBufferSizing.recommendedInputSize("video/av01", 2560, 1440, 0, 0);
        assertEquals(rec * 2, DecoderInputBufferSizing.requestedInputSize(DecoderInputBufferSizing.MODE_FORCE,
                "video/av01", 2560, 1440, 0, 0, rec * 2));
        assertEquals(rec, DecoderInputBufferSizing.requestedInputSize(DecoderInputBufferSizing.MODE_FORCE,
                "video/av01", 2560, 1440, 0, 0, 0));
        assertEquals(0, DecoderInputBufferSizing.requestedInputSize(DecoderInputBufferSizing.MODE_OFF,
                "video/av01", 2560, 1440, 0, 0, 0));
        assertTrue(DecoderInputBufferSizing.retryWithoutRequest(DecoderInputBufferSizing.MODE_AUTO, rec));
        assertFalse(DecoderInputBufferSizing.retryWithoutRequest(DecoderInputBufferSizing.MODE_FORCE, rec));
        assertFalse(DecoderInputBufferSizing.retryWithoutRequest(DecoderInputBufferSizing.MODE_AUTO, 0));
    }

    @Test
    public void growsToAtLeastDouble() {
        assertEquals(4 * 1024 * 1024, DecoderInputBufferSizing.expandedInputSize(2 * 1024 * 1024 + 1, 2 * 1024 * 1024, 0));
        assertEquals(9_000_000, DecoderInputBufferSizing.expandedInputSize(9_000_000, 2 * 1024 * 1024, 0));
        // Fits already, or an earlier big enough request was ignored: growing again won't help
        assertEquals(0, DecoderInputBufferSizing.expandedInputSize(1000, 2 * 1024 * 1024, 0));
        assertEquals(0, DecoderInputBufferSizing.expandedInputSize(3_000_000, 2 * 1024 * 1024, 4_000_000));
    }

    @Test
    public void preferenceValues() {
        assertEquals(DecoderInputBufferSizing.MODE_AUTO, DecoderInputBufferSizing.modeFromPreference(null));
        assertEquals(DecoderInputBufferSizing.MODE_AUTO, DecoderInputBufferSizing.modeFromPreference("auto"));
        assertEquals(DecoderInputBufferSizing.MODE_FORCE, DecoderInputBufferSizing.modeFromPreference("force"));
        assertEquals(DecoderInputBufferSizing.MODE_OFF, DecoderInputBufferSizing.modeFromPreference("off"));
    }
}
