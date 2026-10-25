```
┌─ muxway-android ─┐
```

Android client for [muxway](https://github.com/notmarkopadjen/muxway): your tmux panes on a phone, one per screen or side by side.

> Early development. The basics work: connect with a token, list panes, open one or several, switch with tabs, set up by scanning a code, size the host pane to the phone (fit) or read it at its own width with pinch zoom, type, send keys, scroll by swipe, see which pane calls for attention, be told when one does, and see it on a Wear OS watch: a face, complications, a tile, and a pane's tail with a few answers on the wrist.

## Install

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
pane. See [docs/features/watch.md](docs/features/watch.md).

## Connect

On the hub, make a token for the phone:

```sh
muxway hub token create --name phone --read 'alice-laptop/*' --write 'alice-laptop/@build'
```

In the app: hub URL (`https://hub.example.com`), paste the token, **save and
connect**. Or skip the typing: pipe the same command into
`scripts/connect-qr.py https://hub.example.com`, which prints a QR code,
and tap **scan code** in the app. The token goes into the Android Keystore
and is never shown again. Plain HTTP is accepted only for `localhost`, so a hub without TLS is
reached through `adb reverse` or an SSH tunnel.

Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how it works and
[docs/features/](docs/features/) for each screen.

Contributors and AI agents: read [AGENTS.md](AGENTS.md) first.
Security issues: see [SECURITY.md](SECURITY.md).
