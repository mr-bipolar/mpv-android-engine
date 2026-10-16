package com.mrbipolar.mpvengine.sample.player;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.ImageViewCompat;

import com.mrbipolar.mpvengine.sample.R;
import com.mrbipolar.mpvengine.sample.databinding.ActivityMpvPlayerBinding;
import com.mrbipolar.mpvengine.sample.utils.ClickUtils;
import com.maxplayer.mpv.AudioTrackInfo;
import com.maxplayer.mpv.BufferingInfo;
import com.maxplayer.mpv.LiveStatus;
import com.maxplayer.mpv.LoadOptions;
import com.maxplayer.mpv.M3uParser;
import com.maxplayer.mpv.MediaInfo;
import com.maxplayer.mpv.MpvError;
import com.maxplayer.mpv.MpvPlayer;
import com.maxplayer.mpv.MpvPlayerConfig;
import com.maxplayer.mpv.MpvPlayerListener;
import com.maxplayer.mpv.PlaybackState;
import com.maxplayer.mpv.PlaybackStats;
import com.maxplayer.mpv.PlaylistEntry;
import com.maxplayer.mpv.SubtitleTrackInfo;
import com.maxplayer.mpv.UriResolver;
import com.maxplayer.mpv.VideoInfo;
import com.maxplayer.mpv.VideoScaleMode;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The whole video player in one class: owns the mpv engine, the surface and every control of
 * {@code activity_mpv_player.xml}. The Activity only inflates the layout, creates this object and forwards
 * the lifecycle ({@link #onStart()}, {@link #onStop()}, {@link #onDestroy()}, {@link #onWindowFocusChanged}).
 *
 * <p>Everything runs on the main thread (engine callbacks are delivered there by {@code MpvPlayer.create}).
 */
public final class MpvVideoPlayer {

    /** Hooks for the host Activity. */
    public interface Callbacks {
        void onCloseRequested();
    }

    private static final String TAG = "MPV";
    private static final long AUTO_HIDE_MS = 5000;
    private static final long TICK_MS = 500;
    private static final int DOUBLE_TAP_SEEK_SECONDS = 10;
    private static final double[] SPEEDS = {0.5, 0.75, 1.0, 1.25, 1.5, 2.0};
    private static final String PREFS = "mpv_player_prefs";
    private static final String KEY_FAVORITES = "favorites";
    private static final float DISABLED_ALPHA = 0.35f;
    private static final String KEY_SUB_SIZE = "subtitle_size";
    /** mpv sub-font-size is in "scaled pixels at 720p window height": mpv's own default is large on phones, so we default smaller. */
    private static final double DEFAULT_SUB_SIZE = 26;
    private static final double[] SUB_SIZES = {20, 26, 32, 40};
    private static final int[] SUB_SIZE_LABELS = {R.string.mp_sub_small, R.string.mp_sub_medium, R.string.mp_sub_large, R.string.mp_sub_xl};

    private enum AspectMode {
        FIT(R.string.mp_aspect_fit), RATIO_16_9(R.string.mp_aspect_16_9), RATIO_4_3(R.string.mp_aspect_4_3),
        FILL(R.string.mp_aspect_fill), STRETCH(R.string.mp_aspect_stretch);
        final int labelRes;
        AspectMode(int labelRes) { this.labelRes = labelRes; }
    }

    private final AppCompatActivity activity;
    private final ActivityMpvPlayerBinding b;
    private final Callbacks callbacks;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final ActivityResultLauncher<String[]> subtitlePicker;

    private MpvPlayer player;
    private SurfaceHolder.Callback engineSurfaceCallback;
    private PlaybackState state = PlaybackState.IDLE;
    private boolean controlsVisible, userSeeking, destroyed, resumeAfterSurface, initialized;
    private AspectMode aspectMode = AspectMode.FIT;
    private String explicitTitle, mediaTitle, currentUrl;
    private AlertDialog dialog;
    private double subtitleSize = DEFAULT_SUB_SIZE;
    private Integer userSubtitleId;      // subtitle track the user picked (null = none / off)

    public MpvVideoPlayer(AppCompatActivity activity, ActivityMpvPlayerBinding binding, Callbacks callbacks) {
        this.activity = activity;
        this.b = binding;
        this.callbacks = callbacks;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // must be registered before the Activity is STARTED -> construct this class in onCreate()
        this.subtitlePicker = activity.registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onSubtitlePicked);
    }


    // Setup
    /** Wires the UI and creates the engine. Call once from onCreate(), then one of the play methods. */
    public void initialize() {
        if (initialized) return;
        initialized = true;
        setupSystemUi();
        setupInsets();
        setupButtons();
        setupSeekBar();
        setupGestures();
        createEngine();
        setControlsVisible(true);
        renderState(PlaybackState.IDLE);
        updateHeader();
    }

    private void createEngine() {
        subtitleSize = prefs.getFloat(KEY_SUB_SIZE, (float) DEFAULT_SUB_SIZE);
        String caPath = null;
        try {
            caPath = copyCaBundle();
        } catch (IOException e) {
            Log.e(TAG, "CA bundle copy failed", e);
            toast(R.string.mp_cert_failed);
        }
        try {
            MpvPlayerConfig.Builder cfg = MpvPlayerConfig.builder(MpvPlayerConfig.BufferingProfile.BALANCED)
                    .networkTimeoutSeconds(15)
                    .retry(3, 1000, 2.0)
                    .hardwareDecoding(MpvPlayerConfig.HardwareDecoding.AUTO_ANDROID)
                    .subtitleFontSize(subtitleSize)
                    .tlsVerify(true);
            if (caPath != null) cfg.tlsCaFile(caPath);
            player = MpvPlayer.create(activity.getApplicationContext(), cfg.build());
            player.addListener(new EngineListener());
            player.initialize();
            Log.i(TAG, player.getMpvVersion() + " | " + player.getFfmpegVersion() + " | rejected options: " + player.getOptionReport());
        } catch (RuntimeException | UnsatisfiedLinkError e) {   // MpvException (native libs missing, bad config) etc.
            Log.e(TAG, "Engine start failed", e);
            player = null;
            showFatal(String.valueOf(e.getMessage()));
            return;
        }
        bindSurface();
    }

    /** Copies assets/cacert.pem to filesDir once (mbedTLS builds need a PEM bundle for https). */
    private String copyCaBundle() throws IOException {
        File out = new File(activity.getFilesDir(), "cacert.pem"); // app/src/main/assets/cacert.pem
        if (out.exists() && out.length() > 0) return out.getAbsolutePath();
        try (InputStream in = activity.getAssets().open("cacert.pem");
             FileOutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
        }
        return out.getAbsolutePath();
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override public void surfaceCreated(SurfaceHolder holder) {
            engineSurfaceCallback.surfaceCreated(holder);
            onSurfaceReady();
        }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            engineSurfaceCallback.surfaceChanged(holder, format, width, height);
        }
        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            engineSurfaceCallback.surfaceDestroyed(holder);
        }
    };

    private void bindSurface() {
        engineSurfaceCallback = player.getSurfaceCallback();          // keep ONE reference: add and remove the same object
        SurfaceHolder holder = b.surfaceView.getHolder();
        holder.addCallback(surfaceCallback);
        if (holder.getSurface() != null && holder.getSurface().isValid()) {   // surface may already exist
            android.graphics.Rect f = holder.getSurfaceFrame();
            engineSurfaceCallback.surfaceCreated(holder);
            engineSurfaceCallback.surfaceChanged(holder, PixelFormat.RGBA_8888, f.width(), f.height());
        }
    }

    private void onSurfaceReady() {
        scheduleSubtitleReapply();
        if (player == null || !resumeAfterSurface) return;
        resumeAfterSurface = false;
        if (player.isLive()) player.jumpToLive();      // do not continue a stale live buffer
        else player.play();
    }

    private void setupSystemUi() {
        Window w = activity.getWindow();
        WindowInsetsControllerCompat c = new WindowInsetsControllerCompat(w, w.getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
    }

    /** The video fills the whole screen; only the controls are padded away from bars / cutouts. */
    private void setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.getRoot(), (v, insets) -> {
            Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            b.playerControllers.setPadding(i.left, i.top, i.right, i.bottom);
            return insets;
        });
    }

    private void setupButtons() {
        ClickUtils.applyTouchEffect(b.btnBack, v -> callbacks.onCloseRequested());
        ClickUtils.applyTouchEffect(b.btnFavorite, v -> toggleFavorite());
        ClickUtils.applyTouchEffect(b.btnMore, v -> showMoreMenu());
        ClickUtils.applyTouchEffect(b.playPause, v -> onPlayPauseClicked());
        ClickUtils.applyTouchEffect(b.btnPrev, v -> { if (player != null) player.previous(); });
        ClickUtils.applyTouchEffect(b.btnNext, v -> { if (player != null) player.next(); });
        ClickUtils.applyTouchEffect(b.btnAudio, v -> showAudioDialog());
        ClickUtils.applyTouchEffect(b.btnSubtitles, v -> showSubtitleDialog());
        ClickUtils.applyTouchEffect(b.btnSpeed, v -> showSpeedDialog());
        ClickUtils.applyTouchEffect(b.btnAspect, v -> cycleAspect());
        ClickUtils.applyTouchEffect(b.btnChannels, v -> showChannelsDialog());
        ClickUtils.applyTouchEffect(b.btnFullscreen, v -> toggleOrientation());
        ClickUtils.applyTouchEffect(b.liveRow, v -> { if (player != null && player.jumpToLive()) restartAutoHide(); });
        ClickUtils.applyTouchEffect(b.btnRetry, v -> { if (player != null) player.reopen(); });
        b.tvAspectLabel.setText(aspectMode.labelRes);
    }

    private void setupSeekBar() {
        b.videoProgress.setMax(1000);
        b.videoProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser && player != null) b.tvPosition.setText(formatTime(player.getDurationSeconds() * progress / s.getMax()));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                userSeeking = true;
                handler.removeCallbacks(hideRunnable);
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (player != null) {
                    double duration = player.getDurationSeconds();
                    if (duration > 0) {
                        double targetSeconds = duration * s.getProgress() / s.getMax();     // the engine takes SECONDS
                        if (!player.seekTo(targetSeconds)) toast(R.string.mp_not_seekable);
                    }
                }
                restartAutoHide();
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupGestures() {
        final GestureDetector detector = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleControls(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (!isVodUi()) return false;
                boolean left = e.getX() < b.surfaceView.getWidth() / 2f;
                player.seekBy(left ? -DOUBLE_TAP_SEEK_SECONDS : DOUBLE_TAP_SEEK_SECONDS);
                setControlsVisible(true);
                return true;
            }
        });
        b.surfaceView.setOnTouchListener((v, e) -> { detector.onTouchEvent(e); return true; });
    }

    /** Public playback entry points.
     * Plays one stream. {@code liveHint}: LIVE/VOD if you know it, or null to let the engine detect.
     */
    public void playStream(String url, String title, LiveStatus liveHint) {
        if (player == null) return;
        if (url == null || url.trim().isEmpty()) { showFatal(activity.getString(R.string.mp_no_url)); return; }
        explicitTitle = title;
        mediaTitle = null;
        currentUrl = url;
        LoadOptions.Builder lo = LoadOptions.builder();
        if (liveHint != null) lo.liveHint(liveHint);
        player.load(url, lo.build());
        updateHeader();
    }

    /** Plays a ready-made list of entries starting at {@code startIndex}. */
    public void playEntries(List<PlaylistEntry> entries, int startIndex) {
        if (player == null) return;
        explicitTitle = null;
        player.getPlaylist().setEntries(entries, Math.max(0, Math.min(startIndex, entries.size() - 1)));
    }

    /** Downloads an M3U channel list in the background, then plays {@code startIndex}. */
    public void playPlaylistFromUrl(final String m3uUrl, final int startIndex) {
        if (player == null) return;
        showBusy(true);
        io.execute(() -> {
            try {
                final UriResolver.Text t = UriResolver.readText(activity.getApplicationContext(), m3uUrl, 20_000_000, 15_000);
                handler.post(() -> {
                    if (destroyed || player == null) return;
                    try {
                        M3uParser.Result r = player.getPlaylist().loadM3u(t.text, new M3uParser.Options().baseUri(t.baseUri), false);
                        explicitTitle = null;
                        player.getPlaylist().playIndex(Math.max(0, Math.min(startIndex, r.getEntries().size() - 1)));
                    } catch (RuntimeException e) {
                        showFatal(activity.getString(R.string.mp_playlist_failed, e.getMessage()));
                    }
                });
            } catch (IOException e) {
                handler.post(() -> { if (!destroyed) showFatal(activity.getString(R.string.mp_playlist_failed, e.getMessage())); });
            }
        });
    }


    /** Lifecycle (forwarded by the Activity) */
    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) setupSystemUi();
    }

    public void onStart() {
        if (player == null || !resumeAfterSurface) return;
        SurfaceHolder h = b.surfaceView.getHolder();
        if (h.getSurface() != null && h.getSurface().isValid()) onSurfaceReady();   // else surfaceCreated() will do it
    }

    public void onStop() {
        if (player == null) return;
        PlaybackState s = player.getState();
        if (s == PlaybackState.PLAYING || s == PlaybackState.BUFFERING || s == PlaybackState.LOADING) {
            resumeAfterSurface = true;
            player.pause();
        }
    }

    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (dialog != null) { dialog.setOnDismissListener(null); dialog.dismiss(); dialog = null; }
        io.shutdownNow();
        b.surfaceView.getHolder().removeCallback(surfaceCallback);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    /** Engine events -> UI */
    private final class EngineListener implements MpvPlayerListener {
        @Override public void onStateChanged(PlaybackState newState, PlaybackState oldState) {
            Log.d(TAG, oldState + " -> " + newState);
            renderState(newState);
        }
        @Override public void onMediaLoaded(MediaInfo info) {
            Log.i(TAG, "Loaded: format=" + info.getFileFormat() + " duration=" + info.getDurationSeconds() + " seekable="
                    + info.isSeekable() + " partiallySeekable=" + info.isPartiallySeekable() + " live=" + info.getLiveStatus()
                    + " (" + info.getLiveStatusReason() + ")");
            mediaTitle = info.getTitle();
            updateHeader();
            updateLiveUi();
        }
        @Override public void onLiveStatusChanged(LiveStatus status, String reason) {
            Log.d(TAG, "Live status: " + status + " (" + reason + ")");
            updateLiveUi();
        }
        @Override public void onDurationChanged(double durationSeconds) { updateLiveUi(); }
        @Override public void onSpeedChanged(double speed) {
            Log.d(TAG, "Speed is now " + speed);
            b.tvSpeedLabel.setText(Math.abs(speed - 1.0) < 0.001 ? activity.getString(R.string.player_ctl_speed) : formatSpeed(speed));
        }
        @Override public void onTracksChanged(List<AudioTrackInfo> audio, List<SubtitleTrackInfo> subs) {
            Log.d(TAG, "Audio tracks: " + audio.size() + ", subtitles: " + subs.size());
        }
        @Override public void onVideoInfoChanged(VideoInfo info) { updateHeader(); }
        @Override public void onPlaylistPositionChanged(int position, int count) {
            PlaylistEntry e = player == null ? null : player.getPlaylist().getCurrent();
            if (e != null) currentUrl = e.getUrl();
            mediaTitle = null;
            updateHeader();
            updateNavButtons();
        }
        @Override public void onError(MpvError error) {
            Log.e(TAG, "Playback error: " + error.getMessage() + ", willRetry=" + error.willRetry() + ", log=" + error.getLogLines());
        }
        @Override public void onRetryScheduled(int attempt, int max, long delayMs) {
            Log.w(TAG, "Retry " + attempt + "/" + max + " in " + delayMs + " ms");
        }
        @Override public void onOptionRejected(String name, String value, int code, String reason) {
            Log.w(TAG, "Rejected option " + name + "=" + value + " (" + code + "): " + reason);
        }
    }

    private void renderState(PlaybackState s) {
        state = s;
        boolean busy = s == PlaybackState.LOADING || s == PlaybackState.BUFFERING;
        boolean playingIntent = busy || s == PlaybackState.PLAYING;
        b.bufferingIndicator.setVisibility(busy ? View.VISIBLE : View.GONE);
        b.playPauseBtn.setVisibility(busy ? View.INVISIBLE : View.VISIBLE);       // the spinner takes the icon's place
        b.playPauseBtn.setImageResource(playingIntent ? R.drawable.ic_pause : R.drawable.ic_play);
        b.playPause.setContentDescription(activity.getString(playingIntent ? R.string.cd_pause : R.string.cd_play));
        b.getRoot().setKeepScreenOn(playingIntent);

        if (s == PlaybackState.ERROR) {
            MpvError e = player == null ? null : player.getLastError();
            b.tvErrorMessage.setText(e != null ? e.getMessage() : activity.getString(R.string.error_desc));
            b.btnRetry.setVisibility(View.VISIBLE);
            b.errorPanel.setVisibility(View.VISIBLE);
        } else if (b.errorPanel.getVisibility() == View.VISIBLE && s != PlaybackState.IDLE) {
            b.errorPanel.setVisibility(View.GONE);
        }

        if (s == PlaybackState.PLAYING) restartAutoHide();
        else if (s != PlaybackState.RELEASED) setControlsVisible(true);      // keep controls up unless playing
        updateNavButtons();
        updateLiveUi();
    }

    private void showFatal(String message) {
        b.bufferingIndicator.setVisibility(View.GONE);
        b.tvErrorMessage.setText(message);
        b.btnRetry.setVisibility(View.GONE);
        b.errorPanel.setVisibility(View.VISIBLE);
        setControlsVisible(true);
    }

    private void showBusy(boolean busy) {
        b.bufferingIndicator.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) b.errorPanel.setVisibility(View.GONE);
    }


    /** Header, live/VOD row, navigation buttons */
    private void updateHeader() {
        PlaylistEntry e = player == null ? null : player.getPlaylist().getCurrent();
        String title;
        if (e != null && notEmpty(e.getTitle())) title = e.getTitle();
        else if (notEmpty(explicitTitle)) title = explicitTitle;
        else if (notEmpty(mediaTitle)) title = mediaTitle;
        else title = activity.getString(R.string.mp_untitled);
        b.tvTitle.setText(title);

        StringBuilder sub = new StringBuilder();
        if (e != null && notEmpty(e.getGroup())) sub.append(e.getGroup());
        else if (currentUrl != null) {
            String host = Uri.parse(currentUrl).getHost();
            if (notEmpty(host)) sub.append(host);
        }
        VideoInfo vi = player == null ? null : player.getVideoInfo();
        if (vi != null && vi.getSourceWidth() > 0 && vi.getSourceHeight() > 0) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(vi.getSourceWidth()).append('×').append(vi.getSourceHeight());
        }
        b.tvSubtitle.setText(sub);          // always visible (minLines=1): the header never changes height while loading
        updateFavoriteIcon();
    }

    /**
     * Timeline rule: never for LIVE; otherwise whenever mpv reports a finite duration AND the stream is either seekable
     * or positively classified VOD (a progressive file from a server without range support has a duration but is not
     * seekable: the bar is then shown read-only). UNKNOWN + not seekable (e.g. an HLS stream that may be live) shows
     * the live row instead, so a growing live duration is never presented as a timeline.
     */
    private boolean isVodUi() {
        if (player == null) return false;
        double d = player.getDurationSeconds();
        if (Double.isNaN(d) || d <= 0) return false;
        LiveStatus ls = player.getLiveStatus();
        return ls != LiveStatus.LIVE && (player.isSeekable() || ls == LiveStatus.VOD);
    }

    /** LIVE badge + live row for live/unknown streams, timeline for seekable VOD. */
    private void updateLiveUi() {
        if (player == null) return;
        boolean live = player.getLiveStatus() == LiveStatus.LIVE;
        boolean vod = isVodUi();
        b.tvLiveBadge.setVisibility(live ? View.VISIBLE : View.GONE);
        b.vodRow.setVisibility(vod ? View.VISIBLE : View.GONE);
        b.liveRow.setVisibility(vod ? View.GONE : View.VISIBLE);
        b.liveDot.setVisibility(live ? View.VISIBLE : View.GONE);
        b.tvLiveLabel.setText(live ? R.string.badge_live : R.string.mp_stream_label);
        b.tvLiveLabel.setTextColor(ContextCompat.getColor(activity, live ? R.color.white_alpha_75 : R.color.live));
        b.btnSpeed.setAlpha(live ? DISABLED_ALPHA : 1f);
        boolean canSeek = player.isSeekable();
        b.videoProgress.setEnabled(canSeek);
        b.videoProgress.setAlpha(canSeek ? 1f : 0.5f);
        updateProgressUi();
    }

    private void updateProgressUi() {
        if (player == null || !controlsVisible) return;
        if (isVodUi()) {
            double dur = player.getDurationSeconds();
            b.tvDuration.setText(formatTime(dur));
            if (!userSeeking) {
                double pos = player.getPositionSeconds();
                if (!Double.isNaN(pos)) {
                    b.tvPosition.setText(formatTime(pos));
                    b.videoProgress.setProgress((int) Math.max(0, Math.min(1000, pos / dur * 1000)));
                }
            }
        } else {
            BufferingInfo bi = player.getBufferingInfo();
            double ahead = bi.getBufferedAheadSeconds();
            b.tvBufferInfo.setText(Double.isNaN(ahead) ? "" : activity.getString(R.string.mp_buffer_fmt, Math.round(ahead)));
        }
    }

    private void updateNavButtons() {
        boolean hasList = player != null && player.getPlaylist().size() > 1;
        boolean prev = hasList && player.getPlaylist().hasPrevious();
        boolean next = hasList && player.getPlaylist().hasNext();
        b.btnPrev.setAlpha(prev ? 1f : DISABLED_ALPHA);
        b.btnNext.setAlpha(next ? 1f : DISABLED_ALPHA);
        b.btnChannels.setAlpha(hasList ? 1f : DISABLED_ALPHA);
    }


    /** Controls visibility */
    private final Runnable hideRunnable = () -> { if (shouldAutoHide()) setControlsVisible(false); };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!controlsVisible || destroyed) return;
            updateLiveUi();                                  // also re-evaluates timeline vs live row
            handler.postDelayed(this, TICK_MS);
        }
    };

    private boolean shouldAutoHide() {
        return state == PlaybackState.PLAYING && !userSeeking && dialog == null;
    }

    private void restartAutoHide() {
        handler.removeCallbacks(hideRunnable);
        if (controlsVisible && shouldAutoHide()) handler.postDelayed(hideRunnable, AUTO_HIDE_MS);
    }

    private void toggleControls() {
        setControlsVisible(!controlsVisible);
    }

    private void setControlsVisible(boolean visible) {
        handler.removeCallbacks(hideRunnable);
        handler.removeCallbacks(ticker);
        View c = b.playerControllers;
        c.animate().cancel();
        controlsVisible = visible;
        if (visible) {
            c.setVisibility(View.VISIBLE);
            c.animate().alpha(1f).setDuration(150).start();
            updateProgressUi();
            handler.postDelayed(ticker, TICK_MS);
            restartAutoHide();
        } else {
            c.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                if (!controlsVisible) c.setVisibility(View.GONE);
            }).start();
        }
    }

    /** Button actions */
    private void onPlayPauseClicked() {
        if (player == null) return;
        PlaybackState s = player.getState();
        if (s == PlaybackState.PLAYING || s == PlaybackState.BUFFERING || s == PlaybackState.LOADING) player.pause();
        else player.play();                         // PAUSED, ENDED (restarts), ERROR/IDLE (reopens)
        restartAutoHide();
    }

    private void cycleAspect() {
        if (player == null) return;
        aspectMode = AspectMode.values()[(aspectMode.ordinal() + 1) % AspectMode.values().length];
        switch (aspectMode) {
            case FIT:
                player.setVideoScaleMode(VideoScaleMode.FIT);
                player.resetAspectRatio();
                break;
            case RATIO_16_9:
                player.setVideoScaleMode(VideoScaleMode.FIT);
                player.setAspectRatio("16:9");
                break;
            case RATIO_4_3:
                player.setVideoScaleMode(VideoScaleMode.FIT);
                player.setAspectRatio("4:3");
                break;
            case FILL:
                player.setVideoScaleMode(VideoScaleMode.FILL);
                player.resetAspectRatio();
                break;
            case STRETCH:
                player.setVideoScaleMode(VideoScaleMode.STRETCH);
                player.resetAspectRatio();
                break;
        }
        b.tvAspectLabel.setText(aspectMode.labelRes);
        restartAutoHide();
    }

    private void toggleOrientation() {
        boolean landscape = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        activity.setRequestedOrientation(landscape
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    /** ---- favorites (kept locally in SharedPreferences, keyed by stream URL) */
    private boolean isFavorite() {
        return currentUrl != null && prefs.getStringSet(KEY_FAVORITES, new HashSet<String>()).contains(currentUrl);
    }

    private void toggleFavorite() {
        if (currentUrl == null) return;
        Set<String> copy = new HashSet<>(prefs.getStringSet(KEY_FAVORITES, new HashSet<String>()));   // never edit the returned set
        if (!copy.remove(currentUrl)) copy.add(currentUrl);
        prefs.edit().putStringSet(KEY_FAVORITES, copy).apply();
        updateFavoriteIcon();
        restartAutoHide();
    }

    private void updateFavoriteIcon() {
        boolean fav = isFavorite();
        b.btnFavorite.setImageResource(fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart_outline);
        ImageViewCompat.setImageTintList(b.btnFavorite,
                ColorStateList.valueOf(ContextCompat.getColor(activity, fav ? R.color.accent : R.color.white)));
    }

    /** ---- dialogs */
    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(activity, R.style.PlayerDialogTheme);
    }

    private void showDialog(AlertDialog.Builder builder) {
        handler.removeCallbacks(hideRunnable);
        builder.setOnDismissListener(d -> { dialog = null; restartAutoHide(); });
        dialog = builder.show();
        styleDialog(dialog);
    }

    /** Black window, accent title and accent buttons (theme-independent: done on the created views). */
    private void styleDialog(AlertDialog d) {
        int accent = ContextCompat.getColor(activity, R.color.accent);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog_black);
        TextView title = d.findViewById(androidx.appcompat.R.id.alertTitle);
        if (title != null) { title.setTextColor(accent); title.setTypeface(null, Typeface.BOLD); }
        int[] which = {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL};
        for (int w : which) {
            Button btn = d.getButton(w);
            if (btn != null) btn.setTextColor(accent);
        }
        TextView message = d.findViewById(android.R.id.message);
        if (message != null) message.setTextColor(ContextCompat.getColor(activity, R.color.white));
    }

    private void showAudioDialog() {
        if (player == null) return;
        final List<AudioTrackInfo> tracks = player.getAudioTracks();
        if (tracks.isEmpty()) { toast(R.string.mp_no_audio); return; }
        CharSequence[] labels = new CharSequence[tracks.size()];
        int checked = -1;
        for (int i = 0; i < tracks.size(); i++) {
            AudioTrackInfo t = tracks.get(i);
            StringBuilder sb = new StringBuilder(trackName(t.getTitle(), t.getLanguage(), t.getId()));
            if (notEmpty(t.getCodec())) sb.append(" · ").append(t.getCodec().toUpperCase(Locale.ROOT));
            if (t.getChannels() > 0) sb.append(" · ").append(t.getChannels()).append("ch");
            labels[i] = sb;
            if (t.isSelected()) checked = i;
        }
        showDialog(dialogBuilder().setTitle(R.string.mp_dlg_audio)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    player.selectAudioTrack(tracks.get(which).getId());
                    d.dismiss();
                }).setNegativeButton(R.string.mp_cancel, null));
    }

    private void showSubtitleDialog() {
        if (player == null) return;
        final List<SubtitleTrackInfo> tracks = player.getSubtitleTracks();
        final int n = tracks.size();
        CharSequence[] labels = new CharSequence[n + 3];
        labels[0] = activity.getString(R.string.mp_off);
        int checked = 0;
        for (int i = 0; i < n; i++) {
            SubtitleTrackInfo t = tracks.get(i);
            labels[i + 1] = trackName(t.getTitle(), t.getLanguage(), t.getId());
            if (t.isSelected()) checked = i + 1;
        }
        labels[n + 1] = activity.getString(R.string.mp_sub_size_item);
        labels[n + 2] = activity.getString(R.string.mp_add_subtitle_file);
        showDialog(dialogBuilder().setTitle(R.string.mp_dlg_subtitles)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    if (which == 0) { userSubtitleId = null; player.disableSubtitles(); }
                    else if (which == n + 1) handler.post(this::showSubtitleSizeDialog);
                    else if (which == n + 2) subtitlePicker.launch(new String[]{"*/*"});
                    else { userSubtitleId = tracks.get(which - 1).getId(); player.selectSubtitleTrack(userSubtitleId); }
                }).setNegativeButton(R.string.mp_cancel, null));
    }

    private void showSubtitleSizeDialog() {
        if (player == null) return;
        CharSequence[] labels = new CharSequence[SUB_SIZES.length];
        int checked = 1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = activity.getString(SUB_SIZE_LABELS[i]);
            if (Math.abs(SUB_SIZES[i] - subtitleSize) < 0.5) checked = i;
        }
        showDialog(dialogBuilder().setTitle(R.string.mp_dlg_sub_size)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    subtitleSize = SUB_SIZES[which];
                    prefs.edit().putFloat(KEY_SUB_SIZE, (float) subtitleSize).apply();
                    player.setSubtitleSize(subtitleSize);
                    d.dismiss();
                }).setNegativeButton(R.string.mp_cancel, null));
    }

    /**
     * After the video output was re-created (rotation / fullscreen / returning to the app) put the subtitle state back:
     * same track selected, visible, same size. Idempotent when nothing was lost.
     */
    private void scheduleSubtitleReapply() {
        handler.postDelayed(() -> {
            if (destroyed || player == null) return;
            Integer id = null;
            for (SubtitleTrackInfo t : player.getSubtitleTracks()) if (t.isSelected()) id = t.getId();
            if (id == null) id = userSubtitleId;
            if (id != null) player.selectSubtitleTrack(id);          // also sets sub-visibility=yes
            player.setSubtitleSize(subtitleSize);
        }, 400);
    }

    /** Forwarded by the Activity (needs android:configChanges on the Activity, see the manifest note). */
    public void onConfigurationChanged(Configuration newConfig) {
        ViewCompat.requestApplyInsets(b.getRoot());
        scheduleSubtitleReapply();
    }

    private void onSubtitlePicked(Uri uri) {
        if (uri == null || player == null) return;
        try {
            activity.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) { /* provider does not offer persistable grants: fine for this session */ }
        player.loadExternalSubtitle(uri.toString());
        handler.postDelayed(() -> {            // the engine selects the new track; remember it for re-apply after rotation
            if (player == null) return;
            for (SubtitleTrackInfo t : player.getSubtitleTracks()) if (t.isSelected()) userSubtitleId = t.getId();
        }, 600);
    }

    private void showSpeedDialog() {
        if (player == null) return;
        if (player.isLive()) {
            Toast.makeText(activity, activity.getString(R.string.mp_speed_live) + "\n(" + player.getLiveStatusReason() + ")", Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence[] labels = new CharSequence[SPEEDS.length];
        int checked = 0;
        double current = player.getSpeed();
        for (int i = 0; i < SPEEDS.length; i++) {
            labels[i] = formatSpeed(SPEEDS[i]);
            if (Math.abs(SPEEDS[i] - current) < 0.01) checked = i;
        }
        showDialog(dialogBuilder().setTitle(R.string.mp_dlg_speed)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    player.setSpeed(SPEEDS[which]);          // the label is updated by onSpeedChanged() with mpv's real value
                    d.dismiss();
                }).setNegativeButton(R.string.mp_cancel, null));
    }

    private void showChannelsDialog() {
        if (player == null) return;
        final List<PlaylistEntry> entries = player.getPlaylist().getEntries();
        if (entries.isEmpty()) { toast(R.string.mp_no_channels); return; }
        CharSequence[] labels = new CharSequence[entries.size()];
        for (int i = 0; i < labels.length; i++) {
            PlaylistEntry e = entries.get(i);
            labels[i] = notEmpty(e.getTitle()) ? e.getTitle() : e.getUrl();
        }
        showDialog(dialogBuilder().setTitle(R.string.mp_dlg_channels)
                .setSingleChoiceItems(labels, player.getPlaylist().getPosition(), (d, which) -> {
                    player.getPlaylist().playIndex(which);
                    d.dismiss();
                }).setNegativeButton(R.string.mp_cancel, null));
    }

    /** ---- overflow menu */
    private void showMoreMenu() {
        if (player == null) return;
        PopupMenu menu = new PopupMenu(new ContextThemeWrapper(activity, R.style.PlayerPopupMenuTheme), b.btnMore);
        menu.getMenu().add(0, 1, 0, R.string.mp_menu_info);
        menu.getMenu().add(0, 2, 1, R.string.mp_menu_screenshot);
        menu.getMenu().add(0, 3, 2, R.string.mp_menu_reload);
        if (player.getLiveStatus() != LiveStatus.VOD) menu.getMenu().add(0, 4, 3, R.string.mp_menu_jump_live);
        menu.getMenu().add(0, 5, 4, R.string.mp_menu_copy_url);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showStreamInfo(); return true;
                case 2: takeScreenshot(); return true;
                case 3: player.reopen(); return true;
                case 4: player.jumpToLive(); return true;
                case 5: copyUrl(); return true;
                default: return false;
            }
        });
        handler.removeCallbacks(hideRunnable);
        menu.setOnDismissListener(m -> restartAutoHide());
        menu.show();
    }

    private void showStreamInfo() {
        StringBuilder s = new StringBuilder();
        s.append("mpv: ").append(player.getMpvVersion()).append('\n').append("FFmpeg: ").append(player.getFfmpegVersion()).append("\n\n");
        s.append("State: ").append(player.getState()).append('\n');
        s.append("Live: ").append(player.getLiveStatus()).append(" (").append(player.getLiveStatusReason()).append(")\n");
        s.append("Duration: ").append(player.getDurationSeconds()).append("   Speed: ").append(player.getSpeed()).append('\n');
        s.append("Seekable: ").append(player.isSeekable()).append("   Network: ").append(player.isNetworkStream()).append('\n');
        MediaInfo mi = player.getMediaInfo();
        if (mi != null) s.append("Format: ").append(mi.getFileFormat()).append("   Video tracks: ").append(mi.getVideoTracks().size())
                .append("   Audio: ").append(mi.getAudioTracks().size()).append("   Subs: ").append(mi.getSubtitleTracks().size()).append('\n');
        VideoInfo vi = player.getVideoInfo();
        if (vi != null) s.append("Video: ").append(vi.getSourceWidth()).append('×').append(vi.getSourceHeight())
                .append("   aspect ").append(String.format(Locale.ROOT, "%.3f", vi.getSourceAspect())).append('\n');
        PlaybackStats st = player.getStats();
        if (st != null) s.append("Decoder: ").append(st.hardwareDecoder).append("   fps ").append(String.format(Locale.ROOT, "%.1f", st.outputFps))
                .append("   dropped ").append(st.voDroppedFrames).append('/').append(st.decoderDroppedFrames).append('\n');
        s.append("Subtitles: visible=").append(player.areSubtitlesVisible()).append(" size=").append(subtitleSize);
        for (SubtitleTrackInfo t : player.getSubtitleTracks()) if (t.isSelected()) s.append(" selected=#").append(t.getId());
        s.append('\n');
        BufferingInfo bi = player.getBufferingInfo();
        s.append("Buffer: ").append(bi).append('\n');
        if (!player.getOptionReport().isEmpty()) s.append("\nRejected options: ").append(player.getOptionReport()).append('\n');
        if (currentUrl != null) s.append("\nURL: ").append(currentUrl);
        showDialog(dialogBuilder().setTitle(R.string.mp_menu_info).setMessage(s).setPositiveButton(R.string.mp_close, null));
    }

    private void takeScreenshot() {
        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (dir == null) dir = activity.getCacheDir();
        final File file = new File(dir, "mpv_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg");
        player.takeScreenshot(file.getAbsolutePath(), false);
        // mpv reports no completion through this API: check the file shortly afterwards
        handler.postDelayed(() -> {
            if (destroyed) return;
            if (file.exists() && file.length() > 0) toast(R.string.mp_screenshot_saved, file.getName());
            else toast(R.string.mp_screenshot_failed);
        }, 800);
    }

    private void copyUrl() {
        if (currentUrl == null) return;
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("stream", currentUrl));
            toast(R.string.mp_url_copied);
        }
    }


    /**  Helpers functions */
    private String trackName(String title, String lang, int id) {
        if (notEmpty(title) && notEmpty(lang)) return title + " (" + lang + ")";
        if (notEmpty(title)) return title;
        if (notEmpty(lang)) return lang;
        return activity.getString(R.string.mp_track_fmt, id);
    }

    private static String formatSpeed(double s) {
        return (s == Math.rint(s) ? String.valueOf((int) s) : String.valueOf(s)) + "x";
    }

    private static String formatTime(double seconds) {
        if (Double.isNaN(seconds) || seconds < 0) seconds = 0;
        long t = (long) seconds;
        long h = t / 3600, m = (t % 3600) / 60, sec = t % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }

    private void toast(int res, Object... args) {
        Toast.makeText(activity, activity.getString(res, args), Toast.LENGTH_SHORT).show();
    }
}