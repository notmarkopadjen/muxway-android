# Look and motion

The app shares the web client's design tokens, so the two look like one
product: phosphor amber on near-black by default, green, and paper for
daylight. The brand and panel titles are set in Press Start 2P, the web
client's pixel font; everything else in DejaVu Sans Mono.

| Token | Amber | Green | Paper |
|---|---|---|---|
| background | `#0a0603` | `#050806` | `#f1ead8` |
| panel | `#140b05` | `#0a120c` | `#e8dfc8` |
| ink | `#ffb000` | `#39ff14` | `#1f2a1f` |
| dim · faint | `#a06c00` · `#4a3200` | `#1f8f14` · `#0f4a0c` | `#5a6a5a` · `#b9b39f` |
| attention · alert | the ink, inverted · `#ff3b3b` | the ink, inverted · `#ff3b3b` | the ink, inverted · `#a3161b` |
| attention, on the watch only | `#ffd36b` | `#ffb000` | `#9a5b00` |

The watch is the one exception to the inverted attention, decided by the
owner on 2026-10-25: a face is always on, where a filled ink bar is
burn-in and battery, and a complication is drawn by whatever face it sits
in, which honours no inversion of ours. Its face, its complications and
its app paint a waiting pane in the hue above; working stays dim and
quiet plain, so the three states stay apart (`wear/Palette.kt`, the face's
`theme` colour options, and `ui-guideline.md` §1.5).

Effects, on by default and off with the **effects** setting:

- text glows faintly, the brand and titles more;
- scanlines and a vignette lie over the screen (drawn, not composed, so
  they cost nothing to lay out);
- the picture powers on like a tube when the app starts;
- a mark that waits for you pulses;
- a selected button is inverted and its border lit;
- code is coloured as the browser colours it (`protocol/Code.kt`): a
  fenced block's language in Markdown and a file's extension or name in
  the sheet pick a small scanner that tells comments (dim, italic),
  strings, numbers, keywords (bold too) and names apart, each in a hue
  of the palette's `Syntax` set (the browser's tokens: amber strings
  pale green, numbers cyan, keywords orange, names violet; green strings
  yellow; paper green, blue, brown, violet), glowing in its hue where
  text glows; punctuation is dim; an unknown language is plain. Code is
  the one place a second hue carries meaning (the owner's decision of
  2026-10-30);
- the key row's groups and the command row's tones carry a discreet
  tint on the border and the ground, never the ink: editing, direction,
  control and function keys; danger, start, info and act commands. The
  values are the web's tint tokens, per theme, in `ui/Theme.kt`.

```
┌─ muxway ─┐ panes ─────────────────── [refresh] [settings]
 ─ alice-laptop ─────────────────────────────────────────
│ * build server        work/0.0 · claude     ⟳ working  │
│ * ! review            work/0.1 · claude     ! permission│
 ─────────────────────────────────────────────────────────
```

Everything is drawn from `Palette` in `ui/Theme.kt`; the web's tokens are
in the core repository under `web/src/styles/tokens.css`.

## Security considerations

Colours, fonts and effects only: nothing on this page reads input, keeps
data or talks to the hub. What the screen may give away is covered where
it arises: the screenshot block in [pane-view.md](pane-view.md), the
notification text setting in [notifications.md](notifications.md).
