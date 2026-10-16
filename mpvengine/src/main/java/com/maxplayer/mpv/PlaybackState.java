package com.maxplayer.mpv;

/** High-level playback state derived from mpv properties (see {@code MpvPlayer} for the rules). */
public enum PlaybackState {
    /** Engine created but no media loaded (mpv {@code idle-active}). */
    IDLE,
    /** A load was requested and the file is opening / probing; not yet playing. */
    LOADING,
    /** Playing (or about to produce output); {@code pause=no}, not stalled on cache. */
    PLAYING,
    PAUSED,
    /** Stalled waiting for network/cache data ({@code paused-for-cache}). */
    BUFFERING,
    /** Reached the end of the last playlist entry. */
    ENDED,
    /** The last load or playback failed and is not (or no longer) being retried. See {@code getLastError()}. */
    ERROR,
    /** {@code release()} was called. */
    RELEASED
}
