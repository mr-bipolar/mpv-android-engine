package com.maxplayer.mpv;

/** Immutable video track description (container-reported values; not always exact). */
public final class VideoTrackInfo {
    private final int id, width, height; private final double fps; private final String codec, title;
    private final boolean selected, albumArt;
    public VideoTrackInfo(int id, String title, String codec, int width, int height, double fps,
                          boolean selected, boolean albumArt) {
        this.id = id; this.title = title; this.codec = codec; this.width = width; this.height = height;
        this.fps = fps; this.selected = selected; this.albumArt = albumArt;
    }
    public int getId() { return id; }
    public String getTitle() { return title; }
    public String getCodec() { return codec; }
    /** Container-reported width, -1 if unknown. */
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    /** Container-reported fps, NaN if unknown. */
    public double getFps() { return fps; }
    public boolean isSelected() { return selected; }
    /** True for cover art embedded in audio files (not real video). */
    public boolean isAlbumArt() { return albumArt; }
    @Override public String toString() {
        return "VideoTrack{id=" + id + ", codec=" + codec + ", " + width + "x" + height + ", fps=" + fps + "}";
    }
}
