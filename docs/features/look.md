# Look and motion

The app shares the web client's design tokens, so the two look like one
product: phosphor green on near-black by default, amber, and paper for
daylight. The brand and panel titles are set in Press Start 2P, the web
client's pixel font; everything else in DejaVu Sans Mono.

| Token | Green | Amber | Paper |
|---|---|---|---|
| background | `#050806` | `#0a0603` | `#f1ead8` |
| panel | `#0a120c` | `#140b05` | `#e8dfc8` |
| ink | `#39ff14` | `#ffb000` | `#1f2a1f` |
| dim · faint | `#1f8f14` · `#0f4a0c` | `#a06c00` · `#4a3200` | `#5a6a5a` · `#b9b39f` |
| attention · alert | the ink, inverted · `#ff3b3b` | the ink, inverted · `#ff3b3b` | the ink, inverted · `#a3161b` |

Effects, on by default and off with the **effects** setting:

- text glows faintly, the brand and titles more;
- scanlines and a vignette lie over the screen (drawn, not composed, so
  they cost nothing to lay out);
- the picture powers on like a tube when the app starts;
- a mark that waits for you pulses;
- a selected button is inverted and its border lit.

```
┌─ muxway ─┐ panes ─────────────────── [refresh] [settings]
 ─ alice-laptop ─────────────────────────────────────────
│ * build server        work/0.0 · claude     ⟳ working  │
│ * ! review            work/0.1 · claude     ! permission│
 ─────────────────────────────────────────────────────────
```

Everything is drawn from `Palette` in `ui/Theme.kt`; the web's tokens are
in the core repository under `web/src/styles/tokens.css`.
