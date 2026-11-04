# Share: one pane, read only, by a link and a password

Someone should see what an agent is doing in a pane, for a while, without
a token: a reviewer, a colleague on a call. A **share** is a link and a
password, sent apart, that open one pane's screen in a browser, read
only, at the size the owner's clients gave it (see the core's
[share](https://github.com/notmarkopadjen/muxway/blob/main/docs/features/share.md)).
The phone makes shares and revokes them; observing is the web's, since
the link is a web page. Parity with the core's web client
(`docs/ui-guideline.md` §1.6 *share the pane read-only*).

## The share sheet

```
┌─ share ─ read only ───────────────────────────────── [×] ┐
│ ✳ Claude · fixing the build  alice-laptop/work/0.0       │
│   until Nov 5, 10:12 · 1 watching               [revoke] │
│ ─────────────────────────────────────────────────────────│
│ password [k7mv-3qzp-wd8n          ]                      │
│ for [1 h] [24 h] [7 d]                         [create]  │
│   the observer sees this pane's screen and the web       │
│   links in it; nothing else                              │
└──────────────────────────────────────────────────────────┘
```

- **Opening it.** *⇗ share* in the pane menu (a tab or a tile's header
  held still, see [the pane view](pane-view.md)) opens the sheet for a
  writable pane; the row is dim with `read only` on a pane the token
  cannot write and hidden on a hub older than shares (a `404` to
  `GET /v1/shares`, asked once with the listing). While the pane has
  live shares made by this token, the tile's header carries `⇗ n`, dim,
  before the changes count, and a tap on it opens the sheet too.
- **The shares.** The pane's live shares (`GET /v1/shares`, filtered by
  host and pane id so they follow the pane when it moves), each with
  when it ends, how many watch, and *revoke* (`DELETE /v1/shares/{id}`,
  which ends every observer's session); *not shared* when there are
  none. Asked again every ten seconds while the sheet is open.
- **The form.** A password, filled with one the phone made
  (`xxxx-xxxx-xxxx` from `a-z2-7`, 60 bits, editable, 8 to 64
  characters), a lifetime (`1 h`, `24 h`, `7 d`), *create*:
  `POST /v1/panes/{address}/shares` with `{"ttl", "password"}`.
- **Once.** The answer replaces the form: *shared until …, the password
  is shown only now, never again*, the link with *copy link* and *share
  link*, the password with *copy password*, the link as a QR code with
  *share image* and *save image*, and *done*. The code and the image
  hold the link alone, so a picture that leaks opens nothing by itself;
  send the link and the password apart. *copy password* puts a
  sensitive clip on the clipboard (not previewed by the system); *share
  link* and *share image* go through the system's share sheet, the
  image read-only from the app's cache; *save image* puts the PNG in
  Downloads. *done*, or closing the sheet, forgets the share: the hub
  has only its hash, and the password is nowhere on the phone.

```
┌─ share ─ read only ───────────────────────────────── [×] ┐
│   shared until Nov 5, 10:12. the password is shown only  │
│   now, never again:                                      │
│   link      https://hub.example.com/s/3nK…               │
│             [copy link] [share link]                     │
│   password  k7mv-3qzp-wd8n          [copy password]      │
│   send the link and the password apart; the image holds  │
│   the link only.                                         │
│   ▛▀▀▀▀▀▀▀▀▜                        [share image]        │
│   ▌ ▛▀▜ ▄▀ ▐                        [save image]         │
│   ▙▄▄▄▄▄▄▄▄▟                        [done]               │
└──────────────────────────────────────────────────────────┘
```

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

The observer opens the link in a browser, gives the password, and sees
the pane's screen at the size the host has, the web links in it, and
what the owner's **web** page opens over it; the phone relays nothing
over a share. A share ends when its lifetime is up, when the owner or an
admin revokes it, when the sharer's token is revoked, when the pane has
been gone from its host for a minute, or after ten wrong passwords.

## Security considerations

- **What a share gives.** With the link and the password: one pane's
  screen, read only, for at most seven days, which a writer could pipe
  anywhere already; so the write grant on a writable pane is the grant
  to share, checked by the hub on every call, a reader gets
  `403 read_only`, and the phone offers the row only where it holds.
  The hub caps a token at twenty live shares and a burst of creations,
  and burns a share after ten wrong passwords.
- **The password** is made on the phone from `SecureRandom`, or typed;
  it is sent once in the create body under the same TLS as the token,
  held in the sheet's state while the made share shows, and nowhere
  after *done* or closing: not in preferences, not in the keystore, not
  in the listing, not in a log. Copying it or sending it to another app
  is the owner's own act, as on the web; the clip is flagged sensitive.
- **The link** carries the share's id, which opens nothing without the
  password; the code, the shared image and the shared text carry the
  link alone.
- **Input across the boundary.** The lifetime is one of three constants
  and the password is checked for length and printable characters before
  anything leaves the phone (the hub checks again); the answer's link
  is kept only as a web address of bounded length, and a share's id
  only in the protocol's alphabet before it goes into a `DELETE` path;
  counts and times are clamped at zero.
- **Logging.** Nothing new is logged on the phone. The hub logs a
  share's creation and revocation by a handle, never the id or the
  password; the end-to-end run searches every log for the password it
  used.
- **No dependency** was added: ZXing core, on the phone already for the
  connect code, encodes the QR; the PNG is Android's own; the file
  provider is the one the file sheet uses.
