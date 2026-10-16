# Licensing (read before shipping)

This is a factual summary of what was verified in the upstream sources, not legal advice.

| Component | License | Verified where |
|---|---|---|
| mpv-android (JNI glue `app/src/main/jni`, `MPVLib` interface) | MIT ("Copyright (c) 2016 Ilya Zhuravlev, sfan5") | `ref-mpv-android/LICENSE` |
| libmpv (mpv) | GPLv2+ by default; LGPLv2.1+ only if built with `-Dgpl=false` **and** without GPL-only files | `mpv/Copyright`; `meson.options` has `gpl` default **true** |
| FFmpeg as built by mpv-android's recipe | **GPL + version3** (`--enable-gpl --enable-version3`) | `buildscripts/scripts/ffmpeg.sh` |
| mpv as built by mpv-android's recipe | `-Dgpl` not overridden -> GPL build | `buildscripts/scripts/mpv.sh` |
| This library's Java sources | **no license chosen** — add your own header/LICENSE | — |

**Consequence:** native binaries produced with the unmodified upstream recipe are GPL-licensed. Distributing an app that
includes them carries GPL obligations (source offer, license notices, compatibility of the whole work). If that is not
acceptable you must change the native build (e.g. `-Dgpl=false` for mpv, a LGPL-only FFmpeg configuration without
`--enable-gpl`) and re-check which mpv features/filters you lose. Other bundled libraries (mbedTLS, libass/freetype/fribidi/
harfbuzz, dav1d, curl, libxml2, lua, ...) have their own licenses; collect them from the build's `prefix/` sources.

`is.xyz.mpv.MPVLib.java` was authored here against the interface of the MIT-licensed mpv-android native layer. Keep the
MIT notice from upstream's LICENSE with your distribution when you ship the native glue built from their sources.
mpv-android's README states plainly that it is **not** an importable library/AAR; nothing here changes that.
