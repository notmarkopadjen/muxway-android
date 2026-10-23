# Feature: pane titles, a wrap toggle and the Claude lens

> Polish after the second hands-on test.

## 1. Goal

1. Tabs, headers and the pane list show the pane's **title** when the
   program running in it set one (AI coding CLIs set it to the session's
   name), instead of only `session/window.pane` and the command.
2. A **wrap** toggle in the pane view: with it on, rows reflow to the
   columns that fit at the current font size, so pinch zoom changes the
   margins rather than panning a fixed-width page. Off by default, because
   full-screen programs need their own width.

3. The **Claude Code lens** (`docs/features/lenses.md`): a pane running
   Claude Code shows `⟳ working` while it is active and `! ? z ✓` with the
   notification's kind when it waits for its owner, in tabs, headers and
   the list, so the pane that needs attention stands out. An open tab or
   focused header that waits is inverted in the attention colour.

Why the font differs between panes: it is sized so the pane's own width
fits the tile. A 194-column pane comes out half the size of a 96-column
one. That is by design; wrap mode is the way out for text that may reflow.

## 2. Design

- `Pane.name`: the title when it is not empty, else the label (alias or
  `session/window.pane`). Titles already pass `Input.printable`.
- Tabs show `name`; the header shows `name · command · mode`; the list row
  shows the label and then the title or, without one, the command.
- Wrap on: font size is the text setting times the zoom, columns are what
  fits the tile at that size, rows are cut there, no sideways scroll.
  Wrap off: unchanged (fit to the pane's width, pinch zooms, pans).

```mermaid
flowchart LR
    C[content] --> W{wrap?}
    W -- off --> F[cols = pane width; size = min(text, fit) × zoom; pan]
    W -- on --> R[size = text × zoom; cols = tile / glyph; reflow]
```

- `Lens` (protocol package, pure Kotlin): `isClaude` from command or
  title mark, `status` from the notification and `activity` (within 30 s
  is working), `glyph`, `name`. A five-second ticker in the screens ages
  the working mark out.

## 3. Protocol and API changes

None; `title` and `activity` are already in the listing. The owner asked
for the phone to resize the pane to its own screen; the protocol has no
resize and a tmux pane's size is shared with every attached terminal, so
that is a core decision, noted for the core repository.

## 4. Security analysis

The title is pane-controlled text and was already filtered of control
characters and escapes on the way in; it is shown as text, never
interpreted. Nothing else changes.

## 5. Privacy check

Fixtures use synthetic titles.

## 6. Test plan

| # | Test | Kind | Expected |
|---|------|------|----------|
| 1 | Title with escapes and control characters in the listing | unit | `name` is the cleaned title |
| 2 | Empty title | unit | `name` falls back to the label |
| 3 | Lens: command or mark, recent activity, notification kinds, mark stripping | unit | states and glyphs as the table says |
| 4 | Wrap toggle and lens marks on a device | UI | needs a device |

## 7. Documentation

`docs/features/pane-view.md`, `docs/features/panes.md`.

## 8. Implementation steps

1. `Pane.name` and its test.
2. Tabs, header, list row.
3. Wrap toggle.
4. Docs, run, build, hand over.

---

## Review (after implementation)

- [x] Diff matches the plan
- [x] Security rules hold: titles were filtered already
- [x] Privacy check: `scripts/prepush-check.sh` clean
- [x] Docs updated

## Test run

Local run `test-runs/20261023-235714-5710711`: **passed**, 59 tests (15 end-to-end with the secret search, 44 unit). The UI test was skipped for lack of a device.
