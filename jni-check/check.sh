#!/usr/bin/env bash
# Compile-checks the REAL mpv-android JNI C++ sources (ref-mpv-android/app/src/main/jni) against the header
# javac -h generated from OUR is.xyz.mpv.MPVLib. A mismatching native signature fails the build here.
# Environment-only differences are stubbed: android/log.h, av_jni_set_android_app_ctx (Android-only FFmpeg API),
# JavaVM::AttachCurrentThread(JNIEnv**) (Android's jni.h) via -fpermissive for jni_utils.cpp only.
set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$SCRIPT_DIR"

SRC="${MPVA_JNI:-$REPO_ROOT/../mpv-android/app/src/main/jni}"
JDK_ROOT="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
JH="$JDK_ROOT/include"
MPVLIB_CLASSES="${MPVLIB_CLASSES:-/tmp/jnicls}"

if [[ ! -d "$SRC" ]]; then
    echo "JNI source directory not found: $SRC" >&2
    echo "Set MPVA_JNI to the mpv-android JNI source directory." >&2
    exit 1
fi

if [[ ! -f "$JH/jni.h" ]]; then
    echo "JNI headers not found: $JH/jni.h" >&2
    echo "Set JAVA_HOME to your JDK installation." >&2
    exit 1
fi

for file in is_xyz_mpv_MPVLib.h stubs/avjni_stub.h; do
    if [[ ! -f "$SCRIPT_DIR/$file" ]]; then
        echo "Required checker file not found: $SCRIPT_DIR/$file" >&2
        exit 1
    fi
done

if [[ ! -f "$MPVLIB_CLASSES/is/xyz/mpv/MPVLib.class" ]]; then
    echo "Compiled MPVLib class not found in: $MPVLIB_CLASSES" >&2
    echo "Compile MPVLib.java first, or set MPVLIB_CLASSES." >&2
    exit 1
fi

for tool in g++ javap grep sed cut; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Required tool not found: $tool" >&2
        exit 1
    fi
done

rc_all=0

for f in main render log jni_utils property event; do
    source_file="$SRC/$f.cpp"

    if [[ ! -f "$source_file" ]]; then
        echo "$f.cpp: source file not found"
        rc_all=1
        continue
    fi

    extra=()
    if [[ "$f" == "jni_utils" ]]; then
        extra=(-fpermissive -w)
    fi

    set +e
    output="$(g++ -std=c++11 -fsyntax-only -D__ANDROID__ \
        "${extra[@]}" \
        -include is_xyz_mpv_MPVLib.h \
        -include stubs/avjni_stub.h \
        -I. -Istubs -I"$JH" -I"$JH/linux" -I"$SRC" \
        "$source_file" 2>&1)"
    rc=$?
    set -e

    summary="$(printf '%s\n' "$output" |
        grep -m2 -E 'error:|fatal error:' |
        tr '\n' ' ' | cut -c1-200 || true)"

    printf '%-14s rc=%d  %s\n' "$f.cpp" "$rc" "$summary"

    if [[ "$rc" -ne 0 ]]; then
        rc_all=1
    fi
done

if [[ -f "$SRC/thumbnail.cpp" ]]; then
    count="$(grep -c \
        'jni_func(jobject, grabThumbnail, jint' \
        "$SRC/thumbnail.cpp" || true)"
    printf '%-14s %s\n' "thumbnail.cpp" \
        "not compiled; textual check: $count matching definition"
else
    echo "thumbnail.cpp: not found; skipped"
fi

echo "Checking native static callbacks against MPVLib..."

if [[ ! -f "$SRC/jni_utils.cpp" ]]; then
    echo "Missing $SRC/jni_utils.cpp" >&2
    exit 1
fi

while IFS= read -r line; do
    name="$(printf '%s\n' "$line" | cut -d'"' -f2)"
    sig="$(printf '%s\n' "$line" | cut -d'"' -f4)"

    found="$(javap -s -p -cp "$MPVLIB_CLASSES" \
        is.xyz.mpv.MPVLib 2>/dev/null |
        grep -A1 -F " $name(" |
        grep -F -c "descriptor: $sig" || true)"

    if [[ "$found" -ge 1 ]]; then
        status="OK"
    else
        status="MISSING"
        rc_all=1
    fi

    printf '  %-14s %-34s %s\n' "$name" "$sig" "$status"
done < <(
    grep -o \
        'GetStaticMethodID(mpv_MPVLib, "[a-zA-Z]*", "[^"]*")' \
        "$SRC/jni_utils.cpp" |
    sed 's/GetStaticMethodID(mpv_MPVLib, //;s/)$//'
)

exit "$rc_all"
