# Building the native libraries (reproducible recipe)

The Java library needs `libmpv.so`, `libplayer.so` (the JNI glue) and the FFmpeg/etc. shared libraries for each ABI you ship.
No prebuilt binaries and no Maven artifact are provided or assumed. **This recipe was not executed in the authoring
sandbox** (no NDK, no network access to Google/FFmpeg/etc. hosts); it is derived from upstream's `buildscripts/README.md`,
`depinfo.sh` and `mpv-android.sh` as read at upstream commit `c2e02fe111541e4063ab8f868bf629649ed937a3`.

## Requirements (from upstream)
* Linux or macOS (upstream: Windows/WSL will **not** work). Network access for the dependency downloads.
* Android SDK platform 36, build-tools 36.0.0 and **NDK r30 (30.0.16248370)** per `depinfo.sh` (`download.sh` installs them).

## Steps
```sh
git clone https://github.com/mpv-android/mpv-android && cd mpv-android
git checkout c2e02fe111541e4063ab8f868bf629649ed937a3     # the commit this engine was written against
# optional but recommended: exact end-of-file reason / error code (see LIMITATIONS.md)
patch -p1 < /path/to/docs/native-patches/end-file-reason.patch
cd buildscripts
./download.sh                                    # SDK, NDK, sources
./buildall.sh --arch arm64 mpv                   # repeat for: armv7l (default), x86, x86_64
cd .. && ./buildscripts/scripts/mpv-android.sh build   # ndk-build of app/src/main/jni -> app/src/main/libs/<abi>/
```
Copy `app/src/main/libs/<abi>/*.so` (libmpv, libplayer, libav*.so, libsw*.so, libc++_shared, ...) to
`mpvengine/src/main/libs/<abi>/`. The Gradle module already points `jniLibs.srcDirs` there.

## Version pinning
Upstream builds **mpv from git master (not pinned)** and FFmpeg from its own pinned source tag. Two builds on different
days can therefore expose different mpv options. The engine reads `mpv-version` at start (`MpvPlayer.getMpvVersion()`) and
adapts the one syntax difference known to matter (`loadfile` argument layout, changed in 0.38). Record the version you ship.

## Verifying the Java <-> native contract (done in the sandbox)
`jni-check/check.sh` runs `javac -h` on our `MPVLib.java` and compiles upstream's real C++ (`main, render, log, jni_utils,
property, event`) against the generated header, then checks that every static callback the native code looks up
(`jni_utils.cpp`) exists in our class with the identical descriptor. Result: all pass, also with the optional patch applied.
This proves *symbol/signature* agreement only; it is not a device test.
