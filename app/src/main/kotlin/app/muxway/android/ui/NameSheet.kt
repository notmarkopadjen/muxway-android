package app.muxway.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.muxway.android.AppModel
import app.muxway.android.protocol.Keys
import app.muxway.android.protocol.Lens

/**
 * The name sheet, the phone's tooltip on a pane's name: what the program says of itself, the
 * place with the size and the mode, the folder's whole path, and for a writer on a host that
 * names panes a field for the alias, slugged as typed the way the web's is. Enter or `name`
 * asks the hub, `clear` takes the name away, `cancel` or back leaves it.
 */
@Composable
fun NameSheet(model: AppModel, key: String, onClose: () -> Unit) {
    val p = LocalPalette.current
    val pane = model.view(key).pane ?: model.panes.firstOrNull { it.key == key }
    val can = pane != null && pane.writable && model.hostRenames(key)
    var name by remember(key) { mutableStateOf(pane?.alias ?: "") }
    val focus = remember { FocusRequester() }
    val save = { if (can && !model.renaming) model.rename(key, name, onClose) }
    Dialog(onDismissRequest = onClose) {
        Panel(pane?.name ?: Keys.address(key), Modifier.testTag("name-sheet"), active = true) {
            if (pane != null) {
                val d = Lens.describe(pane.copy(alias = ""))
                Text("  " + (if (d.status.isEmpty()) d.name else "${d.name} · ${d.status}"), color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("  ${pane.place} · ${pane.width}×${pane.height} · ${pane.mode}", color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                pane.cwd?.let { Text("  $it", color = p.dim, fontStyle = FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (pane != null && can) {
                Field("name", name, { name = Lens.aliasOf(it); model.renameError = null }, hint = "a-z, 0-9 and -, up to 32", focusRequester = focus, onEnter = save,
                    keyboard = KeyboardOptions(imeAction = ImeAction.Done), actions = KeyboardActions(onDone = { save() }))
                val under = model.renameError?.let { "! $it" } ?: if (name.isEmpty()) "  no name: the program's own" else "  agents reach it as ${pane.host}/@$name"
                Text(under, color = if (model.renameError != null) p.alert else p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Btn("cancel", onClose)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pane.alias.isNotEmpty()) Btn("clear", { name = ""; model.rename(key, "", onClose) }, enabled = !model.renaming, modifier = Modifier.padding(end = 6.dp).testTag("name-clear"))
                        Btn(if (model.renaming) "…" else "name", save, enabled = !model.renaming, modifier = Modifier.testTag("name-save"))
                    }
                }
            } else {
                Text(if (pane?.writable != true) "  read-only: not yours to name" else "  this host does not name panes", color = p.dim, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Btn("close", onClose) }
            }
        }
    }
    LaunchedEffect(key) { if (can) focus.requestFocus() }
}
