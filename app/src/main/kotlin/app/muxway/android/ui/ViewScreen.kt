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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.muxway.android.AppModel
import app.muxway.android.Naming
import app.muxway.android.PaneState
import app.muxway.android.Screen
import app.muxway.android.audio.Recorder
import app.muxway.android.protocol.AddMenu
import app.muxway.android.protocol.Ansi
import app.muxway.android.protocol.Clip
import app.muxway.android.protocol.Commands
import app.muxway.android.protocol.Complete
import app.muxway.android.protocol.Files
import app.muxway.android.protocol.Input
import app.muxway.android.protocol.Keys
import app.muxway.android.protocol.Lens
import app.muxway.android.protocol.Links
import app.muxway.android.protocol.Blocks
import app.muxway.android.protocol.Pane
import app.muxway.android.protocol.WindowLayout
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * One or more panes, side by side in landscape and stacked in portrait. The top row is the tab
 * strip, coloured by each pane's state, `+` for one more, and the controls; a swipe across the
 * page moves to the neighbouring pane; a pane minimised waits in a row above the keys; one
 * input bar serves the focused pane. [addresses] are keys, every open pane of the view.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ViewScreen(model: AppModel, addresses: List<String>) {
    val p = LocalPalette.current
    // Minimised panes are open but not tiles: their tabs wait in the row above the keys.
    var parked by rememberSaveable(saver = listSaver<MutableState<List<String>>, String>({ it.value }, { mutableStateOf(it) })) { mutableStateOf(listOf()) }
    val minimised = parked.filter { it in addresses }
    val tiles = addresses.filter { it !in minimised }.ifEmpty { addresses.take(1) }
    var focusedIndex by rememberSaveable { mutableIntStateOf(0) }
    val index = focusedIndex.coerceIn(0, tiles.lastIndex)
    val focused = tiles[index]
    val state = model.view(focused)
    // Fit: the host pane follows the tile, as a terminal would. On by default. Kept by the count
    // of panes, not their addresses: a layout the host took may rename them.
    var fit by rememberSaveable(addresses.size) { mutableStateOf(true) }
    var fitAgain by remember { mutableIntStateOf(0) }
    val hardware = model.settings.hardwareKeyboard
    // With a hardware keyboard assumed the on-screen one stays down until asked for.
    var ime by rememberSaveable(hardware) { mutableStateOf(!hardware) }
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FieldFocus() }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    // Several panes: the arrangement cycles through the ones that make sense for the count, the
    // splits drag, and a pane's header dragged onto another tile swaps the two.
    var layout by rememberSaveable(addresses.size) { mutableStateOf(if (landscape) Layout.ROW else Layout.COLUMN) }
    val writable = state.pane?.writable == true
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val now = ticker()
    val order = model.panes.map { it.key }.ifEmpty { addresses }
    DisposableEffect(Unit) { onDispose { model.releaseFits() } }
    // The `+` menu after the tabs, and the session dialog it may open.
    var adding by remember { mutableStateOf(false) }
    model.naming?.takeIf { it.into != null }?.let { SessionDialog(it, model) }
    // A file of the host over the pane; the height the text line takes beyond one row, which
    // the tiles lose and the fit must not take for a resize.
    var shown by remember { mutableStateOf<Shown?>(null) }
    var slackPx by remember { mutableIntStateOf(0) }
    shown?.let { FileSheet(model, it, onOpen = { next -> shown = next }) { shown = null } }

    fun hardKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown || !writable) return false
        val name = HardKeys.name(e.key) ?: return false
        model.key(focused, name)
        return true
    }

    fun show(key: String) {
        val at = tiles.indexOf(key)
        when {
            at >= 0 -> focusedIndex = at
            key in minimised -> {
                parked = minimised - key
                focusedIndex = addresses.filter { it !in parked }.indexOf(key)
            }
            else -> model.swap(addresses, addresses.indexOf(focused), key)
        }
        focus.requestFocus()
    }

    // An existing pane from the menu: one more tile, focused; at four it takes the focused tile's place.
    fun add(key: String) {
        if (key in minimised) { show(key); return }
        val full = tiles.size >= AddMenu.MAX_TILES
        focusedIndex = if (full) index else tiles.size
        model.addPane(addresses, addresses.indexOf(focused), key, full)
        focus.requestFocus()
    }

    // `_`: the tile leaves for the row above the keys and gives its size back; the last tile stays.
    fun minimise(key: String) {
        if (tiles.size < 2) return
        model.release(key)
        parked = minimised + key
        if (key == focused) focusedIndex = 0
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
            Tabs(model, tiles, minimised, focused, now, Modifier.weight(1f), ::show)
            Box {
                Btn("+", { adding = true }, selected = adding, modifier = Modifier.testTag("add"))
                if (adding) AddMenuPopup(model, addresses, tiles, minimised, focused, now, onClose = { adding = false }) { add(it) }
            }
            if (tiles.size > 1) Btn("▦", { layout = layout.next(tiles.size) })
            // Tap: fit on, or send this size once more when it is on already; hold: fit off.
            Btn("⤢", { if (fit) fitAgain++ else fit = true }, selected = fit, enabled = writable, onLongClick = { fit = false })
            if (hardware) Btn("abc", { ime = !ime; if (ime) { fieldFocus.request(); keyboard?.show() } else keyboard?.hide() }, selected = ime)
            Btn("⚙", { model.open(Screen.Connect) })
        }
        Rule()
        Box(Modifier.weight(1f)) {
            // Tiles that are every pane of one window fit it as one, laid out as the tiles are;
            // tiles that are only some of a window's panes share one fit and the others are held.
            val windowOf = { a: String -> model.panes.firstOrNull { it.key == a }?.let { "${it.hub}/${it.host}/${it.session}/${it.windowIndex}" } ?: a }
            val ids = TileFit.oneWindow(tiles, model.panes, windowOf)
            val held = if (ids != null) emptySet() else TileFit.held(tiles, focused, windowOf)
            // A pane alone in the view sizes its window, zoomed over its siblings when it has any.
            val siblings = { a: String -> model.panes.any { it.key != a && windowOf(it.key) == windowOf(a) } }
            // Each tile reports its cells; the window's layout is exact to them, the input line's
            // growth counted on the tiles along the bottom only.
            val tree = layout.tree(tiles.size)
            val bottom = WindowLayout.bottomLeaves(tree).toSet()
            val sizes = remember { mutableStateMapOf<String, WindowLayout.Size>() }
            LaunchedEffect(tiles) { sizes.keys.retainAll(tiles.toSet()) }
            val exact = if (ids != null && tiles.all { it in sizes }) WindowLayout.exact(tree, tiles.map { sizes.getValue(it) }, ids) else null
            val lead = tiles[0]
            val imeUp = WindowInsets.isImeVisible
            var sentAgain by remember { mutableIntStateOf(fitAgain) }
            LaunchedEffect(ids, exact?.layout, fit, imeUp, fitAgain) {
                if (ids == null) return@LaunchedEffect
                if (!fit) { for (t in tiles) model.release(t); return@LaunchedEffect }
                if (exact == null || imeUp) return@LaunchedEffect
                model.fit(lead, exact.cols.coerceIn(20, 500), exact.rows.coerceIn(5, 300), again = fitAgain != sentAgain, window = true, layout = exact.layout, mates = tiles)
                sentAgain = fitAgain
            }
            val sent = ids != null && model.view(lead).fitted
            val tile: @Composable (Int, Modifier) -> Unit = { i, m ->
                key(tiles[i]) {
                    PaneTile(
                        model, tiles[i], i == index, tiles.size > 1, fit, tiles[i] in held, siblings(tiles[i]), ids != null, exact?.sizes?.get(i)?.takeIf { sent },
                        fitAgain, now, if (i in bottom) slackPx else 0, m,
                        onMeasured = { sizes[tiles[i]] = it },
                        onDrop = { from -> model.swapTiles(addresses, from, tiles[i]) },
                        onClose = { model.closePane(tiles[i]) },
                        onMinimise = if (tiles.size > 1) ({ minimise(tiles[i]) }) else null,
                        onFile = { shown = it },
                        onPrompt = { focusedIndex = i; fieldFocus.request() },
                    ) {
                        focusedIndex = i
                        focus.requestFocus()
                    }
                }
            }
            Tiles(tiles.size, layout, tile)
        }
        Rule()
        if (minimised.isNotEmpty()) MinimisedRow(model, minimised, now) { show(it) }
        model.sendError?.let { Text(" ! $it", color = p.alert, modifier = Modifier.padding(horizontal = 4.dp)) }
        model.told?.let { Text(" $it", color = p.dim, modifier = Modifier.padding(horizontal = 4.dp)) }
        LaunchedEffect(model.told) { if (model.told != null) { delay(4000); model.told = null } }
        val pane = state.pane
        when {
            pane == null -> Text(" …", color = p.dim, modifier = Modifier.padding(4.dp))
            !pane.writable -> Text(" read-only · ${pane.address}", color = p.dim, modifier = Modifier.padding(4.dp))
            else -> InputBar(model, state, fieldFocus, onGrow = { slackPx = it }) { gained -> if (gained && hardware && !ime) keyboard?.hide() }
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

    /** The arrangement as the tree of splits [Tiles] draws, its leaves the tile indexes. */
    fun tree(n: Int): WindowLayout.Node {
        val all: List<WindowLayout.Node> = (0 until n).map { WindowLayout.Node.Leaf(it) }
        fun split(horizontal: Boolean, kids: List<WindowLayout.Node>) = if (kids.size == 1) kids[0] else WindowLayout.Node.Split(horizontal, kids)
        if (n <= 1) return all.firstOrNull() ?: WindowLayout.Node.Leaf(0)
        return when (this) {
            ROW -> split(true, all)
            COLUMN -> split(false, all)
            MAIN_LEFT -> split(true, listOf(all[0], split(false, all.drop(1))))
            MAIN_TOP -> split(false, listOf(all[0], split(true, all.drop(1))))
            GRID -> split(false, listOf(split(true, all.take((n + 1) / 2)), split(true, all.drop((n + 1) / 2))))
        }
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
private fun Tabs(model: AppModel, open: List<String>, minimised: List<String>, focused: String, now: Long, modifier: Modifier, onTap: (String) -> Unit) {
    val p = LocalPalette.current
    val panes = ArrayList<Pair<String, Pane?>>()
    for (pane in model.panes) if (pane.key !in minimised) panes.add(pane.key to pane)
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
                    pane?.name ?: Keys.address(address).substringAfter('/'), color = ink, maxLines = 1,
                    style = LocalTextStyle.current.copy(fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal, shadow = null),
                )
            }
        }
    }
}

/**
 * The panes minimised out of the tiles, as tabs right above the keys, each still read so its
 * state keeps moving; a tap brings the tile back.
 */
@Composable
private fun MinimisedRow(model: AppModel, keys: List<String>, now: Long, onTap: (String) -> Unit) {
    val p = LocalPalette.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val every = model.settings.refreshSeconds
    Row(Modifier.fillMaxWidth().background(p.bg).horizontalScroll(rememberScrollState()).testTag("minimised"), verticalAlignment = Alignment.CenterVertically) {
        Text(" _ ", color = p.faint, maxLines = 1)
        for (k in keys) key(k) {
            LaunchedEffect(k, every) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        model.pollOnce(k)
                        delay(every * 1000L)
                    }
                }
            }
            val pane = model.panes.firstOrNull { it.key == k } ?: model.view(k).pane
            val status = pane?.let { model.status(it, now) }
            val (back, ink) = stateColours(status)
            Row(
                Modifier.padding(2.dp).border(1.dp, p.ink).background(back).clickable { onTap(k) }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                status?.let { Lens.glyph(it, pane?.attention?.kind) }?.let { Text("$it ", color = ink, maxLines = 1, modifier = if (status == Lens.Status.WAITING) Modifier.alpha(pulse()) else Modifier) }
                Text(pane?.name ?: Keys.address(k).substringAfter('/'), color = ink, maxLines = 1, style = LocalTextStyle.current.copy(shadow = null))
            }
        }
    }
    Rule()
}

/**
 * The `+` menu: one more pane beside the focused one, a session on its host, the minimised
 * panes, then every other pane by hub, host and session; a pick becomes a tile.
 */
@Composable
private fun AddMenuPopup(model: AppModel, addresses: List<String>, tiles: List<String>, minimised: List<String>, focused: String, now: Long, onClose: () -> Unit, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    val rows = AddMenu.rows(model.panes, model.hosts, tiles, minimised, focused) { model.hubName(it) }
    BackHandler(onBack = onClose)
    Popup(alignment = Alignment.TopEnd, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(300.dp).border(1.dp, p.ink).background(p.panel).padding(4.dp).testTag("add-menu")
                .then(Modifier.heightIn(max = 420.dp)).verticalScroll(rememberScrollState()),
        ) {
            val item: @Composable (String, String?, String?, () -> Unit) -> Unit = { label, place, reason, act ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = reason == null, onClick = act).padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = if (reason == null) p.ink else p.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    (reason ?: place)?.let { Text("  $it", color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                }
            }
            for (row in rows) when (row) {
                is AddMenu.Row.NewPane -> item("+ pane", "beside ${row.pane.name}", row.reason) {
                    onClose(); model.splitWindow(row.pane, into = addresses)
                }
                is AddMenu.Row.NewSession -> item("+ session", "on ${row.host}", row.reason) {
                    onClose(); model.createError = null; model.naming = Naming(row.hub, row.host, into = addresses)
                }
                is AddMenu.Row.Rule -> Rule(Modifier.padding(vertical = 2.dp))
                is AddMenu.Row.Heading -> Text(row.text, color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                is AddMenu.Row.Item -> {
                    val g = Lens.glyph(model.status(row.pane, now), row.pane.attention?.kind)?.let { "$it " } ?: ""
                    item("$g${row.pane.name}", if (row.minimised) "minimised" else row.pane.label, null) { onClose(); onPick(row.pane.key) }
                }
            }
            if (rows.isEmpty()) Text("nothing to add", color = p.dim, modifier = Modifier.padding(4.dp))
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
private fun InputBar(model: AppModel, state: PaneState, fieldFocus: FieldFocus, onGrow: (Int) -> Unit, onFieldFocus: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val line = key(state.address) { rememberTextFieldState() }
    val text = line.text.toString()
    fun send(enter: Boolean) {
        if (text.isEmpty() && !enter) return
        model.type(state.key, text, enter)
        line.clearText()
    }
    val busy = state.busy

    // Files for the pane's program: from the keyboard or a pasted clip through the line, from
    // the picker through ⇪, from a share through the chooser. Each path lands on the line with
    // a space after it, focused; nothing is sent. A host without an inbox is told about.
    LaunchedEffect(state.address) { model.probeInbox(state.key) }
    val takes = model.takesFiles(state.key)
    val sending = state.sending
    fun files(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (takes == false) model.sendError = "No inbox on ${state.pane?.host ?: state.address.substringBefore('/')}: its agent needs --inbox"
        else model.sendFiles(state.key, uris)
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
        hearing = model.transcribe(state.key, clip.wav()) { heard ->
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
    if (board && writable) state.pane?.let { KeyboardPanel(model, state.key, it.host) { board = false } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Btn("Esc", { model.key(state.key, "Escape") }, enabled = !busy)
        LazyRow(Modifier.weight(1f)) {
            items(Input.KEYS.filter { it.first != "Escape" && it.first != "Enter" }, key = { it.first }) { (name, label) ->
                Btn(label, { model.key(state.key, name) }, enabled = !busy)
            }
        }
        Btn("Enter", { model.key(state.key, "Enter") }, enabled = !busy)
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
                            if (c.send) model.type(state.key, c.text, enter = true)
                            else { line.setTextAndPlaceCursorAtEnd(c.text); fieldFocus.request() }
                        }, enabled = !busy)
                    }
                }
            }
        }
    }
    // While a word is being typed, what the program takes is offered above the line, then the
    // words on the pane's screen, dimmer; a tap takes one. Nothing the hub said is ever offered.
    val words = remember(state.content) { Complete.screenWords(state.content) }
    val picks = if (recorder != null || hearing || sending != null) emptyList() else state.pane?.let { Complete.offers(it, text, text.length, model.recent, words) }.orEmpty()
    if (picks.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(" ⇥ ", color = p.faint, maxLines = 1)
            LazyRow(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                items(picks, key = { it.text }) { c ->
                    Btn(if (c.word) "${c.text} ⇥" else c.text, { line.setTextAndPlaceCursorAtEnd(Complete.take(text, text.length, c.text).first); fieldFocus.request() }, enabled = !busy)
                }
            }
        }
    }
    // The field's height beyond one row is the view's to know: it is not a resize.
    var oneRow by remember { mutableIntStateOf(Int.MAX_VALUE) }
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
                    Modifier.weight(1f).onSizeChanged { size -> oneRow = minOf(oneRow, size.height); onGrow((size.height - oneRow).coerceAtLeast(0)) },
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
        if (model.transcriber(state.key)) {
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
private fun PaneTile(
    model: AppModel, address: String, focused: Boolean, several: Boolean, fitAsked: Boolean, shared: Boolean, siblings: Boolean,
    windowFit: Boolean, expected: WindowLayout.Size?, fitAgain: Int, now: Long, slackPx: Int, modifier: Modifier,
    onMeasured: (WindowLayout.Size) -> Unit, onDrop: (String) -> Unit, onClose: () -> Unit, onMinimise: (() -> Unit)?, onFile: (Shown) -> Unit, onPrompt: () -> Unit, onFocus: () -> Unit,
) {
    // Another tile of this window carries the fit: this one is held. With the window fitted as
    // one ([windowFit]) the view sends the size and this tile is [expected] to be its share.
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
                val held = when {
                    shared -> true
                    windowFit -> expected != null && (pane.width != expected.cols || pane.height != expected.rows)
                    else -> state.fitKey.isNotEmpty() && state.fitKey != "${pane.width}x${pane.height}"
                }
                Text(" ${pane.width}×${pane.height}${if (held) " held" else ""} ", color = if (held) p.alert else ink, maxLines = 1)
            }
            // Minimising keeps the pane open in the row above the keys; off on the last tile.
            Text(" _ ", color = if (onMinimise != null) ink else p.faint, maxLines = 1, modifier = Modifier.clickable(enabled = onMinimise != null) { onMinimise?.invoke() }.testTag("minimise"))
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
            // The rows the text line took while a message is written are counted as if they were here.
            val visibleRows = ((heightPx + slackPx) / rowPx).toInt().coerceAtLeast(1)
            val cols = if (fitting || reflow) visibleCols else paneCols
            val rows = remember(state.content, cols) { if (cols == paneCols && pane?.width == null) unwrapped else Ansi.rows(state.content, cols) }
            // The on-screen keyboard halves the tile for a moment; the host keeps its size then.
            val imeUp = WindowInsets.isImeVisible
            val listed = pane?.let { "${it.width}x${it.height}" }
            // Alone in the view the window takes the size, with the pane zoomed over its siblings.
            val fitWindow = !several
            val fitZoom = fitWindow && siblings
            LaunchedEffect(visibleCols, visibleRows) { onMeasured(WindowLayout.Size(visibleCols, visibleRows)) }
            // A held tile keeps the size it has; fit turned off gives it back.
            LaunchedEffect(fitting, visibleCols, visibleRows, imeUp, fitAgain, listed, fitWindow, fitZoom, windowFit, shared) {
                when {
                    windowFit -> {}
                    !fitting -> if (shared && fitAsked) model.keep(address) else model.release(address)
                    !imeUp -> model.fit(address, visibleCols.coerceIn(20, 500), visibleRows.coerceIn(5, 300), again = fitAgain > 0 && listed != null, window = fitWindow, zoom = fitZoom)
                }
            }
            // A tile that gives way to another pane leaves its own at size; the view gives them
            // all back when it is left.
            DisposableEffect(address) { onDispose { model.keep(address) } }
            val clickable = pane?.writable == true
            val previews = clickable && model.hostPreviews(address)
            val lines = remember(rows, p, previews) { rows.map { styled(it, p, previews) }.asReversed() }
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
            // A tap is a click at the cell under the finger; the rows sit at the bottom, and the
            // screen is the content's last rows less the blank ones the host left out. A tap on
            // a link is the browser's: the text opens it and the host hears nothing; an inbox
            // file or, on a host with roots, a printed path opens over the pane. A tap on the
            // cursor's row (or the last row shown, when the cursor is on a blank one below) is a
            // wish to type: the line takes the focus, after the click the program gets. A long
            // press on a block copies it without its markers.
            val top = (heightPx - rows.size * rowPx).coerceAtLeast(0f)
            val shown by rememberUpdatedState(rows)
            val screenRows = (pane?.height ?: 0) - state.trimmed
            val cursorRow = pane?.cursorY?.takeIf { it > 0 }?.coerceAtMost(screenRows) ?: screenRows
            // The row under the finger, as an index into the rows shown and as a row of the pane.
            fun rowAt(py: Float): Pair<Int, Int>? {
                val t = ((py - top) / rowPx).toInt()
                val index = (shown.size - visibleRows).coerceAtLeast(0) + t
                if (py < top || index !in shown.indices) return null
                return index to screenRows - (shown.size - 1 - index)
            }
            Box(Modifier.fillMaxSize().pointerInput(address) { pinch { z -> zoom = (zoom * z).coerceIn(0.5f, 4f) } }
                .pointerInput(address, top, colPx, rowPx, previews, screenRows, cursorRow) {
                    detectTapGestures(
                        onLongPress = { at ->
                            val (index, _) = rowAt(at.y) ?: return@detectTapGestures
                            val plain = shown.map { r -> r.joinToString("") { it.text } }
                            val b = Blocks.at(Blocks.find(plain), index) ?: return@detectTapGestures
                            model.copyBlock(b.text, b.end - b.start + 1)
                        },
                    ) { at ->
                        val x = (at.x / colPx).toInt() + 1
                        val (index, y) = rowAt(at.y) ?: return@detectTapGestures
                        val row = shown[index].joinToString("") { it.text }
                        if (Links.at(row, x - 1) != null) return@detectTapGestures
                        Links.fileAt(row, x - 1)?.let { onFile(Shown(address, name = it.name)); return@detectTapGestures }
                        if (previews) Links.pathAt(row, x - 1)?.let { onFile(Shown(address, path = it.path)); return@detectTapGestures }
                        if (!clickable) return@detectTapGestures
                        val w = pane?.width ?: 0
                        val h = pane?.height ?: 0
                        if (x in 1..w && y in 1..h) model.click(address, x, y)
                        if (y == cursorRow) onPrompt()
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
                                val cy = (rowAt(at.y)?.second ?: screenRows).coerceIn(1, (pane?.height ?: 1).coerceAtLeast(1))
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

// Colours as the pane gave them; a web address in the row opens through the system, and an
// inbox file or, where the host previews, a printed path is underlined the same way.
private fun styled(row: List<Ansi.Span>, p: Palette, paths: Boolean): AnnotatedString = buildAnnotatedString {
    for (s in row) {
        val st = spanStyle(s.style, p)
        if (st == null) append(s.text) else withStyle(st) { append(s.text) }
    }
    // The builder's own toString is not its text.
    val text = row.joinToString("") { it.text }
    for (l in Links.find(text)) addLink(LinkAnnotation.Url(l.href, linkStyles), l.start, l.end)
    for (f in Links.findFiles(text)) if (Links.at(text, f.start) == null) addStyle(underlined, f.start, f.end)
    if (paths) for (l in Links.findPaths(text)) if (Links.at(text, l.start) == null && Links.fileAt(text, l.start) == null) addStyle(underlined, l.start, l.end)
    if (length == 0) append(" ")
}

private val underlined = SpanStyle(textDecoration = TextDecoration.Underline)

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
