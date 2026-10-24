package app.muxway.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.ui.unit.Dp
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
import app.muxway.android.protocol.Pane
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * One or more panes, side by side in landscape and stacked in portrait. The top row is the tab
 * strip, coloured by each pane's state, and the controls; a swipe across the page moves to the
 * neighbouring pane; one input bar serves the focused pane.
 */
@Composable
fun ViewScreen(model: AppModel, addresses: List<String>) {
    val p = LocalPalette.current
    var focusedIndex by rememberSaveable { mutableIntStateOf(0) }
    val index = focusedIndex.coerceIn(0, addresses.lastIndex)
    val focused = addresses[index]
    val state = model.view(focused)
    // Fit: the host pane follows the tile, as a terminal would. On by default for one pane.
    var fit by rememberSaveable(addresses) { mutableStateOf(addresses.size == 1) }
    val hardware = model.settings.hardwareKeyboard
    // With a hardware keyboard assumed the on-screen one stays down until asked for.
    var ime by rememberSaveable(hardware) { mutableStateOf(!hardware) }
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val writable = state.pane?.writable == true
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val now = ticker()
    val order = model.panes.map { it.address }.ifEmpty { addresses }

    fun hardKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown || !writable) return false
        val name = HardKeys.name(e.key) ?: return false
        model.key(focused, name)
        return true
    }

    fun show(address: String) {
        val at = addresses.indexOf(address)
        if (at >= 0) focusedIndex = at else model.swap(addresses, index, address)
        focus.requestFocus()
    }

    fun neighbour(step: Int) {
        val at = order.indexOf(focused)
        if (at < 0) return
        val next = (at + step).coerceIn(0, order.lastIndex)
        if (next != at) show(order[next])
    }

    val swipe = with(LocalDensity.current) { 72.dp.toPx() }
    Column(
        Modifier.fillMaxSize().onKeyEvent(::hardKey).focusRequester(focus).focusable()
            .pointerInput(order, focused, addresses) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (abs(total) > swipe) neighbour(if (total < 0) 1 else -1) },
                ) { _, dx -> total += dx }
            },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Btn("‹", { model.open(Screen.Panes) })
            if (addresses.size == 1) Tabs(model, addresses, focused, now, Modifier.weight(1f), ::show)
            else Text(" ${addresses.size} panes", color = p.dim, maxLines = 1, modifier = Modifier.weight(1f))
            Btn("⤢", { fit = !fit }, selected = fit, enabled = writable)
            if (hardware) Btn("⌨", { ime = !ime; if (ime) { fieldFocus.requestFocus(); keyboard?.show() } else keyboard?.hide() }, selected = ime)
            Btn("⚙", { model.open(Screen.Connect) })
        }
        Rule()
        Box(Modifier.weight(1f)) {
            val tile: @Composable (Int, Modifier) -> Unit = { i, m ->
                PaneTile(model, addresses[i], i == index, addresses.size > 1, fit, now, m) { focusedIndex = i; focus.requestFocus() }
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
            else -> InputBar(model, state, fieldFocus) { gained -> if (gained && hardware && !ime) keyboard?.hide() }
        }
        Text(
            state.error?.let { "! $it" } ?: state.updated.ifEmpty { "connecting" },
            color = if (state.error != null) p.alert else p.faint, maxLines = 1,
            style = LocalTextStyle.current.copy(fontSize = 10.sp), modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

/** The colours a pane's state paints a tab or header with: background, then ink. */
@Composable
private fun stateColours(status: Lens.Status?): Pair<Color, Color> {
    val p = LocalPalette.current
    return when (status) {
        Lens.Status.WAITING -> p.attention to p.bg
        Lens.Status.WORKING -> p.dim to p.bg
        else -> p.panel to p.ink
    }
}

/**
 * Every pane the token may read, each painted with its state: attention colour when it waits for
 * you, dim when it works, plain when quiet. Open panes have a thick border, the focused one the
 * thickest and its name in bold. Tapping a closed one swaps it into the focused tile.
 */
@Composable
private fun Tabs(model: AppModel, open: List<String>, focused: String, now: Long, modifier: Modifier, onTap: (String) -> Unit) {
    val p = LocalPalette.current
    val panes = ArrayList<Pair<String, Pane?>>()
    for (pane in model.panes) panes.add(pane.address to pane)
    for (a in open) if (panes.none { it.first == a }) panes.add(a to model.view(a).pane)
    val list = rememberLazyListState()
    LaunchedEffect(focused, panes.size) { panes.indexOfFirst { it.first == focused }.takeIf { it >= 0 }?.let { list.animateScrollToItem(it) } }
    LazyRow(modifier.background(p.bg), state = list, verticalAlignment = Alignment.CenterVertically) {
        items(panes, key = { it.first }) { (address, pane) ->
            val isOpen = address in open
            val isFocused = address == focused
            val status = pane?.let { model.status(it, now) }
            val (back, ink) = stateColours(status)
            val waiting = status == Lens.Status.WAITING
            val border: Dp = if (isFocused) 3.dp else if (isOpen) 2.dp else 1.dp
            Row(
                Modifier.padding(2.dp).border(border, if (isOpen) p.ink else p.faint).background(back)
                    .clickable { onTap(address) }.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                status?.let { Lens.glyph(it, pane?.attention?.kind) }?.let { Text("$it ", color = ink, maxLines = 1, modifier = if (waiting) Modifier.alpha(pulse()) else Modifier) }
                Text(
                    pane?.name ?: address.substringAfter('/'), color = ink, maxLines = 1,
                    style = LocalTextStyle.current.copy(fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal, shadow = null),
                )
            }
        }
    }
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

/** Esc pinned left, Enter pinned right, the other keys scrolling between; then the text line. */
@Composable
private fun InputBar(model: AppModel, state: PaneState, fieldFocus: FocusRequester, onFieldFocus: (Boolean) -> Unit) {
    var text by rememberSaveable(state.address) { mutableStateOf("") }
    fun send(enter: Boolean) {
        if (text.isEmpty() && !enter) return
        model.type(state.address, text, enter)
        text = ""
    }
    val busy = state.busy
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Btn("Esc", { model.key(state.address, "Escape") }, enabled = !busy)
        LazyRow(Modifier.weight(1f)) {
            items(Input.KEYS.filter { it.first != "Escape" && it.first != "Enter" }, key = { it.first }) { (name, label) ->
                Btn(label, { model.key(state.address, name) }, enabled = !busy)
            }
        }
        Btn("Enter", { model.key(state.address, "Enter") }, enabled = !busy)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(
            ">", text, { text = Input.printable(it) },
            Modifier.weight(1f),
            hint = state.pane?.label ?: "",
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send),
            actions = KeyboardActions(onSend = { send(true) }),
            focusRequester = fieldFocus,
            onFocus = onFieldFocus,
        )
        Btn("type", { send(false) }, enabled = !busy && text.isNotEmpty())
        Btn("send", { send(true) }, enabled = !busy)
    }
}

/**
 * A pane: a header painted with its state, then its rows in colour, newest at the bottom, scrolling
 * back through what the hub sent. With fit the host pane is sized to the tile at the text setting;
 * otherwise the pane's own width is scaled to fit (or reflowed, by setting), pinch zooms.
 */
@Composable
private fun PaneTile(model: AppModel, address: String, focused: Boolean, several: Boolean, fit: Boolean, now: Long, modifier: Modifier, onFocus: () -> Unit) {
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

    val pane = state.pane
    val status = pane?.let { model.status(it, now) }
    val (back, ink) = stateColours(status)
    val edge: Dp = if (several && focused) 3.dp else 1.dp
    Column(modifier.padding(2.dp).border(edge, if (several && focused) p.ink else p.faint).clickable(onClick = onFocus)) {
        val title = pane?.let { "${it.name} · ${it.command} · ${it.mode}" } ?: address
        val waiting = status == Lens.Status.WAITING
        Row(Modifier.fillMaxWidth().background(back).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                style = LocalTextStyle.current.copy(fontWeight = if (several && focused) FontWeight.Bold else FontWeight.Normal, shadow = null),
            )
            status?.let { Lens.glyph(it, pane?.attention?.kind) }?.let { g ->
                Text(" $g ${Lens.word(status, pane?.attention?.kind)} ", color = ink, maxLines = 1, modifier = if (waiting) Modifier.alpha(pulse()) else Modifier)
            }
            if (fit && pane?.writable == true) Text(" ${pane.width}×${pane.height} ", color = ink, maxLines = 1)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(p.panel)) {
            val base = LocalTextStyle.current
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            // Glyph advance at 100 sp; the size that fits a number of columns is then a division.
            val advance100 = remember(base) { measurer.measure("M", base.copy(fontSize = 100.sp)).size.width.toFloat().coerceAtLeast(1f) }
            val widthPx = (constraints.maxWidth - with(density) { 4.dp.roundToPx() }).coerceAtLeast(1)
            val heightPx = constraints.maxHeight.coerceAtLeast(1)
            var zoom by remember(address) { mutableFloatStateOf(1f) }
            val maxSp = model.settings.textSp.toFloat()
            val fitting = fit && pane?.writable == true
            val reflow = model.settings.reflow
            val unwrapped = remember(state.content) { Ansi.rows(state.content, 0) }
            val paneCols = pane?.width?.takeIf { it > 0 } ?: unwrapped.maxOf { r -> r.sumOf { it.text.length } }.coerceIn(1, 400)
            val fitSp = widthPx * 100f / (advance100 * paneCols)
            val sizeSp = ((if (fitting || reflow) maxSp else minOf(maxSp, fitSp)) * zoom).coerceAtLeast(2f)
            val lineSp = sizeSp * 1.2f
            val colPx = advance100 * sizeSp / 100f
            val rowPx = with(density) { lineSp.sp.toPx() }
            val visibleCols = (widthPx / colPx).toInt().coerceAtLeast(8)
            val visibleRows = (heightPx / rowPx).toInt().coerceAtLeast(1)
            val cols = if (fitting || reflow) visibleCols else paneCols
            val rows = remember(state.content, cols) { if (cols == paneCols && pane?.width == null) unwrapped else Ansi.rows(state.content, cols) }
            LaunchedEffect(fitting, visibleCols, visibleRows) {
                if (fitting) model.fit(address, visibleCols.coerceIn(20, 500), visibleRows.coerceIn(5, 300)) else model.release(address)
            }
            DisposableEffect(address) { onDispose { model.release(address) } }
            val lines = remember(rows, p) { rows.map { styled(it, p) }.asReversed() }
            val style = base.copy(fontSize = sizeSp.sp, lineHeight = lineSp.sp)
            val sideways = !fitting && !reflow
            val horizontal = rememberScrollState()
            val body = Modifier.fillMaxHeight().then(if (sideways) Modifier.width(with(density) { (cols * colPx).toDp() } + 4.dp) else Modifier.fillMaxWidth())
            // A full-screen program (alternate screen) leaves nothing to scroll locally, so a
            // drag pages it instead: a third of the tile per PageUp or PageDown.
            val paging = rows.size <= visibleRows && pane?.writable == true
            val page = heightPx / 3f
            Box(Modifier.fillMaxSize().pointerInput(address) { pinch { z -> zoom = (zoom * z).coerceIn(0.5f, 4f) } }
                .then(if (sideways) Modifier.horizontalScroll(horizontal) else Modifier)
                .then(if (paging) Modifier.pointerInput(address, page) {
                    var acc = 0f
                    detectDragGestures(onDragStart = { acc = 0f }) { change, drag ->
                        change.consume()
                        acc = (acc + drag.y).coerceIn(-2 * page, 2 * page)
                        if (acc >= page && model.key(address, "PageUp")) acc -= page
                        else if (acc <= -page && model.key(address, "PageDown")) acc += page
                    }
                } else Modifier)) {
                // Newest row first in a reversed list: the bottom stays in view until you scroll up.
                LazyColumn(body, reverseLayout = true) {
                    items(lines.size, key = { lines.size - it }) { i ->
                        Text(lines[i], color = p.ink, style = style, softWrap = false, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
                    }
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

private fun styled(row: List<Ansi.Span>, p: Palette): AnnotatedString = buildAnnotatedString {
    for (s in row) {
        val st = spanStyle(s.style, p)
        if (st == null) append(s.text) else withStyle(st) { append(s.text) }
    }
    if (length == 0) append(" ")
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
