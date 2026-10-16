package com.maxplayer.mpv;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parser for M3U / M3U8 <i>channel playlists</i> (EXTM3U with EXTINF entries). No UI, no network.
 *
 * <p>Note: an {@code .m3u8} file may also be an <b>HLS stream manifest</b> (what a single IPTV channel
 * URL usually points to). {@link #looksLikeHlsManifest(String)} tells them apart so you do not import a
 * manifest as if it were a channel list.
 *
 * <p>Security: playlists are untrusted input. By default only network streaming schemes are accepted;
 * {@code file:}, {@code content:} and bare paths require {@link Options#allowLocal(boolean)}; mpv-specific
 * pseudo-protocols (e.g. {@code edl://}, {@code memory://}, {@code lavf://}, {@code ytdl://}) are always rejected.
 */
public final class M3uParser {

    public static final Set<String> DEFAULT_SCHEMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "http", "https", "rtmp", "rtmps", "rtsp", "rtp", "udp", "srt", "mms", "mmsh", "mmst")));
    private static final Set<String> LOCAL_SCHEMES = new HashSet<>(Arrays.asList("file", "content"));

    public static final class Options {
        private URI base; private boolean allowLocal; private Set<String> schemes = DEFAULT_SCHEMES;
        /** Base URI used to resolve relative entries (the playlist's own location). */
        public Options baseUri(String base) { this.base = base == null ? null : URI.create(base.replace(" ", "%20")); return this; }
        public Options allowLocal(boolean v) { allowLocal = v; return this; }
        public Options allowedSchemes(Set<String> s) { schemes = s; return this; }
    }

    /** Parsing outcome: valid entries plus a warning per rejected/ignored line (never throws on bad lines). */
    public static final class Result {
        private final List<PlaylistEntry> entries; private final List<String> warnings;
        private final boolean extm3uHeader; private final Map<String, String> headerAttributes;
        Result(List<PlaylistEntry> e, List<String> w, boolean h, Map<String, String> a) {
            entries = Collections.unmodifiableList(e); warnings = Collections.unmodifiableList(w);
            extm3uHeader = h; headerAttributes = Collections.unmodifiableMap(a);
        }
        public List<PlaylistEntry> getEntries() { return entries; }
        /** Lines such as "line 12: unsupported scheme 'edl'". */
        public List<String> getWarnings() { return warnings; }
        public boolean hasExtm3uHeader() { return extm3uHeader; }
        /** Attributes on the #EXTM3U line (e.g. url-tvg). */
        public Map<String, String> getHeaderAttributes() { return headerAttributes; }
    }

    private M3uParser() {}

    /** True if the text is an HLS manifest (media or master playlist) rather than a channel list. */
    public static boolean looksLikeHlsManifest(String text) {
        return text != null && (text.contains("#EXT-X-TARGETDURATION") || text.contains("#EXT-X-STREAM-INF")
                || text.contains("#EXT-X-MEDIA-SEQUENCE") || text.contains("#EXT-X-ENDLIST"));
    }

    public static Result parse(String text) { return parse(text, new Options()); }

    public static Result parse(String text, Options opt) {
        List<PlaylistEntry> out = new ArrayList<>();
        List<String> warn = new ArrayList<>();
        Map<String, String> headerAttrs = new LinkedHashMap<>();
        if (text == null) return new Result(out, warn, false, headerAttrs);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        String[] lines = text.split("\r\n|\n|\r", -1);
        boolean header = false;

        // pending #EXTINF / option state for the next URL line
        String title = null, group = null, ua = null, ref = null; double dur = -1;
        Map<String, String> attrs = null; boolean havePending = false;

        for (int n = 0; n < lines.length; n++) {
            String raw = lines[n].trim();
            if (raw.isEmpty()) continue;
            if (raw.startsWith("#")) {
                String up = raw.toUpperCase(Locale.ROOT);
                if (up.startsWith("#EXTM3U")) { header = true; headerAttrs.putAll(attributes(raw.substring(7))); }
                else if (up.startsWith("#EXTINF:")) {
                    havePending = true;
                    String body = raw.substring(8);
                    int comma = commaOutsideQuotes(body);
                    String meta = comma >= 0 ? body.substring(0, comma) : body;
                    title = comma >= 0 ? body.substring(comma + 1).trim() : null;
                    if (title != null && title.isEmpty()) title = null;
                    int sp = meta.indexOf(' ');
                    String durStr = (sp >= 0 ? meta.substring(0, sp) : meta).trim();
                    try { dur = Double.parseDouble(durStr); } catch (NumberFormatException e) { dur = -1; warn.add("line " + (n + 1) + ": bad EXTINF duration '" + durStr + "'"); }
                    if (dur < 0) dur = -1;
                    attrs = attributes(sp >= 0 ? meta.substring(sp) : "");
                    if (attrs.containsKey("group-title")) group = attrs.get("group-title");
                } else if (up.startsWith("#EXTGRP:")) { group = raw.substring(8).trim(); havePending = true; }
                else if (up.startsWith("#EXTVLCOPT:")) {
                    String kv = raw.substring(11); int eq = kv.indexOf('=');
                    if (eq > 0) {
                        String k = kv.substring(0, eq).trim().toLowerCase(Locale.ROOT), v = kv.substring(eq + 1).trim();
                        if (k.equals("http-user-agent")) ua = v; else if (k.equals("http-referrer") || k.equals("http-referer")) ref = v;
                    }
                    havePending = true;
                }
                continue; // other comments / unknown tags ignored
            }
            // URL line
            String resolved = validate(raw, opt, n + 1, warn);
            if (resolved != null) {
                out.add(new PlaylistEntry(resolved, title, group, attrs == null ? null : attrs.get("tvg-logo"),
                        attrs == null ? null : attrs.get("tvg-id"), dur, ua, ref, attrs));
            }
            title = null; group = null; ua = null; ref = null; dur = -1; attrs = null; havePending = false;
        }
        if (havePending) warn.add("trailing #EXTINF/#EXTVLCOPT without a URL ignored");
        return new Result(out, warn, header, headerAttrs);
    }

    private static String validate(String raw, Options opt, int line, List<String> warn) {
        String scheme = null;
        int colon = raw.indexOf(':');
        if (colon > 0 && raw.substring(0, colon).matches("[A-Za-z][A-Za-z0-9+.\\-]*")) scheme = raw.substring(0, colon).toLowerCase(Locale.ROOT);
        if (scheme != null && scheme.length() == 1) scheme = null; // Windows drive letter C:\...
        if (scheme == null) {
            // relative reference or bare path
            if (opt.base != null) {
                try {
                    String r = opt.base.resolve(URI.create(raw.replace(" ", "%20"))).toString();
                    String rs = URI.create(r).getScheme();
                    if (rs == null) { warn.add("line " + line + ": cannot resolve '" + raw + "'"); return null; }
                    if (LOCAL_SCHEMES.contains(rs.toLowerCase(Locale.ROOT)) && !opt.allowLocal) {
                        warn.add("line " + line + ": local path rejected (allowLocal=false): " + raw); return null;
                    }
                    return r;
                } catch (IllegalArgumentException e) { warn.add("line " + line + ": invalid relative URL '" + raw + "'"); return null; }
            }
            if (!opt.allowLocal) { warn.add("line " + line + ": relative/local path rejected: " + raw); return null; }
            return raw;
        }
        if (LOCAL_SCHEMES.contains(scheme)) {
            if (!opt.allowLocal) { warn.add("line " + line + ": local scheme '" + scheme + "' rejected (allowLocal=false)"); return null; }
            return raw;
        }
        if (!opt.schemes.contains(scheme)) { warn.add("line " + line + ": unsupported scheme '" + scheme + "'"); return null; }
        if (raw.indexOf(' ') >= 0 && raw.indexOf('|') < 0) { /* spaces are tolerated; mpv will percent-handle or fail at open time */ }
        return raw;
    }

    private static int commaOutsideQuotes(String s) {
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') q = !q; else if (c == ',' && !q) return i;
        }
        return -1;
    }

    static Map<String, String> attributes(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            int ks = i;
            while (i < n && s.charAt(i) != '=' && !Character.isWhitespace(s.charAt(i))) i++;
            if (i >= n || s.charAt(i) != '=') { continue; }
            String key = s.substring(ks, i).toLowerCase(Locale.ROOT);
            i++;
            String val;
            if (i < n && s.charAt(i) == '"') {
                int end = s.indexOf('"', i + 1);
                if (end < 0) end = n;
                val = s.substring(i + 1, end); i = Math.min(n, end + 1);
            } else {
                int vs = i; while (i < n && !Character.isWhitespace(s.charAt(i))) i++;
                val = s.substring(vs, i);
            }
            if (!key.isEmpty()) m.put(key, val);
        }
        return m;
    }
}
