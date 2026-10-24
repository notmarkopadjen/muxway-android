# Feature: the command row: Claude's slash commands, a shell's starters

> Status: asked for by the owner on 2026-10-24, together with the web
> client's row; implemented the same day. The shared design is in the
> core's `docs/plans/2026-10-24-claude-commands.md` and
> `docs/ui-guideline.md` §1.4.

## 1. Goal

Claude Code is driven by slash commands as much as by keys: `/clear`,
`/compact`, `/rename` and a few more are typed many times a day, and on
a phone each is a line to type and send. A shell is driven by a few
starters: `exit`, `htop`, `claude` and the other agents, and whatever
was typed last. A **second row** under the key row, one row that
scrolls sideways, offers one button per command for the program in the
focused pane: Claude's slash commands when the lens says the pane is
Claude Code's, a shell's starters and the lines sent to a shell lately
when the pane's command is a shell, nothing otherwise. A button sends
the command and `Enter`, the way *send* does; `/rename` fills the text
line instead and waits for the name.

Out of scope: the hub, the agent and the protocol (unchanged); a row
for other programs; configuring the lists; reading the host's shell
history (the recent lines are the phone's own).

## 2. Design

`Commands` in the protocol module holds the two lists, each entry with
its label, its text and whether it sends or fills, the same as the web
client's in the same order; `isShell` for the pane's command;
`forPane`, which picks the row and appends the recent lines not already
among the starters, labels cut at 24 characters; and `remembered`,
which puts a sent line first, once, keeps eight and drops a line over
200 characters. `SettingsStore` keeps the recent lines with the
settings; `AppModel.recent` holds them and `type(…, enter = true)` on
a shell pane adds to them. The pane view's `InputBar` draws the row
between the key row and the text line: the mark (Claude's, in the
Claude colour, or `$`), then a `LazyRow` of buttons with a bar before
the recent lines.

| Pane | Row |
|---|---|
| Claude Code (`Lens.isClaude`) | `/clear` `/compact` `/rename` (fills) `/model` `/resume` `/usage` `/context` `/help` |
| a shell (`bash` `zsh` `fish` `sh` `dash` `ash` `ksh` `tcsh` `csh`) | `exit` `htop` `claude` `claude -c` `codex` `gemini` `git status` `ls` `clear` │ recent lines |
| anything else | none |

```mermaid
flowchart LR
    P[focused pane] -->|Lens.isClaude| C{Claude?}
    C -- yes --> R[✳ slash commands]
    C -- no --> S{Commands.isShell?}
    S -- yes --> T[$ starters │ recent]
    S -- no --> N[key row, text line]
    R -->|/clear| G[type: text, then Enter]
    R -->|/rename| F[text line := "/rename ", focus]
    T -->|any| G
    G -->|shell pane| M[remembered, saved]
```

The palette gains `claude` (`#d97757`, every theme), the colour the web
client paints Claude's mark with.

## 3. Protocol and API changes

None.

## 4. Security analysis

- A button sends what a person could type: a fixed string from the
  app's own lists, or a line they sent before, and `Enter`, to the
  focused pane, over the same endpoint and write grant, audit-logged by
  the host as any input. Nothing from the pane, its title or the hub
  reaches the lists.
- A pane can make itself look like Claude's by its title, or like a
  shell by its command; that chooses the row, nothing more, and a tap
  then sends a fixed command or the person's own line. The lens already
  accepts this.
- `exit` ends the shell and the pane; it is first because the owner
  asked for it first, and a tap is deliberate.
- Read-only panes have no input bar at all, so no row.
- The recent lines sit in the app's private preferences with the
  settings, bounded to eight short lines; a line that carried a secret
  stays there until pushed out, which the docs say.
- Nothing is logged. No new dependencies.

## 5. Privacy check

Synthetic fixtures only; no screenshots outside the test runs.

## 6. Test plan

| # | Test | Kind | Expected |
|---|------|------|----------|
| 1 | `Commands` | unit | slash commands start with `/`, sending ones without a trailing space, filling ones with one; a shell's row starts with `exit` and sends every entry; another program gets none; recent lines deduplicated, newest first, at most eight, long labels cut, over-long lines dropped |
| 2 | the row on a shell pane | UI (`FlowTest`, with a device) | `exit` first; a sent line joins it and sends again when tapped |
| 3 | title `✳ building` | UI | the row becomes Claude's; `/usage` sends `/usage` and `Enter`; `/rename` fills the line |

The UI tests need a device; this machine has none, so 2 and 3 run on
the next device run (`scripts/e2e.sh` with adb). The web client's spec
`87-commands` covers the same behaviour against the same hub.

## 7. Documentation

`docs/features/pane-view.md` (Typing, security), `docs/features/lenses.md`,
this plan.

## 8. Implementation steps

1. `Palette.claude`, `Commands` and `CommandsTest`.
2. The store, the model, the row in `InputBar`.
3. Docs; version 0.7.1 (13).
4. Run the JVM suites, record the run, push.

---

## Review (after implementation)

- [ ] Diff matches the plan, deviations explained here
- [ ] Security rules (AGENTS.md section 2) hold
- [ ] Privacy check passed: `scripts/prepush-check.sh` clean and diff read
- [ ] Docs and diagrams updated

## Test run

To be filled in after the run.
