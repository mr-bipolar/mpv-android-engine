package com.maxplayer.mpv;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of what mpv currently knows about the loaded media. Values mpv cannot supply are
 * {@code null} / {@link Double#NaN} / -1 — never guessed.
 */
public final class MediaInfo {
    private final String url, title, fileFormat;
    private final boolean networkStream;
    private final double durationSeconds;
    private final boolean seekable, partiallySeekable;
    private final LiveStatus liveStatus; private final String liveStatusReason;
    private final Map<String, String> metadata;
    private final List<VideoTrackInfo> videoTracks; private final List<AudioTrackInfo> audioTracks;
    private final List<SubtitleTrackInfo> subtitleTracks;
    private final String hardwareDecoder;

    MediaInfo(String url, String title, String fileFormat, boolean networkStream, double durationSeconds,
              boolean seekable, boolean partiallySeekable, LiveStatus liveStatus, String liveStatusReason,
              Map<String, String> metadata, List<VideoTrackInfo> v, List<AudioTrackInfo> a,
              List<SubtitleTrackInfo> s, String hardwareDecoder) {
        this.url = url; this.title = title; this.fileFormat = fileFormat; this.networkStream = networkStream;
        this.durationSeconds = durationSeconds; this.seekable = seekable; this.partiallySeekable = partiallySeekable;
        this.liveStatus = liveStatus; this.liveStatusReason = liveStatusReason;
        this.metadata = Collections.unmodifiableMap(metadata);
        this.videoTracks = Collections.unmodifiableList(v); this.audioTracks = Collections.unmodifiableList(a);
        this.subtitleTracks = Collections.unmodifiableList(s); this.hardwareDecoder = hardwareDecoder;
    }
    /** The path/URL mpv was asked to open (mpv {@code path}). */
    public String getUrl() { return url; }
    /** mpv {@code media-title}: container title, or the file name / URL when none. */
    public String getTitle() { return title; }
    /** libavformat demuxer name(s), e.g. "hls", "mpegts", "mov,mp4,m4a,3gp,3g2,mj2". */
    public String getFileFormat() { return fileFormat; }
    /** mpv's own heuristic ({@code demuxer-via-network}); documented by mpv as imperfect. */
    public boolean isNetworkStream() { return networkStream; }
    /**
     * Duration in seconds, or NaN if mpv reports none. <b>For live HLS mpv may report a growing
     * duration</b> (the length of the playlist seen so far), so a non-NaN value does not imply VOD.
     */
    public double getDurationSeconds() { return durationSeconds; }
    public boolean isSeekable() { return seekable; }
    public boolean isPartiallySeekable() { return partiallySeekable; }
    public LiveStatus getLiveStatus() { return liveStatus; }
    /** Human-readable justification of {@link #getLiveStatus()} for logs/diagnostics. */
    public String getLiveStatusReason() { return liveStatusReason; }
    public Map<String, String> getMetadata() { return metadata; }
    public List<VideoTrackInfo> getVideoTracks() { return videoTracks; }
    public List<AudioTrackInfo> getAudioTracks() { return audioTracks; }
    public List<SubtitleTrackInfo> getSubtitleTracks() { return subtitleTracks; }
    /** Active hardware decoder name ({@code hwdec-current}), "no" for software, null if unknown. */
    public String getHardwareDecoder() { return hardwareDecoder; }
    /** True if the file has a real (non album-art) video track. */
    public boolean hasVideo() {
        for (VideoTrackInfo t : videoTracks) if (!t.isAlbumArt()) return true;
        return false;
    }
    public boolean hasAudio() { return !audioTracks.isEmpty(); }
    @Override public String toString() {
        return "MediaInfo{title=" + title + ", format=" + fileFormat + ", dur=" + durationSeconds
                + ", seekable=" + seekable + ", live=" + liveStatus + ", video=" + videoTracks.size()
                + ", audio=" + audioTracks.size() + ", subs=" + subtitleTracks.size() + "}";
    }
}
