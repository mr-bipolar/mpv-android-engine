# Known limitations and behaviours that differ from what you might assume

**Engine / native layer**
1. **One player per process.** The native glue keeps one global mpv instance; creating a second aborts the process. Create `MpvPlayer` once.
2. **`command()` and `setProperty*()` return nothing** over JNI. A rejected value/command shows up only as a mpv log line (`onLog`) —
   which is why the public setters validate ranges in Java first, and why configuration options (which *do* return an error code)
   are reported through `onOptionRejected` / `getOptionReport()`.
3. **End-of-file reason is not exposed by stock mpv-android natives.** The engine therefore *infers* it (user-initiated stop vs
   `eof-reached` vs everything else = error). Measured against real mpv this gave the same outcome as the exact reason on every tested
   path (see integration tests "same outcome with INFERRED end reason"), but it is still an inference. The optional
   `native-patches/end-file-reason.patch` forwards the real `reason`/`error`; with it `MpvError.getMpvErrorCode()` is filled.
4. `time-pos` is observed in whole seconds (`onPositionChanged` fires about once a second). Use `getPositionSeconds()` for precision.

**Live / IPTV**
5. **Duration does not mean VOD.** For live HLS mpv (FFmpeg 6.1) reports a *growing* duration with `seekable=no`. The detector uses
   growth over >=2 s and >=2 increments; until then HLS-not-seekable is `UNKNOWN`. Providers using MPEG-TS/RTMP/Icecast without
   duration stay `UNKNOWN` unless you pass a `liveHint`. DVR-window HLS (seekable *and* growing) is first reported `VOD`, then flips to `LIVE`.
6. "Live edge" = end of the demuxer cache (`demuxer-cache-time`), an approximation, not a provider-confirmed "now".
   `jumpToLive()` on non-seekable live **reopens** the stream (the demuxer's start position decides where you land).
7. Buffer/latency numbers are mpv estimates; mpv does not guarantee a given size or latency. Measured profile differences are
   from a simulated 12-second HLS window only.
8. Reconnect options (`reconnect*`) are FFmpeg **http** protocol options; RTMP/RTSP/UDP/SRT ignore them. Application-level retry
   (`retry(...)`) covers those but restarts the stream.

**Configuration**
9. HTTP header **values containing ','** cannot be passed through mpv's `http-header-fields` (verified: dropped/truncated). The
   engine rejects them with a clear error instead of silently losing them. User-Agent and Referer may contain commas.
10. Per-load `http-header-fields` replaces the global list in mpv; the engine merges config + per-load headers itself.
11. `sub-font-size` does not restyle ASS/SSA subtitles that carry their own styles; use `setSubtitleScale` (affects ASS, may distort layout).
12. `hwdec` changes apply to the next opened file (`reopen()` to apply now). mpv documents `mediacodec` zero-copy as "not safe" in
    general (forces RGB conversion) — `AUTO_ANDROID` tries it first and falls back to `mediacodec-copy`; verify on your target devices.
13. `video-aspect-override` default text differs by mpv version ("-1" on 0.37); `resetAspectRatio()` restores the build's own default.
14. Option names/ranges are those of the mpv you build. Rejected options are reported, never silently ignored.

**Platform**
15. Screenshots need a real video output (a surface); success is not reported by mpv's API — check the file. On desktop with `vo=null` a file *was* written.
16. `content://`: needs a readable URI grant (persist it with `takePersistableUriPermission`). Reading works either through a real
    file path (`/proc/self/fd/N`) or FFmpeg's `content:` protocol (needs the JNI-enabled FFmpeg upstream builds). **Not tested on a device.**
17. Playing with the GPU video output requires a Surface; the engine queues loads until one is attached (audio-only loads do not wait).
18. Retry never resumes live streams at an old position; VOD resumes at the last whole second.
19. The engine disables mpv's `ytdl` hook (`ytdl=no`) so untrusted URLs/playlists cannot make mpv spawn an external downloader.
