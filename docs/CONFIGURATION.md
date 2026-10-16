# Configuration reference (`MpvPlayerConfig`)

Build with `MpvPlayerConfig.builder([profile])...build()`. `build()` throws `IllegalArgumentException` listing **every** invalid value;
`Builder.problems()` returns the list without throwing. Names/units below were verified against mpv master docs (`DOCS/man/options.rst`)
and against a real libmpv 0.37 (`docs/test-logs/mpv-names-check.log`).

## Buffering memory model (what each knob really is)
| Concept | mpv option(s) | Unit | Notes |
|---|---|---|---|
| Demuxer cache (RAM) — the only "memory cache" in current mpv | `demuxer-max-bytes`, `demuxer-max-back-bytes`, `cache-secs` | bytes, bytes, **seconds** | Effective forward limit = whichever of bytes/seconds is hit first. Not exact. Cap enforced here: 512 MiB. |
| Enable/disable cache | `cache` = `yes/no/auto` | — | `auto` = only for network-like input (mpv's heuristic). |
| Initial buffering | `cache-pause-initial` + `cache-pause-wait` | bool + seconds | Wait for N s of data before first playback. |
| Stall handling | `cache-pause`, `cache-pause-wait` | bool, seconds | `no` = stutter instead of pausing (used by LOW_LATENCY). |
| Readahead | `demuxer-readahead-secs` | seconds | Mostly overridden when the cache is on. |
| HLS **segment** fetching | *none in mpv* | — | Done by FFmpeg's `hls` demuxer. mpv exposes `hls-bitrate` (variant) and passes FFmpeg options via `demuxer-lavf-o` (`live_start_index`). |
| Network timeout | `network-timeout` | seconds | HTTP at least; other protocols may ignore. |
| Reconnect | `stream-lavf-o=reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=N` | seconds | FFmpeg **http** protocol options (present in FFmpeg 6.1: `ffmpeg -h protocol=http`). Invalid keys are accepted at set time and only logged at open time. |

## Profiles (starting points; every value can be overridden afterwards)
| | STABLE | BALANCED (default) | LOW_LATENCY |
|---|---|---|---|
| `cache` | yes | auto | auto |
| `demuxer-max-bytes` | 96 MiB | 48 MiB | 16 MiB |
| `demuxer-max-back-bytes` | 16 MiB | 16 MiB | 4 MiB |
| `cache-secs` | 60 | 20 | 3 |
| `demuxer-readahead-secs` | 10 | 5 | 1 |
| initial buffering / wait | yes / 4 s | no / 1.5 s | no / 0.5 s |
| `cache-pause` | yes | yes | **no** |
| `profile` (mpv builtin) | — | — | `low-latency` |
| `demuxer-lavf-o` | — | — | `fflags=+nobuffer,live_start_index=-1` |
| HLS live start (segments from end) | 3 (FFmpeg default) | 3 | 1 |

Measured on a *simulated* 12-second HLS window (desktop mpv 0.37): buffered-ahead ~4 s (STABLE, BALANCED) vs ~0 s (LOW_LATENCY).
The simulated window is too short to separate STABLE from BALANCED; LOW_LATENCY trades stall resilience for latency. Not a guarantee for real providers.

## All settings
| Builder method | mpv option | Unit / range (validated) | Default |
|---|---|---|---|
| `networkCache` | `cache` | AUTO/ENABLED/DISABLED | per profile |
| `demuxerMaxBytes` / `demuxerMaxBackBytes` | `demuxer-max-bytes` / `-back-bytes` | bytes; 1 MiB..512 MiB / 0..512 MiB | per profile |
| `cacheSeconds` / `readaheadSeconds` | `cache-secs` / `demuxer-readahead-secs` | s; 0.5..3600 / 0..3600 | per profile |
| `initialBuffering(on, wait)` / `rebufferSeconds` / `pauseOnUnderrun` | `cache-pause-initial`, `cache-pause-wait`, `cache-pause` | bool, s 0..60 | per profile |
| `networkTimeoutSeconds` | `network-timeout` | s 0..300 (0 = FFmpeg default) | 15 |
| `reconnect(on, delayMax)` | `stream-lavf-o` | s 1..120 | on, 5 |
| `hlsLiveStartSegmentsFromEnd` | `demuxer-lavf-o: live_start_index=-N` | 1..10 | per profile |
| `hlsBitrate` | `hls-bitrate` | MAX/MIN/NONE | MAX |
| `liveHint` | (engine) | LIVE/VOD/UNKNOWN | UNKNOWN |
| `hardwareDecoding` | `hwdec` | AUTO_ANDROID=`mediacodec,mediacodec-copy`, MEDIACODEC, MEDIACODEC_COPY, SOFTWARE=`no` | AUTO_ANDROID |
| `videoOutput` | `vo`, `gpu-context=android`, `opengl-es=yes` | GPU / GPU_NEXT / NULL | GPU |
| `audioOutput` | `ao` | e.g. `audiotrack,opensles` | that value (as mpv-android) |
| `defaultAspectRatio` / `defaultScaleMode` | `video-aspect-override`, `keepaspect`, `panscan`, `video-unscaled` | ratio 0.1..10; FIT/FILL/STRETCH/ORIGINAL | source / FIT |
| `subtitlesEnabled`, `subtitleFontSize`, `subtitlePosition`, `subtitleDelaySeconds`, `subtitleLanguages` | `sub-visibility`, `sub-font-size`, `sub-pos`, `sub-delay`, `slang` | 1..200 (720p px), 0..150, +-3600 s | mpv defaults |
| `audioLanguages` | `alang` | list | mpv default |
| `defaultPlaybackSpeed` / `initialVolume` | `speed` / `volume` | 0.01..100 / 0..130 | 1.0 / mpv |
| `keepLastFrameAtEnd` | `keep-open` | bool | true (EOF detection relies on it) |
| `deinterlace` | `deinterlace` | bool | false |
| `userAgent`, `referrer`, `httpHeader` | `user-agent`, `referrer`, `http-header-fields` | no control chars; header values no `,` | — |
| `tlsVerify`, `tlsCaFile` | `tls-verify`, `tls-ca-file` | bool, path | true, — |
| `screenshotDirectory` | `screenshot-dir` | path | — |
| `logLevel` | `msg-level` | mpv syntax | `all=warn` |
| `retry(max, firstDelayMs, backoff)`, `retryOnlyNetworkStreams` | (engine) | 0..20; 10..60000 ms; 1..4 | 3, 1000, 2.0; true |

Always set by the engine: `idle=yes`, `force-window=no`, `ytdl=no`, `deinterlace`.
`MpvPlayerConfig.describe()` prints the exact option list that will be passed to mpv.
