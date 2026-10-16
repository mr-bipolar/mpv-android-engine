package com.maxplayer.mpv;

/** Immutable subtitle track description. Unknown strings are null. */
public final class SubtitleTrackInfo {
    private final int id; private final String title, language, codec, externalFilename;
    private final boolean selected, isDefault, external, forced, hearingImpaired;

    public SubtitleTrackInfo(int id, String title, String language, String codec, String externalFilename,
                             boolean selected, boolean isDefault, boolean external, boolean forced,
                             boolean hearingImpaired) {
        this.id = id; this.title = title; this.language = language; this.codec = codec;
        this.externalFilename = externalFilename; this.selected = selected; this.isDefault = isDefault;
        this.external = external; this.forced = forced; this.hearingImpaired = hearingImpaired;
    }
    /** mpv track id as used by {@code sid}; unique among subtitle tracks only. */
    public int getId() { return id; }
    public String getTitle() { return title; }
    public String getLanguage() { return language; }
    public String getCodec() { return codec; }
    public String getExternalFilename() { return externalFilename; }
    public boolean isSelected() { return selected; }
    public boolean isDefault() { return isDefault; }
    public boolean isExternal() { return external; }
    public boolean isForced() { return forced; }
    public boolean isHearingImpaired() { return hearingImpaired; }
    @Override public String toString() {
        return "SubtitleTrack{id=" + id + ", lang=" + language + ", title=" + title + ", codec=" + codec
                + (external ? ", external" : "") + (selected ? ", selected" : "") + "}";
    }
}
