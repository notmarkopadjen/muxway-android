package app.muxway.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.muxway.android.AppModel
import app.muxway.android.protocol.Inbox
import app.muxway.android.protocol.Lens
import kotlinx.coroutines.delay

/**
 * The inbox, the browser's window as a sheet: every pane that waits for you, on every hub,
 * oldest first, with its mark, name, place, kind and text, how long it has waited and a
 * question's choices, then the panes parked for later, oldest parking first, with the moon and
 * what waits under it dimmed. A tap on a row opens the pane, and wakes a parked one; *oldest*
 * at the top opens the first awake row, and is not offered when every row is parked. Each
 * hub's `/v1/inbox` is asked every five seconds while the sheet is open; until the first
 * answer the rows are the listings the app already holds.
 */
@Composable
fun InboxSheet(model: AppModel, now: Long, onClose: () -> Unit) {
    val p = LocalPalette.current
    LaunchedEffect(Unit) {
        while (true) {
            model.fetchInbox()
            delay(5_000)
        }
    }
    val rows = model.inboxRows ?: model.waiting
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(p.bg).border(1.dp, p.ink).padding(4.dp).testTag("inbox")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("┌─ inbox ─ ", color = p.dim, maxLines = 1)
                Text("${rows.size} waiting ", color = p.ink, maxLines = 1)
                Rule(Modifier.weight(1f))
                Btn("×", onClose)
            }
            Rule()
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 480.dp).padding(top = 4.dp)) {
                if (rows.isEmpty()) Text("nothing waits for you", color = p.dim, modifier = Modifier.padding(8.dp))
                else LazyColumn(Modifier.fillMaxWidth()) {
                    Inbox.oldest(rows)?.let { first ->
                        item(key = "oldest") {
                            Text(
                                "⚑ oldest", color = p.accent, maxLines = 1,
                                modifier = Modifier.fillMaxWidth().clickable { model.openPane(first.key) }.padding(horizontal = 4.dp, vertical = 6.dp).testTag("inbox-oldest"),
                            )
                            Rule()
                        }
                    }
                    items(rows.size, key = { rows[it].key }) { i ->
                        val pane = rows[i]
                        val a = pane.attention
                        val status = model.status(pane, now)
                        val parked = status == Lens.Status.LATER
                        Column(
                            Modifier.fillMaxWidth().clickable { model.openPane(pane.key) }.padding(horizontal = 4.dp, vertical = 6.dp).testTag("inbox-row:${pane.address}"),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (status != Lens.Status.QUIET) { Mark(status, a?.kind, if (parked) p.dim else p.attention); Text(" ", maxLines = 1) }
                                PaneName(pane, p.ink, p.dim, modifier = Modifier.weight(1f, fill = false))
                                Text("  ${pane.place}", color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(" ${Inbox.ago(a?.at ?: pane.laterAt, now)}", color = p.dim, maxLines = 1)
                            }
                            // A parked row says so first, with what waits under the mark after a dot.
                            val text = if (a?.text.isNullOrEmpty()) "" else ": ${a.text}"
                            val said = if (parked) "later" + (a?.let { " · ${it.kind}" } ?: "") + text else (a?.kind ?: "waiting") + text
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("  $said", color = if (parked) p.dim else p.attention, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Choices(model, pane)
                            }
                        }
                        Rule()
                    }
                }
            }
        }
    }
}

/** `⚑ n`, the count of waiting panes for a header; nothing while none waits. */
@Composable
fun InboxCount(model: AppModel) {
    val n = model.waiting.size
    if (n > 0) Btn("⚑ $n", { model.inboxOpen = true }, selected = model.inboxOpen, modifier = Modifier.testTag("inbox-count"))
}
