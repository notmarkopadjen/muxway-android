package app.muxway.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import android.Manifest
import android.content.ClipData
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
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
import app.muxway.android.audio.Recorder
import app.muxway.android.protocol.Ansi
import app.muxway.android.protocol.Clip
import app.muxway.android.protocol.Commands
import app.muxway.android.protocol.Complete
import app.muxway.android.protocol.Files
import app.muxway.android.protocol.Input
import app.muxway.android.protocol.Lens
import app.muxway.android.protocol.Links
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
    // Fit: the host pane follows the tile, as a terminal would. On by default.
    var fit by rememberSaveable(addresses) { mutableStateOf(true) }
    var fitAgain by remember { mutableIntStateOf(0) }
    val hardware = model.settings.hardwareKeyboard
    // With a hardware keyboard assumed the on-screen one stays down until asked for.
    var ime by rememberSaveable(hardware) { mutableStateOf(!hardware) }
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FieldFocus() }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    // Several panes: the arrangement cycles through the ones that make sense for the count, the
    // splits drag, and a pane's header dragged onto another tile swaps the two.
    var layout by rememberSaveable(addresses) { mutableStateOf(if (landscape) Layout.ROW else Layout.COLUMN) }
    val writable = state.pane?.writable == true
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val now = ticker()
    val order = model.panes.map { it.address }.ifEmpty { addresses }
    DisposableEffect(Unit) { onDispose { model.releaseFits() } }

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

    // The strip comes round at either end, as ] and [ do in the browser.
    fun neighbour(step: Int) {
        val at = order.indexOf(focused)
        if (at < 0 || order.size < 2) return
        show(order[(at + step + order.size) % order.size])
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
            Tabs(model, addresses, focused, now, Modifier.weight(1f), ::show)
            if (addresses.size > 1) Btn("▦", { layout = layout.next(addresses.size) })
            // Tap: fit on, or send this size once more when it is on already; hold: fit off.
            Btn("⤢", { if (fit) fitAgain++ else fit = true }, selected = fit, enabled = writable, onLongClick = { fit = false })
            if (hardware) Btn("abc", { ime = !ime; if (ime) { fieldFocus.request(); keyboard?.show() } else keyboard?.hide() }, selected = ime)
            Btn("⚙", { model.open(Screen.Connect) })
        }
        Rule()
        Box(Modifier.weight(1f)) {
            // Tiles of one window share one fit; the others of it are held.
            val windowOf = { a: String -> model.panes.firstOrNull { it.address == a }?.let { "${it.host}/${it.session}/${it.windowIndex}" } ?: a }
            val held = TileFit.held(addresses, focused, windowOf)
            // A pane alone in the view sizes its window, zoomed over its siblings when it has any.
            val siblings = { a: String -> model.panes.any { it.address != a && windowOf(it.address) == windowOf(a) } }
            val tile: @Composable (Int, Modifier) -> Unit = { i, m ->
                PaneTile(model, addresses[i], i == index, addresses.size > 1, fit, addresses[i] in held, siblings(addresses[i]), fitAgain, now, m, onDrop = { from -> model.swapTiles(addresses, from, addresses[i]) }, onClose = { model.closePane(addresses, addresses[i]) }) {
                    focusedIndex = i
                    focus.requestFocus()
                }
            }
            Tiles(addresses.size, layout, tile)
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

/** How several panes share the screen. Which ones apply depends on how many there are. */
enum class Layout {
    ROW, COLUMN, GRID, MAIN_LEFT, MAIN_TOP;

    companion object {
        /** Two panes split one way or the other; three and four add a main pane and, for four, a grid. */
        fun options(n: Int): List<Layout> = when {
            n <= 2 -> listOf(ROW, COLUMN)
            n == 3 -> listOf(ROW, COLUMN, MAIN_LEFT, MAIN_TOP)
            else -> listOf(ROW, COLUMN, GRID, MAIN_LEFT, MAIN_TOP)
        }
    }

    fun next(n: Int): Layout {
        val all = options(n)
        return all[(all.indexOf(this) + 1) % all.size]
    }
}

/** The tiles in the chosen arrangement, every split draggable. */
@Composable
private fun Tiles(n: Int, layout: Layout, tile: @Composable (Int, Modifier) -> Unit) {
    val all = (0 until n).toList()
    val weights = remember(n, layout) { Array(3) { SnapshotStateList<Float>().apply { repeat(n) { add(1f) } } } }
    when (layout) {
        Layout.ROW -> Split(false, all, weights[0], Modifier.fillMaxSize(), tile)
        Layout.COLUMN -> Split(true, all, weights[0], Modifier.fillMaxSize(), tile)
        Layout.MAIN_LEFT -> Split(false, listOf(0, 1), weights[0], Modifier.fillMaxSize()) { part, m ->
            if (part == 0) tile(0, m) else Split(true, all.drop(1), weights[1], m, tile)
        }
        Layout.MAIN_TOP -> Split(true, listOf(0, 1), weights[0], Modifier.fillMaxSize()) { part, m ->
            if (part == 0) tile(0, m) else Split(false, all.drop(1), weights[1], m, tile)
        }
        Layout.GRID -> {
            val top = all.take((n + 1) / 2)
            val bottom = all.drop((n + 1) / 2)
            Split(true, listOf(0, 1), weights[0], Modifier.fillMaxSize()) { part, m ->
                if (part == 0) Split(false, top, weights[1], m, tile) else Split(false, bottom, weights[2], m, tile)
            }
        }
    }
}

/**
 * [items] side by side ([vertical] false) or stacked, sharing the space by [weights] (indexed like
 * [items]), with a bar between neighbours that drags the split. One item takes it all, no bar.
 */
@Composable
private fun Split(vertical: Boolean, items: List<Int>, weights: SnapshotStateList<Float>, modifier: Modifier, content: @Composable (Int, Modifier) -> Unit) {
    val p = LocalPalette.current
    if (items.size == 1) { content(items[0], modifier); return }
    BoxWithConstraints(modifier) {
        val total = (if (vertical) constraints.maxHeight else constraints.maxWidth).toFloat().coerceAtLeast(1f)
        val sum = items.sumOf { weights[it].toDouble() }.toFloat()
        val least = 0.15f * sum
        fun drag(k: Int, px: Float) {
            val a = items[k]
            val b = items[k + 1]
            val d = (px / total * sum).coerceIn(-(weights[a] - least), weights[b] - least)
            weights[a] += d
            weights[b] -= d
        }
        val bar: @Composable (Int) -> Unit = { k ->
            Box(
                Modifier.then(if (vertical) Modifier.fillMaxWidth().height(10.dp) else Modifier.fillMaxHeight().width(10.dp))
                    .background(p.bg)
                    .pointerInput(k, vertical) { detectDragGestures { change, delta -> change.consume(); drag(k, if (vertical) delta.y else delta.x) } },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.then(if (vertical) Modifier.fillMaxWidth(0.3f).height(2.dp) else Modifier.fillMaxHeight(0.3f).width(2.dp)).background(p.dim))
            }
        }
        if (vertical) {
            Column(Modifier.fillMaxSize()) {
                for ((k, i) in items.withIndex()) {
                    content(i, Modifier.fillMaxWidth().weight(weights[i]))
                    if (k < items.lastIndex) bar(k)
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                for ((k, i) in items.withIndex()) {
                    content(i, Modifier.fillMaxHeight().weight(weights[i]))
                    if (k < items.lastIndex) bar(k)
                }
            }
        }
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

/**
 * Focus for the text field, which leaves the screen while a clip records or is heard. Asked for
 * then, it is given once the field is back; asking the requester directly would throw.
 */
private class FieldFocus {
    val requester = FocusRequester()
    var wanted by mutableStateOf(false)
        private set

    fun request() { wanted = true }

    fun give() {
        wanted = false
        requester.requestFocus()
    }
}

/**
 * Esc pinned left, Enter pinned right, the other keys scrolling between; under them the command
 * row for the program in the pane, when it has one; then the text line, with the file button
 * where the host has an inbox and the microphone button when the hub has a transcriber.
 */
@Composable
private fun InputBar(model: AppModel, state: PaneState, fieldFocus: FieldFocus, onFieldFocus: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val line = key(state.address) { rememberTextFieldState() }
    val text = line.text.toString()
    fun send(enter: Boolean) {
        if (text.isEmpty() && !enter) return
        model.type(state.address, text, enter)
        line.clearText()
    }
    val busy = state.busy

    // Files for the pane's program: from the keyboard or a pasted clip through the line, from
    // the picker through ⇪, from a share through the chooser. Each path lands on the line with
    // a space after it, focused; nothing is sent. A host without an inbox is told about.
    LaunchedEffect(state.address) { model.probeInbox(state.address) }
    val takes = model.takesFiles(state.address)
    val sending = state.sending
    fun files(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (takes == false) model.sendError = "No inbox on ${state.pane?.host ?: state.address.substringBefore('/')}: its agent needs --inbox"
        else model.sendFiles(state.address, uris)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files(it) }
    LaunchedEffect(state.landed) {
        if (state.landed.isEmpty()) return@LaunchedEffect
        var t = line.text.toString()
        for (path in state.landed) t = Files.onLine(t, path)
        state.landed = emptyList()
        line.setTextAndPlaceCursorAtEnd(t)
        fieldFocus.request()
    }

    // The microphone: a clip for the hub's transcriber, its words onto the line to be read
    // before send. The recorder reports from its own thread; the main one draws.
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var recorder by remember(state.address) { mutableStateOf<Recorder?>(null) }
    var meter by remember { mutableStateOf(Clip.meter(emptyList())) }
    var seconds by remember { mutableIntStateOf(0) }
    var hearing by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { if (!busy) hearing = false }
    fun stopClip() {
        val r = recorder ?: return
        recorder = null
        val clip = r.stop()
        if (!clip.sendable) {
            model.sendError = "Too short"
            return
        }
        hearing = model.transcribe(state.address, clip.wav()) { heard ->
            val t = line.text.toString()
            line.setTextAndPlaceCursorAtEnd(if (t.isEmpty()) heard else "$t $heard")
            fieldFocus.request()
        }
    }
    fun startClip() {
        if (recorder != null) return
        meter = Clip.meter(emptyList())
        seconds = 0
        val r = Recorder(
            onFrame = { c -> main.post { meter = Clip.meter(c.levels); seconds = c.seconds.toInt() } },
            onFull = { main.post { stopClip() } },
        )
        if (r.start()) recorder = r else model.sendError = "No microphone"
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startClip() else model.sendError = "Microphone refused"
    }
    fun speak() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startClip()
        else ask.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun dropClip() {
        recorder?.cancel()
        recorder = null
    }
    // The keyboard panel, for this pane; back closes it.
    var board by rememberSaveable(state.address) { mutableStateOf(false) }
    val writable = state.pane?.mode == "write"
    BackHandler(enabled = board && recorder == null) { board = false }
    BackHandler(enabled = recorder != null) { dropClip() }
    DisposableEffect(state.address) { onDispose { dropClip() } }
    if (board && writable) state.pane?.let { KeyboardPanel(model, state.address, it.host) { board = false } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Btn("Esc", { model.key(state.address, "Escape") }, enabled = !busy)
        LazyRow(Modifier.weight(1f)) {
            items(Input.KEYS.filter { it.first != "Escape" && it.first != "Enter" }, key = { it.first }) { (name, label) ->
                Btn(label, { model.key(state.address, name) }, enabled = !busy)
            }
        }
        Btn("Enter", { model.key(state.address, "Enter") }, enabled = !busy)
    }
    state.pane?.let { Commands.forPane(it, model.recent) }?.let { (kind, commands) ->
        val claude = kind == Commands.Kind.CLAUDE
        val fixed = if (claude) commands.size else Commands.SHELL.size
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (claude) " ${Lens.MARK} " else " $ ", color = if (claude) p.claude else p.dim, maxLines = 1)
            LazyRow(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(commands, key = { _, c -> c.text }) { i, c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (i == fixed) Text(" │ ", color = p.faint, maxLines = 1)
                        Btn(c.label, {
                            if (c.send) model.type(state.address, c.text, enter = true)
                            else { line.setTextAndPlaceCursorAtEnd(c.text); fieldFocus.request() }
                        }, enabled = !busy)
                    }
                }
            }
        }
    }
    // While the first word is being typed, what the program takes is offered above the line; a
    // tap takes one. Only constants and this client's own sent lines are ever offered.
    val picks = if (recorder != null || hearing || sending != null) emptyList() else state.pane?.let { Complete.forPane(it, text, text.length, model.recent) }.orEmpty()
    if (picks.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(" ⇥ ", color = p.faint, maxLines = 1)
            LazyRow(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                items(picks, key = { it }) { c ->
                    Btn(c, { line.setTextAndPlaceCursorAtEnd(Complete.take(text, text.length, c).first); fieldFocus.request() }, enabled = !busy)
                }
            }
        }
    }
    // The microphone sits between the line and the sending keys in every state, so a press and
    // its release are one gesture even though the line gives way to the meter in between.
    var pressedIdle by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            recorder != null -> {
                Text("● ", color = p.alert, maxLines = 1)
                Text(
                    "$meter $seconds s · tap to stop", color = p.ink, maxLines = 1,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp).border(1.dp, p.ink).clickable { stopClip() }.padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
            hearing -> {
                Text("> ", color = p.dim, maxLines = 1)
                Text("⟳ hearing…", color = p.dim, maxLines = 1, modifier = Modifier.weight(1f).padding(vertical = 4.dp).border(1.dp, p.dim).padding(horizontal = 6.dp, vertical = 6.dp))
            }
            sending != null -> {
                Text("> ", color = p.dim, maxLines = 1)
                Text(sending, color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 4.dp).border(1.dp, p.dim).padding(horizontal = 6.dp, vertical = 6.dp))
            }
            else -> {
                LaunchedEffect(fieldFocus.wanted) { if (fieldFocus.wanted) fieldFocus.give() }
                Line(
                    ">", line,
                    Modifier.weight(1f),
                    hint = state.pane?.label ?: "",
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                    focusRequester = fieldFocus.requester,
                    onFocus = onFieldFocus,
                    maxLines = 6,
                    onSend = { send(true) },
                    onContent = ::files,
                )
            }
        }
        Btn("⌨\uFE0E", { board = !board }, selected = board, enabled = writable, modifier = Modifier.testTag("keyboard-open"))
        if (takes == true) Btn("⇪", { pick.launch(Files.MIMES.toTypedArray()) }, enabled = !busy && sending == null, modifier = Modifier.testTag("files"))
        // Held, it records until the finger lifts; tapped, it records and the next tap stops.
        if (model.transcriber) {
            Btn(
                if (recorder != null) "■" else "◉", {}, enabled = !busy && !hearing,
                onDown = { pressedIdle = recorder == null; if (pressedIdle) speak() },
                onUp = { held -> if (held || !pressedIdle) stopClip() },
            )
        }
        when {
            recorder != null -> Btn("cancel", { dropClip() })
            hearing || sending != null -> Btn("⏎", {}, enabled = false)
            else -> {
                Btn("type", { send(false) }, enabled = !busy && text.isNotEmpty())
                Btn("⏎", { send(true) }, enabled = !busy, modifier = Modifier.testTag("send"))
            }
        }
    }
}

/**
 * A pane: a header painted with its state, then its rows in colour, newest at the bottom, scrolling
 * back through what the hub sent. With fit the host pane is sized to the tile at the text setting;
 * otherwise the pane's own width is scaled to fit (or reflowed, by setting), pinch zooms.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun PaneTile(model: AppModel, address: String, focused: Boolean, several: Boolean, fitAsked: Boolean, shared: Boolean, siblings: Boolean, fitAgain: Int, now: Long, modifier: Modifier, onDrop: (String) -> Unit, onClose: () -> Unit, onFocus: () -> Unit) {
    // Another tile of this window carries the fit: this one is held.
    val fit = fitAsked && !shared
    var asking by remember(address) { mutableStateOf(false) }
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
    // Another tile's header dropped here swaps the two panes.
    val target = remember(address) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val from = event.toAndroidDragEvent().clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: return false
                onDrop(from)
                return true
            }
        }
    }
    Column(
        modifier.padding(2.dp).border(edge, if (several && focused) p.ink else p.faint).clickable(onClick = onFocus)
            .then(if (several) Modifier.dragAndDropTarget(shouldStartDragAndDrop = { it.mimeTypes().contains(MIME) }, target = target) else Modifier),
    ) {
        val title = pane?.let { "${it.name} · ${it.command} · ${it.mode}" } ?: address
        val waiting = status == Lens.Status.WAITING
        val header = Modifier.fillMaxWidth().background(back).padding(horizontal = 4.dp, vertical = 2.dp)
            .then(if (several) Modifier.dragAndDropSource {
                detectDragGesturesAfterLongPress { _, _ -> startTransfer(DragAndDropTransferData(ClipData.newPlainText(MIME, address))) }
            } else Modifier)
        Row(header, verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                style = LocalTextStyle.current.copy(fontWeight = if (several && focused) FontWeight.Bold else FontWeight.Normal, shadow = null),
            )
            status?.let { Lens.glyph(it, pane?.attention?.kind) }?.let { g ->
                Text(" $g ${Lens.word(status, pane?.attention?.kind)} ", color = ink, maxLines = 1, modifier = if (waiting) Modifier.alpha(pulse()) else Modifier)
            }
            if (fitAsked && pane?.writable == true) {
                // Alert colour when the host did not keep this phone's size: another terminal holds
                // it, or another tile of the window carries the fit.
                val held = shared || (state.fitKey.isNotEmpty() && state.fitKey != "${pane.width}x${pane.height}")
                Text(" ${pane.width}×${pane.height}${if (held) " held" else ""} ", color = if (held) p.alert else ink, maxLines = 1)
            }
            // Closing ends what runs in the pane on the host: the header asks first.
            if (pane?.writable == true && model.hostCloses(address)) {
                if (asking) {
                    Text(" close the pane on ${pane.host}? ", color = ink, maxLines = 1, fontWeight = FontWeight.Bold)
                    Text(" y ", color = ink, maxLines = 1, modifier = Modifier.border(1.dp, ink).clickable { asking = false; onClose() })
                    Text(" n ", color = ink, maxLines = 1, modifier = Modifier.padding(start = 4.dp).border(1.dp, ink).clickable { asking = false })
                } else {
                    Text(" × ", color = ink, maxLines = 1, modifier = Modifier.clickable { asking = true })
                }
            }
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
            // The on-screen keyboard halves the tile for a moment; the host keeps its size then.
            val imeUp = WindowInsets.isImeVisible
            val listed = pane?.let { "${it.width}x${it.height}" }
            // Alone in the view the window takes the size, with the pane zoomed over its siblings.
            val fitWindow = !several
            val fitZoom = fitWindow && siblings
            LaunchedEffect(fitting, visibleCols, visibleRows, imeUp, fitAgain, listed, fitWindow, fitZoom) {
                if (!fitting) model.release(address)
                else if (!imeUp) model.fit(address, visibleCols.coerceIn(20, 500), visibleRows.coerceIn(5, 300), again = fitAgain > 0 && listed != null, window = fitWindow, zoom = fitZoom)
            }
            // A tile that gives way to another pane leaves its own at size; the view gives them
            // all back when it is left.
            DisposableEffect(address) { onDispose { model.keep(address) } }
            val lines = remember(rows, p) { rows.map { styled(it, p) }.asReversed() }
            val style = base.copy(fontSize = sizeSp.sp, lineHeight = lineSp.sp)
            val sideways = !fitting && !reflow
            val horizontal = rememberScrollState()
            val body = Modifier.fillMaxHeight().then(if (sideways) Modifier.width(with(density) { (cols * colPx).toDp() } + 4.dp) else Modifier.fillMaxWidth())
            // A full-screen program (alternate screen) leaves nothing to scroll locally, so a
            // vertical drag scrolls it instead: a wheel notch per row when the program has the
            // mouse, the rows since the last request counted into one, else a third of the tile
            // per PageUp or PageDown. The list then takes no drags at all, and never its stretch
            // effect, so the gesture reaches us and sideways swipes reach the page.
            val scrolls = rows.size > visibleRows
            val paging = !scrolls && pane?.writable == true
            val wheel = pane?.mouse == true
            val page = if (wheel) rowPx else heightPx / 3f
            // A tap is a click at the cell under the finger; the rows sit at the bottom. A tap
            // on a link is the browser's: the text opens it and the host hears nothing.
            val clickable = pane?.writable == true
            val top = (heightPx - rows.size * rowPx).coerceAtLeast(0f)
            val shown by rememberUpdatedState(rows)
            Box(Modifier.fillMaxSize().pointerInput(address) { pinch { z -> zoom = (zoom * z).coerceIn(0.5f, 4f) } }
                .pointerInput(address, clickable, top, colPx, rowPx) {
                    if (!clickable) return@pointerInput
                    detectTapGestures { at ->
                        val x = (at.x / colPx).toInt() + 1
                        val y = ((at.y - top) / rowPx).toInt() + 1
                        val w = pane?.width ?: 0
                        val h = pane?.height ?: 0
                        val row = shown.getOrNull(y - 1)?.joinToString("") { it.text }
                        if (row != null && Links.at(row, x - 1) != null) return@detectTapGestures
                        if (at.y >= top && x in 1..w && y in 1..h) model.click(address, x, y)
                    }
                }
                .then(if (sideways) Modifier.horizontalScroll(horizontal) else Modifier)
                .pointerInput(address, page, paging, wheel) {
                    if (!paging) return@pointerInput
                    var acc = 0f
                    detectVerticalDragGestures(onDragStart = { acc = 0f }) { change, dy ->
                        change.consume()
                        acc += dy
                        if (wheel) {
                            // Whole rows go now, at the cell under the finger; the rest waits.
                            val notches = (acc / page).toInt()
                            if (notches != 0) {
                                val at = change.position
                                val cx = ((at.x / colPx).toInt() + 1).coerceIn(1, (pane?.width ?: 1).coerceAtLeast(1))
                                val cy = (((at.y - top) / rowPx).toInt() + 1).coerceIn(1, (pane?.height ?: 1).coerceAtLeast(1))
                                model.wheel(address, notches > 0, cx, cy, abs(notches))
                                acc -= notches * page
                            }
                        } else {
                            acc = acc.coerceIn(-3 * page, 3 * page)
                            val up = acc >= page
                            val down = acc <= -page
                            if ((up || down) && model.key(address, if (up) "PageUp" else "PageDown")) acc += if (up) -page else page
                        }
                    }
                }) {
                // Newest row first in a reversed list: the bottom stays in view until you scroll up.
                @OptIn(ExperimentalFoundationApi::class)
                CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                    LazyColumn(body, reverseLayout = true, userScrollEnabled = scrolls) {
                        items(lines.size, key = { lines.size - it }) { i ->
                            Text(lines[i], color = p.ink, style = style, softWrap = false, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

private const val MIME = "text/plain"

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

private val linkStyles = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))

// Colours as the pane gave them; a web address in the row opens through the system.
private fun styled(row: List<Ansi.Span>, p: Palette): AnnotatedString = buildAnnotatedString {
    for (s in row) {
        val st = spanStyle(s.style, p)
        if (st == null) append(s.text) else withStyle(st) { append(s.text) }
    }
    for (l in Links.find(toString())) addLink(LinkAnnotation.Url(l.href, linkStyles), l.start, l.end)
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
