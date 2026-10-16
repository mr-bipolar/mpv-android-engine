package com.maxplayer.mpv;

import java.util.ArrayList;
import java.util.List;

/** Reads mpv's {@code track-list/N/*} properties into immutable model objects (same approach as mpv-android). */
final class TrackReader {
    final List<VideoTrackInfo> video = new ArrayList<>();
    final List<AudioTrackInfo> audio = new ArrayList<>();
    final List<SubtitleTrackInfo> subs = new ArrayList<>();

    static TrackReader read(MpvBackend b) {
        TrackReader r = new TrackReader();
        Integer count = b.getPropertyInt(Mpv.TRACK_LIST_COUNT);
        if (count == null) return r;
        for (int i = 0; i < count; i++) {
            String p = "track-list/" + i + "/";
            String type = b.getPropertyString(p + "type");
            Integer id = b.getPropertyInt(p + "id");
            if (type == null || id == null) continue;
            String title = b.getPropertyString(p + "title");
            String lang = b.getPropertyString(p + "lang");
            String codec = b.getPropertyString(p + "codec");
            boolean sel = bool(b, p + "selected"), def = bool(b, p + "default"), forced = bool(b, p + "forced"),
                    ext = bool(b, p + "external");
            switch (type) {
                case "audio":
                    r.audio.add(new AudioTrackInfo(id, title, lang, codec, intOr(b, p + "demux-channel-count"),
                            intOr(b, p + "demux-samplerate"), sel, def, ext, forced));
                    break;
                case "sub":
                    r.subs.add(new SubtitleTrackInfo(id, title, lang, codec, ext ? b.getPropertyString(p + "external-filename") : null,
                            sel, def, ext, forced, bool(b, p + "hearing-impaired")));
                    break;
                case "video":
                    Double fps = b.getPropertyDouble(p + "demux-fps");
                    r.video.add(new VideoTrackInfo(id, title, codec, intOr(b, p + "demux-w"), intOr(b, p + "demux-h"),
                            fps == null ? Double.NaN : fps, sel, bool(b, p + "albumart")));
                    break;
                default: break;
            }
        }
        return r;
    }

    private static boolean bool(MpvBackend b, String n) { Boolean v = b.getPropertyBoolean(n); return v != null && v; }
    private static int intOr(MpvBackend b, String n) { Integer v = b.getPropertyInt(n); return v == null ? -1 : v; }
}
