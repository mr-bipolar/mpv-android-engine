package com.maxplayer.mpv;

/**
 * Tri-state live classification. {@link #UNKNOWN} is a first-class answer: IPTV providers differ
 * widely, so the engine reports what it can justify and nothing more.
 */
public enum LiveStatus {
    LIVE, VOD, UNKNOWN
}
