# Feature: the client touched last wins, and the header says who holds the size

> Status: written 2026-11-09 from the owner's decision in the console,
> after `2026-11-09-held-scale-and-ghost-right.md` and its amendment
> left a pane at 197 columns on a phone that fits 49, because the
> owner's browser fitted the same pane. The protocol half is the core's
> `docs/plans/2026-11-09-size-holder.md`, merged and deployed first.
> Waiting for the owner's approval; no code before it.

## 1. Goal

When another client holds the size of a pane's window, the phone says
so and takes the size back the moment the owner uses the pane here.
Today the tile reads *held* for every cause alike, sends its fit once
more after twenty seconds and then never again, and on a narrow phone
a wide pane is specks or re-cut rows until the view is left and
re-entered. After this change:

- the header reads `197×42 held by web · Chrome` when another client's
  fit holds the window, `held` alone when nobody does (the host's floor,
  a terminal at the host, another tile of this view), and nothing when
  the host took this phone's size;
- the phone sends its fit again, once, when the owner interacts with a
  held pane here: text or a key sent to it, a tap on its tile, the view
  opened on it, the on-screen keyboard going away with it focused; at
  most once every two seconds per pane; never while the holder is this
  phone, or nobody;
- a watch or another phone on the same token is another client: the
  label tells them apart.

Out of scope: the readable-held-tile rule (a held tile under about 6 sp
re-wrapping instead), which the owner did not take; the watch, which
does not fit panes.

## 2. Design

`Pane` in the protocol module gains `sizedBy: SizedBy?` (`token`,
`client`, `at`, `mine`), read from the listing's `sized_by` and absent
from an older hub. `Api.resize` sends `"client"`: the device's model
(`Build.MODEL`), ASCII printable only, cut to 32 characters, `"phone"`
when nothing is left. The model holds the label once.

`TileFit.holder(pane, mine)` decides what the header says: `Held.NONE`
(the size is this phone's), `Held.HOST` (held, nobody named, or the
holder is this client), `Held.OTHER(token, client)`. The header draws
`held` or `held by <token> · <client>` in the alert colour; the client
part is left out when the label is empty.

`Refit.wanted(held, lastRefitAt, now)` decides whether an interaction
sends the fit again: only for `Held.OTHER`, only two seconds after the
last. `PaneTile` calls `model.fit(..., again = true)` through it from
the four interactions above; the fit's own debounce and once-per-size
rule stay, so a size the host already has is not sent twice.

```mermaid
sequenceDiagram
    participant O as Owner
    participant T as Tile (phone)
    participant H as Hub
    T->>H: GET /v1/panes
    H-->>T: pane 197×42, sized_by {token: web, client: Chrome, mine: false}
    T->>T: header "197×42 held by web · Chrome"; nothing sent
    O->>T: types "go" and sends
    T->>H: POST input
    T->>H: POST resize {cols:49, rows:48, scope:window, zoom:true, client:"Pixel 9"}
    H-->>T: pane 49×48, sized_by {mine: true}
    T->>T: header "49×48"; rows fill the tile
```

## 3. Protocol and API changes

None of this repository's own: it reads `sized_by` and sends `client`
as the core's plan defines them. `docs/ARCHITECTURE.md` lists the field
among the ones the app uses.

## 4. Security analysis

- The label the phone sends is the device model, ASCII, 32 characters
  at most, validated by the hub; it is no secret and grants nothing.
- What the phone shows is the token name and label the hub gives, drawn
  as text in the header; nothing is interpreted.
- A refit is one more resize on an interaction the owner made, gated to
  one per two seconds per pane, inside the host's bucket; the phone
  never sends on its own while idle.
- Nothing logged, no dependency.

## 5. Privacy check

Tests use the test hub's `alice-laptop`, tokens `phone` and `web`, the
label `Chrome`; the owner's screenshots are described, not committed.

## 6. Test plan

| # | Test | Kind | Expected |
|---|------|------|----------|
| 1 | The label is ASCII printable, 1 to 32 characters, from any `Build.MODEL` (empty, long, non-ASCII) | unit | as stated |
| 2 | A `sized_by` with markup characters is drawn as text in the header | UI | no interpretation |
| 3 | `TileFit.holder`: mine → NONE; absent with a drift → HOST; another token → OTHER; same token, other label → OTHER; same token and label → HOST | unit | as stated |
| 4 | `Refit.wanted`: OTHER after two seconds → true; within two seconds, NONE, HOST → false | unit | as stated |
| 5 | FlowTest: the script fits `work/0.0` as the `web` token with label `Chrome` to 120×24; the phone's tile reads `held by web · Chrome`; typing a line makes the header read the phone's size with no *held* | UI, screenshots `held-by`, `held-by-taken` | as stated |
| 6 | FlowTest: with nobody holding, a floor-held tile still reads `held` alone | UI (the five-pane window step) | as stated |

## 7. Documentation

`docs/features/pane-view.md` (the fit bullet: *held by*, the rule),
`docs/ARCHITECTURE.md` (the field), `docs/testing.md` (the steps).

## 8. Implementation steps

1. Protocol module: `SizedBy`, the label; unit tests 1 and 3.
2. `TileFit.holder`, `Refit`; tests 3 and 4.
3. `PaneTile` header and the four interactions; `Api.resize` label.
4. FlowTest steps 5 and 6, the fixture's second token with the `web`
   name; docs; the run; the pull request, after the core is deployed.
