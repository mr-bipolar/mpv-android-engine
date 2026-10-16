package com.maxplayer.mpv;

import java.util.Collections;
import java.util.Map;

/** One playlist item. Only {@link #getUrl()} is mandatory. */
public final class PlaylistEntry {
    private final String url, title, group, logoUrl, tvgId, userAgent, referrer;
    private final double durationSeconds; private final Map<String, String> attributes;

    public PlaylistEntry(String url, String title, String group, String logoUrl, String tvgId,
                         double durationSeconds, String userAgent, String referrer, Map<String, String> attributes) {
        if (url == null || url.trim().isEmpty()) throw new IllegalArgumentException("url is required");
        this.url = url.trim(); this.title = title; this.group = group; this.logoUrl = logoUrl; this.tvgId = tvgId;
        this.durationSeconds = durationSeconds; this.userAgent = userAgent; this.referrer = referrer;
        this.attributes = attributes == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(attributes);
    }
    public static PlaylistEntry of(String url) { return new PlaylistEntry(url, null, null, null, null, -1, null, null, null); }
    public static PlaylistEntry of(String url, String title) { return new PlaylistEntry(url, title, null, null, null, -1, null, null, null); }
    public String getUrl() { return url; }
    /** Display title (from #EXTINF) or null. */
    public String getTitle() { return title; }
    /** {@code group-title} attribute / #EXTGRP, or null. */
    public String getGroup() { return group; }
    public String getLogoUrl() { return logoUrl; }
    public String getTvgId() { return tvgId; }
    /** #EXTINF duration; -1 for live/unknown (M3U uses -1 for streams). */
    public double getDurationSeconds() { return durationSeconds; }
    /** From {@code #EXTVLCOPT:http-user-agent=}, or null. */
    public String getUserAgent() { return userAgent; }
    /** From {@code #EXTVLCOPT:http-referrer=}, or null. */
    public String getReferrer() { return referrer; }
    /** All #EXTINF key="value" attributes. */
    public Map<String, String> getAttributes() { return attributes; }
    @Override public String toString() { return "PlaylistEntry{" + (title != null ? title + " " : "") + url + "}"; }
}
