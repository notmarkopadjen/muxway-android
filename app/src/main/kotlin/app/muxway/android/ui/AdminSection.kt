package app.muxway.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.muxway.android.AppModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun ago(at: Long): String {
    val s = (System.currentTimeMillis() / 1000 - at).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h"
        else -> "${s / 86400}d"
    }
}

/**
 * For an admin token: the hub's last 200 log lines, pulled down to refresh, and who is
 * connected, each browser session with end. Tokens and address lists are made on the web.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminSection(model: AppModel) {
    val p = LocalPalette.current
    LaunchedEffect(Unit) { model.refreshAdmin() }
    Panel("admin", Modifier.padding(vertical = 4.dp), actions = { Btn("refresh", { model.refreshAdmin() }) }) {
        model.adminError?.let { Text("! $it", color = p.alert) }
        Text("log", color = p.dim)
        PullToRefreshBox(model.adminLoading, { model.refreshAdmin() }, Modifier.fillMaxWidth().height(240.dp)) {
            val scroll = rememberScrollState()
            LaunchedEffect(model.adminLog.size) { scroll.scrollTo(scroll.maxValue) }
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                if (model.adminLog.isEmpty()) Text("nothing yet.", color = p.dim)
                for (line in model.adminLog) {
                    val attrs = line.attrs.entries.joinToString(" ") { "${it.key}=${it.value}" }
                    Text(
                        "${CLOCK.format(Instant.ofEpochSecond(line.at))} ${if (line.warning) "!" else " "} ${line.msg}  $attrs",
                        color = if (line.warning) p.alert else p.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text("clients", color = p.dim, modifier = Modifier.padding(top = 6.dp))
        if (model.adminClients.isEmpty()) Text("no one.", color = p.dim)
        for (c in model.adminClients) {
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.client, color = p.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(c.who, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.3f))
                Text(" ${c.addr} ${ago(c.seen)}", color = p.dim, maxLines = 1)
                if (c.endable) Btn("end", { model.endSession(c.id) }) else Text(" token", color = p.faint, maxLines = 1)
            }
        }
        Text("tokens and address lists: on the web, or muxway hub token / access on the hub", color = p.dim, modifier = Modifier.padding(top = 6.dp))
    }
}
