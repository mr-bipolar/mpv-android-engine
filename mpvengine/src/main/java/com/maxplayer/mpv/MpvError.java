package com.maxplayer.mpv;

import java.util.Collections;
import java.util.List;

/**
 * A playback error, suitable for showing a short message to a user and logging details.
 * No native stack traces; {@link #getLogLines()} holds the last mpv error-level log lines.
 */
public final class MpvError {
    public enum Kind { LOAD_FAILED, UNKNOWN_FORMAT, OUTPUT_INIT_FAILED, PLAYBACK_FAILED, UNKNOWN }

    private final Kind kind; private final int mpvErrorCode; private final String message, url;
    private final List<String> logLines; private final boolean willRetry;

    MpvError(Kind kind, int mpvErrorCode, String message, String url, List<String> logLines, boolean willRetry) {
        this.kind = kind; this.mpvErrorCode = mpvErrorCode; this.message = message; this.url = url;
        this.logLines = Collections.unmodifiableList(logLines); this.willRetry = willRetry;
    }
    public Kind getKind() { return kind; }
    /** Raw {@code mpv_error} code from the end-file event, 0 when the native patch is not installed. */
    public int getMpvErrorCode() { return mpvErrorCode; }
    public String getMessage() { return message; }
    public String getUrl() { return url; }
    public List<String> getLogLines() { return logLines; }
    /** True if the engine's retry policy will automatically reopen the stream. */
    public boolean willRetry() { return willRetry; }
    @Override public String toString() { return "MpvError{" + kind + ", code=" + mpvErrorCode + ", " + message + (willRetry ? ", retrying" : "") + "}"; }
}
