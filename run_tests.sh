```bash
#!/usr/bin/env bash
# Usage: ./run_tests.sh [unit|integration]
# Requires JDK 11+ and an Android SDK android.jar.
# Integration tests may require desktop mpv and ffmpeg.

set -Eeuo pipefail

cd "$(dirname "$0")"

MODE="${1:-unit}"
case "$MODE" in
    unit|integration) ;;
    *)
        echo "Usage: $0 [unit|integration]" >&2
        exit 2
        ;;
esac

AJ="${ANDROID_JAR:-}"

if [[ -z "$AJ" ]]; then
    echo "Error: Set ANDROID_JAR to the path of your Android SDK android.jar." >&2
    echo "Example: ANDROID_JAR=\$ANDROID_HOME/platforms/android-35/android.jar $0 $MODE" >&2
    exit 1
fi

if [[ ! -f "$AJ" ]]; then
    echo "Error: android.jar not found: $AJ" >&2
    exit 1
fi

rm -rf out out-test
mkdir -p out out-test

echo "Compiling main Java sources..."
find mpvengine/src/main/java -name '*.java' -print0 |
    xargs -0 javac -encoding UTF-8 -source 8 -target 8 \
        -Xlint:-options -cp "$AJ" -d out

echo "Compiling unit test sources..."
find mpvengine/src/test/java -name '*.java' -print0 |
    xargs -0 javac -encoding UTF-8 -cp "$AJ:out" -d out-test

echo "Running $MODE tests..."
java -cp "$AJ:out:out-test" com.maxplayer.mpv.Main "$MODE"
