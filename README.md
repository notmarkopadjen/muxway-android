```
┌─ muxway-android ─┐
```

Android client for [muxway](https://github.com/notmarkopadjen/muxway):
your tmux panes on a phone, one per screen or side by side, and on a
Wear OS watch.

```
[‹] [ ✳ ! fixing the build ][ bash · make ][ tail ]        ⤢ abc ⚙
┌──────────────────────────────────────────────────────────────┐
│ ✳ Claude · fixing the build · work/0.0 · write   ! permission │
│ ⏺ Bash(go test ./pkg/store/...)                                │
│   ⎿  ok   orbit/pkg/store  0.311s                              │
│ ╭────────────────────────────────────────────╮                 │
│ │ Do you want to proceed?                    │                 │
│ │ ❯ 1. Yes                                   │                 │
│ │   2. No                                    │                 │
│ ╰────────────────────────────────────────────╯                 │
└──────────────────────────────────────────────────────────────┘
> type into alice-laptop/work/0.0                        [⇪] [◉] [⏎]
[Esc] [Tab] [S-Tab] [^C] [↑] [↓] [←] [→] [BS] …
✳ [/clear] [/compact] [/model] [/resume] …
```

> Works day to day: connect with a token or by scanning a code, list
> panes, open one or several, switch with tabs, size the host pane to the
> phone or read it at its own width with pinch zoom, type, send keys,
> click and scroll in programs that take the mouse, see which pane calls
> for attention and be told when one does; on a Wear OS watch, a face,
> complications, a tile, and a pane's tail with a few answers and the
> microphone on the wrist.

## What it does

- **Panes**, grouped by host, named by what runs in them. Tap opens,
  hold picks up to four for one screen. `+ pane` splits a window, `+
  session` starts one, where the host's agent allows it.
- **The pane view**: tabs painted with each pane's state, one input bar
  for the focused pane, a key bar, Claude Code's slash commands or a
  shell's recent lines in a command row. **Fit** sizes the pane on the
  host to the phone; off, pinch to zoom the host's own width.
- **Notifications** when a pane waits: a question, a permission, idle,
  done, as the AI coding agent in the pane reports them to the hub
  through its hooks; a tap lands on the pane.
- **Lenses**: a lens knows one agent's screen. **The Claude Code lens**
  knows Claude's: a pane's state, its question, and what to hide on a
  small display. Only Claude Code has been tested so far; another agent
  needs its own hooks calling `muxway notify` on the host, and a lens for
  its screen.
- **The watch**: a watch face and complications that count the waiting
  panes, a tile, and a pane on the wrist with a reply menu, a row of keys
  and the microphone; *phone* opens the same pane on the phone behind the
  lock screen. See [the watch](docs/features/watch.md).
- **Several hubs**, each with its own token in the Android Keystore.
- **Voice**: hold the microphone, speak, and the words the hub's
  transcriber heard land on the input line to read and send.
- **Attachments**: paste a screenshot into the line, or pick files with
  `⇪`, where the host's agent has an inbox; each goes to the host and
  its path lands on the line for you to finish the sentence and send.

Screens: [connect](docs/features/connect.md),
[panes](docs/features/panes.md), [pane view](docs/features/pane-view.md),
[lenses](docs/features/lenses.md),
[notifications](docs/features/notifications.md),
[attachments](docs/features/attachments.md),
[watch](docs/features/watch.md), [admin](docs/features/admin.md),
[look](docs/features/look.md). How it is built:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

The other clients, and the hub: [muxway](https://github.com/notmarkopadjen/muxway)
(web client, protocol),
[muxway-glasses-even](https://github.com/notmarkopadjen/muxway-glasses-even),
[muxway-midp](https://github.com/notmarkopadjen/muxway-midp).

## Install

The latest build is on the repository's
[Releases page](https://github.com/notmarkopadjen/muxway-android/releases/latest)
as three APKs: app, wear and watchface. They are signed with a debug key,
so they install with `adb install` and by side-loading; building them
yourself is described below.

Build it yourself, Android 8.0 or newer:

```sh
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

The build needs JDK 17 and an Android SDK with platform 35 (`sdk.dir` in
`local.properties`, or `ANDROID_HOME`).

For a Wear OS watch, build the watch app and the watch face too and install
them on the watch; all three APKs must carry the same signature:

```sh
./gradlew :wear:assembleDebug :watchface:assembleDebug
adb -s <watch> install wear/build/outputs/apk/debug/wear-debug.apk
adb -s <watch> install watchface/build/outputs/apk/debug/watchface-debug.apk
```

Then switch on **watch and notifications** in the phone app's settings,
and **watch may answer** if the watch is to send keys and spoken lines to a
pane.

## Connect

On the hub, make a token for the phone:

```sh
muxway hub token create --name phone --read 'alice-laptop/*' --write 'alice-laptop/@build'
```

In the app: hub URL (`https://hub.example.com`), paste the token, **save and
connect**. Or skip the typing: pipe the same command into
`scripts/connect-qr.py https://hub.example.com`, which prints a QR code,
and tap **scan code** in the app. The token goes into the Android Keystore
and is never shown again. Plain HTTP is accepted only for `localhost`, so
a hub without TLS is reached through `adb reverse` or an SSH tunnel.

## Tests

```sh
./gradlew test                            # unit tests
MUXWAY_CORE=../muxway scripts/e2e.sh      # the suite against a real hub and agent; MUXWAY_UI=0 skips the device run
```

See [docs/testing.md](docs/testing.md).

Contributors and AI agents: read [AGENTS.md](AGENTS.md) first.
Security issues: see [SECURITY.md](SECURITY.md).

[Apache License 2.0](LICENSE), like the rest of muxway.
