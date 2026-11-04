# Feature: share a pane read-only, by a link and a password

> Status: written 2026-11-04 for
> [#71](https://github.com/notmarkopadjen/muxway-android/issues/71),
> parity with the core's PR 243 (`docs/plans/2026-11-03-pane-share.md`
> and `docs/features/share.md` there, merged 2026-11-03); implemented
> the same day, on top of the mail sheet (#69). The diff is the owner's
> to review in the pull request.

## 1. Goal

The owner wants to let one person watch one pane for a while without
giving them a token. On the web since PR 243 a writable pane's `⇗`
button opens the share window: the pane's live shares with *revoke*, and
a form that makes one, a password and a lifetime; the hub answers a link
and shows the password once, the link as a QR code too, and the tile's
header carries `⇗ n` while a share lives. The phone has none of it: a
share made on the web is not even counted here.

After this change, on the phone:

- *⇗ share* in the pane menu (a tab or a tile's header held still) opens
  the **share sheet** for a writable pane; `⇗ n` in the tile's header,
  dim, before the changes count, while the pane has live shares made by
  this token, and a tap on it opens the sheet too;
- the sheet lists the pane's live shares (until when, how many watch,
  *revoke*), and the form: a password filled with a generated one
  (`xxxx-xxxx-xxxx` from `a-z2-7`, editable, 8 to 64 characters) and a
  lifetime (`1 h`, `24 h`, `7 d`), *create*;
- after *create* the sheet shows, once: the link, the password, the
  link as a QR code, and *copy link*, *copy password*, *share link*,
  *share image*, *save image*, *done*; the system share sheet carries
  the link, or the PNG of the code, to another app; the image holds the
  link only, so the link and the password travel apart; the password is
  nowhere after *done*;
- the entry hides on a hub older than shares (a `404` to
  `GET /v1/shares`), and is dim with the reason on a read-only pane.

Out of scope: observing a share (the link is a web page and opens in a
browser; the native clients never observe); the owner's *showing* (the
file or diff the web relays to observers); sharing from the watch, the
glasses or MIDP.

## 2. Design

### The protocol layer

`Share` is the hub's record as the sharer sees it: `id`, `url`,
`password` (only in the create answer, empty otherwise), `expires`,
`host`, `paneId`, `address`, `watching`. `Pane.shared` is the listing's
`shared`, the number of live shares the token made on it, 0 otherwise.
`Json.share`, `Json.shares` and `Json.shareBody` read and write them
defensively: an id outside `[A-Za-z0-9_-]{1,64}` drops the share, a URL
is kept only when it is `http(s)` and at most 1024 characters, texts
are printable. `Api.share(address, ttl, password)` posts
`{"ttl", "password"}` after checking them itself (60 to 604800 seconds,
8 to 64 printable characters), `Api.shares()` lists, `Api.revokeShare(id)`
deletes. `Shares` is the web's helpers: `suggest()` makes a password of
twelve letters and digits from `a-z2-7` in three groups out of
`SecureRandom` (60 bits), `LIFETIMES` are the three choices, `of(shares,
pane)` picks a pane's by host and pane id, `until(expires)` says when
it ends in the device's zone.

### The model

`Conn.sharing` is null until the connection's first listing probes
`GET /v1/shares` with the transcriber and the admin grant: `404` says
the hub is older than shares and the menu row hides; otherwise the
answer is `Conn.shares`. `fetchShares(hub)` asks again every ten seconds
while the sheet is open and after each create or revoke; `share(key,
ttl, password, made)` posts and hands the answer to the sheet, which
alone holds the password; `revokeShare(key, id)` deletes. Refusals land
in `shareError`: *twenty shares already: revoke one first* for
`too_many`, *the password needs 8 to 64 characters* for `bad_input`,
*only a writer of the pane may share it* for `read_only`, the hub's
word otherwise.

```mermaid
sequenceDiagram
    participant O as owner (phone)
    participant M as AppModel
    participant H as hub
    O->>O: hold the header, let go in place, ⇗ share
    M->>H: GET /v1/shares
    H-->>M: 200, the token's live shares
    O->>O: the sheet: this pane's shares, the form with a suggested password, 24 h
    O->>M: share(key, 86400, password)
    M->>H: POST /v1/panes/alice-laptop/work/0.0/shares {"ttl": 86400, "password": "…"}
    H-->>M: 201 {"share": {"id", "url", "password", "expires", …}}
    O->>O: the link, the password once, the code; copy, share, save, done
    M->>H: GET /v1/hosts, GET /v1/panes
    H-->>M: the pane with "shared": 1, the header says ⇗ 1
    O->>O: revoke
    M->>H: DELETE /v1/shares/{id}
    H-->>M: 204
```

### The share sheet

`ShareSheet(model, key)`: `share · read only` in its bar, the pane's
name and address, then the pane's live shares (`until <date>`, `n
watching` or `nobody watching`, *revoke*) or *not shared*. Under them
the form: `password [k7mv-3qzp-wd8n]`, `for (1 h) (24 h) (7 d)`,
*create* (dim below eight characters and while one is on its way). The
answer replaces the form with the made share: *shared until <date>. the
password is shown only now, never again:*, `link <url>` with *copy link*
and *share link*, `password <password>` with *copy password*, the note
*send the link and the password apart; the image holds the link only*,
the code (ZXing's encoder, which the app has for scanning the connect
code, drawn black on white into a bitmap, four modules of quiet zone),
*share image*, *save image*, *done*. The code and the shared PNG hold
the link alone. The password goes on the clipboard as a sensitive clip
(no preview on Android 13 and later); the PNG goes to another app
through the app's existing file provider from its cache, and *save
image* puts it in the device's Downloads (the app's own pictures folder
before Android 10). *done* and closing the sheet forget the share: the
hub keeps only its hash.

```
┌─ share ─ read only ───────────────────────────────── [×] ┐
│ ✳ Claude · fixing the build  alice-laptop/work/0.0       │
│ until Nov 5, 10:12 · 1 watching                 [revoke] │
│ ─────────────────────────────────────────────────────────│
│ password [k7mv-3qzp-wd8n          ]                      │
│ for  [1 h] [24 h] [7 d]                        [create]  │
│                                                          │
│ shared until Nov 5, 10:12. the password is shown only    │
│ now, never again:                                        │
│ link      https://hub.example.com/s/3nK…                 │
│           [copy link] [share link]              ▛▀▀▀▀▜   │
│ password  k7mv-3qzp-wd8n   [copy password]      ▌ ▛▜ ▐   │
│ send the link and the password apart; the image ▙▄▄▄▄▟   │
│ holds the link only       [share image] [save image]     │
│                                                  [done]  │
└──────────────────────────────────────────────────────────┘
```

### Where it opens from

| Opener | When |
|---|---|
| *⇗ share* in the pane menu (a tab or a header held still) | a writable pane on a hub with shares; dim with `read only` otherwise; hidden on a hub without shares |
| `⇗ n` in the tile's header | while the pane has live shares made by this token |

## 3. Protocol and API changes

None to the protocol. Newly used by this client, v1 and compatible:
`POST /v1/panes/{address}/shares` with `{"ttl", "password"}` (write
grant, the pane marked for writing), `GET /v1/shares`,
`DELETE /v1/shares/{id}`, and the listing's `shared`. A hub older than
shares answers `404 not_found` to `GET /v1/shares`, which hides the
entry, and lists no `shared`. `docs/ARCHITECTURE.md` gets the rows.

## 4. Security analysis

- **Abuse.** With the write grant: a link and a password that show one
  pane's screen read-only for at most seven days, which a writer could
  pipe anywhere already; the hub checks the grant and the writable mark
  on every call, caps a principal at twenty live shares and the burst
  of creations, and burns a share after ten wrong passwords. With the
  read grant: nothing, `403 read_only`. The phone offers the row only
  for a writable pane and takes the hub's answer.
- **The password** is made on the phone from `SecureRandom`, 60 bits,
  or typed; it is sent once in the create body over the same TLS as the
  token, held in the sheet's state while the made share shows, and
  nowhere after *done* or closing: not in preferences, not in the
  keystore, not in the listing, not in a log. Putting it on the
  clipboard or in another app is the owner's own act, as on the web;
  the clip is flagged sensitive so the system does not preview it.
- **The link** carries the share's id, which opens nothing without the
  password; the PNG and the shared text carry the link alone, never the
  password, so a picture that leaks is not a way in.
- **Input across the boundary.** The ttl is one of three constants; the
  password is checked for length and printable characters before it
  leaves the phone, and the hub checks again; the answer's URL is kept
  only as `http(s)` and bounded, the id only in the protocol's alphabet
  before it goes into a `DELETE` path; counts and times are numbers
  clamped at zero.
- **Logging.** Nothing new is logged on the phone. The hub logs a
  share's creation and revocation by a handle, never the id or the
  password; the end-to-end run searches every log for the password.
- **Dependencies.** None added: ZXing core, already on the phone for
  the connect code, encodes the QR; the PNG is Android's own encoder;
  the file provider is the one the file sheet uses.

## 5. Privacy check

The plan, the diff, the docs and the tests name `hub.example.com`,
`alice-laptop`, `work`, `alice` and made-up ids and passwords
(`3nK…`, `k7mv-3qzp-wd8n`); no generated one is committed. The UI run
uses the test hub's synthetic panes. `scripts/prepush-check.sh` before
the push.

## 6. Test plan

| # | Test | Kind | Expected |
|---|------|------|----------|
| 1 | `E2ETest s36`: a reader creates a share; raw `{"ttl":59}`, `{"ttl":604801}`, `{"password":"seven77"}`, a password with a tab, with a write token; the client's own refusals | e2e-security | 403 `read_only`; 400 four times; `IllegalArgumentException` before anything is sent; `GET /v1/shares` empty |
| 2 | `E2ETest s36b`: a read-only pane shared with a write token | e2e-security | 403 `read_only` |
| 3 | `E2ETest f37`: the password is added to the run's secrets; `z99` | e2e-security | no password in any log |
| 4 | `E2ETest f37`: create with `3600` and a password: the link under the hub's URL, the password back, `expires` within the hour; the listing says `shared: 1` for the sharer and nothing for another full token; `shares()` lists it with `watching` 0 and `Shares.of` picks it; the observer's raw `POST /v1/share/{id}/open` answers 204 with the password and 401 without it; revoke: 204, the list empty, `shared` gone, the probe `404` | e2e | as said |
| 5 | `SharesTest`: `suggest` is `xxxx-xxxx-xxxx` from `a-z2-7` and two differ; the lifetimes; `of` by host and pane id; `until` | unit | as said |
| 6 | `JsonTest`: a share's fields, `password` absent in a listing, a bad id dropped, a `javascript:` URL dropped, `shared` on a pane and its absence | unit | as said |
| 7 | `ApiTest`: `share` posts `{"ttl","password"}` to `…/shares`, refuses a short password and a ttl outside the range; `shares` and `revokeShare` paths; a bad id refused | unit | as said |
| 8 | `FlowTest`: the header held opens the menu, *⇗ share* opens the sheet; *create* shows a link and a password; *done* leaves no password; the header says `⇗ 1`; *revoke* empties the list | UI (`share-sheet`, `share-made`) | assertions on the texts |
| 9 | the full suite, `MUXWAY_UI=0`, recorded | e2e | green, run issue filed |

This machine has no device, so the UI step is written and waits for the
owner's phone or the emulator.

## 7. Documentation

- The core's `docs/ui-guideline.md` §1.6 *share the pane read-only*:
  the Android column (done, no longer a follow-up): a branch in the
  core checkout for the owner to push.
- `docs/features/share.md`: new, the sheet and its security section;
  `pane-view.md`: the header's `⇗ n` and the menu's row;
  `docs/ARCHITECTURE.md`: the rows; `docs/testing.md`: the unit suite;
  the README's sentence.

## 8. Implementation steps

1. `protocol`: `Share`, `Pane.shared`, `Json`, `Api`, `Shares`; unit
   tests.
2. `app`: `Conn.sharing`/`shares`, the probe, `fetchShares`, `share`,
   `revokeShare`; `ShareSheet` with the code, the clipboard, the system
   share sheet and the save; the menu row; the header's `⇗ n`.
3. Docs: the pages above, the guideline in the core; the version
   0.21.0 (52).
4. `FlowTest`: the share step. `E2ETest`: s36, s36b, f37.
5. The e2e run, recorded; the pull request.

---

## Review (after implementation)

- [x] Diff matches the plan. Deviations: the core's guideline change
  (§1.6 *share the pane read-only*, Android column) is committed on the
  branch `guideline-android-share` (bafdfd3, from origin/main 1b2a71a)
  in a worktree of the core checkout on the build machine and waits for
  the owner to push it, since this session may not push to the core;
  the hub is asked for `GET /v1/shares` with each listing until it has
  answered either way, not only with the first probes, so a hub that
  was offline at the first listing is asked again; the sheet's note
  says the observer sees the screen and the web links, since the phone
  relays no *showing*.
- [x] Security rules (AGENTS.md section 2) hold: the write grant on a
  writable pane is the hub's to check and the phone offers the row only
  where it holds, the password comes from `SecureRandom` or the owner
  and lives in the sheet's state alone until *done*, the clip is flagged
  sensitive, the code and the image carry the link alone, the lifetime
  and the password are checked before they leave the phone, the
  answer's link and id are bounded on parse, and nothing is logged or
  stored.
- [x] Privacy check passed: the secret scan and the deny-list grep over
  the branch's commits are clean, and the diff read.
- [x] Docs and diagrams updated: the share page with its sequence
  diagram, the pane view, the architecture table and diagram, the
  testing page, the README.

## Test run

The full suite without the UI run, 319 tests, 0 failed, at `b5f5e38`
against the core's `1b2a71a`:
https://github.com/notmarkopadjen/muxway-android/issues/83
(`20261104-142010-b5f5e38`). The UI step
`theShareSheetMakesALinkOnceCountsItOnTheHeaderAndRevokesIt` waits for
a device.
