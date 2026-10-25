#!/usr/bin/env bash
# Checks the watch face against the Watch Face Format schema with Google's
# validator, fetched once into tmp/ (ignored by git). Writes a JUnit result
# into the run directory when one is given, so the check shows in the run.
#   scripts/validate-face.sh [run directory]
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
run=${1:-}
face=watchface/src/main/res/raw/watchface.xml
version=$(grep -o 'format\.version" android:value="[0-9]\+"' watchface/src/main/AndroidManifest.xml | grep -o '[0-9]\+' | head -1)
[ -n "$version" ] || { echo "no watch face format version in the manifest" >&2; exit 1; }
jar=tmp/wff-validator.jar
if [ ! -f "$jar" ]; then
    mkdir -p tmp
    curl -sSL --fail -o "$jar" https://github.com/google/watchface/releases/download/release/wff-validator.jar
fi
start=$(date +%s)
out=$(java -jar "$jar" "$version" "$face" 2>&1) && status=0 || status=$?
# The validator answers usage, and exits 0, when it does not like its arguments.
case "$out" in *PASSED*) ;; *) status=1 ;; esac
echo "$out" | tail -3
if [ -n "$run" ]; then
    took=$(( $(date +%s) - start ))
    failure=""
    [ "$status" -eq 0 ] || failure="<failure message=\"validator failed\"><![CDATA[$out]]></failure>"
    cat > "$run/face-check.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="watchface" tests="1" failures="$([ "$status" -eq 0 ] && echo 0 || echo 1)" errors="0" skipped="0">
  <testcase classname="app.muxway.android.e2e.WatchFace" name="z98_theFaceIsValidWatchFaceFormat$version" time="$took">$failure</testcase>
</testsuite>
XML
fi
exit "$status"
