package com.maxplayer.mpv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, validated engine configuration. Build with {@link Builder}; {@link Builder#build()}
 * throws {@link IllegalArgumentException} listing <i>every</i> invalid value.
 *
 * <h3>What the buffering settings really control (verified against mpv docs)</h3>
 * <ul>
 *   <li><b>Demuxer cache</b> (RAM): {@code demuxer-max-bytes} (forward limit, bytes),
 *       {@code demuxer-max-back-bytes} (already-played data kept for backwards seeks),
 *       {@code cache-secs} (forward limit in seconds; the effective limit is whichever of seconds / bytes
 *       is hit first). In current mpv there is no separate "memory cache" besides this demuxer cache.</li>
 *   <li><b>Initial buffering</b>: {@code cache-pause-initial} + {@code cache-pause-wait}.</li>
 *   <li><b>Stall behaviour</b>: {@code cache-pause} (pause on underrun) + {@code cache-pause-wait}
 *       (how much data to rebuffer before resuming).</li>
 *   <li><b>HLS segment fetching</b> is performed by FFmpeg's libavformat HLS demuxer, <i>not</i> by mpv.
 *       mpv only exposes {@code hls-bitrate} (variant choice) and passes libavformat options through
 *       {@code demuxer-lavf-o} (here: {@code live_start_index}). Segment prefetching depth is not a
 *       mpv setting.</li>
 *   <li><b>Network timeouts / reconnect</b>: {@code network-timeout} (seconds, HTTP) and FFmpeg's http
 *       protocol options {@code reconnect*} passed via {@code stream-lavf-o}. Other protocols (RTMP, RTSP,
 *       UDP) may ignore them.</li>
 * </ul>
 * mpv gives no hard guarantee for buffer sizes or latency; limits can be exceeded slightly.
 */
public final class MpvPlayerConfig {

    public enum BufferingProfile { STABLE, BALANCED, LOW_LATENCY }
    public enum CacheMode { AUTO, ENABLED, DISABLED }
    public enum HlsBitrate { MAX, MIN, NONE }
    public enum VideoOutput { GPU, GPU_NEXT, NULL }

    /** Android hardware decoding choices (values verified in mpv's options documentation). */
    public enum HardwareDecoding {
        /** MediaCodec zero-copy first, MediaCodec copy-back as fallback ("mediacodec,mediacodec-copy"). */
        AUTO_ANDROID("mediacodec,mediacodec-copy"),
        MEDIACODEC("mediacodec"),
        MEDIACODEC_COPY("mediacodec-copy"),
        SOFTWARE("no");
        final String mpvValue;
        HardwareDecoding(String v) { mpvValue = v; }
    }

    public static final long MIB = 1024L * 1024L;
    public static final long MAX_CACHE_BYTES_LIMIT = 512 * MIB;

    // ---- fields (package-private for MpvPlayer) ----
    final BufferingProfile profile;
    final CacheMode cacheMode;
    final long demuxerMaxBytes, demuxerMaxBackBytes;
    final double cacheSecs, readaheadSecs, cachePauseWaitSecs;
    final boolean initialBuffering, pauseOnUnderrun;
    final int networkTimeoutSecs, reconnectDelayMaxSecs, hlsLiveStartSegmentsFromEnd;
    final boolean reconnect;
    final HlsBitrate hlsBitrate;
    final LiveStatus liveHint;
    final HardwareDecoding hardwareDecoding; final VideoOutput videoOutput; final String audioOutput;
    final Double defaultAspectRatio; final VideoScaleMode defaultScaleMode;
    final boolean subtitlesEnabled; final Double subtitleFontSize; final Integer subtitlePosition; final double subtitleDelaySeconds;
    final String subtitleLanguages, audioLanguages;
    final double defaultSpeed; final Integer initialVolume; final boolean keepLastFrameAtEnd, deinterlace;
    final String userAgent, referrer, tlsCaFile, screenshotDirectory, logLevel;
    final boolean tlsVerify;
    final Map<String, String> httpHeaders;
    final int maxRetries; final long retryInitialDelayMs; final double retryBackoff; final boolean retryOnlyNetwork;

    private MpvPlayerConfig(Builder b) {
        profile = b.profile; cacheMode = b.cacheMode; demuxerMaxBytes = b.demuxerMaxBytes;
        demuxerMaxBackBytes = b.demuxerMaxBackBytes; cacheSecs = b.cacheSecs; readaheadSecs = b.readaheadSecs;
        cachePauseWaitSecs = b.cachePauseWaitSecs; initialBuffering = b.initialBuffering;
        pauseOnUnderrun = b.pauseOnUnderrun; networkTimeoutSecs = b.networkTimeoutSecs;
        reconnect = b.reconnect; reconnectDelayMaxSecs = b.reconnectDelayMaxSecs;
        hlsLiveStartSegmentsFromEnd = b.hlsLiveStartSegmentsFromEnd; hlsBitrate = b.hlsBitrate;
        liveHint = b.liveHint; hardwareDecoding = b.hardwareDecoding; videoOutput = b.videoOutput;
        audioOutput = b.audioOutput; defaultAspectRatio = b.defaultAspectRatio; defaultScaleMode = b.defaultScaleMode;
        subtitlesEnabled = b.subtitlesEnabled; subtitleFontSize = b.subtitleFontSize;
        subtitlePosition = b.subtitlePosition; subtitleDelaySeconds = b.subtitleDelaySeconds;
        subtitleLanguages = b.subtitleLanguages; audioLanguages = b.audioLanguages;
        defaultSpeed = b.defaultSpeed; initialVolume = b.initialVolume; keepLastFrameAtEnd = b.keepLastFrameAtEnd;
        deinterlace = b.deinterlace; userAgent = b.userAgent; referrer = b.referrer; tlsCaFile = b.tlsCaFile;
        tlsVerify = b.tlsVerify; screenshotDirectory = b.screenshotDirectory; logLevel = b.logLevel;
        httpHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(b.httpHeaders));
        maxRetries = b.maxRetries; retryInitialDelayMs = b.retryInitialDelayMs; retryBackoff = b.retryBackoff;
        retryOnlyNetwork = b.retryOnlyNetwork;
    }

    public static Builder builder() { return new Builder(BufferingProfile.BALANCED); }
    /** Start from a preset; every value can still be overridden afterwards. */
    public static Builder builder(BufferingProfile profile) { return new Builder(profile); }
    public static MpvPlayerConfig defaults() { return builder().build(); }

    public BufferingProfile getProfile() { return profile; }
    public long getDemuxerMaxBytes() { return demuxerMaxBytes; }
    public double getCacheSecs() { return cacheSecs; }
    public HardwareDecoding getHardwareDecoding() { return hardwareDecoding; }
    public LiveStatus getLiveHint() { return liveHint; }
    public int getMaxRetries() { return maxRetries; }

    /** One option the engine will pass to mpv before initialisation. */
    static final class Opt {
        final String name, value;
        Opt(String n, String v) { name = n; value = v; }
        @Override public String toString() { return name + "=" + value; }
    }

    /** The ordered mpv options for this configuration (everything that can be set before init). */
    List<Opt> toMpvOptions() {
        List<Opt> o = new ArrayList<>();
        o.add(new Opt(Mpv.O_MSG_LEVEL, logLevel));
        o.add(new Opt(Mpv.O_IDLE, "yes"));
        o.add(new Opt(Mpv.O_KEEP_OPEN, keepLastFrameAtEnd ? "yes" : "no"));
        o.add(new Opt(Mpv.O_FORCE_WINDOW, "no"));
        if (profile == BufferingProfile.LOW_LATENCY) o.add(new Opt(Mpv.O_PROFILE, "low-latency"));
        o.add(new Opt(Mpv.O_CACHE, cacheMode.name().toLowerCase(java.util.Locale.ROOT)));
        o.add(new Opt(Mpv.O_DEMUXER_MAX_BYTES, Long.toString(demuxerMaxBytes)));
        o.add(new Opt(Mpv.O_DEMUXER_MAX_BACK_BYTES, Long.toString(demuxerMaxBackBytes)));
        o.add(new Opt(Mpv.O_CACHE_SECS, fmt(cacheSecs)));
        o.add(new Opt(Mpv.O_DEMUXER_READAHEAD_SECS, fmt(readaheadSecs)));
        o.add(new Opt(Mpv.O_CACHE_PAUSE, pauseOnUnderrun ? "yes" : "no"));
        o.add(new Opt(Mpv.O_CACHE_PAUSE_INITIAL, initialBuffering ? "yes" : "no"));
        o.add(new Opt(Mpv.O_CACHE_PAUSE_WAIT, fmt(cachePauseWaitSecs)));
        o.add(new Opt(Mpv.O_NETWORK_TIMEOUT, Integer.toString(networkTimeoutSecs)));
        if (reconnect) {
            // FFmpeg http protocol options (verified present in FFmpeg 6.1: ffmpeg -h protocol=http).
            o.add(new Opt(Mpv.O_STREAM_LAVF_O, "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,"
                    + "reconnect_delay_max=" + reconnectDelayMaxSecs));
        }
        // libavformat demuxer options (HLS option verified with `ffmpeg -h demuxer=hls`). NOTE: mpv_set_option_string
        // does NOT accept the "-add" suffix (verified on libmpv 0.37: error -5), so the full list is composed here.
        // The low-latency profile itself sets demuxer-lavf-o=fflags=+nobuffer; it is repeated so it is not lost.
        List<String> lavf = new ArrayList<>();
        if (profile == BufferingProfile.LOW_LATENCY) lavf.add("fflags=+nobuffer");
        if (hlsLiveStartSegmentsFromEnd != 3) lavf.add("live_start_index=-" + hlsLiveStartSegmentsFromEnd);
        if (!lavf.isEmpty()) {
            StringBuilder lb = new StringBuilder();
            for (String x : lavf) { if (lb.length() > 0) lb.append(','); lb.append(x); }
            o.add(new Opt(Mpv.O_DEMUXER_LAVF_O, lb.toString()));
        }
        o.add(new Opt(Mpv.O_HLS_BITRATE, hlsBitrate.name().toLowerCase(java.util.Locale.ROOT)));
        if (userAgent != null) o.add(new Opt(Mpv.O_USER_AGENT, userAgent));
        if (referrer != null) o.add(new Opt(Mpv.O_REFERRER, referrer));
        if (!httpHeaders.isEmpty()) o.add(new Opt(Mpv.O_HTTP_HEADER_FIELDS, headerList(httpHeaders)));
        // never let untrusted URLs/playlists trigger mpv's youtube-dl hook (spawns an external program where present)
        o.add(new Opt(Mpv.O_YTDL, "no"));
        o.add(new Opt(Mpv.O_TLS_VERIFY, tlsVerify ? "yes" : "no"));
        if (tlsCaFile != null) o.add(new Opt("tls-ca-file", tlsCaFile));
        if (videoOutput == VideoOutput.NULL) {
            o.add(new Opt(Mpv.O_VO, "null"));
        } else {
            // vo=gpu + gpu-context=android + opengl-es=yes: exactly what mpv-android's MPVView sets.
            o.add(new Opt(Mpv.O_VO, videoOutput == VideoOutput.GPU_NEXT ? "gpu-next" : "gpu"));
            o.add(new Opt(Mpv.O_GPU_CONTEXT, "android"));
            o.add(new Opt("opengl-es", "yes"));
            o.add(new Opt(Mpv.O_HWDEC, hardwareDecoding.mpvValue));
        }
        if (audioOutput != null) o.add(new Opt(Mpv.O_AO, audioOutput));
        o.add(new Opt(Mpv.O_DEINTERLACE, deinterlace ? "yes" : "no"));
        if (subtitleLanguages != null) o.add(new Opt(Mpv.O_SLANG, subtitleLanguages));
        if (audioLanguages != null) o.add(new Opt(Mpv.O_ALANG, audioLanguages));
        if (subtitleFontSize != null) o.add(new Opt(Mpv.O_SUB_FONT_SIZE, fmt(subtitleFontSize)));
        if (subtitlePosition != null) o.add(new Opt(Mpv.O_SUB_POS, Integer.toString(subtitlePosition)));
        if (screenshotDirectory != null) o.add(new Opt(Mpv.O_SCREENSHOT_DIR, screenshotDirectory));
        return o;
    }

    /** "Name: value,Name2: value2". Values may not contain ',' (verified: mpv cannot carry them, see problems()). */
    private static String headerList(Map<String, String> h) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (b.length() > 0) b.append(',');
            b.append(e.getKey()).append(": ").append(e.getValue());
        }
        return b.toString();
    }

    static String fmt(double d) {
        return (d == Math.rint(d) && Math.abs(d) < 1e12) ? Long.toString((long) d) : Double.toString(d);
    }

    public String describe() {
        StringBuilder b = new StringBuilder("MpvPlayerConfig{profile=" + profile + "\n");
        for (Opt o : toMpvOptions()) b.append("  ").append(o).append('\n');
        return b.append('}').toString();
    }

    // ------------------------------------------------------------------ builder
    public static final class Builder {
        BufferingProfile profile;
        CacheMode cacheMode = CacheMode.AUTO;
        long demuxerMaxBytes, demuxerMaxBackBytes;
        double cacheSecs, readaheadSecs, cachePauseWaitSecs;
        boolean initialBuffering, pauseOnUnderrun = true, reconnect = true;
        int networkTimeoutSecs = 15, reconnectDelayMaxSecs = 5, hlsLiveStartSegmentsFromEnd = 3;
        HlsBitrate hlsBitrate = HlsBitrate.MAX;
        LiveStatus liveHint = LiveStatus.UNKNOWN;
        HardwareDecoding hardwareDecoding = HardwareDecoding.AUTO_ANDROID;
        VideoOutput videoOutput = VideoOutput.GPU;
        String audioOutput = "audiotrack,opensles"; // as in mpv-android
        Double defaultAspectRatio; VideoScaleMode defaultScaleMode = VideoScaleMode.FIT;
        boolean subtitlesEnabled = true; Double subtitleFontSize; Integer subtitlePosition; double subtitleDelaySeconds;
        String subtitleLanguages, audioLanguages;
        double defaultSpeed = 1.0; Integer initialVolume; boolean keepLastFrameAtEnd = true, deinterlace;
        String userAgent, referrer, tlsCaFile, screenshotDirectory, logLevel = "all=warn";
        boolean tlsVerify = true;
        final Map<String, String> httpHeaders = new LinkedHashMap<>();
        int maxRetries = 3; long retryInitialDelayMs = 1000; double retryBackoff = 2.0; boolean retryOnlyNetwork = true;

        private Builder(BufferingProfile p) { applyProfile(p); }

        /** Reset all buffering values to the preset. */
        public Builder profile(BufferingProfile p) { applyProfile(p); return this; }

        private void applyProfile(BufferingProfile p) {
            profile = p;
            switch (p) {
                case STABLE:       // fewer stalls, more memory, slower start
                    cacheMode = CacheMode.ENABLED; demuxerMaxBytes = 96 * MIB; demuxerMaxBackBytes = 16 * MIB;
                    cacheSecs = 60; readaheadSecs = 10; initialBuffering = true; cachePauseWaitSecs = 4;
                    pauseOnUnderrun = true; hlsLiveStartSegmentsFromEnd = 3; break;
                case LOW_LATENCY:  // closest to the live edge, more stall risk
                    cacheMode = CacheMode.AUTO; demuxerMaxBytes = 16 * MIB; demuxerMaxBackBytes = 4 * MIB;
                    cacheSecs = 3; readaheadSecs = 1; initialBuffering = false; cachePauseWaitSecs = 0.5;
                    pauseOnUnderrun = false; hlsLiveStartSegmentsFromEnd = 1; break;
                default:           // BALANCED
                    cacheMode = CacheMode.AUTO; demuxerMaxBytes = 48 * MIB; demuxerMaxBackBytes = 16 * MIB;
                    cacheSecs = 20; readaheadSecs = 5; initialBuffering = false; cachePauseWaitSecs = 1.5;
                    pauseOnUnderrun = true; hlsLiveStartSegmentsFromEnd = 3;
            }
        }

        /** mpv {@code cache}: AUTO enables the cache only for network-like input (mpv's own heuristic). */
        public Builder networkCache(CacheMode m) { cacheMode = req(m, "networkCache"); return this; }
        /** Forward demuxer cache limit in <b>bytes</b> ({@code demuxer-max-bytes}); 1 MiB .. 512 MiB. */
        public Builder demuxerMaxBytes(long bytes) { demuxerMaxBytes = bytes; return this; }
        /** Played-data retention for backward seeks in <b>bytes</b> ({@code demuxer-max-back-bytes}); 0 .. 512 MiB. */
        public Builder demuxerMaxBackBytes(long bytes) { demuxerMaxBackBytes = bytes; return this; }
        /** Forward cache limit in <b>seconds</b> ({@code cache-secs}); 0.5 .. 3600. */
        public Builder cacheSeconds(double s) { cacheSecs = s; return this; }
        /** {@code demuxer-readahead-secs}; mostly ignored by mpv when the cache is active. 0 .. 3600. */
        public Builder readaheadSeconds(double s) { readaheadSecs = s; return this; }
        /** Wait for {@code cache-pause-wait} seconds of data before starting playback ({@code cache-pause-initial}). */
        public Builder initialBuffering(boolean enabled, double waitSeconds) {
            initialBuffering = enabled; cachePauseWaitSecs = waitSeconds; return this;
        }
        /** {@code cache-pause-wait} in seconds (0 .. 60): data required before resuming after a stall / initial buffer. */
        public Builder rebufferSeconds(double s) { cachePauseWaitSecs = s; return this; }
        /** {@code cache-pause}: pause playback on cache underrun instead of stuttering. */
        public Builder pauseOnUnderrun(boolean v) { pauseOnUnderrun = v; return this; }
        /** {@code network-timeout} in seconds, 0 .. 300 (0 = FFmpeg default). Affects HTTP at least. */
        public Builder networkTimeoutSeconds(int s) { networkTimeoutSecs = s; return this; }
        /** Enable FFmpeg http reconnect options; {@code delayMaxSeconds} 1..120 gives up after that backoff. */
        public Builder reconnect(boolean enabled, int delayMaxSeconds) { reconnect = enabled; reconnectDelayMaxSecs = delayMaxSeconds; return this; }
        /** HLS live only: start this many segments from the end (FFmpeg {@code live_start_index=-n}); 1..10, FFmpeg default 3. */
        public Builder hlsLiveStartSegmentsFromEnd(int n) { hlsLiveStartSegmentsFromEnd = n; return this; }
        public Builder hlsBitrate(HlsBitrate b) { hlsBitrate = req(b, "hlsBitrate"); return this; }
        /** Host knowledge that overrides detection (e.g. a channel from a live IPTV playlist). */
        public Builder liveHint(LiveStatus hint) { liveHint = req(hint, "liveHint"); return this; }
        public Builder hardwareDecoding(HardwareDecoding h) { hardwareDecoding = req(h, "hardwareDecoding"); return this; }
        public Builder videoOutput(VideoOutput v) { videoOutput = req(v, "videoOutput"); return this; }
        /** mpv {@code ao} list, e.g. "audiotrack,opensles", or null for mpv's default. */
        public Builder audioOutput(String ao) { audioOutput = ao; return this; }
        /** Default aspect override as width/height (e.g. 16.0/9.0); null = use the source aspect. */
        public Builder defaultAspectRatio(Double ratio) { defaultAspectRatio = ratio; return this; }
        public Builder defaultScaleMode(VideoScaleMode m) { defaultScaleMode = req(m, "defaultScaleMode"); return this; }
        public Builder subtitlesEnabled(boolean v) { subtitlesEnabled = v; return this; }
        /** {@code sub-font-size}: scaled pixels at a 720-pixel window height, 1 .. 200. null = mpv default. */
        public Builder subtitleFontSize(Double size) { subtitleFontSize = size; return this; }
        /** {@code sub-pos}: 0 .. 150 (100 = default position, larger = lower). null = mpv default. */
        public Builder subtitlePosition(Integer percent) { subtitlePosition = percent; return this; }
        public Builder subtitleDelaySeconds(double s) { subtitleDelaySeconds = s; return this; }
        /** Preferred subtitle languages, comma separated (mpv {@code slang}), e.g. "eng,en". */
        public Builder subtitleLanguages(String l) { subtitleLanguages = l; return this; }
        /** Preferred audio languages, comma separated (mpv {@code alang}). */
        public Builder audioLanguages(String l) { audioLanguages = l; return this; }
        /** 0.01 .. 100 (mpv's range). */
        public Builder defaultPlaybackSpeed(double s) { defaultSpeed = s; return this; }
        /** Initial volume 0..130 (mpv {@code volume}); null = mpv default (100). */
        public Builder initialVolume(Integer v) { initialVolume = v; return this; }
        /** Keep the last frame / do not go idle at the end of the final entry ({@code keep-open}). */
        public Builder keepLastFrameAtEnd(boolean v) { keepLastFrameAtEnd = v; return this; }
        public Builder deinterlace(boolean v) { deinterlace = v; return this; }
        public Builder userAgent(String ua) { userAgent = ua; return this; }
        public Builder referrer(String r) { referrer = r; return this; }
        /** Extra HTTP headers for every request; names/values must not contain CR/LF or ',' (mpv limitation). */
        public Builder httpHeader(String name, String value) { httpHeaders.put(name, value); return this; }
        /**
         * TLS verification. Keep true. Note: mpv-android's mbedTLS build needs a CA bundle file
         * ({@link #tlsCaFile}) because Android has no system PEM bundle; without it HTTPS fails when verifying.
         */
        public Builder tlsVerify(boolean v) { tlsVerify = v; return this; }
        /** Path to a PEM CA bundle (mpv {@code tls-ca-file}); mpv-android ships {@code cacert.pem} in filesDir. */
        public Builder tlsCaFile(String path) { tlsCaFile = path; return this; }
        /** Directory for {@code screenshot-dir}; must be writable by the app. */
        public Builder screenshotDirectory(String dir) { screenshotDirectory = dir; return this; }
        /** mpv {@code msg-level} string, e.g. "all=warn" (default) or "all=v,ffmpeg=error". */
        public Builder logLevel(String level) { logLevel = level; return this; }
        /** Automatic reopen on playback errors: attempts (0 = off), first delay, backoff multiplier. */
        public Builder retry(int maxAttempts, long initialDelayMs, double backoff) {
            maxRetries = maxAttempts; retryInitialDelayMs = initialDelayMs; retryBackoff = backoff; return this;
        }
        /** If true (default) only network streams are retried automatically. */
        public Builder retryOnlyNetworkStreams(boolean v) { retryOnlyNetwork = v; return this; }

        private static <T> T req(T v, String n) {
            if (v == null) throw new IllegalArgumentException(n + " must not be null");
            return v;
        }

        public MpvPlayerConfig build() {
            List<String> p = problems();
            if (!p.isEmpty()) throw new IllegalArgumentException("Invalid MpvPlayerConfig: " + p);
            return new MpvPlayerConfig(this);
        }

        /** All validation problems (empty = valid). */
        public List<String> problems() {
            List<String> p = new ArrayList<>();
            range(p, "demuxerMaxBytes", demuxerMaxBytes, MIB, MAX_CACHE_BYTES_LIMIT);
            range(p, "demuxerMaxBackBytes", demuxerMaxBackBytes, 0, MAX_CACHE_BYTES_LIMIT);
            range(p, "cacheSeconds", cacheSecs, 0.5, 3600);
            range(p, "readaheadSeconds", readaheadSecs, 0, 3600);
            range(p, "rebufferSeconds", cachePauseWaitSecs, 0, 60);
            range(p, "networkTimeoutSeconds", networkTimeoutSecs, 0, 300);
            range(p, "reconnectDelayMaxSeconds", reconnectDelayMaxSecs, 1, 120);
            range(p, "hlsLiveStartSegmentsFromEnd", hlsLiveStartSegmentsFromEnd, 1, 10);
            range(p, "defaultPlaybackSpeed", defaultSpeed, 0.01, 100);
            if (defaultAspectRatio != null) range(p, "defaultAspectRatio", defaultAspectRatio, 0.1, 10);
            if (subtitleFontSize != null) range(p, "subtitleFontSize", subtitleFontSize, 1, 200);
            if (subtitlePosition != null) range(p, "subtitlePosition", subtitlePosition, 0, 150);
            range(p, "subtitleDelaySeconds", subtitleDelaySeconds, -3600, 3600);
            if (initialVolume != null) range(p, "initialVolume", initialVolume, 0, 130);
            range(p, "maxRetries", maxRetries, 0, 20);
            range(p, "retryInitialDelayMs", retryInitialDelayMs, 10, 60000);
            range(p, "retryBackoff", retryBackoff, 1.0, 4.0);
            if (userAgent != null && (userAgent.trim().isEmpty() || hasCtl(userAgent))) p.add("userAgent is empty or contains control characters");
            if (referrer != null && hasCtl(referrer)) p.add("referrer contains control characters");
            for (Map.Entry<String, String> e : httpHeaders.entrySet()) {
                if (e.getKey() == null || e.getKey().trim().isEmpty() || e.getKey().indexOf(':') >= 0 || hasCtl(e.getKey()))
                    p.add("invalid HTTP header name '" + e.getKey() + "'");
                if (e.getValue() == null || hasCtl(e.getValue())) p.add("invalid value for HTTP header '" + e.getKey() + "'");
                else if (e.getValue().indexOf(',') >= 0 || e.getKey().indexOf(',') >= 0)
                    p.add("HTTP header '" + e.getKey() + "' contains ',': mpv's http-header-fields list cannot carry commas (verified on mpv 0.37)");
            }
            if (logLevel == null || logLevel.trim().isEmpty()) p.add("logLevel is empty");
            if (demuxerMaxBackBytes > demuxerMaxBytes * 4) p.add("demuxerMaxBackBytes should not exceed 4x demuxerMaxBytes");
            if (profile == BufferingProfile.LOW_LATENCY && cacheSecs > 30) p.add("LOW_LATENCY profile with cacheSeconds > 30 defeats its purpose");
            return p;
        }

        private static boolean hasCtl(String s) {
            for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c < 0x20 || c == 0x7f) return true; }
            return false;
        }
        private static void range(List<String> p, String n, double v, double lo, double hi) {
            if (Double.isNaN(v) || v < lo || v > hi) p.add(n + "=" + v + " outside [" + fmt(lo) + ", " + fmt(hi) + "]");
        }
    }
}
