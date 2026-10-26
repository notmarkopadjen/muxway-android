package app.muxway.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.muxway.android.AppModel
import app.muxway.android.Screen
import app.muxway.android.protocol.Lens
import app.muxway.android.protocol.Pane

/**
 * The chooser for files another app shared: the writable panes by host, each asked whether it
 * takes files, the ones that do tappable. A tap sends the files to that pane and opens it;
 * cancel or back drops the share.
 */
@Composable
fun ShareScreen(model: AppModel) {
    val p = LocalPalette.current
    val now = ticker()
    val n = model.sharing.size
    val writable = model.panes.filter { it.writable }
    LaunchedEffect(writable.map { it.address }) { for (pane in writable) model.probeInbox(pane.address) }
    Column(Modifier.fillMaxSize().padding(4.dp)) {
        Frame("muxway", crumb = if (n == 1) "share a file" else "share $n files") {
            Btn("cancel", { model.open(Screen.Panes) })
        }
        model.listError?.let { Text("! $it", color = p.alert, modifier = Modifier.padding(4.dp)) }
        LazyColumn(Modifier.weight(1f)) {
            when {
                n == 0 -> item { Text("  nothing to share: a text came, not a file", color = p.dim, modifier = Modifier.padding(8.dp)) }
                writable.isEmpty() && !model.listing -> item { Text("  no pane to type into", color = p.dim, modifier = Modifier.padding(8.dp)) }
            }
            if (n > 0) for ((host, panes) in writable.groupBy { it.host }) {
                item(key = "host:$host") {
                    Panel(host, Modifier.padding(vertical = 4.dp)) {
                        for (pane in panes) ShareRow(pane, model.status(pane, now), model.takesFiles(pane.address)) { model.shareInto(pane.address) }
                    }
                }
            }
        }
        Text("  tap a pane: the files go to its host, their paths onto its line", color = p.dim, modifier = Modifier.padding(4.dp))
    }
}

/** `⇪ name  label · command`, dim with the reason where the pane takes no files. */
@Composable
private fun ShareRow(pane: Pane, status: Lens.Status, takes: Boolean?, onTap: () -> Unit) {
    val p = LocalPalette.current
    val ok = takes == true
    Row(
        Modifier.fillMaxWidth().clickable(enabled = ok, onClick = onTap).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (ok) "⇪ " else "  ", color = p.ink)
        Text(pane.name, color = if (ok) p.ink else p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text("  ${pane.label} · ${pane.command}", color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        when (takes) {
            true -> Lens.glyph(status, pane.attention?.kind)?.let { g ->
                val waiting = status == Lens.Status.WAITING
                Text(" $g ${Lens.word(status, pane.attention?.kind)}", color = if (waiting) p.attention else p.dim, maxLines = 1, modifier = if (waiting) Modifier.alpha(pulse()) else Modifier)
            }
            false -> Text(" takes no files", color = p.dim, maxLines = 1)
            null -> Text(" …", color = p.dim, maxLines = 1)
        }
    }
    Rule()
}
