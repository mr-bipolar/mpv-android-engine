package com.maxplayer.mpv;

/** Decoder / output statistics from mpv. NaN / -1 / null = unavailable. */
public final class PlaybackStats {
    public final String hardwareDecoder; public final double containerFps, outputFps, avSyncSeconds;
    public final long videoBitrateBps, audioBitrateBps, voDroppedFrames, decoderDroppedFrames;
    public final double cacheSpeedBytesPerSecond;
    PlaybackStats(String hwdec, double containerFps, double outputFps, double avsync, long vbr, long abr,
                  long voDrop, long decDrop, double cacheSpeed) {
        this.hardwareDecoder = hwdec; this.containerFps = containerFps; this.outputFps = outputFps;
        this.avSyncSeconds = avsync; this.videoBitrateBps = vbr; this.audioBitrateBps = abr;
        this.voDroppedFrames = voDrop; this.decoderDroppedFrames = decDrop; this.cacheSpeedBytesPerSecond = cacheSpeed;
    }
    @Override public String toString() {
        return "Stats{hwdec=" + hardwareDecoder + ", fps=" + outputFps + "/" + containerFps + ", avsync=" + avSyncSeconds
                + ", dropped(vo/dec)=" + voDroppedFrames + "/" + decoderDroppedFrames + "}";
    }
}
