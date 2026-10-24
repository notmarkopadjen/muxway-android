#!/usr/bin/env bash
# Runs the UI tests on one device against a throwaway hub on this machine,
# reached from the device through adb reverse. Called by scripts/e2e.sh.
#   scripts/ui-tests.sh <run directory> <device serial>
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
run=${1:?run directory}
serial=${2:?device serial}
bin="$run/muxway"
logs="$run/logs/FlowTest"
mkdir -p "$logs" "$run/screens/FlowTest"
tmp=$(mktemp -d)
chmod 700 "$tmp"
tmuxname="mxw-android-ui-$$"
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')

cleanup() {
    kill "${agent_pid:-}" "${hub_pid:-}" 2>/dev/null || true
    tmux -L "$tmuxname" kill-server 2>/dev/null || true
    adb -s "$serial" reverse --remove "tcp:$port" 2>/dev/null || true
    rm -rf "$tmp"
}
trap cleanup EXIT

tmux -L "$tmuxname" -f /dev/null new-session -d -s work -n shell -x 80 -y 24 \
    "env -i PATH=/usr/bin:/bin TERM=xterm LANG=C.UTF-8 PS1='$ ' bash --norc --noprofile"
"$bin" hub serve --dir "$tmp/hub" --listen "127.0.0.1:$port" --public-url "http://127.0.0.1:$port" >> "$logs/hub.log" 2>&1 &
hub_pid=$!
for _ in $(seq 50); do [ -S "$tmp/hub/admin.sock" ] && break; sleep 0.2; done
code=$("$bin" hub pair-code --dir "$tmp/hub" --host alice-laptop)
"$bin" agent pair --dir "$tmp/agent" "$code"
"$bin" agent run --dir "$tmp/agent" --tmux-name "$tmuxname" --allow-sessions >> "$logs/agent.log" 2>&1 &
agent_pid=$!
for _ in $(seq 50); do "$bin" hub agents --dir "$tmp/hub" | grep -q "alice-laptop	online" && break; sleep 0.2; done
"$bin" expose --tmux-name "$tmuxname" -t work:0.0 --write
token=$("$bin" hub token create --dir "$tmp/hub" --name phone --read 'alice-laptop/*' --write 'alice-laptop/*')

adb -s "$serial" reverse "tcp:$port" "tcp:$port"
status=0
ANDROID_SERIAL="$serial" ./gradlew --no-daemon -q :app:connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.hub=http://127.0.0.1:$port" \
    "-Pandroid.testInstrumentationRunnerArguments.token=$token" \
    > "$logs/gradle.log" 2>&1 || status=$?
adb -s "$serial" pull /sdcard/Android/data/app.muxway.android/files/screens/. "$run/screens/FlowTest/" >> "$logs/adb.log" 2>&1 || true
adb -s "$serial" logcat -d -t 2000 > "$logs/logcat.log" 2>&1 || true
if grep -q "$token" "$logs"/*.log "$run"/screens -r 2>/dev/null; then
    echo "the token leaked into a UI test log" >&2
    status=1
fi
exit "$status"
