package com.maxplayer.mpv;

/**
 * Names and numeric constants of the mpv/libmpv API used by this engine. Every property, option and
 * command listed here was checked against (a) mpv's master documentation (DOCS/man) and (b) a real
 * libmpv 0.37 on desktop; see docs/VERIFICATION.md and verify/names.txt.
 */
final class Mpv {
    private Mpv() {}

    /** mpv_format values (client.h). Only these formats can be observed through the JNI layer. */
    static final class Format {
        static final int NONE = 0, STRING = 1, FLAG = 3, INT64 = 4, DOUBLE = 5;
        private Format() {}
    }

    /** mpv_event_id values (client.h), as exposed by mpv-android's MPVLib. */
    static final class Event {
        static final int SHUTDOWN = 1, START_FILE = 6, END_FILE = 7, FILE_LOADED = 8,
                VIDEO_RECONFIG = 17, AUDIO_RECONFIG = 18, SEEK = 20, PLAYBACK_RESTART = 21,
                QUEUE_OVERFLOW = 24;
        private Event() {}
    }

    /** mpv_end_file_reason (client.h). */
    static final class EndFileReason {
        static final int EOF = 0, STOP = 2, QUIT = 3, ERROR = 4, REDIRECT = 5;
        private EndFileReason() {}
    }

    /** mpv_error codes (client.h); confirmed with mpv_error_string() on libmpv 0.37. */
    static final class Err {
        static final int SUCCESS = 0, INVALID_PARAMETER = -4, OPTION_NOT_FOUND = -5,
                OPTION_FORMAT = -6, OPTION_ERROR = -7, PROPERTY_NOT_FOUND = -8,
                PROPERTY_FORMAT = -9, PROPERTY_UNAVAILABLE = -10, PROPERTY_ERROR = -11,
                COMMAND = -12, LOADING_FAILED = -13;
        static String describe(int code) {
            switch (code) {
                case SUCCESS: return "success";
                case INVALID_PARAMETER: return "invalid parameter";
                case OPTION_NOT_FOUND: return "option not found (not supported by this mpv build)";
                case OPTION_FORMAT: return "value has unsupported format or is out of the option's range";
                case OPTION_ERROR: return "error setting option (invalid value)";
                case PROPERTY_NOT_FOUND: return "property not found";
                case PROPERTY_UNAVAILABLE: return "property unavailable";
                default: return "mpv error " + code;
            }
        }
    }

    /** mpv_log_level (client.h). */
    static final class Log {
        static final int FATAL = 10, ERROR = 20, WARN = 30, INFO = 40, V = 50;
        private Log() {}
    }

    // ---- properties ----
    static final String PAUSE = "pause", TIME_POS = "time-pos", DURATION = "duration",
            PERCENT_POS = "percent-pos", EOF_REACHED = "eof-reached", IDLE_ACTIVE = "idle-active",
            CORE_IDLE = "core-idle", SEEKABLE = "seekable", PARTIALLY_SEEKABLE = "partially-seekable",
            PAUSED_FOR_CACHE = "paused-for-cache", SEEKING = "seeking",
            CACHE_BUFFERING_STATE = "cache-buffering-state",
            DEMUXER_CACHE_TIME = "demuxer-cache-time", DEMUXER_CACHE_DURATION = "demuxer-cache-duration",
            DEMUXER_CACHE_STATE = "demuxer-cache-state", DEMUXER_VIA_NETWORK = "demuxer-via-network",
            FILE_FORMAT = "file-format", PATH = "path", STREAM_OPEN_FILENAME = "stream-open-filename",
            MEDIA_TITLE = "media-title", METADATA = "metadata", VOLUME = "volume", MUTE = "mute",
            SPEED = "speed", AUDIO_DELAY = "audio-delay", SUB_DELAY = "sub-delay",
            SUB_VISIBILITY = "sub-visibility", SID = "sid", AID = "aid", VID = "vid",
            TRACK_LIST = "track-list", TRACK_LIST_COUNT = "track-list/count",
            PLAYLIST_POS = "playlist-pos", PLAYLIST_COUNT = "playlist-count",
            VIDEO_PARAMS_ASPECT = "video-params/aspect", VIDEO_PARAMS_W = "video-params/w",
            VIDEO_PARAMS_H = "video-params/h", VIDEO_PARAMS_ROTATE = "video-params/rotate",
            DWIDTH = "dwidth", DHEIGHT = "dheight", ESTIMATED_VF_FPS = "estimated-vf-fps",
            CONTAINER_FPS = "container-fps", FRAME_DROP_COUNT = "frame-drop-count",
            DECODER_FRAME_DROP_COUNT = "decoder-frame-drop-count", AVSYNC = "avsync",
            HWDEC_CURRENT = "hwdec-current", VIDEO_BITRATE = "video-bitrate",
            AUDIO_BITRATE = "audio-bitrate", MPV_VERSION = "mpv-version",
            FFMPEG_VERSION = "ffmpeg-version", ANDROID_SURFACE_SIZE = "android-surface-size",
            CACHE_SPEED = "cache-speed";

    // ---- options (settable with setOptionString before/after init where mpv allows) ----
    static final String O_CACHE = "cache", O_CACHE_SECS = "cache-secs", O_CACHE_PAUSE = "cache-pause",
            O_CACHE_PAUSE_INITIAL = "cache-pause-initial", O_CACHE_PAUSE_WAIT = "cache-pause-wait",
            O_DEMUXER_MAX_BYTES = "demuxer-max-bytes", O_DEMUXER_MAX_BACK_BYTES = "demuxer-max-back-bytes",
            O_DEMUXER_READAHEAD_SECS = "demuxer-readahead-secs", O_NETWORK_TIMEOUT = "network-timeout",
            O_STREAM_LAVF_O = "stream-lavf-o", O_DEMUXER_LAVF_O = "demuxer-lavf-o",
            O_HLS_BITRATE = "hls-bitrate", O_PROFILE = "profile", O_USER_AGENT = "user-agent",
            O_HTTP_HEADER_FIELDS = "http-header-fields", O_REFERRER = "referrer", O_TLS_VERIFY = "tls-verify",
            O_IDLE = "idle", O_KEEP_OPEN = "keep-open", O_FORCE_WINDOW = "force-window",
            O_HWDEC = "hwdec", O_VO = "vo", O_AO = "ao", O_GPU_CONTEXT = "gpu-context",
            O_SUB_FONT_SIZE = "sub-font-size", O_SUB_POS = "sub-pos", O_SUB_SCALE = "sub-scale",
            O_KEEPASPECT = "keepaspect", O_PANSCAN = "panscan", O_ASPECT_OVERRIDE = "video-aspect-override",
            O_VIDEO_ZOOM = "video-zoom", O_VIDEO_PAN_X = "video-pan-x", O_VIDEO_PAN_Y = "video-pan-y",
            O_VIDEO_ROTATE = "video-rotate", O_VIDEO_CROP = "video-crop", O_DEINTERLACE = "deinterlace",
            O_SCREENSHOT_DIR = "screenshot-dir", O_SCREENSHOT_FORMAT = "screenshot-format",
            O_MSG_LEVEL = "msg-level", O_LOOP_FILE = "loop-file", O_LOOP_PLAYLIST = "loop-playlist",
            O_VIDEO_SYNC = "video-sync", O_SUB_AUTO = "sub-auto", O_ALANG = "alang", O_SLANG = "slang",
            O_ANDROID_SURFACE_SIZE = "android-surface-size", O_AUDIO_PITCH_CORRECTION = "audio-pitch-correction",
            O_VOLUME_MAX = "volume-max", O_YTDL = "ytdl";

    /** Escapes a value for use inside an mpv key=value,key=value list using the %len%value form. */
    static String kvValue(String value) {
        return "%" + value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + "%" + value;
    }

    /** Parses "mpv 0.37.0", "mpv v0.40.0-123-gabc" etc. into {major, minor}; {-1,-1} if unknown. */
    static int[] parseVersion(String s) {
        if (s == null) return new int[]{-1, -1};
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)").matcher(s);
        if (!m.find()) return new int[]{-1, -1};
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }
}
