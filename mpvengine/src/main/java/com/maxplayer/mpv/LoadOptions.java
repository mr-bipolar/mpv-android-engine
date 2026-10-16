package com.maxplayer.mpv;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-load settings for {@link MpvPlayer#load(String, LoadOptions)}. Immutable; use {@link Builder}. */
public final class LoadOptions {
    final double startPositionSeconds; final LiveStatus liveHint; final boolean audioOnly;
    final String userAgent, referrer; final Map<String, String> headers;

    private LoadOptions(Builder b) {
        startPositionSeconds = b.start; liveHint = b.liveHint; audioOnly = b.audioOnly;
        userAgent = b.userAgent; referrer = b.referrer; headers = new LinkedHashMap<>(b.headers);
    }
    public static LoadOptions defaults() { return new Builder().build(); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private double start = -1; private LiveStatus liveHint; private boolean audioOnly;
        private String userAgent, referrer; private final Map<String, String> headers = new LinkedHashMap<>();
        /** Start playback at this position (mpv {@code start}); ignored for live. */
        public Builder startPositionSeconds(double s) { start = s; return this; }
        /** Overrides the config-level hint for this media only. */
        public Builder liveHint(LiveStatus h) { liveHint = h; return this; }
        /** Disable video decoding/output ({@code vid=no}); no surface needed. Use for radio/audio streams. */
        public Builder audioOnly(boolean v) { audioOnly = v; return this; }
        public Builder userAgent(String ua) { userAgent = ua; return this; }
        public Builder referrer(String r) { referrer = r; return this; }
        public Builder header(String name, String value) { headers.put(name, value); return this; }
        public LoadOptions build() {
            if (Double.isNaN(start) || start < -1) throw new IllegalArgumentException("startPositionSeconds");
            for (Map.Entry<String, String> e : headers.entrySet())
                if (e.getKey().isEmpty() || e.getKey().indexOf(':') >= 0 || ctl(e.getKey()) || ctl(e.getValue()))
                    throw new IllegalArgumentException("invalid header " + e.getKey());
            for (Map.Entry<String, String> e : headers.entrySet())
                if (e.getKey().indexOf(',') >= 0 || e.getValue().indexOf(',') >= 0)
                    throw new IllegalArgumentException("header '" + e.getKey() + "' contains ',': not supported by mpv's http-header-fields (use a value without commas)");
            if (userAgent != null && ctl(userAgent)) throw new IllegalArgumentException("userAgent");
            if (referrer != null && ctl(referrer)) throw new IllegalArgumentException("referrer");
            return new LoadOptions(this);
        }
        private static boolean ctl(String s) {
            for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c < 0x20 || c == 0x7f) return true; }
            return false;
        }
    }
}
