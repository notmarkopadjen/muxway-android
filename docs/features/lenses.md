# Lenses

A lens knows one program and tells the rest of the app what its pane is up
to. The first, and so far only, lens is for Claude Code. It is one object in
the protocol package, pure Kotlin, unit-tested; nothing else in the app
knows what it looks for.

## The Claude Code lens

A pane is Claude Code's when its command is `claude` or its title starts
with Claude's mark `✳` (Claude sets the title to the mark and the session's
name). The lens answers one of three states:

| State | When | Glyph |
|---|---|---|
| waiting for you | the listing carries a notification: `permission`, `question`, `idle`, `done` | `!` `?` `z` `✓` in the attention colour |
| working | the last event the hub sent for the pane was `working` (Claude's hook on a submitted prompt), or the app is reading the pane and its screen changed in the last 10 s | `⟳`, dim |
| quiet | anything else | nothing |

The glyph sits before the pane's name in the tab strip, in the pane's
header with the word (`! permission`, `⟳ working`), and in the pane list.
The name is the title without the mark, so a tab reads `⟳ build server`
rather than `✳ build server`. Panes that are not Claude's show
notifications the same way and never show "working".

Tabs and headers are painted with the state: the attention colour while
the pane waits for you, dim while it works, plain when quiet. Selection
is a border, never a colour, so the state shows even on the pane you are
looking at.

```mermaid
stateDiagram-v2
    [*] --> Quiet
    Quiet --> Working: working event, or the open pane's screen changes
    Working --> Quiet: a later event that is not a notification, 10 s without a change
    Quiet --> Waiting: notification arrives
    Working --> Waiting: notification arrives
    Waiting --> Quiet: notification cleared (working hook, or input from a client)
```

Why not the listing's `activity`: it is tmux's window activity, shared by
every pane of the window, so with four Claude panes in one window it
marked all four as working whenever one printed. The event feed is per
pane. It needs the hooks from `muxway hooks claude-code` on the host,
which also feed the long poll that re-lists the panes at once; without
hooks only the open pane's own screen changes count.

## Security

The lens reads three fields the app has already filtered (title, command,
notification kind) and one number. A pane can at most make itself look
like Claude Code or look busy; it cannot make the app do anything.
