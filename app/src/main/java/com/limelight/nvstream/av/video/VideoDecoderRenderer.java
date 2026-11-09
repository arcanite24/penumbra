package com.limelight.nvstream.av.video;

public abstract class VideoDecoderRenderer {
    public abstract int setup(int format, int width, int height, int redrawRate);

    public abstract void start();

    public abstract void stop();

    // This is called once for each frame-start NALU. This means it will be called several times
    // for an IDR frame which contains several parameter sets and the I-frame data.
    public abstract int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                         int frameNumber, int frameType, char frameHostProcessingLatency,
                                         long receiveTimeMs, long enqueueTimeMs);

    // What moonlight-common-c calls with: times in microseconds, and for PyroWave frames that lost
    // packets or were cut short, their gaps (offset and length pairs), where records start in
    // them, and MoonBridge.PARTIAL_KIND_*. Renderers that only take whole frames get the above.
    public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                int frameNumber, int frameType, char frameHostProcessingLatency,
                                long receiveTimeUs, long enqueueTimeUs, long presentationTimeUs,
                                int[] missingRanges, int[] recordStarts, int partialKind) {
        return submitDecodeUnit(decodeUnitData, decodeUnitLength, decodeUnitType, frameNumber, frameType,
                frameHostProcessingLatency, receiveTimeUs / 1000, enqueueTimeUs / 1000);
    }
    
    public abstract void cleanup();

    public abstract int getCapabilities();

    public abstract void setHdrMode(boolean enabled, byte[] hdrMetadata);
}
