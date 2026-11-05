# Feature: the file sheet names a file the host found elsewhere in the pane's repository

> Status: written 2026-11-05 for
> [#92](https://github.com/notmarkopadjen/muxway-android/issues/92),
> parity with the core's PR 296 (`docs/plans/2026-11-05-relative-paths-find-their-file.md`
> and `docs/features/preview.md` there, merged 2026-11-05 at `7f094d3`).
> Waits for the owner's approval in the console.

## 1. Goal

Since the core's PR 296 a relative path a pane printed is looked for
beyond the pane's directory when it is not there: up the tree to the
repository's top, then in the repository's other worktrees (a coding
agent working in a worktree prints paths relative to it while its
pane's shell sits in the main checkout). The lookup lives in the agent,
so the phone's file sheet opens such a file already. What the phone
lacks is the web's note: when the file came from elsewhere, the hub's
answer carries a `Muxway-File` header with the file's path on the host,
and the web's file window names it after the printed path, dim,
`found /home/alice/src/feature/docs/a.md` (guideline §1.6, *see a file
printed in a pane*), so the reader sees which tree answered.

After this change the file sheet shows the same under its title, dim,
when the header is present, and nothing when it is not. Out of scope:
the lookup itself (the agent's), the inbox (its files carry no header),
the watch (no file sheet there), and any change to how a path becomes a
link.

## 2. Design

### The protocol layer

`Api.Fetched` gains `found`: the `Muxway-File` header's value, empty
when the header is absent. `Api.fetch` reads it as it reads an ETag,
defensively: `Input.printable` over the value, kept only when it is one
to 1024 characters (the bound `Sent.path` has), empty otherwise. The
inbox's `readFile` goes through the same code and gets an empty `found`,
since the hub sends the header on the preview alone.

### The sheet

`FileSheet` draws one more row between the bar and the rule, only on
the *file* side and only when `got.found` is not empty: `found ` dim,
then the path dim, wrapped to at most three lines, tagged `found` for
the UI test. A change's diff side, an inbox file and a file of the
pane's own directory show no row, as today.

```
┌─ file ─ docs/plans/a.md ──────── 1.2 KiB [plain] [⤓] [×] ┐
│ found /home/alice/src/feature/docs/plans/a.md            │
│ ─────────────────────────────────────────────────────────│
│ # Feature: a plan a                                      │
│ …                                                        │
└──────────────────────────────────────────────────────────┘
```

```mermaid
sequenceDiagram
    participant O as owner (phone)
    participant S as FileSheet
    participant H as hub
    participant A as agent
    O->>S: tap docs/plans/a.md in a pane whose shell is in the main checkout
    S->>H: GET /v1/panes/alice-laptop/work/0.0/preview?path=docs%2Fplans%2Fa.md
    H->>A: read-path
    A->>A: not under the pane's directory; up the tree; the feature worktree has it
    A-->>H: the bytes, path=/home/alice/src/feature/docs/plans/a.md
    H-->>S: 200, Muxway-File: /home/alice/src/feature/docs/plans/a.md
    S->>S: Fetched(type, bytes, found); the row under the bar
    O->>S: tap docs/top.md, the pane's own
    S->>H: GET …/preview?path=docs%2Ftop.md
    H-->>S: 200, no Muxway-File
    S->>S: found empty; no row
```

## 3. Protocol and API changes

None to the protocol. Newly read by this client, v1 and compatible: the
`Muxway-File` answer header of `GET /v1/panes/{address}/preview`
(`docs/protocol/v1.md` in the core). A hub older than the lookup sends
no header and the sheet shows no row. `docs/ARCHITECTURE.md` names the
header in the preview row.

## 4. Security analysis

- **Abuse.** Nothing new reaches the host: the request is the one the
  sheet sends today, the grants, the roots, the secret names and the
  rate are the hub's and the agent's to check, and the header names a
  file only with its bytes, which the caller could then read anyway.
- **Input across the boundary.** The header is text from the hub: it
  is cleaned of control characters, bounded at 1024 characters, shown
  as text in a `Text` and never used as a path by the phone (no file
  is opened by it, nothing is typed from it). A hub that sends a huge
  or odd header gets no row.
- **Logging.** Nothing new is logged on the phone. The hub and the
  agent audit the found path by the core's rules; the end-to-end run's
  secret search covers their logs as before.
- **Dependencies.** None added.

## 5. Privacy check

The plan, the diff, the docs and the tests name `hub.example.com`,
`alice-laptop`, `work`, `alice` and paths under `/home/alice`; the test
repositories are made in a temporary directory. The UI run uses the
test hub's synthetic repository and a worktree of it. The screenshot
shows the test environment only. `scripts/prepush-check.sh` before the
push.

## 6. Test plan

| # | Test | Kind | Expected |
|---|------|------|----------|
| 1 | `E2ETest s41`: with a repository under the root, a worktree under it and one outside; a reader asks for a worktree's file; a writer asks for the outside worktree's file, a `key.pem` only a worktree has, and `.git/worktrees/feature/gitdir` by name; a raw request for each refusal | e2e-security | `read_only`; `not_found` three times, the raw answers carry no `Muxway-File` |
| 2 | `E2ETest f41`: the pane in the main checkout asks for `docs/plans/a.md` that only the worktree has; `docs/both.md` that both have; from `main/web`, `docs/top.md` | e2e | the worktree's bytes and `found` = its path; the main copy and `found` empty; the top's bytes and `found` = the main checkout's path |
| 3 | `ApiTest`: `previewFile` asks `GET …/preview?path=` with the path encoded; a `Muxway-File` header comes back as `found`; no header is empty; a header with a control character or over 1024 characters is empty; `readFile` has an empty `found` | unit | as said |
| 4 | `FlowTest`: the pane prints `docs/side.md` that only the worktree `side` has; a tap opens the sheet with the file and the `found` row naming `…/side/docs/side.md`; back; `notes.txt` opens with no `found` row | UI (`file-found`) | assertions on the texts |
| 5 | the full suite, `MUXWAY_UI=0`, recorded | e2e | green, run issue filed |

This machine has no device, so the UI step is written and waits for the
owner's phone or the emulator; `scripts/ui-tests.sh` gains the worktree.

## 7. Documentation

- The core's `docs/ui-guideline.md` §1.6 *see a file printed in a pane*:
  the Android column's *(to come)* becomes the row under the bar: a
  branch in the core checkout for the owner to push.
- `docs/features/pane-view.md`: the file sheet's paragraph names the
  row; `docs/ARCHITECTURE.md`: the header in the preview row;
  `docs/testing.md`: the unit suite's `Api` line.

## 8. Implementation steps

1. `protocol`: `Fetched.found`, read in `fetch`; `ApiTest`.
2. `app`: the row in `FileSheet`; the version 0.22.1 (54).
3. `E2ETest`: the worktree fixture, s41, f41; `FlowTest` and
   `scripts/ui-tests.sh`: the UI step and its worktree.
4. Docs: the pages above, the guideline in the core.
5. The e2e run, recorded; the pull request, a draft.

---

## Review (after implementation)

- [ ] Diff matches the plan, deviations explained here
- [ ] Security rules (AGENTS.md section 2) hold
- [ ] Privacy check passed: `scripts/prepush-check.sh` clean and diff read
- [ ] Docs and diagrams updated

## Test run

To come.
