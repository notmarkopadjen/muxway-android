```
┌─ muxway-android ─┐
```

Android client for [muxway](https://github.com/notmarkopadjen/muxway): your tmux panes on a phone, one per screen or side by side.

> Early development. A first version with the basics works: connect with a token, list panes, open one or several, read, type, send keys, see which pane calls for attention.

## Install

Build it yourself, Android 8.0 or newer:

```sh
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

The build needs JDK 17 and an Android SDK with platform 35 (`sdk.dir` in
`local.properties`, or `ANDROID_HOME`).

## Connect

On the hub, make a token for the phone:

```sh
muxway hub token create --name phone --read 'alice-laptop/*' --write 'alice-laptop/@build'
```

In the app: hub URL (`https://hub.example.com`), paste the token, **save and
connect**. The token goes into the Android Keystore and is never shown
again. Plain HTTP is accepted only for `localhost`, so a hub without TLS is
reached through `adb reverse` or an SSH tunnel.

Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how it works and
[docs/features/](docs/features/) for each screen.

Contributors and AI agents: read [AGENTS.md](AGENTS.md) first.
Security issues: see [SECURITY.md](SECURITY.md).
