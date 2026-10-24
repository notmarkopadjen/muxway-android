#!/usr/bin/env bash
# Builds the app and the hub from the core checkout, runs the unit and
# end-to-end suites on the JVM and, when adb sees a device, the UI tests.
# Writes test-runs/<id>/: summary.md, results.xml, logs/<test>/, screens/.
#   MUXWAY_CORE  checkout of the core repository (default: ../muxway)
#   MUXWAY_UI=0  skip the UI tests even with a device
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
for tool in tmux go python3; do
    command -v "$tool" >/dev/null || { echo "the end-to-end tests need $tool" >&2; exit 1; }
done
core=${MUXWAY_CORE:-../muxway}
[ -f "$core/go.mod" ] || { echo "no muxway core checkout at $core (set MUXWAY_CORE)" >&2; exit 1; }
if [ -z "${JAVA_HOME:-}" ] && [ -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
fi

id="$(date -u +%Y%m%d-%H%M%S)-$(git rev-parse --short HEAD)"
run="$PWD/test-runs/$id"
mkdir -p "$run/logs"
(cd "$core" && CGO_ENABLED=0 go build -o "$run/muxway" ./cmd/muxway)
{ echo "core $(git -C "$core" rev-parse --short HEAD)"; tmux -V; "${JAVA_HOME:-/usr}/bin/java" -version 2>&1 | head -1; uname -sm; } > "$run/environment.txt"

status=0
MUXWAY_BIN="$run/muxway" MUXWAY_RUN_DIR="$run" ./gradlew --no-daemon -q :protocol:test :app:testDebugUnitTest :app:assembleDebug \
    > "$run/logs/gradle.log" 2>&1 || status=$?
results=(protocol/build/test-results/test app/build/test-results/testDebugUnitTest)

export PATH="$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools"
device=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1; exit}' || true)
if [ "${MUXWAY_UI:-1}" = 1 ] && [ -n "$device" ]; then
    echo "UI tests on $device" >> "$run/environment.txt"
    scripts/ui-tests.sh "$run" "$device" || status=$?
    results+=(app/build/outputs/androidTest-results/connected)
else
    echo "skipped: no device (adb devices lists none)" > "$run/ui-skipped.txt"
fi

rm -f "$run/muxway"
python3 -I scripts/junit-report.py "$run" "${results[@]}" || status=1
sed -n 3p "$run/summary.md"
echo "run directory: test-runs/$id"
exit "$status"
