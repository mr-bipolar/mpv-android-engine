# Feature support matrix

Legend — **D** verified against a real desktop mpv 0.37 through the engine (integration test); **U** logic unit-tested with a fake
backend only; **S** statically verified (compiled / names and syntax checked in mpv master docs) but never run; **—** not supported/not provided.
**Nothing below has been run on Android** (no device/emulator, no NDK in the sandbox): Android column = "unverified" throughout.

| Area | API | mpv mechanism | Status |
|---|---|---|---|
| Init / release | `initialize()`, `release()` | libmpv create/init/destroy | D (desktop), S (JNI contract) |
| Load / play / pause / stop / reopen | `load`, `play`, `pause`, `togglePause`, `stop`, `reopen` | `loadfile`, `pause`, `stop` | D |
| Seek abs / rel / percent | `seekTo`, `seekBy`, `seekToPercent` | `seek` (absolute / relative / absolute-percent) | D (abs), U (rel/percent) |
| Position / duration / state | `getPositionSeconds`, `getDurationSeconds`, `getState` | `time-pos`, `duration`, observed flags | D |
| Title / metadata / stream info | `getMediaInfo`, `getMetadata`, `getMediaTitle` | `media-title`, `metadata`, `track-list/*`, `file-format` | D |
| EOF, errors, retry/reconnect | listener callbacks; `retry(...)`; `reconnect(...)` | `eof-reached`, END_FILE, `stream-lavf-o` | D (retry; reconnect only observed: playback resumed after a 5 s outage, via FFmpeg/mpv, no app retry needed) |
| Volume / mute / speed | `setVolume`, `setMuted`, `setSpeed` | `volume`, `mute`, `speed` | D |
| Audio output device | `getAudioDevices`, `setAudioDevice` | `audio-device-list` (JSON), `audio-device` | D (list), S (set) |
| Subtitle tracks list/select/disable/visibility | `getSubtitleTracks`, `selectSubtitleTrack`, `disableSubtitles`, `setSubtitlesVisible` | `track-list`, `sid`, `sub-visibility` | D |
| External subtitle | `loadExternalSubtitle` | `sub-add <url> select` | D (SRT). Formats = whatever the mpv/libass/FFmpeg build supports |
| Subtitle delay / size / scale / position | `setSubtitleDelay`, `setSubtitleSize`, `setSubtitleScale`, `setSubtitlePosition` | `sub-delay` (s), `sub-font-size`, `sub-scale`, `sub-pos` (0..150) | D (read back) |
| Aspect override, reset | `setAspectRatio`, `resetAspectRatio` | `video-aspect-override` | D (read back; reset uses build default) |
| Fit / fill / stretch / original | `setVideoScaleMode` | `keepaspect`, `panscan`, `video-unscaled` | D (properties); visual result S (null VO on desktop) |
| Zoom / pan / crop / rotation | `setZoom`, `setPan`, `setCrop`, `clearCrop`, `setRotation` | `video-zoom` (log2), `video-pan-x/y`, `video-crop`, `video-rotate` | D (read back); visual result S |
| Video / display geometry | `getVideoInfo` | `video-params/*`, `dwidth/dheight` | D (source size/aspect); display size S |
| Live detection | `getLiveStatus`, `isLive`, `getLiveStatusReason` | duration growth + `seekable` + `file-format` | D (live HLS, VOD HLS, mp4), U (rules) |
| Seekable / network / live edge / latency | `isSeekable`, `isNetworkStream`, `getLiveEdgePositionSeconds`, `getLiveLatencySeconds`, `jumpToLive` | `seekable`, `demuxer-via-network`, `demuxer-cache-time` | D (simulated HLS) |
| Buffering info | `getBufferingInfo` | `paused-for-cache`, `cache-buffering-state`, `demuxer-cache-*`, `demuxer-cache-state` JSON | D |
| Buffering profiles | `MpvPlayerConfig` | `cache*`, `demuxer-*`, `profile=low-latency`, `live_start_index` | D (simulated HLS) |
| Audio tracks list/select/disable, language/title | `getAudioTracks`, `selectAudioTrack`, `disableAudio` | `track-list`, `aid` | D |
| External audio | `addExternalAudio` | `audio-add <url> select` | D |
| Audio delay (A/V sync) | `setAudioDelay` | `audio-delay` (s) | D (read back) |
| Playlist: parse, navigate, add/remove/insert | `PlaylistManager`, `M3uParser` | Java model; one `loadfile` per entry | D (auto-advance, skip, prev/next), U (rest) |
| M3U/M3U8 channel lists, relative paths | `M3uParser` (`baseUri`) | Java | U |
| Local files, http(s), other schemes | `load` | mpv/FFmpeg protocols | D (local, http, hls); other schemes S |
| `content://` | `UriResolver` | `/proc/self/fd` path or FFmpeg `content:` | S (**needs device**) |
| Frame step / back-step | `frameStep`, `frameBackStep` | `frame-step`, `frame-back-step` | D (step), U (back-step) |
| Screenshot | `takeScreenshot` | `screenshot-to-file` | D (file written with `vo=null`); Android surface S |
| Deinterlace | `setDeinterlace` | `deinterlace` | D (accepted) |
| HW/SW decoding config | `hardwareDecoding`, `setHardwareDecoding` | `hwdec=mediacodec[,mediacodec-copy]` / `no` | S for MediaCodec (desktop only ran `no`) |
| Decoder & playback stats, dropped frames | `getStats` | `hwdec-current`, `estimated-vf-fps`, `frame-drop-count`, `decoder-frame-drop-count`, `avsync`, bitrates | D (software values) |
| Surface attach / detach / size | `getSurfaceCallback`, `attachSurface`, `detachSurface`, `setSurfaceSize` | JNI `attachSurface`, `android-surface-size` | S (compiled; **device-only feature**) |
| Background playback | host service + `detachSurface` | audio continues without VO | S (**not tested**) |
| Thumbnails (`grabThumbnail`) | — (bound in `MPVLib`, not exposed) | native only | — |
| Network interruption recovery | automatic | FFmpeg HLS reload + mpv cache stall; app retry as 2nd line | D (5 s outage, simulated HLS) |
| Exact end-of-file reason | optional native patch | `mpv_event_end_file` | S (patch applies + compiles; inference verified D) |
