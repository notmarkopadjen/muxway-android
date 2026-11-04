package app.muxway.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** One part of the help: its name and the actions with their gestures. */
private class Part(val title: String, val rows: List<Pair<String, String>>)

// The guideline's actions in its order, the Android column: what a gesture or a key does here.
private val PARTS = listOf(
    Part("the list", listOf(
        "open a pane" to "tap its row",
        "pick several, up to four" to "hold a row; with a pick on, a tap adds or removes; open N side by side",
        "one more pane in a window" to "the + pane row under its panes",
        "a new session on a host" to "+ session on the host's line",
        "the inbox: every pane that waits" to "⚑ n in the header; a tap on a row opens the pane",
        "messages: what panes said to panes" to "✉ beside ⚑ n; a tap on a mail row opens the mail, on a typed row lays the two beside; ⇆ on any",
        "wake a parked pane" to "open it from the list, the inbox or the add menu",
    )),
    Part("the strip", listOf(
        "switch to a pane" to "tap its tab; the pane swaps into the focused tile",
        "name the pane" to "tap the focused pane's tab, or the name in its header",
        "move the strip" to "drag it",
        "the pane menu: open beside, ⇆ beside a partner, later / wake, ✉ mail, ⇗ share" to "hold a tab still and let go",
        "one more pane, a new session, another pane as a tile" to "+ after the last tab",
        "the pane's mail" to "✉ n on a tab, a header or a list row",
    )),
    Part("the tiles", listOf(
        "next / previous pane" to "swipe across the page, or tap a tile; parked panes are skipped",
        "zoom: one pane alone, and back" to "⊡, or double tap a header",
        "next arrangement" to "▦",
        "equal shares in every split" to "hold ▦",
        "swap two tiles, or split a tile's place" to "hold a header, drag it onto a tile's middle or its edge",
        "lay a tile across a split" to "drop a header onto the divider, on the half it should take",
        "lay the focused tile across the whole view" to "hold its header still: ↔ across the width, ↕ across the height",
        "lay the pane a tile talks with beside it" to "hold its header still: ⇆ beside; the chain on the divider marks the pair",
        "fit on, again, off: this tile" to "⤢ tap, tap again, hold",
        "minimise a tile; bring it back" to "_ on the header; tap its tab in the row above the keys",
        "close the pane on its host, or the tile" to "× on the header; y / n on the question",
        "the repository's changes" to "⎇ count on the header",
        "the pane's shares" to "⇗ n on the header",
    )),
    Part("the body", listOf(
        "click, where the program asked for the mouse" to "tap; elsewhere a tap focuses the tile",
        "scroll a program that has the mouse" to "drag up or down",
        "focus the line from the row the program waits on" to "tap the cursor's row",
        "copy a block of the screen" to "long press on it",
        "open a link, a file or a name printed in the pane" to "tap it",
        "the address of a link the program named" to "long press on its label",
        "a name of the directory onto the line, quoted" to "long press on it",
        "text size" to "pinch",
        "page a full-screen program" to "drag",
    )),
    Part("the line and the keys", listOf(
        "type, send" to "type sends the text, ⏎ sends it and Enter",
        "a console key" to "the key row; Esc at the left, Enter at the right",
        "a new line" to "Shift+Enter on a hardware keyboard",
        "a command of the row, a line sent before" to "tap it under the line",
        "take a completion" to "tap it above the line",
        "accept the suggestion after the cursor" to "tap it, or swipe right on the line; Right, Ctrl+Right with a hardware keyboard",
        "the keyboard window" to "⌨; back closes",
        "a file for the pane's program" to "⇪, paste into the line, or share to muxway",
        "speak a line" to "◉ held records until the finger lifts; tapped, the next tap or ■ stops; back drops",
        "direct typing, with the hardware keyboard setting on" to "every key goes to the pane while the line is not focused; a tap on the line leaves, back from the line returns",
        "answer a question asked with choices" to "a button on the header, the inbox row, the notification or the watch",
    )),
)

/** The help sheet from Settings: every gesture and key, in the guideline's order. */
@Composable
fun HelpSheet(onClose: () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(p.bg).border(1.dp, p.ink).padding(4.dp).testTag("help-sheet")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("┌─ help ─ gestures and keys ", color = p.dim, maxLines = 1)
                Rule(Modifier.weight(1f))
                Btn("×", onClose)
            }
            Rule()
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
                for (part in PARTS) {
                    Text(part.title, color = p.accent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                    for ((action, how) in part.rows) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(action, color = p.ink, modifier = Modifier.width(150.dp))
                        Text(how, color = p.dim, modifier = Modifier.weight(1f).padding(start = 6.dp))
                    }
                }
                Text("web: ? lists the keys; the same actions on every client", color = p.faint, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
