package com.maxplayer.mpv;

/**
 * Classifies a stream as LIVE / VOD / UNKNOWN from mpv metadata only — never from the URL.
 *
 * <p>Rules are derived from measurements against real mpv (see docs/VERIFICATION.md):
 * <ul>
 *   <li>Live HLS (playlist without #EXT-X-ENDLIST) <b>does report a duration</b>, but it keeps growing
 *       (length of the playlist seen so far) and {@code seekable} is false.</li>
 *   <li>VOD HLS reports a constant duration and {@code seekable=yes}.</li>
 *   <li>A progressive file from a server without range support has a constant duration but
 *       {@code seekable=no}.</li>
 * </ul>
 * Providers differ (MPEG-TS, RTMP, Icecast, DVR-window HLS ...), so the detector returns
 * {@link LiveStatus#UNKNOWN} whenever the evidence is not conclusive; a host-supplied hint always wins.
 */
final class LiveDetector {
    static final class Result {
        final LiveStatus status; final String reason;
        Result(LiveStatus s, String r) { status = s; reason = r; }
    }

    /** Minimum growth (seconds) for a duration change to count as "growing". */
    static final double GROWTH_EPSILON = 0.25;
    static final int GROWTH_STEPS_REQUIRED = 2;
    static final long GROWTH_MIN_ELAPSED_MS = 2000;

    private double firstDuration = Double.NaN, lastDuration = Double.NaN;
    private long firstTimeMs;
    private int growthSteps;

    void reset() {
        firstDuration = lastDuration = Double.NaN; firstTimeMs = 0; growthSteps = 0;
    }

    /** Feed every observed duration value (NaN = unavailable). */
    void onDuration(double duration, long nowMs) {
        if (Double.isNaN(duration)) return;
        if (Double.isNaN(firstDuration)) { firstDuration = lastDuration = duration; firstTimeMs = nowMs; return; }
        if (duration > lastDuration + GROWTH_EPSILON) growthSteps++;
        lastDuration = duration;
    }

    Result evaluate(LiveStatus hint, boolean fileLoaded, double duration, Boolean seekable,
                    Boolean partiallySeekable, String fileFormat, long nowMs) {
        if (hint != null && hint != LiveStatus.UNKNOWN) return new Result(hint, "host-supplied hint");
        if (!fileLoaded) return new Result(LiveStatus.UNKNOWN, "no media loaded");
        boolean hls = fileFormat != null && fileFormat.toLowerCase(java.util.Locale.ROOT).contains("hls");
        boolean growing = growthSteps >= GROWTH_STEPS_REQUIRED && nowMs - firstTimeMs >= GROWTH_MIN_ELAPSED_MS
                && lastDuration > firstDuration + GROWTH_EPSILON;
        if (growing) return new Result(LiveStatus.LIVE, "duration keeps growing (" + fmt(firstDuration) + "s -> "
                + fmt(lastDuration) + "s): live playlist");
        boolean seek = Boolean.TRUE.equals(seekable) || Boolean.TRUE.equals(partiallySeekable);
        if (Double.isNaN(duration)) {
            return new Result(LiveStatus.UNKNOWN, seek ? "no duration reported but seekable"
                    : "no duration and not seekable: could be live or an unknown-length stream");
        }
        if (seek) return new Result(LiveStatus.VOD, "finite duration and seekable");
        if (hls) return new Result(LiveStatus.UNKNOWN, "HLS, not seekable: waiting to see whether the duration grows");
        return new Result(LiveStatus.VOD, "finite, non-growing duration but not seekable (server without range support?)");
    }

    private static String fmt(double d) { return String.format(java.util.Locale.ROOT, "%.1f", d); }
}
