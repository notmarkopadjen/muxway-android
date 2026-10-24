# Architecture

The Android client speaks [protocol v1](https://github.com/notmarkopadjen/muxway/blob/main/docs/protocol/v1.md)
of the muxway hub, JSON mode, and nothing else. Kotlin, Jetpack Compose,
minimum Android 8.0 (API 26).

## Layers

```mermaid
flowchart LR
    subgraph app[Android app]
        UI[ui: Connect, Scan, Panes, View; Theme and widgets after the web client] --> M[AppModel]
        M --> P[protocol: Api, Json, Input, Ansi, Setup, HubUrl]
        M --> S[store: SettingsStore, Vault]
        S --> KS[(Android Keystore)]
    end
    P -- "HTTPS, Authorization: Bearer" --> H[(hub)]
```

- **protocol** has no Android dependency. `Api` makes one blocking request
  per call with `HttpURLConnection`; `Json` reads the hub's answers
  defensively; `Input` filters what is typed; `Ansi` keeps colour and
  attribute sequences out of pane content and drops every other escape,
  then turns the text into styled rows; `HubUrl` decides which URLs are
  acceptable. The end-to-end suite runs this layer on
  the JVM against a real hub.
- **store** keeps settings in app-private preferences and the token in
  `Vault`: AES-256-GCM with a key generated inside the Keystore.
- **AppModel** is the one view model: which screen is open, the pane list,
  one `PaneState` per open pane, and the calls into `Api` on the IO
  dispatcher.
- **ui** is three Compose screens and a handful of widgets drawn as a
  terminal: monospace, box-drawing frames, square corners.

## Screens

```mermaid
stateDiagram-v2
    [*] --> Connect: no hub or token stored
    [*] --> Panes: stored
    Connect --> Panes: save and connect
    Panes --> View: tap one pane
    Panes --> View: hold several, open side by side
    View --> Panes: panes / back
    Panes --> Connect: settings
    View --> Connect: settings
    View --> Connect: token refused
    Panes --> Connect: token refused
```

See [features/connect.md](features/connect.md), [features/panes.md](features/panes.md)
and [features/pane-view.md](features/pane-view.md).

## Flows

### Reading and typing

```mermaid
sequenceDiagram
    participant V as View screen
    participant M as AppModel
    participant H as hub
    loop every N seconds while the screen is resumed
        V->>M: pollOnce(address)
        M->>H: GET /v1/panes/{address}?lines=N, If-None-Match
        H-->>M: 200 pane + content, or 304
        M-->>V: PaneState: content, updated, error
    end
    V->>M: type(address, text, enter)
    M->>H: POST .../input {"text": ...}
    M->>H: POST .../input {"key": "Enter"}
    H-->>M: 204
    M->>M: forget the ETag, pollOnce
```

### The pane list and notifications

```mermaid
sequenceDiagram
    participant L as Panes screen
    participant M as AppModel
    participant H as hub
    L->>M: refreshList
    M->>H: GET /v1/hosts, GET /v1/panes
    H-->>M: hosts, panes with mode and attention
    loop while the screen is resumed
        M->>H: GET /v1/events?after=cursor&wait=30
        H-->>M: events (or empty after 30 s), next
        M->>M: any event: refreshList
    end
```

## Security

- The token is stored only through `Vault`; the ciphertext is useless off
  the device. Backup and device transfer are off in the manifest.
- `Authorization: Bearer` on every request, never a URL parameter. No
  request, token or pane content is logged.
- HTTPS with the system trust store. The network security configuration
  allows cleartext only to `localhost`, `127.0.0.1` and `10.0.2.2`, and
  `HubUrl` refuses anything else before it is saved.
- Bodies are read up to 2 MiB; JSON that does not fit the protocol is "The
  hub's answer made no sense", not a crash. Control characters and escape
  sequences are removed from everything shown; pane content keeps only
  SGR (colour and attribute) sequences, which are parsed as bounded
  integers and never interpreted beyond that.
- Typed text loses control characters and is capped at 4096 bytes; keys
  come from the protocol's fixed list. A read-only pane has no input bar.
- Redirects are not followed, so a proxy cannot send the token elsewhere.
- Dependencies: AndroidX core, activity, lifecycle, Compose BOM 2024.12.01,
  Kotlin coroutines, CameraX 1.4.1 and ZXing core 3.5.3 (both for scanning
  the connect code only), all pinned in `app/build.gradle.kts`. JSON is
  the platform's `org.json`.
