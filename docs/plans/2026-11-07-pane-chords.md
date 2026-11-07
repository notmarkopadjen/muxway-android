# Feature: Ctrl+] and Ctrl+[ cycle the panes from a hardware keyboard

> Status: written 2026-11-07 for
> [#96](https://github.com/notmarkopadjen/muxway-android/issues/96),
> parity with the core's PR 303 (`docs/plans/2026-11-05-fit-shrink-warmup-chords.md`
> there, `docs/ui-guideline.md` §1.6, the *next / previous pane* and
> *direct typing* rows), merged 2026-11-05 at `f3102cc`. Written and
> implemented the same day on the branch of `2026-11-07-own-pane.md`;
> the owner was away, so the diff is the owner's to review in the pull
> request, which is not merged until then.

## 1. Goal

The web client takes `Ctrl+]` and `Ctrl+[` as the next and the previous
pane from anywhere: the page, the line, direct typing and the window
view, coming round at the ends, direct typing staying on so the next
pane is typed into at once; the chords never reach the pane, and
whoever needs the literal keys (`Ctrl+[` is a terminal's `Escape`,
`Ctrl+]` telnet's escape) sends them from the virtual keyboard. On the
phone the same two chords, pressed on a hardware keyboard while typing
direct, go to the pane today as `C-]` and `C-[`, and the only way
between the tiles is a tap or a swipe, which a hand on a keyboard does
not want.

After this change, with the *hardware keyboard* setting on, `Ctrl+]`
moves the focus to the next tile and `Ctrl+[` to the previous, coming
round at the ends; with one tile open the strip's next or previous
awake pane takes the tile, as the swipe does (parked panes skipped).
Direct typing stays on, so the next pane is typed into at once; from
the line, the line keeps the focus and follows the pane. The chords
never reach the pane, read-only or not; the virtual keyboard sends the
keys themselves as now. The soft keyboard has no such chords.

Out of scope: the web's `]` and `[` without Ctrl (on the phone a bare
bracket is text for the pane), the window view (the phone has none),
and any other chord.

## 2. Design

`Chords.pane` in the protocol module decides, from the modifiers and the
key, whether a press is a pane chord: Ctrl held, no Alt, Shift or Meta,
and the key `]` (by its code, or the character it produces on a layout
where the bracket is elsewhere) for `+1`, `[` for `-1`, `0` otherwise.
`HardKeys.paneChord` reads a Compose key event into it.

`ViewScreen.hardKey` asks for the chord first, before the writable check
and before anything is named or typed, so a chord on a read-only tile
moves too and nothing of it is ever sent; it calls `neighbour(step)`,
the swipe's function (several tiles: the focus moves; one tile: the
strip's neighbour, parked panes passed over, coming round at the ends),
and consumes the event. When the line had the focus it asks for it
again (`fieldFocus.request()`), so the line follows the pane as the
web's does; otherwise the view keeps the focus and direct typing stays
on for the new pane (off by itself when that pane is read-only, as
today).

```mermaid
sequenceDiagram
    participant K as hardware keyboard
    participant V as ViewScreen
    participant M as AppModel
    participant H as hub
    K->>V: Ctrl+] (key down)
    V->>V: Chords.pane → +1; nothing to the pane
    V->>V: neighbour(+1): focusedIndex = (index + 1) mod tiles
    K->>V: e (key down, direct typing)
    V->>M: type(next pane, "e")
    M->>H: POST /v1/panes/{next}/input {"text":"e"}
```

## 3. Protocol and API changes

None. Nothing new is sent; two chords that used to go as `C-]` and
`C-[` from direct typing no longer do.

## 4. Security analysis

- **Abuse.** Nothing: the chords are read on the phone and send
  nothing. A key the grammar has (`C-]`) is no longer sendable from a
  hardware keyboard; it stays on the virtual keyboard, where the person
  chooses it on purpose.
- **Input across the boundary.** None crosses: a key event is the
  device's.
- **Logging.** Nothing.
- **Dependencies.** None added.

## 5. Privacy check

The plan, the diff, the docs and the tests name `alice-laptop` and
`work`. `scripts/prepush-check.sh` before the push.

## 6. Test plan

| # | Test | Kind | Expected |
|---|------|------|----------|
| 1 | `ChordsTest`: Ctrl+] is `+1`, Ctrl+[ is `-1`, by code and by character; with Alt, Shift or Meta, or without Ctrl, `0`; any other key `0` | unit | as said |
| 2 | `FlowTest`: with the hardware keyboard setting on, two tiles open; Ctrl+] from the view focuses the second (the line's hint names it), Ctrl+] again comes round to the first, Ctrl+[ goes to the second; a word typed direct lands in the second's screen and no `^]` or `^[` does; from the line the chord moves the pane and the line keeps the focus | UI (`chords`) | assertions on texts and tags |
| 3 | the full suite, `MUXWAY_UI=0`, recorded once for the three plans of the branch | e2e | green, run issue filed |

No end-to-end test: nothing reaches the hub. This machine has no device,
so the UI step is written and compiled and waits for the owner's phone
or the emulator.

## 7. Documentation

- `docs/features/pane-view.md`: the swipe's and direct typing's
  bullets name the chords; `HelpSheet` gains the row.
- The core's `docs/ui-guideline.md` §1.6 *next / previous pane* row,
  Android column, gets the chords with the owner's guideline branch;
  the core repository is not edited from here.

## 8. Implementation steps

1. `protocol`: `Chords` and `ChordsTest`.
2. `app`: `HardKeys.paneChord`, the handler in `ViewScreen.hardKey`,
   the help row.
3. `FlowTest` step.
4. Docs.

---

## Review (after implementation)

- [ ] Diff matches the plan, deviations explained here
- [ ] Security rules (AGENTS.md section 2) hold
- [ ] Privacy check passed: `scripts/prepush-check.sh` clean and diff read
- [ ] Docs and diagrams updated

## Test run

(to come, shared with the branch's other plans)
