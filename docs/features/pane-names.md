# Pane names

The owner's name for a pane, the folder it works in, and the name sheet
that shows both and lets a writer set the name. Parity with the core's
web client (`docs/ui-guideline.md` §1.1, §1.4 *header*, §1.6 *name the
pane*).

## What it does

- **The alias is the name.** A pane has an alias when its owner exposed
  it with `muxway expose --name review` or named it from a client. When
  it has one, the alias is what the phone shows first wherever a pane is
  named: the tab, the tile header, the list row, the inbox, the add menu,
  a notification and the watch. What the program says about itself
  follows as the status, in italics and dimmer: `✳ review · fixing the
  build` for a Claude pane, `notes · vim · notes.md` for vim, `build ·
  bash` for a shell. The tab shows the alias alone. Without an alias
  nothing changes: Claude's codename, the command, the title (see
  [lenses](lenses.md)).
- **The folder.** The listing carries `cwd` for a pane whose directory
  lies under one of the host's preview roots, for a writer, whatever the
  program (a Claude pane's is the checkout it was started in). The tile
  header shows its last segment in italics after the name, before the
  status; the name sheet shows the whole path. A pane outside the roots,
  a host without roots and a read-only token show nothing.
- **The name sheet.** A tap on the focused pane's tab, or on the name in
  its header, opens it. It is the phone's tooltip: the program and its
  status (`Claude · fixing the build`), the place with the size and the
  mode (`alice-laptop/work/1.0 · 126×55 · write`), the folder's path.
  For a writable pane on a host whose agent names panes (`rename` in
  `GET /v1/hosts`) it has a field: what is typed becomes a name as it
  goes (lower case, a space or `_` to `-`, anything else dropped, 32 at
  most; `Fix Tests` reads `fix-tests`), the line under it says how agents
  reach the pane (`alice-laptop/@fix-tests`) or the hub's refusal.
  `name` or Enter saves, `clear` takes the name away, `cancel` or back
  leaves it. On a read-only pane, or a host that does not name, the sheet
  says so and has no field.

```mermaid
sequenceDiagram
    participant O as owner (phone)
    participant M as AppModel
    participant H as hub
    participant A as agent on alice-laptop
    O->>O: tap on the focused tab, "Fix Tests" typed, shown as fix-tests
    O->>M: rename(key, "fix-tests")
    M->>H: POST /v1/panes/alice-laptop/work/1.0/alias {"alias": "fix-tests"}
    H->>A: alias op (write grant, pane writable, name well formed and free)
    A-->>H: ok, audit record
    H-->>M: 200 {"pane": {...}}
    M->>M: the tile's pane is the answer's; the strip and the header redraw
    M->>H: GET /v1/hosts, GET /v1/panes
```

## How to use it

1. Open a pane. Its header shows the name and, under a preview root,
   the folder in italics.
2. Tap its tab once more, or the name in its header. The sheet opens.
3. Type a name. Enter or `name` saves it; the tab, the header and the
   list now show it, and agents can address the pane as
   `alice-laptop/@name`.
4. `clear` in the sheet takes the name away; the pane goes back to its
   codename or command.

## Security considerations

- A rename changes how a pane is addressed, and a grant that names an
  alias follows the alias. The hub refuses a name another pane of the
  host has, so a pane under a grant cannot be shadowed, and a reader gets
  `403 read_only`; a writer can do the same from the pane's shell with
  `muxway expose --name` already. The phone never renames on its own.
- The name needs the write grant and a writable pane, checked by the hub
  and the agent. The phone shows the field only where the listing says
  `write` and the host says `rename`, and acts on the hub's answer, not
  its own guess; a hub or host older than the endpoint answers
  `404 not_found` and the sheet says the host does not name panes.
- The alias is slugged as typed and matched against `[a-z0-9-]{0,32}`
  before the request leaves the phone (`Api.rename`); the hub and the
  agent check it again. `400 bad_alias`, `403 read_only`, `404
  not_found` and `409 alias_taken` are shown under the field in words,
  never echoed as the hub sent them.
- `cwd` is a path on the host, sent only to a writer under a preview
  root. The phone shows the last segment in the header and the path in
  the sheet, derives nothing, and never writes it to a log; the listing
  already drops a `cwd` with control characters or over 1024 bytes.
- Nothing is logged on the phone. The hub and the agent audit the rename
  with the actor, the pane and the old and new name.

## Tests

- `LensTest`: the alias first on Claude, Codex, vim and a shell; the
  slug; the folder. `JsonTest`: `rename` on a host, the alias body.
- `E2ETest s30`, `s30b`: a reader, a read-only pane, malformed names raw
  and from `Api.rename`, a taken name. `f31`: the alias listed, the pane
  read by `@review`, the folder for `cat` under a root, the name cleared.
- `FlowTest`: the sheet from the focused tab with the folder's path,
  `Fix Tests` typed and saved, the tab and the header named, the sheet
  from the header's name, cleared (`name-sheet`, `named`,
  `name-cleared`).
