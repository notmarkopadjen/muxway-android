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
import app.muxway.android.MailTarget
import app.muxway.android.protocol.Beside
import app.muxway.android.protocol.Keys

/**
 * The pane menu, under a tab or a tile's header held and let go in place (the drag-and-drop
 * long press without a move): *open beside* for a tab, *beside* a partner the pane talked with
 * (see [Beside]), *across* the width or the height for a tile among several, then *mail*, the
 * pane's mail sheet with its line focused, and *share*, the share sheet, on a hub that shares
 * panes. Parking a pane for later is the header's `_` (minimise), not a row here.
 * A row is dim with the reason on a pane the listing lacks or the token cannot write.
 */
@Composable
fun PaneMenu(
    model: AppModel, key: String, onBeside: (() -> Unit)?, onClose: () -> Unit, onPartner: (String) -> Unit = {},
    onAcross: ((horizontal: Boolean) -> Unit)? = null, acrossNow: (Boolean) -> Boolean = { false },
) {
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
                    Text(label, color = if (reason == null) p.ink else p.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    reason?.let { Text("  $it", color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                }
            }
            if (onBeside != null) item("open beside", null, "menu-beside") { onClose(); onBeside() }
            // The panes this one talked with lately, each laid beside it; four at most.
            if (pane != null) for (partner in Beside.partners(pane, model.panes).take(Beside.MAX_TILES)) {
                item("⇆ beside ${partner.tab}", null, "menu-partner:${Keys.address(partner.key)}") { onClose(); onPartner(partner.key) }
            }
            if (onAcross != null) {
                item("⬌ across the width", if (acrossNow(false)) "already" else null, "menu-across-width") { onClose(); onAcross(false) }
                item("⬍ across the height", if (acrossNow(true)) "already" else null, "menu-across-height") { onClose(); onAcross(true) }
            }
            item("✉ mail", if (pane == null) "not listed" else null, "menu-mail") { onClose(); model.mailOpen = MailTarget(key, line = true) }
            val shares = model.hubShares(key)
            if (shares != false) {
                val why = when {
                    pane == null -> "not listed"
                    !pane.writable -> "read only"
                    shares == null -> "asking the hub"
                    else -> null
                }
                item("⇗ share", why, "menu-share") { onClose(); model.shareOpen = key }
            }
        }
    }
}
