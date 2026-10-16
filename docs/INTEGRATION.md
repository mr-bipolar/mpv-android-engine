# Integration guide (Java host app)

## 1. Add the module and native libraries
1. Copy `mpvengine/` into your project, `include ':mpvengine'` in `settings.gradle`, `implementation project(':mpvengine')`.
2. Build the natives (BUILDING_NATIVE.md) and place them in `mpvengine/src/main/libs/<abi>/`.
3. Manifest: the library declares nothing. Your app needs `INTERNET`. For background audio you need your own foreground
   service (with a notification; on recent Android versions also the media-playback foreground-service type/permission — check
   the current Android documentation, not verified here).
4. Provide a CA bundle for HTTPS when using mpv-android's mbedTLS build: copy a PEM file to `filesDir` and pass
   `MpvPlayerConfig.Builder.tlsCaFile(path)` (upstream ships `cacert.pem` for this reason).

## 2. Create the player (once per process)
```java
MpvPlayer player = MpvPlayer.create(context, MpvPlayerConfig.defaults()); // main-thread callbacks
player.addListener(listener);
player.initialize();   // creates libmpv, applies options (rejections reported), starts observing
```
`release()` when finished (destroys the native instance; the object cannot be reused). Only one instance per process.

## 3. Attaching the video surface — who does what
The engine **never creates or measures views**. The host:
* creates a `SurfaceView` (or any `SurfaceHolder`) and sizes it in its layout;
* registers `surfaceView.getHolder().addCallback(player.getSurfaceCallback())`.

The callback does exactly what upstream's `BaseMPVView` does: `surfaceCreated` -> `attachSurface` (+ `force-window=yes`, restore the
video output, start any queued load); `surfaceChanged` -> `android-surface-size=WxH`; `surfaceDestroyed` -> `vo=null`,
`force-window=no`, `detachSurface` (order matters: stop drawing before the surface dies).

Rendering-container responsibilities (important):
* mpv scales the picture **inside the surface rectangle** you give it. `setAspectRatio`, `setVideoScaleMode` (FIT/FILL/STRETCH/ORIGINAL),
  `setZoom`, `setPan`, `setCrop`, `setRotation` change what mpv draws in that rectangle. They do **not** resize or re-measure your view.
* If you want the *view* to match the video (e.g. `wrap_content`-style sizing), read `getVideoInfo()` (`getSourceWidth/Height/Aspect`,
  `getDisplayWidth/Height`) in `onVideoInfoChanged` and lay the view out yourself. `dwidth/dheight` is mpv's idea of the scaled size,
  not an Android measurement.
* On configuration changes (rotation, multi-window) the surface size changes: `surfaceChanged` forwards the new size automatically.
* Audio-only streams need no surface: `LoadOptions.builder().audioOnly(true)` loads immediately (`vid=no`). With the GPU output and a
  video load, the engine queues the load until a surface is attached (state `LOADING`).
* Keep playback alive in background: detach the surface when it is destroyed (automatic) and keep the player + your service alive.
  Audio continuing without a surface is how mpv-android behaves; **not tested on a device here**.

## 4. Playback, live and playlists
```java
player.load(url, LoadOptions.builder().liveHint(LiveStatus.LIVE).build());   // hint wins over detection
player.getLiveStatus();   // LIVE / VOD / UNKNOWN (+ getLiveStatusReason())
player.isLive();          // true ONLY when positively LIVE; false for VOD *and* UNKNOWN
player.seekTo(30);        // returns false when mpv says not seekable
player.jumpToLive();      // seek to edge if seekable, otherwise reopen the stream

PlaylistManager pl = player.getPlaylist();          // authoritative list; mpv only holds the current entry
pl.loadM3u(text, new M3uParser.Options().baseUri(listUrl), true);
player.next(); player.previous(); pl.playIndex(5); pl.remove(2);
player.setSkipFailedEntries(false);                 // IPTV default: a dead channel does NOT jump to the next one
```
Reading a playlist from a URL / `content://`: `UriResolver.readText(...)` (blocking, call off the main thread). `.m3u8` URLs may be HLS
manifests, not channel lists: `loadM3u` refuses them with a clear message (`M3uParser.looksLikeHlsManifest`).

`content://` URIs: the app must hold a read grant (ideally persisted via `takePersistableUriPermission`). `MpvPlayer.load` resolves them
through `UriResolver.toMpvPath` (real path via `/proc/self/fd/N` when readable, else the `content://` string for FFmpeg's `content:` protocol).

## 5. Errors
`onError(MpvError)`: `getKind()`, `getMessage()` (user-safe), `getLogLines()` (mpv/FFmpeg error lines for diagnostics), `willRetry()`.
No native stack traces are exposed. Automatic retries use exponential backoff (`retry(max, firstDelayMs, backoff)`); they apply to network
streams by default (`retryOnlyNetworkStreams`) and to anything hinted LIVE.
