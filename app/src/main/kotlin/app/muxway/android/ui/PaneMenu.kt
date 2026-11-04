package app.muxway.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.muxway.android.AppModel

/**
 * The pane menu, under a tab or a tile's header held and let go in place (the drag-and-drop
 * long press without a move): *open beside* for a tab, then
 * *later* or *wake*, the owner's mark on the host (see the notifications page). The mark's row
 * is dim with the reason on a read-only pane or a host that does not park panes.
 */
@Composable
fun PaneMenu(model: AppModel, key: String, onBeside: (() -> Unit)?, onClose: () -> Unit) {
    val p = LocalPalette.current
    val pane = model.panes.firstOrNull { it.key == key } ?: model.view(key).pane
    BackHandler(onBack = onClose)
    Popup(alignment = Alignment.TopStart, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        Column(Modifier.width(220.dp).border(1.dp, p.ink).background(p.panel).padding(4.dp).testTag("pane-menu")) {
            val item: @Composable (String, String?, String, () -> Unit) -> Unit = { label, reason, tag, act ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = reason == null, onClick = act).padding(horizontal = 4.dp, vertical = 6.dp).testTag(tag),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = if (reason == null) p.ink else p.faint, maxLines = 1)
                    reason?.let { Text("  $it", color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                }
            }
            if (onBeside != null) item("open beside", null, "menu-beside") { onClose(); onBeside() }
            val parked = pane?.later == true
            val reason = when {
                pane == null -> "not listed"
                !pane.writable -> "read only"
                !model.hostParks(key) -> "host without later"
                else -> null
            }
            item(if (parked) "☾ wake" else "☾ later", reason, "menu-later") { onClose(); model.later(key, !parked) }
        }
    }
}
