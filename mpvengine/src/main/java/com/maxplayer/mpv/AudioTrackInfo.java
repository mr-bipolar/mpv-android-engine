package com.maxplayer.mpv;

/** Immutable audio track description. Unknown numeric values are -1, unknown strings are null. */
public final class AudioTrackInfo {
    private final int id; private final String title, language, codec;
    private final int channels, sampleRate;
    private final boolean selected, isDefault, external, forced;

    public AudioTrackInfo(int id, String title, String language, String codec, int channels, int sampleRate,
                          boolean selected, boolean isDefault, boolean external, boolean forced) {
        this.id = id; this.title = title; this.language = language; this.codec = codec;
        this.channels = channels; this.sampleRate = sampleRate; this.selected = selected;
        this.isDefault = isDefault; this.external = external; this.forced = forced;
    }
    /** mpv track id as used by {@code aid}; unique among audio tracks only. */
    public int getId() { return id; }
    public String getTitle() { return title; }
    /** Language code as stored in the file (often ISO 639-2, e.g. "eng"); may be null. */
    public String getLanguage() { return language; }
    public String getCodec() { return codec; }
    public int getChannels() { return channels; }
    public int getSampleRate() { return sampleRate; }
    public boolean isSelected() { return selected; }
    public boolean isDefault() { return isDefault; }
    public boolean isExternal() { return external; }
    public boolean isForced() { return forced; }
    @Override public String toString() {
        return "AudioTrack{id=" + id + ", lang=" + language + ", title=" + title + ", codec=" + codec
                + ", ch=" + channels + (selected ? ", selected" : "") + "}";
    }
}
