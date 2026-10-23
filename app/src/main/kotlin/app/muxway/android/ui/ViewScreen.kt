package app.muxway.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.muxway.android.AppModel
import app.muxway.android.PaneState
import app.muxway.android.Screen
import app.muxway.android.protocol.Input
import kotlinx.coroutines.delay

/** One or more panes, side by side in landscape and stacked in portrait, with one input bar. */
@Composable
fun ViewScreen(model: AppModel, addresses: List<String>) {
    val p = LocalPalette.current
    var focused by rememberSaveable(addresses) { mutableStateOf(addresses.first()) }
    val state = model.view(focused)
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (landscape || addresses.size == 1) {
                Row(Modifier.fillMaxSize()) {
                    for (a in addresses) PaneTile(model, a, a == focused && addresses.size > 1, Modifier.weight(1f).fillMaxHeight()) { focused = a }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    for (a in addresses) PaneTile(model, a, a == focused, Modifier.weight(1f).fillMaxWidth()) { focused = a }
                }
            }
        }
        Rule()
        model.sendError?.let { Text(" ! $it", color = p.alert, modifier = Modifier.padding(horizontal = 4.dp)) }
        val pane = state.pane
        when {
            pane == null -> Text(" …", color = p.dim, modifier = Modifier.padding(4.dp))
            !pane.writable -> Text(" read-only · ${pane.address}", color = p.dim, modifier = Modifier.padding(4.dp))
            else -> InputBar(model, state)
        }
        Row(Modifier.fillMaxWidth().padding(2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Btn("panes", { model.open(Screen.Panes) })
            Text(state.error?.let { "! $it" } ?: state.updated.ifEmpty { "connecting" }, color = if (state.error != null) p.alert else p.dim, maxLines = 1)
            Btn("settings", { model.open(Screen.Connect) })
        }
    }
}

@Composable
private fun InputBar(model: AppModel, state: PaneState) {
    val p = LocalPalette.current
    var text by rememberSaveable(state.address) { mutableStateOf("") }
    fun send(enter: Boolean) {
        if (text.isEmpty() && !enter) return
        model.type(state.address, text, enter)
        text = ""
    }
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        items(Input.KEYS, key = { it.first }) { (name, label) ->
            Btn(label, { model.key(state.address, name) }, enabled = !state.busy)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(
            ">", text, { text = Input.printable(it) },
            Modifier.weight(1f),
            hint = state.pane?.label ?: "",
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send),
            actions = KeyboardActions(onSend = { send(true) }),
        )
        Btn("type", { send(false) }, enabled = !state.busy && text.isNotEmpty())
        Btn("send", { send(true) }, enabled = !state.busy)
    }
}

/** A pane: header, then its last rows, bottom-aligned, hard-wrapped at the columns that fit. */
@Composable
private fun PaneTile(model: AppModel, address: String, focused: Boolean, modifier: Modifier, onFocus: () -> Unit) {
    val p = LocalPalette.current
    val state = model.view(address)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val every = model.settings.refreshSeconds
    LaunchedEffect(address, every) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                model.pollOnce(address)
                delay(every * 1000L)
            }
        }
    }

    Column(modifier.clickable(onClick = onFocus).padding(2.dp)) {
        val pane = state.pane
        val title = pane?.let { "${it.label} · ${it.command} · ${it.mode}" } ?: address
        val fg = if (focused) p.bg else p.ink
        Row(Modifier.fillMaxWidth().background(if (focused) p.ink else p.bg), verticalAlignment = Alignment.CenterVertically) {
            Text("┤ $title ├", color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            pane?.attention?.let { Text(" ! ${it.kind} ", color = if (focused) p.bg else p.attention, maxLines = 1) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(p.panel)) {
            val style = LocalTextStyle.current
            val measurer = rememberTextMeasurer()
            val charPx = remember(style) { measurer.measure("M", style).size.width.coerceAtLeast(1) }
            val cols = (constraints.maxWidth / charPx).coerceAtLeast(8)
            val rows = remember(state.content, cols) { Input.wrap(state.content.trimEnd('\n').split('\n'), cols) }
            // Reversed scrolling keeps the newest row in view until the user scrolls up.
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll, reverseScrolling = true)) {
                    Text(rows.joinToString("\n"), color = p.ink, softWrap = false, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp))
                }
            }
        }
    }
}
