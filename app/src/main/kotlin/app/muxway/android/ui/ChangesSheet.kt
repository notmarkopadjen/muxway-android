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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.muxway.android.AppModel
import app.muxway.android.protocol.Change
import app.muxway.android.protocol.Changes
import kotlinx.coroutines.delay

/**
 * Git's view of the pane's repository, the browser's changes window as a sheet: the repository,
 * its branch and distance from the upstream and the counts in the bar; the changed paths as a
 * tree, directories first, folded by a tap, a mark and a colour per file, `●` for staged. A
 * file opens the file sheet with its diff or as it is. Asked again every five seconds.
 */
@Composable
fun ChangesSheet(model: AppModel, address: String, onOpen: (Shown) -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    val state = model.view(address)
    val c = state.changes
    var folded by remember(address) { mutableStateOf(setOf<String>()) }
    LaunchedEffect(address) {
        while (true) {
            model.fetchChanges(address, force = true)
            delay(5_000)
        }
    }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(p.bg).border(1.dp, p.ink).padding(4.dp).testTag("changes")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("┌─ changes ─ ", color = p.dim, maxLines = 1)
                if (c != null) {
                    val where = "${c.repo} · ${c.branch}" + (if (c.ahead > 0) " ↑${c.ahead}" else "") + (if (c.behind > 0) " ↓${c.behind}" else "")
                    Text(where, color = p.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(" ─ ${Changes.summary(Changes.counts(c.entries))}${if (c.truncated) " …" else ""} ", color = p.ink, maxLines = 1)
                }
                Rule(Modifier.weight(1f))
                Btn("×", onClose)
            }
            Rule()
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 480.dp).padding(top = 4.dp)) {
                when {
                    state.changesError != null -> Text("! ${state.changesError}", color = p.alert, modifier = Modifier.padding(8.dp))
                    c == null -> Text("not in a repository the host shows", color = p.dim, modifier = Modifier.padding(8.dp))
                    c.entries.isEmpty() -> Text("nothing has changed", color = p.dim, modifier = Modifier.padding(8.dp))
                    else -> {
                        val rows = remember(c, folded) { flatten(Changes.tree(c.entries), folded) }
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(rows.size) { i ->
                                val r = rows[i]
                                val indent = (4 + r.depth * 12).dp
                                when (r) {
                                    is Row.Dir -> {
                                        val shut = r.dir.path in folded
                                        Text(
                                            (if (shut) "▸ " else "▾ ") + r.dir.name + "/" + (if (shut) "  ${r.dir.count}" else ""), color = if (shut) p.dim else p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth().clickable { folded = if (shut) folded - r.dir.path else folded + r.dir.path }.padding(start = indent, top = 4.dp, bottom = 4.dp, end = 4.dp),
                                        )
                                    }
                                    is Row.File -> {
                                        val f = r.change
                                        val colour = when (f.kind) {
                                            "added", "untracked" -> p.ok
                                            "deleted" -> p.dim
                                            "conflict" -> p.alert
                                            else -> p.ink
                                        }
                                        Row(
                                            Modifier.fillMaxWidth().clickable { onOpen(Shown(address, path = "${c.root}/${f.path}", change = f)) }
                                                .padding(start = indent, top = 4.dp, bottom = 4.dp, end = 4.dp).testTag("change:${f.path}"),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(Changes.mark(f.kind) + (if (f.staged) "●" else " ") + " ", color = colour, maxLines = 1, fontWeight = if (f.kind == "conflict") FontWeight.Bold else null)
                                            Text(
                                                f.name + (if (f.from.isNotEmpty()) "  ← ${f.from}" else ""), color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                textDecoration = if (f.kind == "deleted") TextDecoration.LineThrough else null,
                                                fontWeight = if (f.kind == "conflict") FontWeight.Bold else null,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed class Row(val depth: Int) {
    class Dir(val dir: Changes.Dir, depth: Int) : Row(depth)
    class File(val change: Change, depth: Int) : Row(depth)
}

/** The tree as rows, a folded directory's below left out. */
private fun flatten(dir: Changes.Dir, folded: Set<String>, depth: Int = 0): List<Row> {
    val out = ArrayList<Row>()
    for (d in dir.dirs) {
        out.add(Row.Dir(d, depth))
        if (d.path !in folded) out.addAll(flatten(d, folded, depth + 1))
    }
    for (f in dir.files) out.add(Row.File(f, depth))
    return out
}
