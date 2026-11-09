package com.limelight.binding.video;

/**
 * Decoder input buffer sizing for high bitrates (PEN-10, after Moonlight V+ PR #587). Pure logic,
 * unit tested.
 * <p>
 * MediaCodec decoders size their input buffers from the resolution, and some of them pick a size
 * that a single high-bitrate IDR frame overflows. The stream then used to stop with "Decode unit
 * length N too large for input buffer". Two fixes:
 * <ul>
 * <li>At configure time, ask for KEY_MAX_INPUT_SIZE: a raw 4:2:0 frame at 2:1 compression, and at
 * least two frames' worth of the configured bitrate. In AUTO mode only when the decoder's default
 * is smaller, and the decoder is configured again without it if it refuses the request.</li>
 * <li>When a frame still doesn't fit, grow to at least twice the current capacity and restart the
 * decoder instead of failing the stream.</li>
 * </ul>
 */
public final class DecoderInputBufferSizing {
    public static final int MODE_AUTO = 0;
    public static final int MODE_FORCE = 1;
    public static final int MODE_OFF = 2;

    private static final String MIME_AVC = "video/avc";
    private static final String MIME_HEVC = "video/hevc";
    private static final String MIME_AV1 = "video/av01";
    private static final String MIME_DOLBY_VISION = "video/dolby-vision";
    private static final int MIN_AVC_AV1_INPUT_SIZE = 1024 * 1024;
    private static final int MIN_HEVC_INPUT_SIZE = 2 * 1024 * 1024;
    private static final long MIN_COMPRESSION_RATIO = 2L;

    private DecoderInputBufferSizing() {}

    public static int modeFromPreference(String value) {
        if ("force".equals(value)) return MODE_FORCE;
        if ("off".equals(value)) return MODE_OFF;
        return MODE_AUTO;
    }

    /** The input buffer this stream needs, bytes; 0 for MIME types this doesn't handle. */
    public static int recommendedInputSize(String mimeType, int width, int height, int bitrateKbps, int frameRate) {
        long minimum;
        if (MIME_AVC.equals(mimeType) || MIME_AV1.equals(mimeType)) {
            minimum = MIN_AVC_AV1_INPUT_SIZE;
        } else if (MIME_HEVC.equals(mimeType) || MIME_DOLBY_VISION.equals(mimeType)) {
            minimum = MIN_HEVC_INPUT_SIZE;
        } else {
            return 0;
        }
        if (width <= 0 || height <= 0) return 0;

        long pixels;
        if (MIME_AVC.equals(mimeType)) {
            // H.264 codes whole 16x16 macroblocks
            pixels = ((width + 15L) / 16L * 16L) * ((height + 15L) / 16L * 16L);
        } else {
            pixels = (long) width * height;
        }
        long size = pixels * 3L / (2L * MIN_COMPRESSION_RATIO);

        // At extreme bitrates one intra frame can exceed the resolution estimate: allow two
        // frames' worth of the bitrate (kbps -> bytes per frame, doubled for spikes and headers)
        if (bitrateKbps > 0 && frameRate > 0) {
            long bitrateFloor = (long) bitrateKbps * 1000L / 8L / frameRate * 2L;
            size = Math.max(size, bitrateFloor);
        }
        return (int) Math.max(minimum, Math.min(Integer.MAX_VALUE, size));
    }

    /**
     * What to ask the decoder for, bytes, or 0 to leave its default.
     * @param decoderDefaultSize the decoder's own KEY_MAX_INPUT_SIZE default, 0 when it has none
     */
    public static int requestedInputSize(int mode, String mimeType, int width, int height,
                                         int bitrateKbps, int frameRate, int decoderDefaultSize) {
        if (mode == MODE_OFF) return 0;
        int recommended = recommendedInputSize(mimeType, width, height, bitrateKbps, frameRate);
        if (recommended <= 0) return 0;
        if (mode == MODE_AUTO) {
            return decoderDefaultSize > 0 && decoderDefaultSize >= recommended ? 0 : recommended;
        }
        return Math.max(recommended, decoderDefaultSize);
    }

    /** AUTO retries without the request if the decoder refuses it; FORCE doesn't. */
    public static boolean retryWithoutRequest(int mode, int requestedSize) {
        return mode == MODE_AUTO && requestedSize > 0;
    }

    /**
     * A frame needs requiredSize bytes but the buffer holds currentCapacity: the size to grow to,
     * or 0 when growing can't help (the decoder ignored an earlier, big enough request).
     */
    public static int expandedInputSize(long requiredSize, int currentCapacity, int previousRequest) {
        if (requiredSize <= currentCapacity || requiredSize <= previousRequest || requiredSize > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(requiredSize, currentCapacity * 2L));
    }
}
