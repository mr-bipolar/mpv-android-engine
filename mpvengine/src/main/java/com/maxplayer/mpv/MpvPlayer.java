package com.maxplayer.mpv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Public facade of the playback engine (libmpv). UI-independent: the host app owns the Surface.
 *
 * <h3>Threading</h3>
 * All methods may be called from any thread (libmpv's client API is thread-safe) but
 * {@link #initialize()} and {@link #release()} must each be called once, from one thread. Listener
 * callbacks are delivered on the executor passed to the constructor (main thread for
 * {@link #create(Context, MpvPlayerConfig)}). Only <b>one</b> player may exist per process (the native
 * glue holds a single global mpv instance).
 *
 * <h3>Playback model</h3>
 * mpv's own playlist holds only the entry being played; {@link #getPlaylist()} owns the list and the
 * engine advances itself (see {@link PlaylistManager}). EOF is detected via {@code eof-reached}
 * because the engine runs mpv with {@code keep-open=yes} (configurable).
 */
public final class MpvPlayer implements MpvBackend.EventSink, PlaylistManager.Host {

    // ------------------------------------------------------------------ construction
    private final MpvBackend backend;
    private final MpvPlayerConfig config;
    private final Executor callbackExecutor;
    private final Context appContext;
    private final CopyOnWriteArrayList<MpvPlayerListener> listeners = new CopyOnWriteArrayList<>();
    private final PlaylistManager playlist = new PlaylistManager(this);
    private final LiveDetector liveDetector = new LiveDetector();
    private final AtomicInteger generation = new AtomicInteger();
    private ScheduledExecutorService scheduler;

    /** Creates a player on the real native library; callbacks run on the main thread. */
    public static MpvPlayer create(Context context, MpvPlayerConfig config) {
        final Handler h = new Handler(Looper.getMainLooper());
        return new MpvPlayer(new NativeMpvBackend(context), config, new Executor() {
            @Override public void execute(Runnable r) { h.post(r); }
        }, context.getApplicationContext());
    }

    /** For custom backends / tests. {@code appContext} may be null (disables content:// resolution). */
    public MpvPlayer(MpvBackend backend, MpvPlayerConfig config, Executor callbackExecutor, Context appContext) {
        if (backend == null || config == null || callbackExecutor == null) throw new IllegalArgumentException("null argument");
        this.backend = backend; this.config = config; this.callbackExecutor = callbackExecutor; this.appContext = appContext;
    }

    // ------------------------------------------------------------------ state (guarded by lock)
    private final Object lock = new Object();
    private boolean initialized, released;
    private boolean idleActive = true, paused, pausedForCache, coreIdle = true, eofReached, eofHandled,
            fileActive, loading, retryPending, mediaLoaded, surfaceAttached;
    private Boolean seekable, partiallySeekable;
    private int bufferPercent = -1;
    private double duration = Double.NaN, speed = 1.0, lastPositionSeconds = Double.NaN;
    private int volume = 100; private boolean muted;
    private PlaybackState state = PlaybackState.IDLE;
    private int pendingUserStops;
    private int[] pendingNativeEnd; // {reason, error}
    private MpvError lastError;
    private final List<String> errorLog = new ArrayList<>();
    private MediaInfo mediaInfo;
    private TrackReader tracks = new TrackReader();
    private LiveStatus liveStatus = LiveStatus.UNKNOWN; private String liveReason = "no media loaded";
    private Source current; private Source pendingLoad; private int attempt;
    private boolean autoAdvance = true, skipFailedEntries;
    private String voInUse = "gpu"; private int[] mpvVersion = {-1, -1};
    private String mpvVersionString, ffmpegVersionString;
    private double volumeMax = 130; private int subPosMax = 150;
    private VideoScaleMode scaleMode; private String aspectOverride = "no"; private int userRotation;
    private final List<OptionReport> optionReport = new ArrayList<>();

    /** One configuration option that mpv rejected. */
    public static final class OptionReport {
        public final String name, value, reason; public final int mpvErrorCode;
        OptionReport(String n, String v, int c) { name = n; value = v; mpvErrorCode = c; reason = Mpv.Err.describe(c); }
        @Override public String toString() { return name + "=" + value + " rejected: " + reason + " (" + mpvErrorCode + ")"; }
    }

    private static final class Source {
        final String url, mpvPath; final LoadOptions options; final PlaylistEntry entry;
        Source(String url, String mpvPath, LoadOptions o, PlaylistEntry e) { this.url = url; this.mpvPath = mpvPath; options = o; entry = e; }
    }

    // ------------------------------------------------------------------ lifecycle
    /** Creates and initialises mpv, applies the configuration and starts observing properties. */
    public void initialize() {
        synchronized (lock) {
            if (released) throw new MpvException("player already released");
            if (initialized) return;
            initialized = true;
        }
        backend.setEventSink(this);
        backend.create();
        for (MpvPlayerConfig.Opt o : config.toMpvOptions()) setInitOption(o.name, o.value);
        backend.initialize();

        mpvVersionString = backend.getPropertyString(Mpv.MPV_VERSION);
        ffmpegVersionString = backend.getPropertyString(Mpv.FFMPEG_VERSION);
        mpvVersion = Mpv.parseVersion(mpvVersionString);
        voInUse = config.videoOutput == MpvPlayerConfig.VideoOutput.GPU_NEXT ? "gpu-next" : "gpu";
        Double vm = optionNumber("volume-max", "max"); if (vm != null) volumeMax = vm;
        Double sp = optionNumber("sub-pos", "max"); if (sp != null) subPosMax = (int) Math.round(sp);

        backend.observeProperty(Mpv.PAUSE, Mpv.Format.FLAG);
        backend.observeProperty(Mpv.PAUSED_FOR_CACHE, Mpv.Format.FLAG);
        backend.observeProperty(Mpv.CORE_IDLE, Mpv.Format.FLAG);
        backend.observeProperty(Mpv.IDLE_ACTIVE, Mpv.Format.FLAG);
        backend.observeProperty(Mpv.EOF_REACHED, Mpv.Format.FLAG);
        backend.observeProperty(Mpv.SEEKABLE, Mpv.Format.FLAG);
        backend.observeProperty(Mpv.PARTIALLY_SEEKABLE, Mpv.Format.FLAG);
        backend.observeProperty(Mpv.MUTE, Mpv.Format.FLAG);
        backend.observeProperty(Mpv.TIME_POS, Mpv.Format.INT64);
        backend.observeProperty(Mpv.DURATION, Mpv.Format.DOUBLE);
        backend.observeProperty(Mpv.SPEED, Mpv.Format.DOUBLE);
        backend.observeProperty(Mpv.VOLUME, Mpv.Format.DOUBLE);
        backend.observeProperty(Mpv.CACHE_BUFFERING_STATE, Mpv.Format.INT64);
        backend.observeProperty(Mpv.TRACK_LIST, Mpv.Format.NONE);
        backend.observeProperty(Mpv.VIDEO_PARAMS_ASPECT, Mpv.Format.DOUBLE);

        // post-init defaults (runtime-settable)
        scaleMode = config.defaultScaleMode;
        applyScaleMode(scaleMode);
        if (config.defaultAspectRatio != null) setAspectRatio(config.defaultAspectRatio);
        if (config.defaultSpeed != 1.0) backend.setPropertyDouble(Mpv.SPEED, config.defaultSpeed);
        if (config.initialVolume != null) backend.setPropertyDouble(Mpv.VOLUME, config.initialVolume);
        if (!config.subtitlesEnabled) backend.setPropertyBoolean(Mpv.SUB_VISIBILITY, false);
        if (config.subtitleDelaySeconds != 0) backend.setPropertyDouble(Mpv.SUB_DELAY, config.subtitleDelaySeconds);
        recomputeState();
    }

    private void setInitOption(String name, String value) {
        int rc = backend.setOptionString(name, value);
        if (rc < 0) {
            final OptionReport r = new OptionReport(name, value, rc);
            synchronized (lock) { optionReport.add(r); }
            post(new Notify() { public void to(MpvPlayerListener l) { l.onOptionRejected(r.name, r.value, r.mpvErrorCode, r.reason); } });
        }
    }

    /** Stops playback and destroys the native engine. The instance cannot be reused. */
    public void release() {
        synchronized (lock) {
            if (released) return;
            released = true;
            generation.incrementAndGet();
        }
        ScheduledExecutorService s = scheduler;
        if (s != null) s.shutdownNow();
        boolean wasInit; synchronized (lock) { wasInit = initialized; }
        if (wasInit) backend.destroy();
        synchronized (lock) { state = PlaybackState.RELEASED; }
        listeners.clear();
    }

    public void addListener(MpvPlayerListener l) { if (l != null) listeners.addIfAbsent(l); }
    public void removeListener(MpvPlayerListener l) { listeners.remove(l); }

    // ------------------------------------------------------------------ surface
    private final MpvSurfaceCallback surfaceCallback = new MpvSurfaceCallback(this);

    /**
     * @return the (single, cached) callback to add to a host-owned {@code SurfaceView.getHolder()}. The same instance
     * is returned on every call so {@code holder.removeCallback(player.getSurfaceCallback())} really removes it.
     */
    public MpvSurfaceCallback getSurfaceCallback() { return surfaceCallback; }

    /**
     * Convenience: registers the callback AND attaches the surface right away if it already exists
     * (a SurfaceView whose surface was created before registration never delivers surfaceCreated again).
     */
    public void bindSurface(android.view.SurfaceHolder holder) {
        holder.addCallback(surfaceCallback);
        Surface s = holder.getSurface();
        if (s != null && s.isValid()) {
            attachSurface(s);
            android.graphics.Rect f = holder.getSurfaceFrame();
            if (f.width() > 0 && f.height() > 0) setSurfaceSize(f.width(), f.height());
        }
    }

    /** Counterpart of {@link #bindSurface}: removes the callback and detaches. Safe after {@link #release()}. */
    public void unbindSurface(android.view.SurfaceHolder holder) {
        holder.removeCallback(surfaceCallback);
        detachSurface();
    }

    public void attachSurface(Surface surface) {
        checkAlive();
        backend.attachSurface(surface);
        backend.setOptionString(Mpv.O_FORCE_WINDOW, "yes");
        Source pend;
        synchronized (lock) { surfaceAttached = true; pend = pendingLoad; pendingLoad = null; }
        if (pend != null) issueLoad(pend, -1);
        else if (config.videoOutput != MpvPlayerConfig.VideoOutput.NULL) backend.setPropertyString(Mpv.O_VO, voInUse);
    }

    /** Must be called before the Surface is destroyed. */
    public void detachSurface() {
        if (!isAlive()) return;
        backend.setPropertyString(Mpv.O_VO, "null");
        backend.setPropertyString(Mpv.O_FORCE_WINDOW, "no");
        backend.detachSurface();
        synchronized (lock) { surfaceAttached = false; }
    }

    /** Tell mpv the pixel size of the surface (mpv option {@code android-surface-size}). */
    public void setSurfaceSize(int width, int height) {
        if (!isAlive()) return;
        backend.setPropertyString(Mpv.ANDROID_SURFACE_SIZE, width + "x" + height);
    }

    // ------------------------------------------------------------------ loading / transport
    /** Plays a URL / path / content URI (detaches from the playlist position). */
    public void load(String uri) { load(uri, LoadOptions.defaults()); }

    public void load(String uri, LoadOptions options) {
        checkAlive();
        playlist.detachPosition();
        startSource(uri, options, null);
    }

    @Override public void playEntry(PlaylistEntry entry, int index) {
        LoadOptions.Builder b = LoadOptions.builder();
        if (entry.getUserAgent() != null) b.userAgent(entry.getUserAgent());
        if (entry.getReferrer() != null) b.referrer(entry.getReferrer());
        startSource(entry.getUrl(), b.build(), entry);
    }

    @Override public void playlistStopped() { stop(); onPlaylistModelChanged(-1, playlist.size()); }

    @Override public void onPlaylistModelChanged(final int position, final int count) {
        post(new Notify() { public void to(MpvPlayerListener l) { l.onPlaylistPositionChanged(position, count); } });
    }

    private void startSource(String uri, LoadOptions options, PlaylistEntry entry) {
        generation.incrementAndGet();
        String path = appContext != null ? UriResolver.toMpvPath(appContext, uri) : uri;
        Source s = new Source(uri, path, options, entry);
        boolean needSurface = config.videoOutput != MpvPlayerConfig.VideoOutput.NULL && !options.audioOnly;
        synchronized (lock) {
            current = s; attempt = 0; retryPending = false; lastError = null; pendingLoad = null;
            if (needSurface && !surfaceAttached) {
                // Same rule as mpv-android's BaseMPVView: loading needs a surface for the GPU video output.
                pendingLoad = s; loading = true;
            }
        }
        if (pendingLoad == s) { recomputeState(); return; }
        issueLoad(s, options.startPositionSeconds);
    }

    private void issueLoad(Source s, double startSeconds) {
        synchronized (lock) {
            if (fileActive) pendingUserStops++;
            loading = true; retryPending = false; eofHandled = false; eofReached = false;
            mediaInfo = null; mediaLoaded = false; errorLog.clear();
            liveDetector.reset(); liveStatus = LiveStatus.UNKNOWN; liveReason = "loading";
            duration = Double.NaN;
        }
        recomputeState();
        backend.command(buildLoadfile(s, startSeconds));
    }

    /** Builds the loadfile command; the argument layout depends on the mpv version (verified in docs / 0.37). */
    String[] buildLoadfile(Source s, double startSeconds) {
        List<String> kv = new ArrayList<>();
        LiveStatus hint = s.options.liveHint != null ? s.options.liveHint : config.liveHint;
        if (startSeconds > 0 && hint != LiveStatus.LIVE) kv.add("start=" + MpvPlayerConfig.fmt(startSeconds));
        if (s.options.audioOnly) kv.add("vid=no");
        if (s.options.userAgent != null) kv.add("user-agent=" + Mpv.kvValue(s.options.userAgent));
        if (s.options.referrer != null) kv.add("referrer=" + Mpv.kvValue(s.options.referrer));
        // A per-file http-header-fields REPLACES the global list in mpv (verified), so merge config + per-load
        // headers here; per-load values win. Header values cannot contain ',' (validated in LoadOptions/config).
        Map<String, String> merged = new LinkedHashMap<>(config.httpHeaders);
        merged.putAll(s.options.headers);
        if (!s.options.headers.isEmpty()) {
            StringBuilder h = new StringBuilder();
            for (Map.Entry<String, String> e : merged.entrySet()) {
                if (h.length() > 0) h.append(',');
                h.append(e.getKey()).append(": ").append(e.getValue());
            }
            // the whole list is %len%-quoted because it is itself a value inside the loadfile option list (verified)
            kv.add("http-header-fields=" + Mpv.kvValue(h.toString()));
        }
        StringBuilder opts = new StringBuilder();
        for (String k : kv) { if (opts.length() > 0) opts.append(','); opts.append(k); }
        boolean legacyLayout = mpvVersion[0] == 0 && mpvVersion[1] >= 0 && mpvVersion[1] < 38; // index arg added in 0.38
        List<String> cmd = new ArrayList<>();
        cmd.add("loadfile"); cmd.add(s.mpvPath); cmd.add("replace");
        if (opts.length() > 0) {
            if (!legacyLayout) cmd.add("-1");
            cmd.add(opts.toString());
        }
        return cmd.toArray(new String[0]);
    }

    /** Reopens the current media (resumes VOD at the last position). */
    public void reopen() {
        checkAlive();
        Source s; double pos;
        synchronized (lock) { s = current; pos = lastPositionSeconds; }
        if (s == null) throw new MpvException("nothing to reopen");
        generation.incrementAndGet();
        synchronized (lock) { attempt = 0; lastError = null; }
        issueLoad(s, resumePosition(pos));
    }

    private double resumePosition(double pos) {
        LiveStatus st; synchronized (lock) { st = liveStatus; }
        return (st != LiveStatus.LIVE && !Double.isNaN(pos) && pos > 2) ? pos : -1;
    }

    public void play() {
        checkAlive();
        PlaybackState st; synchronized (lock) { st = state; }
        if (st == PlaybackState.ENDED) { backend.command("seek", "0", "absolute"); }
        else if ((st == PlaybackState.ERROR || st == PlaybackState.IDLE) && current != null) { reopen(); return; }
        backend.setPropertyBoolean(Mpv.PAUSE, false);
    }
    public void pause() { checkAlive(); backend.setPropertyBoolean(Mpv.PAUSE, true); }
    public void togglePause() {
        PlaybackState st = getState();
        if (st == PlaybackState.PAUSED) play(); else pause();
    }

    /** Stops playback (the playlist model is kept). */
    public void stop() {
        checkAlive();
        generation.incrementAndGet();
        synchronized (lock) {
            if (fileActive) pendingUserStops++;
            pendingLoad = null; retryPending = false; loading = false; mediaLoaded = false; mediaInfo = null;
        }
        backend.command("stop");
        recomputeState();
    }

    // ---- seeking
    /** @return false (and does nothing) if mpv reports the media as not seekable. */
    public boolean seekTo(double seconds) {
        checkAlive();
        if (!canSeek()) return false;
        backend.command("seek", MpvPlayerConfig.fmt(seconds), "absolute");
        return true;
    }
    public boolean seekBy(double deltaSeconds) {
        checkAlive();
        if (!canSeek()) return false;
        backend.command("seek", MpvPlayerConfig.fmt(deltaSeconds), "relative");
        return true;
    }
    public boolean seekToPercent(double percent) {
        checkAlive();
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("percent 0..100");
        if (!canSeek()) return false;
        backend.command("seek", MpvPlayerConfig.fmt(percent), "absolute-percent");
        return true;
    }
    private boolean canSeek() {
        synchronized (lock) { return mediaLoaded && (Boolean.TRUE.equals(seekable) || Boolean.TRUE.equals(partiallySeekable)); }
    }

    /**
     * Moves playback to the live edge. If the stream is seekable and the edge is known the player seeks
     * there; otherwise (typical for live HLS: {@code seekable=no}) the stream is <b>reopened</b>, which makes
     * the demuxer start at its configured live start position. Returns false if nothing is loaded.
     */
    public boolean jumpToLive() {
        checkAlive();
        Source s; synchronized (lock) { s = current; }
        if (s == null) return false;
        double edge = getLiveEdgePositionSeconds();
        if (canSeek() && !Double.isNaN(edge)) { backend.command("seek", MpvPlayerConfig.fmt(edge), "absolute"); return true; }
        generation.incrementAndGet();
        synchronized (lock) { attempt = 0; }
        issueLoad(s, -1);
        return true;
    }

    // ------------------------------------------------------------------ playlist
    public PlaylistManager getPlaylist() { return playlist; }
    public boolean next() { return playlist.next(); }
    public boolean previous() { return playlist.previous(); }
    /** Auto-play the next playlist entry when one ends (default true). */
    public void setAutoAdvance(boolean v) { autoAdvance = v; }
    /** After retries are exhausted, continue with the next entry instead of staying in ERROR (default false). */
    public void setSkipFailedEntries(boolean v) { skipFailedEntries = v; }

    // ------------------------------------------------------------------ state getters
    public PlaybackState getState() { synchronized (lock) { return state; } }
    public MpvError getLastError() { synchronized (lock) { return lastError; } }
    public MediaInfo getMediaInfo() { synchronized (lock) { return mediaInfo; } }

    /** Current position in seconds (precise), NaN when nothing is loaded. */
    public double getPositionSeconds() {
        if (!isAlive()) return Double.NaN;
        Double d = backend.getPropertyDouble(Mpv.TIME_POS);
        return d == null ? Double.NaN : d;
    }
    /** Duration in seconds, NaN if unknown. For live HLS this is the growing playlist length — see {@link MediaInfo#getDurationSeconds()}. */
    public double getDurationSeconds() { synchronized (lock) { return duration; } }
    public String getMediaTitle() { return isAlive() ? backend.getPropertyString(Mpv.MEDIA_TITLE) : null; }
    public Map<String, String> getMetadata() { MediaInfo m = getMediaInfo(); return m == null ? Collections.<String, String>emptyMap() : m.getMetadata(); }

    /** LIVE / VOD / UNKNOWN; see {@link LiveDetector} for the rules. */
    public LiveStatus getLiveStatus() { synchronized (lock) { return liveStatus; } }
    public String getLiveStatusReason() { synchronized (lock) { return liveReason; } }
    /** True <b>only</b> when the stream was positively identified (or hinted) as live. False for VOD <i>and</i> UNKNOWN. */
    public boolean isLive() { return getLiveStatus() == LiveStatus.LIVE; }
    /** mpv's {@code seekable}; false when nothing is loaded. */
    public boolean isSeekable() { synchronized (lock) { return mediaLoaded && Boolean.TRUE.equals(seekable); } }
    /** mpv's {@code demuxer-via-network} heuristic. */
    public boolean isNetworkStream() {
        if (!isAlive()) return false;
        Boolean b = backend.getPropertyBoolean(Mpv.DEMUXER_VIA_NETWORK); return b != null && b;
    }
    /** End of buffered data ({@code demuxer-cache-time}) — NaN if unavailable. */
    public double getBufferedPositionSeconds() {
        if (!isAlive()) return Double.NaN;
        Double d = backend.getPropertyDouble(Mpv.DEMUXER_CACHE_TIME); return d == null ? Double.NaN : d;
    }
    /**
     * Approximate live edge on the player's timeline: the end of buffered data for LIVE streams, NaN otherwise.
     * It is the end of what the demuxer has fetched, not a provider-confirmed "now".
     */
    public double getLiveEdgePositionSeconds() {
        if (getLiveStatus() != LiveStatus.LIVE) return Double.NaN;
        double c = getBufferedPositionSeconds();
        return Double.isNaN(c) ? getDurationSeconds() : c;
    }
    /** Seconds the playhead is behind {@link #getLiveEdgePositionSeconds()}, NaN if unknown. */
    public double getLiveLatencySeconds() {
        double e = getLiveEdgePositionSeconds(), p = getPositionSeconds();
        return (Double.isNaN(e) || Double.isNaN(p)) ? Double.NaN : Math.max(0, e - p);
    }

    public BufferingInfo getBufferingInfo() {
        boolean stalled; int pct; synchronized (lock) { stalled = pausedForCache; pct = bufferPercent; }
        if (!isAlive()) return new BufferingInfo(stalled, pct, Double.NaN, Double.NaN, Double.NaN, -1, Double.NaN);
        Double end = backend.getPropertyDouble(Mpv.DEMUXER_CACHE_TIME), dur = backend.getPropertyDouble(Mpv.DEMUXER_CACHE_DURATION);
        double pos = getPositionSeconds();
        long fw = -1; double rate = Double.NaN;
        Map<String, Object> cs = MiniJson.parseObject(backend.getPropertyString(Mpv.DEMUXER_CACHE_STATE));
        if (cs != null) {
            if (cs.get("fw-bytes") instanceof Double) fw = ((Double) cs.get("fw-bytes")).longValue();
            if (cs.get("raw-input-rate") instanceof Double) rate = (Double) cs.get("raw-input-rate");
        }
        double ahead = (end == null || Double.isNaN(pos)) ? Double.NaN : Math.max(0, end - pos);
        return new BufferingInfo(stalled, pct, end == null ? Double.NaN : end, ahead, dur == null ? Double.NaN : dur, fw, rate);
    }

    // ------------------------------------------------------------------ audio
    public void setVolume(int volume) {
        checkAlive();
        if (volume < 0 || volume > volumeMax) throw new IllegalArgumentException("volume 0.." + (int) volumeMax);
        backend.setPropertyDouble(Mpv.VOLUME, volume);
    }
    public int getVolume() { synchronized (lock) { return volume; } }
    public void setMuted(boolean m) { checkAlive(); backend.setPropertyBoolean(Mpv.MUTE, m); }
    public boolean isMuted() { synchronized (lock) { return muted; } }
    /** 0.01 .. 100 (mpv's range). Pitch is corrected by mpv's default {@code audio-pitch-correction}. */
    public void setSpeed(double speed) {
        checkAlive();
        if (Double.isNaN(speed) || speed < 0.01 || speed > 100) throw new IllegalArgumentException("speed 0.01..100");
        backend.setPropertyDouble(Mpv.SPEED, speed);
    }
    public double getSpeed() { synchronized (lock) { return speed; } }

    public List<AudioTrackInfo> getAudioTracks() { synchronized (lock) { return Collections.unmodifiableList(new ArrayList<>(tracks.audio)); } }
    public void selectAudioTrack(int trackId) { checkAlive(); backend.setPropertyInt(Mpv.AID, trackId); }
    public void disableAudio() { checkAlive(); backend.setPropertyString(Mpv.AID, "no"); }
    /** Loads an external audio file/stream and selects it (mpv {@code audio-add}). */
    public void addExternalAudio(String uri) { checkAlive(); backend.command("audio-add", resolve(uri), "select"); }
    /** Positive delays the audio, negative delays the video (mpv docs). -60..60 s. */
    public void setAudioDelay(double seconds) {
        checkAlive();
        if (Double.isNaN(seconds) || Math.abs(seconds) > 60) throw new IllegalArgumentException("audio delay -60..60 s");
        backend.setPropertyDouble(Mpv.AUDIO_DELAY, seconds);
    }
    public double getAudioDelay() { Double d = isAlive() ? backend.getPropertyDouble(Mpv.AUDIO_DELAY) : null; return d == null ? 0 : d; }

    /** One audio output device as reported by mpv ({@code audio-device-list}); availability depends on the audio output. */
    public static final class AudioDevice {
        public final String id, description;
        AudioDevice(String id, String d) { this.id = id; description = d; }
        @Override public String toString() { return id + " (" + description + ")"; }
    }
    public List<AudioDevice> getAudioDevices() {
        List<AudioDevice> out = new ArrayList<>();
        if (!isAlive()) return out;
        Object o = MiniJson.parse(backend.getPropertyString("audio-device-list"));
        if (o instanceof List) for (Object e : (List<?>) o) if (e instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) e; out.add(new AudioDevice(String.valueOf(m.get("name")), String.valueOf(m.get("description"))));
        }
        return out;
    }
    /** @param deviceId an {@link AudioDevice#id}, or "auto". */
    public void setAudioDevice(String deviceId) { checkAlive(); backend.setPropertyString("audio-device", deviceId); }

    // ------------------------------------------------------------------ subtitles
    public List<SubtitleTrackInfo> getSubtitleTracks() { synchronized (lock) { return Collections.unmodifiableList(new ArrayList<>(tracks.subs)); } }
    public void selectSubtitleTrack(int trackId) { checkAlive(); backend.setPropertyInt(Mpv.SID, trackId); backend.setPropertyBoolean(Mpv.SUB_VISIBILITY, true); }
    /** Deselects the subtitle track ({@code sid=no}). */
    public void disableSubtitles() { checkAlive(); backend.setPropertyString(Mpv.SID, "no"); }
    /** Shows/hides the selected subtitles without changing the selection ({@code sub-visibility}). */
    public void setSubtitlesVisible(boolean v) { checkAlive(); backend.setPropertyBoolean(Mpv.SUB_VISIBILITY, v); }
    public boolean areSubtitlesVisible() { Boolean b = isAlive() ? backend.getPropertyBoolean(Mpv.SUB_VISIBILITY) : null; return b == null || b; }
    /** Loads and selects an external subtitle ({@code sub-add}); formats are whatever libass/FFmpeg in the mpv build support. */
    public void loadExternalSubtitle(String uri) { checkAlive(); backend.command("sub-add", resolve(uri), "select"); }
    /** Positive delays the subtitles (mpv {@code sub-delay}, seconds, may be negative). */
    public void setSubtitleDelay(double seconds) {
        checkAlive();
        if (Double.isNaN(seconds) || Math.abs(seconds) > 3600) throw new IllegalArgumentException("subtitle delay -3600..3600 s");
        backend.setPropertyDouble(Mpv.SUB_DELAY, seconds);
    }
    /**
     * {@code sub-font-size}: scaled pixels at a 720-pixel window height (mpv default 55 on 0.37, 38 on master),
     * 1..200. Applies to text subtitles; ASS/SSA with their own styles may ignore it (use {@link #setSubtitleScale}).
     */
    public void setSubtitleSize(double size) {
        checkAlive();
        if (Double.isNaN(size) || size < 1 || size > 200) throw new IllegalArgumentException("subtitle size 1..200");
        backend.setPropertyDouble(Mpv.O_SUB_FONT_SIZE, size);
    }
    /** {@code sub-scale} multiplier 0.1..10; also affects ASS subtitles (may distort their layout, per mpv docs). */
    public void setSubtitleScale(double scale) {
        checkAlive();
        if (Double.isNaN(scale) || scale < 0.1 || scale > 10) throw new IllegalArgumentException("subtitle scale 0.1..10");
        backend.setPropertyDouble(Mpv.O_SUB_SCALE, scale);
    }
    /** {@code sub-pos}: percent of screen height; 100 = default, larger = lower. Range 0..max reported by mpv (150). */
    public void setSubtitlePosition(int percentage) {
        checkAlive();
        if (percentage < 0 || percentage > subPosMax) throw new IllegalArgumentException("subtitle position 0.." + subPosMax);
        backend.setPropertyInt(Mpv.O_SUB_POS, percentage);
    }

    // ------------------------------------------------------------------ video geometry
    /** Aspect override as width/height, e.g. 16.0/9.0. Replaces the source/container aspect. */
    public void setAspectRatio(double ratio) {
        checkAlive();
        if (Double.isNaN(ratio) || ratio < 0.1 || ratio > 10) throw new IllegalArgumentException("aspect ratio 0.1..10");
        aspectOverride = String.format(Locale.ROOT, "%.6f", ratio);
        backend.setPropertyString(Mpv.O_ASPECT_OVERRIDE, aspectOverride);
    }
    /** Accepts "16:9", "4:3", "2.35:1" or a decimal. */
    public void setAspectRatio(String ratio) {
        double r;
        try {
            int c = ratio.indexOf(':');
            r = c > 0 ? Double.parseDouble(ratio.substring(0, c)) / Double.parseDouble(ratio.substring(c + 1)) : Double.parseDouble(ratio);
        } catch (NumberFormatException e) { throw new IllegalArgumentException("bad aspect ratio '" + ratio + "'"); }
        setAspectRatio(r);
    }
    /**
     * Restores the source aspect by writing back the mpv build's own default
     * ({@code option-info/video-aspect-override/default-value}) — the textual default differs between mpv
     * versions ("-1" on 0.37, "no" on newer builds), so it is not hard-coded.
     */
    public void resetAspectRatio() {
        checkAlive();
        String def = backend.getPropertyString("option-info/" + Mpv.O_ASPECT_OVERRIDE + "/default-value");
        if (def == null) def = "no";
        backend.setPropertyString(Mpv.O_ASPECT_OVERRIDE, def);
        aspectOverride = "no";
    }

    public void setVideoScaleMode(VideoScaleMode mode) {
        checkAlive();
        if (mode == null) throw new IllegalArgumentException("mode");
        scaleMode = mode; applyScaleMode(mode);
    }
    private void applyScaleMode(VideoScaleMode m) {
        backend.setPropertyBoolean(Mpv.O_KEEPASPECT, m != VideoScaleMode.STRETCH);
        backend.setPropertyDouble(Mpv.O_PANSCAN, m == VideoScaleMode.FILL ? 1.0 : 0.0);
        backend.setPropertyBoolean("video-unscaled", m == VideoScaleMode.ORIGINAL);
    }
    /** Zoom as a factor (1 = none, 2 = twice the size), 0.25 .. 8. Ignored by mpv when scale mode is STRETCH. */
    public void setZoom(double factor) {
        checkAlive();
        if (Double.isNaN(factor) || factor < 0.25 || factor > 8) throw new IllegalArgumentException("zoom factor 0.25..8");
        backend.setPropertyDouble(Mpv.O_VIDEO_ZOOM, Math.log(factor) / Math.log(2));
    }
    /** Shift the picture by a fraction of the video size (-1..1 on each axis). */
    public void setPan(double x, double y) {
        checkAlive();
        if (Math.abs(x) > 1 || Math.abs(y) > 1) throw new IllegalArgumentException("pan -1..1");
        backend.setPropertyDouble(Mpv.O_VIDEO_PAN_X, x); backend.setPropertyDouble(Mpv.O_VIDEO_PAN_Y, y);
    }
    /** Crop the source picture to the given rectangle in source pixels ({@code video-crop}, mpv format WxH+X+Y). */
    public void setCrop(int x, int y, int width, int height) {
        checkAlive();
        if (x < 0 || y < 0 || width <= 0 || height <= 0) throw new IllegalArgumentException("invalid crop rectangle");
        backend.setPropertyString(Mpv.O_VIDEO_CROP, width + "x" + height + "+" + x + "+" + y);
    }
    /** Removes the manual crop (restores any container-signalled crop). */
    public void clearCrop() { checkAlive(); backend.setPropertyString(Mpv.O_VIDEO_CROP, ""); }
    /** Rotate clockwise, in 90 degree steps (hardware-decoded video without copy-back only supports these anyway). */
    public void setRotation(int degrees) {
        checkAlive();
        if (degrees % 90 != 0 || degrees < 0 || degrees > 270) throw new IllegalArgumentException("rotation must be 0, 90, 180 or 270");
        userRotation = degrees;
        backend.setPropertyString(Mpv.O_VIDEO_ROTATE, Integer.toString(degrees));
    }
    /** Back to the configured defaults: aspect, scale mode, zoom 1x, no pan, no rotation, no crop. */
    public void resetVideoScaling() {
        checkAlive();
        if (config.defaultAspectRatio != null) setAspectRatio(config.defaultAspectRatio); else resetAspectRatio();
        scaleMode = config.defaultScaleMode; applyScaleMode(scaleMode);
        backend.setPropertyDouble(Mpv.O_VIDEO_ZOOM, 0); backend.setPropertyDouble(Mpv.O_VIDEO_PAN_X, 0);
        backend.setPropertyDouble(Mpv.O_VIDEO_PAN_Y, 0);
        backend.setPropertyString(Mpv.O_VIDEO_ROTATE, "0"); userRotation = 0;
        backend.setPropertyString(Mpv.O_VIDEO_CROP, "");
    }

    /** Source and display geometry. The display size is mpv's, not the Android view's: the host still owns view layout. */
    public VideoInfo getVideoInfo() {
        if (!isAlive()) return null;
        Integer w = backend.getPropertyInt(Mpv.VIDEO_PARAMS_W), h = backend.getPropertyInt(Mpv.VIDEO_PARAMS_H),
                dw = backend.getPropertyInt(Mpv.DWIDTH), dh = backend.getPropertyInt(Mpv.DHEIGHT),
                rot = backend.getPropertyInt(Mpv.VIDEO_PARAMS_ROTATE);
        Double asp = backend.getPropertyDouble(Mpv.VIDEO_PARAMS_ASPECT), fps = backend.getPropertyDouble(Mpv.CONTAINER_FPS),
                zoom = backend.getPropertyDouble(Mpv.O_VIDEO_ZOOM), px = backend.getPropertyDouble(Mpv.O_VIDEO_PAN_X),
                py = backend.getPropertyDouble(Mpv.O_VIDEO_PAN_Y);
        return new VideoInfo(n(w), n(h), asp == null ? Double.NaN : asp, n(dw), n(dh), rot == null ? 0 : rot,
                fps == null ? Double.NaN : fps, aspectOverride, scaleMode, zoom == null ? 0 : zoom, px == null ? 0 : px,
                py == null ? 0 : py, userRotation);
    }
    private static int n(Integer i) { return i == null ? -1 : i; }

    // ------------------------------------------------------------------ advanced
    /** Pause and show the next frame ({@code frame-step}). */
    public void frameStep() { checkAlive(); backend.command("frame-step"); }
    /** Step one frame back ({@code frame-back-step}); needs a seekable source. */
    public boolean frameBackStep() { checkAlive(); if (!canSeek()) return false; backend.command("frame-back-step"); return true; }
    /**
     * Writes a screenshot; the format follows the file extension (jpg/png/webp as supported by the build).
     * mpv reports no completion through this API: check the file (or watch for {@code onLog} errors).
     */
    public void takeScreenshot(String absoluteFilePath, boolean includeSubtitles) {
        checkAlive();
        backend.command("screenshot-to-file", absoluteFilePath, includeSubtitles ? "subtitles" : "video");
    }
    public void setDeinterlace(boolean on) { checkAlive(); backend.setPropertyBoolean(Mpv.O_DEINTERLACE, on); }
    /** Changes {@code hwdec}; mpv applies it to the next opened file, so call {@link #reopen()} to apply immediately. */
    public void setHardwareDecoding(MpvPlayerConfig.HardwareDecoding hw) { checkAlive(); backend.setPropertyString(Mpv.O_HWDEC, hw.mpvValue); }

    public PlaybackStats getStats() {
        if (!isAlive()) return null;
        Double cf = backend.getPropertyDouble(Mpv.CONTAINER_FPS), of = backend.getPropertyDouble(Mpv.ESTIMATED_VF_FPS),
                av = backend.getPropertyDouble(Mpv.AVSYNC), vb = backend.getPropertyDouble(Mpv.VIDEO_BITRATE),
                ab = backend.getPropertyDouble(Mpv.AUDIO_BITRATE), cs = backend.getPropertyDouble(Mpv.CACHE_SPEED);
        Double vo = backend.getPropertyDouble(Mpv.FRAME_DROP_COUNT), dec = backend.getPropertyDouble(Mpv.DECODER_FRAME_DROP_COUNT);
        return new PlaybackStats(backend.getPropertyString(Mpv.HWDEC_CURRENT), nan(cf), nan(of), nan(av),
                vb == null ? -1 : vb.longValue(), ab == null ? -1 : ab.longValue(),
                vo == null ? -1 : vo.longValue(), dec == null ? -1 : dec.longValue(), nan(cs));
    }
    private static double nan(Double d) { return d == null ? Double.NaN : d; }

    // ------------------------------------------------------------------ diagnostics
    public String getMpvVersion() { return mpvVersionString; }
    public String getFfmpegVersion() { return ffmpegVersionString; }
    /** Options the loaded mpv build rejected during {@link #initialize()}. */
    public List<OptionReport> getOptionReport() { synchronized (lock) { return new ArrayList<>(optionReport); } }
    /** True if the loaded mpv build knows this option (uses {@code option-info}, verified to report unknown options). */
    public boolean isOptionSupported(String option) {
        checkAlive();
        return backend.getPropertyString("option-info/" + option + "/name") != null;
    }
    public MpvPlayerConfig getConfig() { return config; }

    // ------------------------------------------------------------------ helpers
    private interface Notify { void to(MpvPlayerListener l); }

    private void post(final Notify n) {
        if (listeners.isEmpty()) return;
        callbackExecutor.execute(new Runnable() {
            @Override public void run() { for (MpvPlayerListener l : listeners) { try { n.to(l); } catch (RuntimeException ignored) { } } }
        });
    }

    private boolean isAlive() { synchronized (lock) { return initialized && !released; } }
    private void checkAlive() {
        synchronized (lock) {
            if (released) throw new MpvException("player released");
            if (!initialized) throw new MpvException("initialize() not called");
        }
    }
    private String resolve(String uri) { return appContext != null ? UriResolver.toMpvPath(appContext, uri) : uri; }
    private Double optionNumber(String option, String field) {
        String v = backend.getPropertyString("option-info/" + option + "/" + field);
        try { return v == null ? null : Double.valueOf(v); } catch (NumberFormatException e) { return null; }
    }
    private static long nowMs() { return System.nanoTime() / 1_000_000L; }

    // ------------------------------------------------------------------ state machine
    /** Pure derivation of the public state, exposed for unit tests. */
    static PlaybackState derive(boolean released, boolean retryPending, boolean loading, boolean hasError, boolean idle,
                                boolean eof, boolean pausedForCache, boolean paused, boolean fileActive) {
        if (released) return PlaybackState.RELEASED;
        if (retryPending || loading) return PlaybackState.LOADING;
        if (hasError && (idle || !fileActive)) return PlaybackState.ERROR;
        if (idle && !fileActive) return PlaybackState.IDLE;
        if (eof) return PlaybackState.ENDED;
        if (pausedForCache) return PlaybackState.BUFFERING;
        if (paused) return PlaybackState.PAUSED;
        return PlaybackState.PLAYING;
    }

    private void recomputeState() {
        final PlaybackState old, now;
        synchronized (lock) {
            old = state;
            now = derive(released, retryPending, loading, lastError != null, idleActive, eofReached, pausedForCache, paused, fileActive);
            state = now;
        }
        if (now != old) post(new Notify() { public void to(MpvPlayerListener l) { l.onStateChanged(now, old); } });
    }

    // ------------------------------------------------------------------ native events (mpv event thread)
    @Override public void onPropertyChange(String name) {
        if (Mpv.TRACK_LIST.equals(name)) refreshTracks();
    }

    @Override public void onPropertyChange(String name, boolean v) {
        boolean handleEof = false, seekCh = false;
        synchronized (lock) {
            switch (name) {
                case Mpv.PAUSE: paused = v; break;
                case Mpv.PAUSED_FOR_CACHE: pausedForCache = v; break;
                case Mpv.CORE_IDLE: coreIdle = v; break;
                case Mpv.IDLE_ACTIVE: idleActive = v; break;
                case Mpv.MUTE: muted = v; break;
                case Mpv.SEEKABLE: seekable = v; seekCh = true; break;
                case Mpv.PARTIALLY_SEEKABLE: partiallySeekable = v; seekCh = true; break;
                case Mpv.EOF_REACHED:
                    eofReached = v;
                    if (v && fileActive && !eofHandled) { eofHandled = true; handleEof = true; }
                    if (!v) eofHandled = false;
                    break;
                default: break;
            }
        }
        if (name.equals(Mpv.PAUSED_FOR_CACHE)) {
            final boolean st = v; final int pct; synchronized (lock) { pct = bufferPercent; }
            post(new Notify() { public void to(MpvPlayerListener l) { l.onBufferingChanged(st, pct); } });
        }
        if (name.equals(Mpv.MUTE)) { final boolean m = v; final int vol = getVolume(); post(new Notify() { public void to(MpvPlayerListener l) { l.onVolumeChanged(vol, m); } }); }
        if (seekCh) refreshLive();
        recomputeState();
        if (handleEof) onEndOfEntry();
    }

    @Override public void onPropertyChange(String name, long v) {
        if (Mpv.TIME_POS.equals(name)) {
            synchronized (lock) { lastPositionSeconds = v; }
            final double p = v;
            post(new Notify() { public void to(MpvPlayerListener l) { l.onPositionChanged(p); } });
        } else if (Mpv.CACHE_BUFFERING_STATE.equals(name)) {
            synchronized (lock) { bufferPercent = (int) v; }
        }
    }

    @Override public void onPropertyChange(String name, double v) {
        switch (name) {
            case Mpv.DURATION: {
                synchronized (lock) { duration = v; liveDetector.onDuration(v, nowMs()); }
                final double d = v;
                post(new Notify() { public void to(MpvPlayerListener l) { l.onDurationChanged(d); } });
                refreshLive();
                break;
            }
            case Mpv.SPEED: { synchronized (lock) { speed = v; } final double s = v; post(new Notify() { public void to(MpvPlayerListener l) { l.onSpeedChanged(s); } }); break; }
            case Mpv.VOLUME: {
                synchronized (lock) { volume = (int) Math.round(v); }
                final int vol = (int) Math.round(v); final boolean m = isMuted();
                post(new Notify() { public void to(MpvPlayerListener l) { l.onVolumeChanged(vol, m); } });
                break;
            }
            case Mpv.VIDEO_PARAMS_ASPECT: notifyVideoInfo(); break;
            default: break;
        }
    }

    @Override public void onPropertyChange(String name, String v) { /* none observed as string */ }

    @Override public void onEndFile(int reason, int error) {
        synchronized (lock) { pendingNativeEnd = new int[]{reason, error}; }
    }

    @Override public void onLogMessage(final String prefix, final int level, final String text) {
        if (level <= Mpv.Log.ERROR) {
            synchronized (lock) {
                errorLog.add(prefix + ": " + text.trim());
                if (errorLog.size() > 20) errorLog.remove(0);
            }
        }
        if (level <= Mpv.Log.WARN) post(new Notify() { public void to(MpvPlayerListener l) { l.onLog(level, prefix, text); } });
    }

    @Override public void onEvent(int id) {
        switch (id) {
            case Mpv.Event.START_FILE:
                synchronized (lock) { fileActive = true; loading = true; eofHandled = false; eofReached = false; mediaLoaded = false;
                    liveDetector.reset(); }
                recomputeState();
                break;
            case Mpv.Event.FILE_LOADED: onFileLoaded(); break;
            case Mpv.Event.PLAYBACK_RESTART: {
                boolean wasLoading;
                synchronized (lock) { wasLoading = loading; loading = false; }
                recomputeState();
                if (!wasLoading) { final double p = getPositionSeconds(); post(new Notify() { public void to(MpvPlayerListener l) { l.onSeekCompleted(p); } }); }
                break;
            }
            case Mpv.Event.VIDEO_RECONFIG: notifyVideoInfo(); break;
            case Mpv.Event.END_FILE: onEndFile(); break;
            default: break;
        }
    }

    private void onFileLoaded() {
        // Property-change notifications for duration/seekable can arrive after FILE_LOADED (observed on a real
        // mpv), so read the current values directly; otherwise the first MediaInfo would claim "not seekable".
        Double d = backend.getPropertyDouble(Mpv.DURATION);
        Boolean sk = backend.getPropertyBoolean(Mpv.SEEKABLE), psk = backend.getPropertyBoolean(Mpv.PARTIALLY_SEEKABLE);
        synchronized (lock) {
            mediaLoaded = true; attempt = 0; lastError = null; loading = false;
            if (d != null) { duration = d; liveDetector.onDuration(d, nowMs()); }
            if (sk != null) seekable = sk;
            if (psk != null) partiallySeekable = psk;
        }
        refreshTracks();
        refreshLive();
        final MediaInfo mi = buildMediaInfo();
        synchronized (lock) { mediaInfo = mi; }
        recomputeState();
        post(new Notify() { public void to(MpvPlayerListener l) { l.onMediaLoaded(mi); } });
    }

    private void refreshTracks() {
        if (!isAlive()) return;
        final TrackReader t = TrackReader.read(backend);
        synchronized (lock) { tracks = t; if (mediaInfo != null) mediaInfo = rebuild(mediaInfo); }
        post(new Notify() { public void to(MpvPlayerListener l) { l.onTracksChanged(Collections.unmodifiableList(t.audio), Collections.unmodifiableList(t.subs)); } });
    }

    private MediaInfo rebuild(MediaInfo m) { return buildMediaInfo(); }

    private void refreshLive() {
        final LiveStatus oldS; final LiveStatus newS; final String why;
        synchronized (lock) {
            oldS = liveStatus;
            LiveStatus hint = current != null && current.options.liveHint != null ? current.options.liveHint : config.liveHint;
            String fmt = isAliveLocked() ? backend.getPropertyString(Mpv.FILE_FORMAT) : null;
            LiveDetector.Result r = liveDetector.evaluate(hint, mediaLoaded, duration, seekable, partiallySeekable, fmt, nowMs());
            liveStatus = r.status; liveReason = r.reason; newS = r.status; why = r.reason;
            if (mediaInfo != null && newS != oldS) mediaInfo = buildMediaInfoLocked();
        }
        if (newS != oldS) post(new Notify() { public void to(MpvPlayerListener l) { l.onLiveStatusChanged(newS, why); } });
    }
    private boolean isAliveLocked() { return initialized && !released; }

    private MediaInfo buildMediaInfo() { synchronized (lock) { return buildMediaInfoLocked(); } }

    private MediaInfo buildMediaInfoLocked() {
        Map<String, String> meta = new LinkedHashMap<>();
        Map<String, Object> m = MiniJson.parseObject(backend.getPropertyString(Mpv.METADATA));
        if (m != null) for (Map.Entry<String, Object> e : m.entrySet()) meta.put(e.getKey(), String.valueOf(e.getValue()));
        Boolean net = backend.getPropertyBoolean(Mpv.DEMUXER_VIA_NETWORK);
        return new MediaInfo(backend.getPropertyString(Mpv.PATH), backend.getPropertyString(Mpv.MEDIA_TITLE),
                backend.getPropertyString(Mpv.FILE_FORMAT), net != null && net, duration,
                Boolean.TRUE.equals(seekable), Boolean.TRUE.equals(partiallySeekable), liveStatus, liveReason, meta,
                new ArrayList<>(tracks.video), new ArrayList<>(tracks.audio), new ArrayList<>(tracks.subs),
                backend.getPropertyString(Mpv.HWDEC_CURRENT));
    }

    private void notifyVideoInfo() {
        if (!isAlive()) return;
        final VideoInfo vi = getVideoInfo();
        if (vi != null) post(new Notify() { public void to(MpvPlayerListener l) { l.onVideoInfoChanged(vi); } });
    }

    // ---- end of entry handling
    private void onEndOfEntry() { // EOF detected through eof-reached
        post(new Notify() { public void to(MpvPlayerListener l) { l.onPlaybackCompleted(); l.onEntryEnded(EndReason.EOF, null); } });
        if (autoAdvance && playlist.hasNext()) playlist.next();
    }

    private void onEndFile() {
        EndReason reason; int code = 0; boolean eofAlready;
        synchronized (lock) {
            fileActive = false; loading = false; mediaLoaded = false;
            int[] nat = pendingNativeEnd; pendingNativeEnd = null;
            boolean userStop = pendingUserStops > 0;
            if (userStop) pendingUserStops--;
            eofAlready = eofHandled;
            if (nat != null) {
                code = nat[1];
                switch (nat[0]) {
                    case Mpv.EndFileReason.EOF: reason = EndReason.EOF; break;
                    case Mpv.EndFileReason.STOP: reason = EndReason.STOPPED; break;
                    case Mpv.EndFileReason.QUIT: reason = EndReason.QUIT; break;
                    case Mpv.EndFileReason.ERROR: reason = EndReason.ERROR; break;
                    case Mpv.EndFileReason.REDIRECT: reason = EndReason.REDIRECT; break;
                    default: reason = EndReason.UNKNOWN;
                }
            } else if (userStop) reason = EndReason.STOPPED;
            else if (eofReached || eofHandled) reason = EndReason.EOF;
            else reason = EndReason.ERROR; // inferred: not requested, no eof observed (see docs/LIMITATIONS)
        }
        switch (reason) {
            case EOF:
                if (!eofAlready) { synchronized (lock) { eofHandled = true; } onEndOfEntry(); }
                break;
            case ERROR: onPlaybackError(code); break;
            default: {
                final EndReason r = reason;
                post(new Notify() { public void to(MpvPlayerListener l) { l.onEntryEnded(r, null); } });
            }
        }
        recomputeState();
    }

    private void onPlaybackError(int code) {
        final Source s; final boolean hadMedia; final String logLine; final double pos; final int att; final int gen;
        synchronized (lock) {
            s = current; hadMedia = mediaInfo != null; pos = lastPositionSeconds; att = attempt; gen = generation.get();
            logLine = errorLog.isEmpty() ? null : errorLog.get(errorLog.size() - 1);
        }
        MpvError.Kind kind;
        switch (code) {
            case Mpv.Err.LOADING_FAILED: kind = MpvError.Kind.LOAD_FAILED; break;
            case -14: case -15: kind = MpvError.Kind.OUTPUT_INIT_FAILED; break;
            case -17: case -18: kind = MpvError.Kind.UNKNOWN_FORMAT; break;
            default: kind = code != 0 ? MpvError.Kind.UNKNOWN : (hadMedia ? MpvError.Kind.PLAYBACK_FAILED : MpvError.Kind.LOAD_FAILED);
        }
        boolean eligible = s != null && att < config.maxRetries && (!config.retryOnlyNetwork || looksNetwork(s.url) || isLiveHinted(s))
                && !isAbortCode(code);
        String msg = hadMedia ? "Playback was interrupted" : "Unable to open this stream";
        List<String> lines; synchronized (lock) { lines = new ArrayList<>(errorLog); }
        final MpvError err = new MpvError(kind, code, msg, s == null ? null : s.url, lines, eligible);
        synchronized (lock) { if (!eligible) lastError = err; }
        post(new Notify() { public void to(MpvPlayerListener l) { l.onEntryEnded(EndReason.ERROR, err); l.onError(err); } });
        if (eligible) {
            final int nextAttempt = att + 1;
            final long delay = (long) (config.retryInitialDelayMs * Math.pow(config.retryBackoff, att));
            synchronized (lock) { attempt = nextAttempt; retryPending = true; }
            post(new Notify() { public void to(MpvPlayerListener l) { l.onRetryScheduled(nextAttempt, config.maxRetries, delay); } });
            final double resume = resumePosition(pos);
            scheduler().schedule(new Runnable() {
                @Override public void run() {
                    if (gen != generation.get()) return;
                    synchronized (lock) { if (released) return; }
                    issueLoad(s, resume);
                }
            }, delay, TimeUnit.MILLISECONDS);
        } else if (skipFailedEntries && playlist.hasNext()) {
            playlist.next();
        }
    }

    private static boolean isAbortCode(int code) { return code == Mpv.Err.INVALID_PARAMETER; }
    private boolean isLiveHinted(Source s) {
        LiveStatus h = s.options.liveHint != null ? s.options.liveHint : config.liveHint; return h == LiveStatus.LIVE;
    }
    /** URL scheme check used ONLY to decide whether automatic retry makes sense (never for live classification). */
    static boolean looksNetwork(String url) {
        int c = url.indexOf("://");
        if (c <= 0) return false;
        String s = url.substring(0, c).toLowerCase(Locale.ROOT);
        return !(s.equals("file") || s.equals("content") || s.equals("fd") || s.equals("data"));
    }

    private synchronized ScheduledExecutorService scheduler() {
        if (scheduler == null) scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "mpv-retry"); t.setDaemon(true); return t; }
        });
        return scheduler;
    }
}
