package com.maxplayer.mpv;

import java.util.List;

/**
 * Callbacks from {@link MpvPlayer}. All methods have empty defaults so implement only what you need.
 * They are delivered on the executor given to the player (main thread by default) and are safe to
 * register/unregister from any thread.
 */
public interface MpvPlayerListener {
    default void onStateChanged(PlaybackState newState, PlaybackState oldState) {}
    /** Whole-second position changes (mpv {@code time-pos}); use {@code getPositionSeconds()} for precise values. */
    default void onPositionChanged(double positionSeconds) {}
    default void onDurationChanged(double durationSeconds) {}
    default void onMediaLoaded(MediaInfo info) {}
    /** Live/VOD classification changed (it can be refined a few seconds after loading). */
    default void onLiveStatusChanged(LiveStatus status, String reason) {}
    default void onTracksChanged(List<AudioTrackInfo> audio, List<SubtitleTrackInfo> subtitles) {}
    default void onBufferingChanged(boolean stalled, int percent) {}
    default void onVideoInfoChanged(VideoInfo info) {}
    default void onPlaylistPositionChanged(int position, int count) {}
    default void onVolumeChanged(int volume, boolean muted) {}
    default void onSpeedChanged(double speed) {}
    default void onSeekCompleted(double positionSeconds) {}
    /** The current entry finished playing ({@link EndReason#EOF}); mpv advances automatically if more entries remain. */
    default void onPlaybackCompleted() {}
    /** End of an entry for any reason. {@code error} is non-null only for {@link EndReason#ERROR}. */
    default void onEntryEnded(EndReason reason, MpvError error) {}
    default void onError(MpvError error) {}
    /** An automatic retry was scheduled. */
    default void onRetryScheduled(int attempt, int maxAttempts, long delayMs) {}
    /** mpv rejected a configuration option (unsupported by this build, or invalid value). */
    default void onOptionRejected(String name, String value, int mpvErrorCode, String reason) {}
    /** Engine-level log line (warnings and errors from mpv/FFmpeg), already filtered by msg-level. */
    default void onLog(int level, String prefix, String text) {}
}
