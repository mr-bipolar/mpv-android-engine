package com.maxplayer.mpv;

/** Why the current playlist entry stopped. */
public enum EndReason {
    /** Played to the end. */
    EOF,
    /** Stopped by the application (stop/load another/next/prev). */
    STOPPED,
    /** Failed (open failure, demux/network error, ...). */
    ERROR,
    /** The core is shutting down. */
    QUIT,
    /** Entry redirected to another (e.g. a playlist file expanded). */
    REDIRECT,
    UNKNOWN
}
