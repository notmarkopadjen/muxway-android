package app.muxway.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.muxway.android.AppModel
import app.muxway.android.Screen
import app.muxway.android.protocol.Pane
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PanesScreen(model: AppModel) {
    val p = LocalPalette.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch { model.followEvents() }
            while (true) {
                model.refreshList()
                delay(15_000)
            }
        }
    }

    val byHost = model.panes.groupBy { it.host }
    val offline = model.hosts.filter { !it.online && it.name !in byHost }
    val quiet = model.hosts.filter { it.online && it.name !in byHost }

    Column(Modifier.fillMaxSize().padding(4.dp)) {
        Frame("muxway · panes") {
            Btn(if (model.listing) "…" else "refresh", { model.refreshList() }, enabled = !model.listing)
            Btn("settings", { model.open(Screen.Connect) })
        }
        model.listError?.let { Text("! $it", color = p.alert, modifier = Modifier.padding(4.dp)) }
        LazyColumn(Modifier.weight(1f)) {
            if (model.panes.isEmpty() && model.listError == null && !model.listing) {
                item { Text("  no pane is exposed to this token", color = p.dim, modifier = Modifier.padding(8.dp)) }
            }
            for ((host, panes) in byHost) {
                item(key = "host:$host") { HostRow(host, "online") }
                items(panes, key = { it.address }) { pane ->
                    PaneRow(pane, pane.address in model.selected,
                        onTap = { if (model.selected.isEmpty()) model.open(Screen.View(listOf(pane.address))) else model.toggleSelected(pane.address) },
                        onHold = { model.toggleSelected(pane.address) })
                }
            }
            for (h in quiet) item(key = "host:${h.name}") { HostRow(h.name, "online · nothing exposed") }
            for (h in offline) item(key = "host:${h.name}") { HostRow(h.name, "offline") }
        }
        if (model.selected.isNotEmpty()) {
            Rule()
            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val n = model.selected.size
                Btn(if (n == 1) "open" else "open $n side by side", { model.open(Screen.View(model.selected.toList())) })
                Text("${n}/4 · hold to select", color = p.dim)
                Btn("clear", { model.selected.clear() })
            }
        } else {
            Text("  tap to open · hold to pick several", color = p.dim, modifier = Modifier.padding(4.dp))
        }
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
private fun PaneRow(pane: Pane, selected: Boolean, onTap: () -> Unit, onHold: () -> Unit) {
    val p = LocalPalette.current
    val fg = if (selected) p.bg else p.ink
    val bg = if (selected) p.ink else p.bg
    Row(
        Modifier.fillMaxWidth().background(bg).combinedClickable(onClick = onTap, onLongClick = onHold).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (pane.writable) "* " else "  ", color = fg)
        Text(pane.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text("  ${pane.command}", color = if (selected) p.bg else p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        pane.attention?.let { Text(" ! ${it.kind}", color = if (selected) p.bg else p.attention, maxLines = 1) }
    }
    Rule()
}
