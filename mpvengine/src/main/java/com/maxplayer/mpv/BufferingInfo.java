package com.maxplayer.mpv;

/**
 * Cache / buffering status derived from mpv's demuxer cache properties. These are mpv's
 * <i>estimates</i>; mpv documents {@code demuxer-cache-duration} as "very unreliable".
 */
public final class BufferingInfo {
    private final boolean stalled; private final int fillPercent;
    private final double cacheEndSeconds, bufferedAheadSeconds, cacheDurationSeconds, inputRateBytesPerSecond;
    private final long forwardBytes;

    BufferingInfo(boolean stalled, int fillPercent, double cacheEndSeconds, double bufferedAheadSeconds,
                  double cacheDurationSeconds, long forwardBytes, double inputRate) {
        this.stalled = stalled; this.fillPercent = fillPercent; this.cacheEndSeconds = cacheEndSeconds;
        this.bufferedAheadSeconds = bufferedAheadSeconds; this.cacheDurationSeconds = cacheDurationSeconds;
        this.forwardBytes = forwardBytes; this.inputRateBytesPerSecond = inputRate;
    }
    /** True while playback is paused waiting for data ({@code paused-for-cache}). */
    public boolean isStalled() { return stalled; }
    /** 0-100 progress until playback resumes after a stall ({@code cache-buffering-state}), -1 if n/a. */
    public int getFillPercent() { return fillPercent; }
    /** Timestamp of the last buffered data ({@code demuxer-cache-time}); NaN if n/a. For live streams this approximates the live edge. */
    public double getCacheEndSeconds() { return cacheEndSeconds; }
    /** {@code cacheEnd - playbackPosition}; NaN if either is unknown. */
    public double getBufferedAheadSeconds() { return bufferedAheadSeconds; }
    public double getCacheDurationSeconds() { return cacheDurationSeconds; }
    /** Bytes buffered ahead of the decoder ({@code demuxer-cache-state/fw-bytes}), -1 if n/a. */
    public long getForwardBytes() { return forwardBytes; }
    /** Estimated network input rate, bytes/s (may be missing/inaccurate), NaN if n/a. */
    public double getInputRateBytesPerSecond() { return inputRateBytesPerSecond; }
    @Override public String toString() {
        return "Buffering{stalled=" + stalled + ", fill=" + fillPercent + "%, ahead=" + bufferedAheadSeconds
                + "s, fwBytes=" + forwardBytes + "}";
    }
}
