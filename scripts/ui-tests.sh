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
tmuxname2="mxw-android-ui2-$$"
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
port2=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')

cleanup() {
    kill "${ask_pid:-}" "${agent_pid:-}" "${hub_pid:-}" "${agent2_pid:-}" "${hub2_pid:-}" 2>/dev/null || true
    tmux -L "$tmuxname" kill-server 2>/dev/null || true
    tmux -L "$tmuxname2" kill-server 2>/dev/null || true
    adb -s "$serial" reverse --remove "tcp:$port" 2>/dev/null || true
    adb -s "$serial" reverse --remove "tcp:$port2" 2>/dev/null || true
    rm -rf "$tmp"
}
trap cleanup EXIT

# A repository under a preview root, the first pane's shell in it, for the changes sheet.
mkdir -p "$tmp/work/repo/src"
export GIT_AUTHOR_NAME=alice GIT_AUTHOR_EMAIL=alice@example.com GIT_COMMITTER_NAME=alice GIT_COMMITTER_EMAIL=alice@example.com GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
printf 'package a\n' > "$tmp/work/repo/src/a.go"
git -C "$tmp/work/repo" init -q -b main && git -C "$tmp/work/repo" add . && git -C "$tmp/work/repo" commit -q -m first
printf 'package a\n\nfunc A() {}\n' > "$tmp/work/repo/src/a.go"
printf 'loose\n' > "$tmp/work/repo/notes.txt"
tmux -L "$tmuxname" -f /dev/null new-session -d -s work -n shell -x 80 -y 24 -c "$tmp/work/repo" \
    "env -i PATH=/usr/bin:/bin TERM=xterm LANG=C.UTF-8 PS1='$ ' bash --norc --noprofile"
"$bin" hub serve --dir "$tmp/hub" --listen "127.0.0.1:$port" --public-url "http://127.0.0.1:$port" >> "$logs/hub.log" 2>&1 &
hub_pid=$!
for _ in $(seq 50); do [ -S "$tmp/hub/admin.sock" ] && break; sleep 0.2; done
code=$("$bin" hub pair-code --dir "$tmp/hub" --host alice-laptop)
"$bin" agent pair --dir "$tmp/agent" "$code"
"$bin" agent run --dir "$tmp/agent" --tmux-name "$tmuxname" --allow-sessions --preview "$tmp/work" >> "$logs/agent.log" 2>&1 &
agent_pid=$!
for _ in $(seq 50); do "$bin" hub agents --dir "$tmp/hub" | grep -q "alice-laptop	online" && break; sleep 0.2; done
"$bin" expose --tmux-name "$tmuxname" -t work:0.0 --write
# A session that asks a question with fixed answers again and again, for the choices and the
# inbox; and one whose shell is dressed as Codex, for the mark.
tmux -L "$tmuxname" new-session -d -s ask -x 80 -y 24
tmux -L "$tmuxname" new-session -d -s codex -x 80 -y 24 "exec -a codex bash --norc --noprofile"
"$bin" expose --tmux-name "$tmuxname" -t ask:0.0 --write
"$bin" expose --tmux-name "$tmuxname" -t codex:0.0
askpane=$(tmux -L "$tmuxname" display-message -p -t ask:0.0 '#{pane_id}')
( while kill -0 "$hub_pid" 2>/dev/null; do
    "$bin" ask --dir "$tmp/agent" --pane "$askpane" --text "deploy to staging?" --choice yes --choice no --timeout 10m >/dev/null 2>&1 || true
    sleep 5
  done ) &
ask_pid=$!
token=$("$bin" hub token create --dir "$tmp/hub" --name phone --read 'alice-laptop/*' --write 'alice-laptop/*')
admin=$("$bin" hub token create --dir "$tmp/hub" --name owner --read 'alice-laptop/*' --write 'alice-laptop/*' --admin)

# A second hub with its own host, for the list that shows every hub.
tmux -L "$tmuxname2" -f /dev/null new-session -d -s ops -n shell -x 80 -y 24 \
    "env -i PATH=/usr/bin:/bin TERM=xterm LANG=C.UTF-8 PS1='$ ' bash --norc --noprofile"
"$bin" hub serve --dir "$tmp/hub2" --listen "127.0.0.1:$port2" --public-url "http://127.0.0.1:$port2" >> "$logs/hub2.log" 2>&1 &
hub2_pid=$!
for _ in $(seq 50); do [ -S "$tmp/hub2/admin.sock" ] && break; sleep 0.2; done
code=$("$bin" hub pair-code --dir "$tmp/hub2" --host bob-server)
"$bin" agent pair --dir "$tmp/agent2" "$code"
"$bin" agent run --dir "$tmp/agent2" --tmux-name "$tmuxname2" >> "$logs/agent2.log" 2>&1 &
agent2_pid=$!
for _ in $(seq 50); do "$bin" hub agents --dir "$tmp/hub2" | grep -q "bob-server	online" && break; sleep 0.2; done
"$bin" expose --tmux-name "$tmuxname2" -t ops:0.0 --write
token2=$("$bin" hub token create --dir "$tmp/hub2" --name phone --read 'bob-server/*' --write 'bob-server/*')

adb -s "$serial" reverse "tcp:$port" "tcp:$port"
adb -s "$serial" reverse "tcp:$port2" "tcp:$port2"
status=0
ANDROID_SERIAL="$serial" ./gradlew --no-daemon -q :app:connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.hub=http://127.0.0.1:$port" \
    "-Pandroid.testInstrumentationRunnerArguments.token=$token" \
    "-Pandroid.testInstrumentationRunnerArguments.adminToken=$admin" \
    "-Pandroid.testInstrumentationRunnerArguments.hub2=http://127.0.0.1:$port2" \
    "-Pandroid.testInstrumentationRunnerArguments.token2=$token2" \
    > "$logs/gradle.log" 2>&1 || status=$?
adb -s "$serial" pull /sdcard/Android/data/app.muxway.android/files/screens/. "$run/screens/FlowTest/" >> "$logs/adb.log" 2>&1 || true
adb -s "$serial" logcat -d -t 2000 > "$logs/logcat.log" 2>&1 || true
if grep -qE "$token|$admin|$token2" "$logs"/*.log "$run"/screens -r 2>/dev/null; then
    echo "the token leaked into a UI test log" >&2
    status=1
fi
exit "$status"
