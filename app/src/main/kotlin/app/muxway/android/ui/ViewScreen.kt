package app.muxway.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.muxway.android.AppModel
import app.muxway.android.PaneState
import app.muxway.android.Screen
import app.muxway.android.protocol.Ansi
import app.muxway.android.protocol.Input
import app.muxway.android.protocol.Lens
import kotlinx.coroutines.delay

/** One or more panes, side by side in landscape and stacked in portrait, with tabs above and one input bar. */
@Composable
fun ViewScreen(model: AppModel, addresses: List<String>) {
    val p = LocalPalette.current
    var focusedIndex by rememberSaveable { mutableIntStateOf(0) }
    val index = focusedIndex.coerceIn(0, addresses.lastIndex)
    val focused = addresses[index]
    val state = model.view(focused)
    var remote by rememberSaveable { mutableStateOf(false) }
    var wrap by rememberSaveable { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val writable = state.pane?.writable == true
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val now = ticker()

    fun hardKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown || !writable) return false
        val name = HardKeys.name(e.key) ?: return false
        model.key(focused, name)
        return true
    }

    Column(Modifier.fillMaxSize().onKeyEvent(::hardKey).focusRequester(focus).focusable()) {
        Tabs(model, addresses, focused, now) { tapped ->
            val at = addresses.indexOf(tapped)
            if (at >= 0) focusedIndex = at else model.swap(addresses, index, tapped)
            focus.requestFocus()
        }
        Box(Modifier.weight(1f)) {
            val tile: @Composable (Int, Modifier) -> Unit = { i, m ->
                PaneTile(model, addresses[i], i == index && addresses.size > 1, remote && i == index, wrap, now, m) { focusedIndex = i; focus.requestFocus() }
            }
            if (landscape || addresses.size == 1) {
                Row(Modifier.fillMaxSize()) { for (i in addresses.indices) tile(i, Modifier.weight(1f).fillMaxHeight()) }
            } else {
                Column(Modifier.fillMaxSize()) { for (i in addresses.indices) tile(i, Modifier.weight(1f).fillMaxWidth()) }
            }
        }
        Rule()
        model.sendError?.let { Text(" ! $it", color = p.alert, modifier = Modifier.padding(horizontal = 4.dp)) }
        val pane = state.pane
        when {
            pane == null -> Text(" …", color = p.dim, modifier = Modifier.padding(4.dp))
            !pane.writable -> Text(" read-only · ${pane.address}", color = p.dim, modifier = Modifier.padding(4.dp))
            else -> InputBar(model, state, remote, wrap, { remote = it }, { wrap = it })
        }
        Row(Modifier.fillMaxWidth().padding(2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Btn("panes", { model.open(Screen.Panes) })
            Text(state.error?.let { "! $it" } ?: state.updated.ifEmpty { "connecting" }, color = if (state.error != null) p.alert else p.dim, maxLines = 1)
            Btn("settings", { model.open(Screen.Connect) })
        }
    }
}

private class Tab(val address: String, val label: String, val glyph: String?, val waiting: Boolean)

/**
 * Every pane the token may read; the open ones inverted. A glyph before the name says what the
 * Claude lens sees: working, or waiting for you. Tapping a closed one swaps it into the focused tile.
 */
@Composable
private fun Tabs(model: AppModel, open: List<String>, focused: String, now: Long, onTap: (String) -> Unit) {
    val p = LocalPalette.current
    val tabs = ArrayList<Tab>()
    fun add(pane: app.muxway.android.protocol.Pane?, address: String) {
        if (pane == null) tabs.add(Tab(address, address.substringAfter('/'), null, false))
        else tabs.add(Tab(address, pane.name, Lens.glyph(pane, now), Lens.status(pane, now) == Lens.Status.WAITING))
    }
    for (pane in model.panes) add(pane, pane.address)
    for (a in open) if (tabs.none { it.address == a }) add(model.view(a).pane, a)
    LazyRow(Modifier.fillMaxWidth().background(p.bg), verticalAlignment = Alignment.CenterVertically) {
        items(tabs, key = { it.address }) { t ->
            val isOpen = t.address in open
            val fg = if (isOpen) p.bg else p.ink
            val mark = if (isOpen) p.bg else if (t.waiting) p.attention else p.dim
            val isFocused = t.address == focused
            // An open tab is inverted; one that waits for you is inverted in the attention colour.
            val back = if (!isOpen) p.bg else if (t.waiting) p.attention else p.ink
            Row(
                Modifier.background(back).clickable { onTap(t.address) }.padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (isFocused) "┤ " else "│ ", color = fg, maxLines = 1)
                t.glyph?.let { Text("$it ", color = mark, maxLines = 1) }
                Text(t.label + if (isFocused) " ├" else " ", color = fg, maxLines = 1)
            }
        }
    }
    Rule()
}

/** The current time in seconds, refreshed every few seconds so working marks age out. */
@Composable
fun ticker(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis() / 1000
        }
    }
    return now
}

@Composable
private fun InputBar(model: AppModel, state: PaneState, remote: Boolean, wrap: Boolean, onRemote: (Boolean) -> Unit, onWrap: (Boolean) -> Unit) {
    var text by rememberSaveable(state.address) { mutableStateOf("") }
    fun send(enter: Boolean) {
        if (text.isEmpty() && !enter) return
        model.type(state.address, text, enter)
        text = ""
    }
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        item(key = "scroll") { Btn("scroll", { onRemote(!remote) }, selected = remote) }
        item(key = "wrap") { Btn("wrap", { onWrap(!wrap) }, selected = wrap) }
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

/**
 * A pane: header, then its rows in colour at the pane's own width, scaled so that width fits the
 * tile (never larger than the text setting), pinch to zoom, bottom-aligned, scrolling locally
 * unless [remote] turns swipes into arrow keys.
 */
@Composable
private fun PaneTile(model: AppModel, address: String, focused: Boolean, remote: Boolean, wrap: Boolean, now: Long, modifier: Modifier, onFocus: () -> Unit) {
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
        val title = pane?.let { "${it.name} · ${it.command} · ${it.mode}" } ?: address
        val fg = if (focused) p.bg else p.ink
        val waiting = pane != null && Lens.status(pane, now) == Lens.Status.WAITING
        val back = if (!focused) p.bg else if (waiting) p.attention else p.ink
        Row(Modifier.fillMaxWidth().background(back), verticalAlignment = Alignment.CenterVertically) {
            Text("┤ $title ├", color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            pane?.let { Lens.glyph(it, now) }?.let { g ->
                val word = if (waiting) pane.attention?.kind ?: "waiting" else "working"
                Text(" $g $word ", color = if (focused) p.bg else if (waiting) p.attention else p.dim, maxLines = 1)
            }
            if (remote) Text(" scroll ", color = fg, maxLines = 1)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(p.panel)) {
            val base = LocalTextStyle.current
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            // Glyph advance at 100 sp; the size that fits a number of columns is then a division.
            val advance100 = remember(base) { measurer.measure("M", base.copy(fontSize = 100.sp)).size.width.toFloat().coerceAtLeast(1f) }
            val widthPx = (constraints.maxWidth - with(density) { 4.dp.roundToPx() }).coerceAtLeast(1)
            var zoom by remember(address) { mutableFloatStateOf(1f) }
            val maxSp = model.settings.textSp.toFloat()
            // Wrap: the text setting rules and rows reflow to what fits. Otherwise the pane's own
            // width fits the tile, never larger than the setting.
            val unwrapped = remember(state.content) { Ansi.rows(state.content, 0) }
            val paneCols = pane?.width?.takeIf { it > 0 } ?: unwrapped.maxOf { r -> r.sumOf { it.text.length } }.coerceIn(1, 400)
            val fitSp = widthPx * 100f / (advance100 * paneCols)
            val sizeSp = ((if (wrap) maxSp else minOf(maxSp, fitSp)) * zoom).coerceAtLeast(2f)
            val cols = if (wrap) (widthPx * 100f / (advance100 * sizeSp)).toInt().coerceAtLeast(8) else paneCols
            val rows = remember(state.content, cols) { if (cols == paneCols && pane?.width == null) unwrapped else Ansi.rows(state.content, cols) }
            val lineSp = sizeSp * 1.2f
            val text = remember(rows, p) { styled(rows, p) }
            val rowPx = with(density) { lineSp.sp.toPx() }
            val colPx = advance100 * sizeSp / 100f
            val vertical = rememberScrollState()
            val horizontal = rememberScrollState()
            var gestures = Modifier.fillMaxSize().pointerInput(address) { pinch { z -> zoom = (zoom * z).coerceIn(0.5f, 4f) } }
            if (remote) gestures = gestures.pointerInput(address, rowPx, colPx) { swipes(rowPx, colPx) { name -> model.key(address, name) } }
            Box(gestures, contentAlignment = Alignment.BottomStart) {
                Column(Modifier.fillMaxWidth().verticalScroll(vertical, reverseScrolling = true, enabled = !remote).horizontalScroll(horizontal, enabled = !remote && !wrap)) {
                    Text(text, color = p.ink, style = base.copy(fontSize = sizeSp.sp, lineHeight = lineSp.sp), softWrap = false, modifier = Modifier.padding(horizontal = 2.dp))
                }
            }
        }
    }
}

/** Two fingers zoom; one finger is left to the scroll underneath. */
private suspend fun PointerInputScope.pinch(onZoom: (Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val z = event.calculateZoom()
                if (z != 1f) onZoom(z)
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** A row of movement is one Up or Down, two columns one Left or Right; a key waits for the previous answer. */
private suspend fun PointerInputScope.swipes(rowPx: Float, colPx: Float, send: (String) -> Boolean) {
    var acc = Offset.Zero
    val stepY = rowPx.coerceAtLeast(8f)
    val stepX = (colPx * 2).coerceAtLeast(8f)
    detectDragGestures(onDragStart = { acc = Offset.Zero }) { change, drag ->
        change.consume()
        acc = Offset((acc.x + drag.x).coerceIn(-3 * stepX, 3 * stepX), (acc.y + drag.y).coerceIn(-3 * stepY, 3 * stepY))
        when {
            acc.y <= -stepY -> if (send("Down")) acc = Offset(acc.x, acc.y + stepY)
            acc.y >= stepY -> if (send("Up")) acc = Offset(acc.x, acc.y - stepY)
            acc.x <= -stepX -> if (send("Right")) acc = Offset(acc.x + stepX, acc.y)
            acc.x >= stepX -> if (send("Left")) acc = Offset(acc.x - stepX, acc.y)
        }
    }
}

private fun styled(rows: List<List<Ansi.Span>>, p: Palette): AnnotatedString = buildAnnotatedString {
    rows.forEachIndexed { i, row ->
        if (i > 0) append('\n')
        for (s in row) {
            val st = spanStyle(s.style, p)
            if (st == null) append(s.text) else withStyle(st) { append(s.text) }
        }
    }
}

private fun spanStyle(s: Ansi.Style, p: Palette): SpanStyle? {
    if (s == Ansi.Style.PLAIN) return null
    var fg = Colours.resolve(s.fg, p) ?: p.ink
    var bg = Colours.resolve(s.bg, p)
    if (s.inverse) { val t = fg; fg = bg ?: p.panel; bg = t }
    if (s.dim) fg = fg.copy(alpha = 0.6f)
    return SpanStyle(
        color = fg,
        background = bg ?: Color.Unspecified,
        fontWeight = if (s.bold) FontWeight.Bold else null,
        fontStyle = if (s.italic) FontStyle.Italic else null,
        textDecoration = if (s.underline) TextDecoration.Underline else null,
    )
}
