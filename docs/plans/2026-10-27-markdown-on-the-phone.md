# Feature: Markdown rendered in the sheet, paths underlined

> The owner, 2026-10-27, from the demo hub: "I don't see a preview for MD
> on Android, should be there. Also, I click on file names and it works,
> but it doesn't visually underline like on web to show that I can click
> on that." Built at once; this plan records it.

## 1. Goal

The sheet shows a Markdown file as the web's window does: rendered, with
a switch to the source. Printed paths and inbox files in a pane are
underlined like web addresses, so a link looks like one.

## 2. Design

`Markdown` in the protocol module parses the browser's subset (headings,
paragraphs, lists, quotes, fenced and indented code, tables, rules;
inline code, strong, emphasis, strike, links) into blocks and runs, pure
Kotlin, unit-tested; `FileSheet` draws the blocks as the terminal would:
headings bold (the first two levels in the accent, level one with a rule
under), `•` and `1.` for items, `│` for quotes, code on the panel colour
scrolling sideways, tables padded with `│` and `─┼─`. Web addresses in
the text open in the browser; the host's paths open in the same sheet
from the same pane. **plain** shows the source as before. The rows of a
pane underline inbox files always and printed paths on a host with
preview roots, in a writable pane, which is where a tap opens them.

## 3. Protocol and API changes

None.

## 4. Security analysis

No tag in a file reaches the screen: the parser makes blocks and runs
of text, never markup. A link's target is file content, so it is
untrusted: only `http://` and `https://` become web links, and a path
only ever asks the pane's own hub, which checks the roots. Nothing new
is sent, logged or stored.

## 5. Privacy check

Fixtures are synthetic.

## 6. Test plan

| # | Test | Kind | Expected |
|---|------|------|----------|
| 1 | A tag in a file | unit | text, not markup |
| 2 | A relative link in a file | unit | not a web link |
| 3 | Blocks of a plan: headings, items with a second row, quote, fence, table, rule | unit | as parsed |
| 4 | Inline runs: strong, code, emphasis, strike, a web link | unit | as parsed |
| 5 | A printed path in a pane | UI | underlined; the sheet renders the file; *plain* shows the source |

## 7. Documentation

`pane-view.md`; the guideline's §1.6 row.

## 8. Implementation steps

Done in one change: `Markdown.kt` and its test, `FileSheet.kt`, the
underline in `ViewScreen.styled`.

## Test run

Protocol unit tests passed; the end-to-end suite is the run recorded in
the commit. The UI step waits for a device run.
