package app.muxway.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.muxway.android.AppModel
import app.muxway.android.Naming
import app.muxway.android.Screen
import app.muxway.android.protocol.Lens
import app.muxway.android.protocol.Pane

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PanesScreen(model: AppModel) {
    val p = LocalPalette.current
    val now = ticker()
    val conns = model.connections
    val several = conns.size > 1

    Column(Modifier.fillMaxSize().padding(4.dp)) {
        Frame("muxway", crumb = "panes") {
            InboxCount(model)
            Btn(if (model.listing) "…" else "refresh", { model.refreshList() }, enabled = !model.listing)
            Btn("settings", { model.open(Screen.Connect) })
        }
        LazyColumn(Modifier.weight(1f)) {
            if (model.panes.isEmpty() && model.listError == null && !model.listing) {
                item { Text("  no pane is exposed to this token", color = p.dim, modifier = Modifier.padding(8.dp)) }
            }
            // Every hub, one under another; with several each has its own line and its own
            // trouble. A hub that did not answer shows the reason and nothing under it.
            for (c in conns) {
                val failed = c.listError != null
                val byHost = if (failed) emptyMap() else c.panes.groupBy { it.host }
                val offline = if (failed) emptyList() else c.hosts.filter { !it.online && it.name !in byHost }
                val quiet = if (failed) emptyList() else c.hosts.filter { it.online && it.name !in byHost }
                if (several) item(key = "hub:${c.id}") { HubRow(c.name, c.listError) }
                else c.listError?.let { item(key = "error:${c.id}") { Text("! $it", color = p.alert, modifier = Modifier.padding(4.dp)) } }
                for ((host, panes) in byHost) {
                    val starts = c.hosts.any { it.name == host && it.sessions }
                    item(key = "host:${c.id}:$host") {
                        Panel(host, Modifier.padding(vertical = 4.dp), actions = {
                            if (starts) Text("+ session · ", color = p.dim, maxLines = 1, modifier = Modifier.clickable { model.createError = null; model.naming = Naming(c.id, host) })
                            Text("online", color = p.dim, maxLines = 1)
                        }) {
                            // Under a window's panes, when one of them is writable, a row that splits it.
                            for ((_, inWindow) in panes.groupBy { it.session to it.windowIndex }) {
                                for (pane in inWindow) {
                                    PaneRow(pane, model.status(pane, now), pane.key in model.selected,
                                        onTap = { if (model.selected.isEmpty()) model.open(Screen.View(listOf(pane.key))) else model.toggleSelected(pane.key) },
                                        onHold = { model.toggleSelected(pane.key) })
                                }
                                inWindow.firstOrNull { it.writable }?.let { source -> NewPaneRow(enabled = !model.creating) { model.splitWindow(source) } }
                            }
                        }
                    }
                }
                for (h in quiet) {
                    item(key = "host:${c.id}:${h.name}") {
                        HostRow(h.name, "online · nothing exposed")
                        if (h.sessions) Text("  + session", color = p.dim, modifier = Modifier.clickable { model.createError = null; model.naming = Naming(c.id, h.name) }.padding(4.dp))
                    }
                }
                for (h in offline) item(key = "host:${c.id}:${h.name}") { HostRow(h.name, "offline") }
            }
            // The way to open a selection comes right after the list, not pinned to the screen's foot.
            if (model.selected.isNotEmpty()) item(key = "selection") { SelectionBar(model) }
        }
        Text(if (model.selected.isEmpty()) "  tap to open · hold to pick several" else "  ${model.selected.size}/4 · hold to pick more", color = p.dim, modifier = Modifier.padding(4.dp))
    }
    model.naming?.takeIf { it.into == null }?.let { SessionDialog(it, model) }
    if (model.inboxOpen) InboxSheet(model, now) { model.inboxOpen = false }
}

/** `═══ work ═══`, a hub's line over its hosts, with its trouble when it has any. */
@Composable
private fun HubRow(name: String, error: String?) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp).testTag("hub:$name"), verticalAlignment = Alignment.CenterVertically) {
        Text("══ $name ", color = p.accent, maxLines = 1)
        Text("═".repeat(60), color = p.dim, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f))
        error?.let { Text(" ! $it ══", color = p.alert, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** `│  + pane`, dim, under the panes of a window where typing is allowed. */
@Composable
private fun NewPaneRow(enabled: Boolean, onTap: () -> Unit) {
    val p = LocalPalette.current
    Text("│  + pane", color = p.faint, maxLines = 1, modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onTap).padding(horizontal = 4.dp, vertical = 6.dp))
    Rule()
}

/** The name of a session to start on a host: Enter or `start` asks the hub, back or `cancel` drops it. */
@Composable
internal fun SessionDialog(naming: Naming, model: AppModel) {
    val p = LocalPalette.current
    val host = naming.host
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val start = { model.startSession(naming.hub, host, name, naming.into) }
    Dialog(onDismissRequest = { model.naming = null }) {
        Panel("+ session · $host", active = true) {
            Field("name", name, { name = it; model.createError = null }, hint = "a-z, 0-9, _ and -", focusRequester = focus, onEnter = start,
                keyboard = KeyboardOptions(imeAction = ImeAction.Done), actions = KeyboardActions(onDone = { start() }))
            Text(model.createError?.let { "! $it" } ?: "  the host's shell, in a new session", color = if (model.createError != null) p.alert else p.dim, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Btn("cancel", { model.naming = null })
                Btn(if (model.creating) "…" else "start", start, enabled = !model.creating)
            }
        }
    }
    LaunchedEffect(host) { focus.requestFocus() }
}

/** `[clear] ………… [open N side by side]`, shown under the whole list while something is selected. */
@Composable
private fun SelectionBar(model: AppModel) {
    val n = model.selected.size
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Btn("clear", { model.selected.clear() })
        Btn(if (n == 1) "open" else "open $n side by side", { model.open(Screen.View(model.selected.toList())) })
    }
}

@Composable
private fun HostRow(name: String, state: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("── $name ", color = if (state == "offline") p.dim else p.ink, maxLines = 1)
        Rule(Modifier.weight(1f))
        Text(" $state ──", color = p.dim, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneRow(pane: Pane, status: Lens.Status, selected: Boolean, onTap: () -> Unit, onHold: () -> Unit) {
    val p = LocalPalette.current
    val fg = if (selected) p.bg else p.ink
    val bg = if (selected) p.ink else p.bg
    Row(
        Modifier.fillMaxWidth().background(bg).combinedClickable(onClick = onTap, onLongClick = onHold).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dim = if (selected) p.bg.copy(alpha = 0.7f) else p.dim
        Text(if (pane.writable) "* " else "  ", color = fg)
        if (status != Lens.Status.QUIET) {
            Mark(status, pane.attention?.kind, if (selected) p.bg else if (status == Lens.Status.WAITING) p.attention else p.dim)
            Text(" ", maxLines = 1)
        }
        PaneName(pane, fg, dim, modifier = Modifier.weight(1f, fill = false))
        Text("  ${pane.place}", color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Lens.word(status, pane.attention?.kind)?.let { w ->
            Text(" $w", color = if (selected) p.bg else if (status == Lens.Status.WAITING) p.attention else p.dim, maxLines = 1)
        }
    }
    Rule()
}
