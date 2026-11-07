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
import android.view.DragEvent
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
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
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
import androidx.compose.ui.text.TextLayoutResult
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
import app.muxway.android.MailTarget
import app.muxway.android.Naming
import app.muxway.android.PaneState
import app.muxway.android.Screen
import app.muxway.android.audio.Recorder
import app.muxway.android.protocol.AddMenu
import app.muxway.android.protocol.Ansi
import app.muxway.android.protocol.Beside
import app.muxway.android.protocol.Clip
import app.muxway.android.protocol.Commands
import app.muxway.android.protocol.Complete
import app.muxway.android.protocol.Desk
import app.muxway.android.protocol.Desks
import app.muxway.android.protocol.Files
import app.muxway.android.protocol.Groups
import app.muxway.android.protocol.Input
import app.muxway.android.protocol.Keys
import app.muxway.android.protocol.Lens
import app.muxway.android.protocol.Links
import app.muxway.android.protocol.Names
import androidx.compose.ui.text.drawText
import app.muxway.android.protocol.Suggest
import app.muxway.android.protocol.Blocks
import app.muxway.android.protocol.Changes
import app.muxway.android.protocol.Pane
import app.muxway.android.protocol.Tiles
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
    // Fit, per tile: the host pane follows its tile, as a terminal would. On unless the tile's
    // address is in the set; ⤢ serves the focused tile. Kept by the count of panes, not their
    // addresses: a layout the host took may rename them.
    var fitOff by rememberSaveable(addresses.size, saver = listSaver<MutableState<Set<String>>, String>({ it.value.toList() }, { mutableStateOf(it.toSet()) })) { mutableStateOf(setOf()) }
    val fit = focused !in fitOff
    var fitAgain by remember { mutableIntStateOf(0) }
    val hardware = model.settings.hardwareKeyboard
    // With a hardware keyboard assumed the on-screen one stays down until asked for.
    var ime by rememberSaveable(hardware) { mutableStateOf(!hardware) }
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FieldFocus() }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    // Several panes: the arrangement is a preset that cycles with ▦, or a tree made here by a
    // header dropped on a tile's edge or a divider, or taken from the host when the tiles are
    // every pane of one window; every split's shares drag. A new count resets it, unless a zoom
    // out or a closed tile brings the tiles back as they were.
    val preset = if (landscape) Layout.ROW else Layout.COLUMN
    var layout by rememberSaveable { mutableStateOf(preset) }
    var custom by remember { mutableStateOf<WindowLayout.Node?>(null) }
    val weights = remember { mutableStateMapOf<String, List<Float>>() }
    val kept = remember { Remembered().also { it.moves = model.moves } }
    // The host renumbered a tile's pane and the model moved the tile: the minimised row and the
    // fit-off set, kept here by key, follow it.
    LaunchedEffect(model.moves) {
        if (model.moves == kept.moves) return@LaunchedEffect
        kept.moves = model.moves
        val by = model.moved
        parked = parked.map { by[it] ?: it }
        fitOff = fitOff.map { by[it] ?: it }.toSet()
    }
    // A made tree is drawn only while it has a leaf per tile: for the frame between a tile
    // leaving or arriving and the effect below catching up, the preset stands in.
    val shape = if (layout == Layout.CUSTOM) custom?.takeIf { Tiles.leaves(it).size == tiles.size } ?: preset.tree(tiles.size) else layout.tree(tiles.size)
    // This view as a desk, for the focused tile's hub: that hub's tiles, the focus, the arrangement
    // (with its tree and shares when every tile is on the hub) and the minimised panes of the hub.
    fun deskOf(parkedNow: List<String>, focusKey: String, layoutNow: Layout, customNow: WindowLayout.Node?, weightsNow: Map<String, List<Float>>): Desk {
        val hub = Keys.hub(focusKey)
        val shown = addresses.filter { it !in parkedNow }.ifEmpty { addresses.take(1) }
        val onHub = shown.filter { Keys.hub(it) == hub }
        val whole = onHub.size == shown.size
        val made = customNow?.takeIf { layoutNow == Layout.CUSTOM && whole && Tiles.leaves(it).size == shown.size }
        return Desk(
            view = "panes",
            panes = onHub.map { Keys.address(it) },
            focused = Keys.address(focusKey),
            arrangement = if (whole && shown.size > 1 && (layoutNow != Layout.CUSTOM || made != null)) layoutNow.word else "",
            tree = made,
            weights = if (whole && shown.size > 1) weightsNow.filter { (k, ws) -> Desks.validWeights(mapOf(k to ws)) } else emptyMap(),
            minimised = addresses.filter { it in parkedNow && Keys.hub(it) == hub }.map { Keys.address(it) },
        )
    }
    LaunchedEffect(addresses, tiles.size) {
        // A desk opened: its arrangement, minimised row and focus, taken once; what the view then
        // is becomes the baseline the model writes nothing for.
        model.opening?.takeIf { it.keys == addresses }?.let { o ->
            model.opening = null
            val shown = addresses.filter { it !in o.minimised }.ifEmpty { addresses.take(1) }
            val l = Layout.from(o.arrangement, shown.size)?.takeIf { it != Layout.CUSTOM || o.tree != null } ?: preset
            layout = l
            custom = o.tree.takeIf { l == Layout.CUSTOM }
            weights.clear()
            weights.putAll(o.weights)
            parked = o.minimised
            kept.carried = null
            kept.count = shown.size
            val f = o.focused?.let { shown.indexOf(it) }?.takeIf { it >= 0 }
            if (f != null) focusedIndex = f
            model.noteDeskBaseline(deskOf(o.minimised, shown[(f ?: focusedIndex).coerceIn(0, shown.lastIndex)], l, custom, weights.toMap()))
            return@LaunchedEffect
        }
        val back = listOf(kept.carried, kept.unzoomed).firstOrNull { it != null && it.panes == addresses }
        if (back != null) {
            kept.carried = null
            layout = back.layout
            custom = back.custom
            weights.clear()
            weights.putAll(back.weights)
        } else if (tiles.size != kept.count) {
            if (layout == Layout.CUSTOM || layout !in Layout.options(tiles.size)) layout = preset
            custom = null
            weights.clear()
        }
        kept.count = tiles.size
        kept.focusNext?.let { f -> tiles.indexOf(f).takeIf { it >= 0 }?.let { focusedIndex = it }; kept.focusNext = null }
    }
    fun touch() { kept.touched = System.currentTimeMillis() }
    // The desk: this view, written half a second after a change (the model skips what the hub
    // has), and the hub's desk followed when another client changed it: the same tiles take
    // the arrangement, the shares and the minimised row here; other tiles open through the
    // model, the focus staying where it is.
    val deskHub = Keys.hub(focused)
    val mine = deskOf(parked, focused, layout, custom, weights.toMap())
    LaunchedEffect(Desks.body(mine), deskHub) { model.writeDesk(deskHub, mine) }
    val theirs = model.deskOf(deskHub)
    LaunchedEffect(theirs, deskHub) {
        val d = theirs ?: return@LaunchedEffect
        if (!model.deskFollowable(deskHub, d)) return@LaunchedEffect
        model.noteDeskKnown(deskHub, d)
        if (Desks.same(d, mine)) return@LaunchedEffect
        if (d.view == "window") { if (d.window.isNotEmpty()) model.openDesk(deskHub, focus = false); return@LaunchedEffect }
        val panes = Desks.panesOf(d).map { Keys.make(deskHub, it) }
        if (panes.isEmpty()) return@LaunchedEffect
        val wanted = (panes + d.minimised.map { Keys.make(deskHub, it) }).distinct().take(AddMenu.MAX_TILES)
        if (panes == tiles && wanted.toSet() == addresses.toSet()) {
            val l = Layout.from(d.arrangement, tiles.size)?.takeIf { it != Layout.CUSTOM || d.tree != null } ?: layout
            layout = l
            custom = d.tree.takeIf { l == Layout.CUSTOM }
            weights.clear()
            weights.putAll(d.weights)
            parked = wanted.drop(panes.size)
            // The focus stays this view's: what the view now is, with its own focus, is the
            // baseline, so the other client's change is not answered with a write of the focus.
            model.noteDeskBaseline(deskOf(parked, focused, l, custom, weights.toMap()))
            return@LaunchedEffect
        }
        model.openDesk(deskHub, focus = false)
    }
    // Tiles that are every pane of one window fit it as one, laid out as the tiles are, and
    // follow the host's layout; moving its panes needs every one writable. Tiles that are only
    // some of a window's panes share one fit and the others are held.
    val windowOf = { a: String -> model.panes.firstOrNull { it.key == a }?.let { "${it.hub}/${it.host}/${it.session}/${it.windowIndex}" } ?: a }
    val ids = TileFit.oneWindow(tiles, model.panes, windowOf)
    val movable = ids != null && TileFit.movable(tiles, model.panes)
    val rearrangeable = tiles.size > 1 && (ids == null || movable)
    fun refusedMove() { model.told = "moving these panes needs every pane of the window writable" }
    // A dropped key names a view only when it is a pane of the listing, and not one waiting below.
    fun listed(from: String) = from !in minimised && model.panes.any { it.key == from }
    // A tab of a pane not open, dropped on the view: the pane comes in as one more tile where
    // the drop says. The count is a new view, so the made tree travels as a closed tile's does,
    // and the pane is focused once the tiles are up.
    fun bring(from: String, made: (leaf: Int) -> WindowLayout.Node) {
        kept.carried = Saved(addresses + from, Layout.CUSTOM, made(tiles.size), emptyMap())
        kept.focusNext = from
        touch()
        model.addPane(addresses, index, from, full = false)
    }
    // A header or a tab dropped on a tile: its middle swaps the two, an edge splits that tile's
    // place. A pane not open takes the tile's place from the middle, as a tap on its tab would
    // take the focused tile's, or comes in beside from an edge while there is room for one more.
    fun drop(from: String, to: Int, zone: Tiles.Zone) {
        val at = tiles.indexOf(from)
        if (at == to) return
        if (at < 0) {
            if (!listed(from)) return
            if (zone == Tiles.Zone.SWAP || addresses.size >= AddMenu.MAX_TILES) {
                touch()
                focusedIndex = to
                model.swap(addresses, addresses.indexOf(tiles[to]), from)
            } else bring(from) { leaf -> Tiles.dropOn(shape, leaf, to, zone) }
            return
        }
        if (!rearrangeable) { refusedMove(); return }
        touch()
        if (zone == Tiles.Zone.SWAP) { model.swapTiles(addresses, from, tiles[to]); return }
        custom = Tiles.dropOn(shape, at, to, zone)
        layout = Layout.CUSTOM
        weights.clear()
    }
    // A header or a tab dropped on a divider: the pane across that split, first or last.
    fun lay(from: String, key: String, horizontal: Boolean, first: Boolean) {
        val at = tiles.indexOf(from)
        if (at < 0) {
            if (!listed(from)) return
            if (addresses.size >= AddMenu.MAX_TILES) model.told = "up to ${AddMenu.MAX_TILES} panes side by side"
            else bring(from) { leaf -> Tiles.across(shape, leaf, key, horizontal, first) }
            return
        }
        if (!rearrangeable) { refusedMove(); return }
        touch()
        custom = Tiles.across(shape, at, key, horizontal, first)
        layout = Layout.CUSTOM
        weights.clear()
    }
    val writable = state.pane?.writable == true
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val now = ticker()
    // The swipe's order: the listing without the parked panes, which a swipe would only wake.
    val order = model.panes.filter { !it.later || it.key == focused }.map { it.key }.ifEmpty { addresses }
    DisposableEffect(Unit) { onDispose { model.releaseFits() } }
    // The `+` menu after the tabs, and the session dialog it may open; the pane menu under a tab
    // or a header held and let go in place, by the pane's key.
    var adding by remember { mutableStateOf(false) }
    var tabMenu by remember { mutableStateOf<String?>(null) }
    var headerMenu by remember { mutableStateOf<String?>(null) }
    model.naming?.takeIf { it.into != null }?.let { SessionDialog(it, model) }
    // A file of the host over the pane; the height the text line takes beyond one row, which
    // the tiles lose and the fit must not take for a resize.
    var shown by remember { mutableStateOf<Shown?>(null) }
    var slackPx by remember { mutableIntStateOf(0) }
    shown?.let { FileSheet(model, it, onOpen = { next -> shown = next }) { shown = null } }
    // The name sheet, for one pane: the whole path, the address, the size and a field for a name.
    var namingPane by remember { mutableStateOf<String?>(null) }
    // The changes sheet, for one tile's repository; a file picked there opens over it.
    var changesOf by remember { mutableStateOf<String?>(null) }
    changesOf?.let { ChangesSheet(model, it, onOpen = { next -> shown = next }) { changesOf = null } }
    namingPane?.let { NameSheet(model, it) { namingPane = null } }

    // Direct typing: with the hardware keyboard setting on and the text line not focused, every
    // key of a hardware keyboard goes to the focused writable pane as pressed; a named key goes by
    // its name either way.
    var lineFocused by remember { mutableStateOf(false) }
    val direct = hardware && !lineFocused && writable
    // The ghost word while typing direct: a shadow of the line from the keys sent, shown where
    // the screen's cursor row confirms it; Right takes it whole, Ctrl+Right a word, as text.
    var shadow by remember(focused) { mutableStateOf<String?>("") }
    val shellPane = state.pane?.let { Commands.isShell(it.command) } == true
    val ghost = if (direct && shellPane && model.settings.suggest) {
        val pane = state.pane!!
        val rows = remember(state.content) { state.content.lines().map { Ansi.plain(it) } }
        val screenRows = pane.height - state.trimmed
        val at = rows.size - screenRows + pane.cursorY - 1
        val row = rows.getOrNull(at)
        if (row == null || pane.cursorY < 1) null
        else Suggest.suggest(row, pane.cursorX, shadow, model.recent + Suggest.screenCommands(rows, at) + Commands.SHELL.map { it.text }, state.listing?.let { Names.index(it.entries) })
    } else null
    fun show(key: String) {
        val at = tiles.indexOf(key)
        // A parked tab's tap wakes it: the owner came back.
        val asleep = model.panes.any { it.key == key && it.later }
        if (asleep) model.wake(key)
        when {
            // The focused tab, tapped once more, names its pane.
            key == focused && at >= 0 -> if (!asleep) namingPane = key
            at >= 0 -> focusedIndex = at
            key in minimised -> {
                parked = minimised - key
                focusedIndex = addresses.filter { it !in parked }.indexOf(key)
            }
            else -> model.swap(addresses, addresses.indexOf(focused), key)
        }
        focus.requestFocus()
    }

    // With several tiles the focus moves to the next one; with one, the strip's next awake pane
    // takes the tile. Either comes round at the end, as ] and [ do in the browser.
    fun neighbour(step: Int) {
        if (tiles.size > 1) {
            focusedIndex = (index + step + tiles.size) % tiles.size
            focus.requestFocus()
            return
        }
        val at = order.indexOf(focused)
        if (at < 0 || order.size < 2) return
        show(order[(at + step + order.size) % order.size])
    }

    fun hardKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        // Ctrl+] and Ctrl+[ go to the next and the previous pane from anywhere, the line and
        // direct typing included, read-only or not, and never reach the pane; the line, when it
        // had the focus, follows the pane.
        HardKeys.paneChord(e).takeIf { it != 0 }?.let { step ->
            val fromLine = lineFocused
            neighbour(step)
            if (fromLine) fieldFocus.request()
            return true
        }
        if (!writable) return false
        HardKeys.name(e)?.let { name ->
            if (direct && ghost != null && (name == "Right" || name == "C-Right")) {
                val taken = if (name == "Right") ghost else Suggest.firstWord(ghost)
                if (taken.isNotEmpty() && model.type(focused, taken, enter = false)) shadow = Suggest.nextShadow(shadow, Suggest.Body.Text(taken))
                return true
            }
            model.key(focused, name)
            if (direct) shadow = Suggest.nextShadow(shadow, Suggest.Body.Key(name))
            return true
        }
        if (!direct) return false
        when (val d = HardKeys.direct(e) ?: return false) {
            is HardKeys.Direct.Chord -> { model.key(focused, d.name); shadow = Suggest.nextShadow(shadow, Suggest.Body.Key(d.name)) }
            is HardKeys.Direct.Text -> { model.type(focused, d.text, enter = false); shadow = Suggest.nextShadow(shadow, Suggest.Body.Text(d.text)) }
        }
        return true
    }

    // An existing pane from the menu: one more tile, focused; at four open, the minimised
    // counted, it takes the focused tile's place.
    fun add(key: String) {
        if (key in minimised) { show(key); return }
        model.wake(key)
        val full = addresses.size >= AddMenu.MAX_TILES
        focusedIndex = if (full) index else tiles.size
        model.addPane(addresses, addresses.indexOf(focused), key, full)
        focus.requestFocus()
    }

    // When each pane was focused here last: a partner takes the place of the tile focused
    // longest ago when the view is full.
    val focusedAt = remember { HashMap<String, Long>() }
    LaunchedEffect(focused) { focusedAt[focused] = System.currentTimeMillis() }
    // The pane [key] talked with laid beside it, the browser's rule (see Beside): a tab's pane
    // not open first takes the focused tile, as a tap on the tab would.
    fun beside(key: String, partner: String) {
        val open = if (key in addresses) addresses else addresses.map { if (it == focused) key else it }
        val laid = Beside.lay(open, key, partner, focusedAt)
        model.wake(partner)
        if (laid == addresses) { tiles.indexOf(key).takeIf { it >= 0 }?.let { focusedIndex = it }; focus.requestFocus(); return }
        touch()
        kept.focusNext = key
        model.open(Screen.View(laid))
    }
    // The focused tile across the whole view: the width (stacked over or under the rest) or the
    // height (beside it), on the side its middle is, as the web's e and E.
    fun across(key: String, horizontal: Boolean) {
        val at = tiles.indexOf(key)
        val root = Tiles.rootKey(shape)
        if (at < 0 || root == null) return
        if (!rearrangeable) { refusedMove(); return }
        val r = Tiles.rects(shape, weights)[at] ?: return
        val first = if (horizontal) r.x + r.w / 2 < 0.5f else r.y + r.h / 2 < 0.5f
        touch()
        custom = Tiles.across(shape, at, root, horizontal, first)
        layout = Layout.CUSTOM
        weights.clear()
    }

    // `_`: the tile leaves for the row above the keys and gives its size back; the last tile stays.
    // A made tree closes up around the tile that left.
    fun minimise(key: String) {
        if (tiles.size < 2) return
        if (layout == Layout.CUSTOM) kept.carried = Saved(addresses, layout, Tiles.prune(shape, tiles.indexOf(key)), emptyMap())
        model.release(key)
        parked = minimised + key
        if (key == focused) focusedIndex = 0
    }

    fun closeTile(key: String) {
        if (tiles.size > 2 && layout == Layout.CUSTOM) kept.carried = Saved(addresses - key, layout, Tiles.prune(shape, tiles.indexOf(key)), emptyMap())
        model.closeTile(key)
    }

    // Zoom: the focused pane alone, and back to the tiles it came from, in their places and
    // arrangement; from a pane opened alone, the window's panes side by side in its order.
    fun zoom() {
        if (tiles.size > 1) {
            kept.unzoomed = Saved(addresses, layout, custom, weights.toMap())
            kept.focusNext = focused
            model.open(Screen.View(listOf(focused)))
            return
        }
        kept.unzoomed?.takeIf { focused in it.panes }?.let { kept.focusNext = focused; model.open(Screen.View(it.panes)); return }
        val mates = model.panes.filter { it.key != focused && windowOf(it.key) == windowOf(focused) }.map { it.key }.take(AddMenu.MAX_TILES - 1)
        if (mates.isEmpty()) return
        kept.focusNext = focused
        model.open(Screen.View(model.panes.map { it.key }.filter { it == focused || it in mates }))
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
            Tabs(model, tiles, minimised, focused, now, Modifier.weight(1f), ::show, onHold = { tabMenu = it }, menu = tabMenu, onMenuClose = { tabMenu = null }, onBeside = ::add, onPartner = ::beside)
            Box {
                Btn("+", { adding = true }, selected = adding, modifier = Modifier.testTag("add"))
                if (adding) AddMenuPopup(model, addresses, tiles, minimised, focused, now, onClose = { adding = false }) { add(it) }
            }
            // ▦: the next preset; held, every split's shares equal. ⊡: the focused pane alone, and back.
            if (tiles.size > 1) Btn("▦", { touch(); layout = layout.next(tiles.size) }, enabled = rearrangeable, onLongClick = { touch(); weights.clear() })
            if (tiles.size > 1 || model.panes.any { it.key != focused && windowOf(it.key) == windowOf(focused) }) Btn("⊡", ::zoom, modifier = Modifier.testTag("zoom"))
            // Tap: fit on, or send this size once more when it is on already; hold: fit off.
            Btn("⤢", { if (fit) fitAgain++ else fitOff = fitOff - focused }, selected = fit, enabled = writable, onLongClick = { fitOff = fitOff + focused })
            if (hardware) Btn("abc", { ime = !ime; if (ime) { fieldFocus.request(); keyboard?.show() } else keyboard?.hide() }, selected = ime)
            InboxCount(model)
            MessagesCount(model)
            Btn("⚙", { model.open(Screen.Connect) })
        }
        if (model.inboxOpen) InboxSheet(model, now) { model.inboxOpen = false }
        if (model.messagesOpen) MessagesSheet(model) { model.messagesOpen = false }
        model.mailOpen?.let { MailSheet(model, it) { model.mailOpen = null } }
        model.shareOpen?.let { ShareSheet(model, it) { model.shareOpen = null } }
        Rule()
        Box(Modifier.weight(1f)) {
            val held = if (ids != null) emptySet() else TileFit.held(tiles, focused, windowOf)
            // A pane alone in the view sizes its window, zoomed over its siblings when it has any.
            val siblings = { a: String -> model.panes.any { it.key != a && windowOf(it.key) == windowOf(a) } }
            // The tiles and their window are kept the same both ways: the tiles take the host's
            // layout when it changes there (not over a change made here in the last three
            // seconds, nor the layout this view sent, which comes back with the headers' rows
            // off), and what is changed here goes to the host.
            val hostLayout = if (ids != null) model.panes.firstOrNull { it.key == tiles[0] }?.windowLayout ?: "" else ""
            var synced by remember { mutableStateOf(false) }
            LaunchedEffect(ids, hostLayout) {
                if (ids == null) { synced = false; return@LaunchedEffect }
                val h = Tiles.fromHost(hostLayout, ids) ?: return@LaunchedEffect
                val settled = System.currentTimeMillis() - kept.touched > 3000
                val mine = kept.sent.takeIf { it.isNotEmpty() }?.let { Tiles.fromHost(it, ids) }
                val ours = mine != null && Tiles.sameTree(h.node, mine.node) && Tiles.nearWeights(h.node, h.weights, mine.weights, 0.03f)
                if (!synced || (settled && !ours && (!Tiles.sameTree(h.node, shape) || !Tiles.nearWeights(h.node, h.weights, weights, 0.03f)))) {
                    custom = h.node
                    layout = Layout.CUSTOM
                    weights.clear()
                    weights.putAll(h.weights)
                }
                synced = true
            }
            // Each tile reports its cells; the window's layout is exact to them, the input line's
            // growth counted on the tiles along the bottom only. A writable pane carries the fit.
            val bottom = WindowLayout.bottomLeaves(shape).toSet()
            val sizes = remember { mutableStateMapOf<String, WindowLayout.Size>() }
            LaunchedEffect(tiles) { sizes.keys.retainAll(tiles.toSet()) }
            val exact = if (ids != null && synced && tiles.all { it in sizes }) WindowLayout.exact(shape, tiles.map { sizes.getValue(it) }, ids) else null
            val lead = tiles.firstOrNull { model.panes.any { p -> p.key == it && p.writable } } ?: tiles[0]
            val imeUp = WindowInsets.isImeVisible
            var sentAgain by remember { mutableIntStateOf(fitAgain) }
            LaunchedEffect(ids, exact?.layout, fit, imeUp, fitAgain) {
                if (ids == null) return@LaunchedEffect
                if (!fit) { for (t in tiles) model.release(t); return@LaunchedEffect }
                if (exact == null || imeUp) return@LaunchedEffect
                kept.sent = exact.layout
                model.fit(lead, exact.cols.coerceIn(20, 500), exact.rows.coerceIn(5, 300), again = fitAgain != sentAgain, window = true, layout = exact.layout, mates = tiles)
                sentAgain = fitAgain
            }
            // With fit off the arrangement still goes to the host, without a size.
            val proportional = if (ids != null && movable && synced && !fit) Tiles.layoutFor(shape, weights, ids) else ""
            LaunchedEffect(proportional, hostLayout) {
                if (proportional.isEmpty()) return@LaunchedEffect
                val h = Tiles.fromHost(hostLayout, ids!!)
                if (h != null && Tiles.sameTree(h.node, shape) && Tiles.nearWeights(shape, h.weights, weights, 0.01f)) return@LaunchedEffect
                delay(300)
                kept.sent = proportional
                model.arrange(lead, proportional, tiles)
            }
            val sent = ids != null && model.view(lead).fitted
            val groups = remember(model.panes) { Groups.of(model.panes) }
            val tile: @Composable (Int, Modifier) -> Unit = { i, m ->
                key(tiles[i]) {
                    PaneTile(
                        model, tiles[i], i == index, tiles.size > 1, tiles[i] in groups, tiles[i] !in fitOff, tiles[i] in held, siblings(tiles[i]), ids != null, exact?.sizes?.get(i)?.takeIf { sent },
                        fitAgain, now, if (i in bottom) slackPx else 0, m,
                        onMeasured = { sizes[tiles[i]] = it },
                        onDrop = { from, zone -> drop(from, i, zone) },
                        onZoom = ::zoom,
                        onClose = { model.closePane(tiles[i]) },
                        onCloseTile = { closeTile(tiles[i]) },
                        onMinimise = if (tiles.size > 1) ({ minimise(tiles[i]) }) else null,
                        onFile = { shown = it },
                        onChanges = { changesOf = tiles[i] },
                        onName = { namingPane = tiles[i] },
                        onShare = { model.shareOpen = tiles[i] },
                        onPrompt = { focusedIndex = i; fieldFocus.request() },
                        ghost = if (i == index) ghost else null,
                        menu = headerMenu == tiles[i],
                        onMenu = { headerMenu = tiles[i] },
                        onMenuClose = { headerMenu = null },
                        onPartner = { beside(tiles[i], it) },
                        onAcross = if (tiles.size > 1) ({ h -> across(tiles[i], h) }) else null,
                        acrossNow = { h -> Tiles.isAcross(shape, i, h) },
                    ) {
                        focusedIndex = i
                        focus.requestFocus()
                    }
                }
            }
            Tree(shape, weights, Modifier.fillMaxSize(), dropping = tiles.size > 1, onAcross = ::lay, groupOf = { groups[tiles.getOrNull(it) ?: ""] }, tile)
        }
        Rule()
        if (minimised.isNotEmpty()) MinimisedRow(model, minimised, now) { show(it) }
        // Refusals and notices pass, as the browser's toasts do.
        model.sendError?.let { Text(" ! $it", color = p.alert, modifier = Modifier.padding(horizontal = 4.dp)) }
        model.told?.let { Text(" $it", color = p.dim, modifier = Modifier.padding(horizontal = 4.dp)) }
        LaunchedEffect(model.told) { if (model.told != null) { delay(4000); model.told = null } }
        LaunchedEffect(model.sendError) { if (model.sendError != null) { delay(4000); model.sendError = null } }
        val pane = state.pane
        when {
            pane == null -> Text(" …", color = p.dim, modifier = Modifier.padding(4.dp))
            !pane.writable -> Text(" read-only · ${pane.address}", color = p.dim, modifier = Modifier.padding(4.dp))
            else -> InputBar(model, state, fieldFocus, onGrow = { slackPx = it }) { gained -> lineFocused = gained; if (gained && hardware && !ime) keyboard?.hide() }
        }
        Text(
            (if (direct) "direct · " else "") + (state.error?.let { "! $it" } ?: state.updated.ifEmpty { "connecting" }),
            color = if (state.error != null) p.alert else p.faint, maxLines = 1,
            style = LocalTextStyle.current.copy(fontSize = 10.sp), modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

/** An arrangement the view came from, to go back to: after a zoom out, or a tile closed. */
private class Saved(val panes: List<String>, val layout: Layout, val custom: WindowLayout.Node?, val weights: Map<String, List<Float>>)

/** What the view keeps across its own changes of panes: the arrangement to go back to, who is focused next, when it was last changed here, the layout last sent. */
private class Remembered {
    var unzoomed: Saved? = null
    var carried: Saved? = null
    var focusNext: String? = null
    var touched = 0L
    var sent = ""
    var count = 0
    var moves = 0
}

/** How several panes share the screen: a preset by count, or a tree made here ([CUSTOM]). */
enum class Layout(val word: String) {
    ROW("row"), COLUMN("column"), GRID("grid"), MAIN_LEFT("main-left"), MAIN_TOP("main-top"), CUSTOM("custom");

    companion object {
        /** The arrangement a desk names, when it is one of ours and fits [n] tiles; null otherwise. */
        fun from(word: String, n: Int): Layout? = entries.firstOrNull { it.word == word }?.takeIf { it == CUSTOM || it in options(n) }

        /** Two panes split one way or the other; three and four add a main pane and, for four, a grid. */
        fun options(n: Int): List<Layout> = when {
            n <= 2 -> listOf(ROW, COLUMN)
            n == 3 -> listOf(ROW, COLUMN, MAIN_LEFT, MAIN_TOP)
            else -> listOf(ROW, COLUMN, GRID, MAIN_LEFT, MAIN_TOP)
        }
    }

    /** The next preset; from a made tree, the first. */
    fun next(n: Int): Layout {
        val all = options(n)
        return all[(all.indexOf(this) + 1) % all.size]
    }

    /** The arrangement as the tree of splits [Tree] draws, its leaves the tile indexes, every split keyed. */
    fun tree(n: Int): WindowLayout.Node {
        val all: List<WindowLayout.Node> = (0 until n).map { WindowLayout.Node.Leaf(it) }
        fun split(horizontal: Boolean, kids: List<WindowLayout.Node>) = if (kids.size == 1) kids[0] else WindowLayout.Node.Split(horizontal, kids)
        if (n <= 1) return all.firstOrNull() ?: WindowLayout.Node.Leaf(0)
        return Tiles.relabel(when (this) {
            ROW, CUSTOM -> split(true, all)
            COLUMN -> split(false, all)
            MAIN_LEFT -> split(true, listOf(all[0], split(false, all.drop(1))))
            MAIN_TOP -> split(false, listOf(all[0], split(true, all.drop(1))))
            GRID -> split(false, listOf(split(true, all.take((n + 1) / 2)), split(true, all.drop((n + 1) / 2))))
        })
    }
}

/**
 * The tree of splits: a leaf is a tile, a split a row or a column sharing the space by its
 * shares, with a bar between neighbours that drags the split and, while a header is dragged,
 * takes it to lay the pane across the split, first or last by which half it lands on. The bar
 * between two tiles of one group (see [Groups]) carries the chain in the talk hue.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tree(
    node: WindowLayout.Node, weights: SnapshotStateMap<String, List<Float>>, modifier: Modifier, dropping: Boolean,
    onAcross: (from: String, key: String, horizontal: Boolean, first: Boolean) -> Unit, groupOf: (Int) -> Int? = { null }, tile: @Composable (Int, Modifier) -> Unit,
) {
    val p = LocalPalette.current
    when (node) {
        is WindowLayout.Node.Leaf -> tile(node.index, modifier)
        is WindowLayout.Node.Split -> BoxWithConstraints(modifier) {
            val n = node.children.size
            val total = (if (node.horizontal) constraints.maxWidth else constraints.maxHeight).toFloat().coerceAtLeast(1f)
            val w = weights[node.key]?.takeIf { it.size == n } ?: Tiles.equal(n)
            fun drag(k: Int, px: Float) { weights[node.key] = Tiles.drag(weights[node.key]?.takeIf { it.size == n } ?: Tiles.equal(n), k, px / total) }
            val bar: @Composable (Int) -> Unit = { k ->
                var bounds by remember { mutableStateOf<Rect?>(null) }
                // Two tiles of one group side by side: the chain between them.
                val a = node.children[k] as? WindowLayout.Node.Leaf
                val b = node.children[k + 1] as? WindowLayout.Node.Leaf
                val chained = a != null && b != null && groupOf(a.index) != null && groupOf(a.index) == groupOf(b.index)
                // The target outlives the tree it was made for: it drops into the latest one.
                val across by rememberUpdatedState(onAcross)
                val target = remember(node.key, k) {
                    object : DragAndDropTarget {
                        override fun onDrop(event: DragAndDropEvent): Boolean {
                            val e = event.toAndroidDragEvent()
                            val from = dragged(e) ?: return false
                            val b = bounds ?: return false
                            across(from, node.key, !node.horizontal, if (node.horizontal) e.y < b.center.y else e.x < b.center.x)
                            return true
                        }
                    }
                }
                Box(
                    Modifier.then(if (node.horizontal) Modifier.fillMaxHeight().width(10.dp) else Modifier.fillMaxWidth().height(10.dp))
                        .background(p.bg)
                        .onGloballyPositioned { bounds = it.boundsInRoot() }
                        .then(if (dropping) Modifier.dragAndDropTarget(shouldStartDragAndDrop = { it.mimeTypes().contains(MIME) }, target = target) else Modifier)
                        .pointerInput(node.key, k) { detectDragGestures { change, delta -> change.consume(); drag(k, if (node.horizontal) delta.x else delta.y) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.then(if (node.horizontal) Modifier.fillMaxHeight(0.3f).width(2.dp) else Modifier.fillMaxWidth(0.3f).height(2.dp)).background(if (chained) p.talk.copy(alpha = 0.4f) else p.dim))
                    if (chained) Chain(p.talk, node.horizontal, Modifier.testTag("divider-chain"))
                }
            }
            if (node.horizontal) {
                Row(Modifier.fillMaxSize()) {
                    node.children.forEachIndexed { k, c ->
                        Tree(c, weights, Modifier.fillMaxHeight().weight(w[k].coerceAtLeast(0.01f)), dropping, onAcross, groupOf, tile)
                        if (k < n - 1) bar(k)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    node.children.forEachIndexed { k, c ->
                        Tree(c, weights, Modifier.fillMaxWidth().weight(w[k].coerceAtLeast(0.01f)), dropping, onAcross, groupOf, tile)
                        if (k < n - 1) bar(k)
                    }
                }
            }
        }
    }
}

/**
 * The colours a pane's state paints a tab or header with: background, then ink. Only waiting
 * paints; a working pane is plain, its ring and word say it.
 */
@Composable
private fun stateColours(status: Lens.Status?): Pair<Color, Color> {
    val p = LocalPalette.current
    return if (status == Lens.Status.WAITING) p.attention to p.bg else p.panel to p.ink
}

/** The dimmer ink for a status next to [ink]: the theme's dim on plain ground, a fainter ink on painted. */
@Composable
private fun dimOf(status: Lens.Status?, ink: Color): Color = if (status == Lens.Status.WAITING) ink.copy(alpha = 0.7f) else LocalPalette.current.dim

/** The mail count's colour: the talk hue on plain ground, the state's paint wins on a painted tab or header. */
@Composable
private fun mailOf(status: Lens.Status?, ink: Color): Color = if (status == Lens.Status.WAITING) ink else LocalPalette.current.talk

/**
 * Every pane the token may read, each painted with its state: attention colour when it waits for
 * you, plain otherwise with the ring while it works, the parked ones after every other with the
 * moon. Open panes have a thick border, the focused one the thickest and its name in bold.
 * Tapping a closed one swaps it into the focused tile; held and let go in place, the
 * drag-and-drop long press without a move, it opens its menu, whose first row opens it beside, as the browser's Shift+click does; held and moved, it drags as a
 * header does, to be dropped on a tile or a divider.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tabs(
    model: AppModel, open: List<String>, minimised: List<String>, focused: String, now: Long, modifier: Modifier,
    onTap: (String) -> Unit, onHold: (String) -> Unit, menu: String?, onMenuClose: () -> Unit, onBeside: (String) -> Unit, onPartner: (String, String) -> Unit,
) {
    val p = LocalPalette.current
    val panes = ArrayList<Pair<String, Pane?>>()
    for (pane in model.panes) if (pane.key !in minimised) panes.add(pane.key to pane)
    for (a in open) if (panes.none { it.first == a }) panes.add(a to model.view(a).pane)
    // The tabs of a group side by side inside one frame in the talk hue, the parked tabs last and
    // outside any frame; the strip's items are the runs.
    val groups = remember(model.panes) { Groups.of(model.panes) }
    val runs = Groups.strip(panes, groups, { it.first }) { it.second?.later == true }
    val list = rememberLazyListState()
    LaunchedEffect(focused, runs.size) { runs.indexOfFirst { r -> r.rows.any { it.first == focused } }.takeIf { it >= 0 }?.let { list.animateScrollToItem(it) } }
    // The drag handler runs on from the tab's first composition: it holds the latest view.
    val hold by rememberUpdatedState(onHold)
    LazyRow(modifier.background(p.bg), state = list, verticalAlignment = Alignment.CenterVertically) {
        items(runs, key = { it.rows.first().first }) { run ->
            val frame = if (run.group != null) Modifier.padding(end = 4.dp).border(1.dp, p.talk).padding(1.dp).testTag("group:${run.group}") else Modifier
            Row(frame, verticalAlignment = Alignment.CenterVertically) {
                for ((address, pane) in run.rows) {
                    val isOpen = address in open
                    val isFocused = address == focused
                    val status = pane?.let { model.status(it, now) }
                    val (back, ink) = stateColours(status)
                    val border: Dp = if (isFocused) 3.dp else if (isOpen) 2.dp else 1.dp
                    // The drag source comes before the border and the ground, so the whole painted
                    // tab is what travels under the finger.
                    Row(
                        Modifier.padding(2.dp)
                            .clickable { onTap(address) }
                            .dragAndDropSource {
                                // The drag starts with the first movement after the long press; let
                                // go in place, the hold opens the pane's menu.
                                var moved = false
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { moved = false },
                                    onDragEnd = { if (!moved) hold(address) },
                                ) { _, _ -> if (!moved) { moved = true; startTransfer(DragAndDropTransferData(paneClip(address))) } }
                            }
                            .border(border, if (isOpen) p.ink else p.faint).background(back)
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .testTag("tab:${Keys.address(address)}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (status != null && status != Lens.Status.QUIET) { Mark(status, pane?.attention?.kind, ink); Text(" ", maxLines = 1) }
                        if (pane != null) PaneName(pane, ink, dimOf(status, ink), bold = isFocused, tab = true, mail = mailOf(status, ink), onMail = { model.mailOpen = MailTarget(address) })
                        else Text(Keys.address(address).substringAfter('/'), color = ink, maxLines = 1, style = LocalTextStyle.current.copy(fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal, shadow = null))
                        if (menu == address) PaneMenu(model, address, onBeside = { onBeside(address) }, onClose = onMenuClose, onPartner = { onPartner(address, it) })
                    }
                }
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
    val every = model.settings.refreshSeconds * 2
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
                if (status != null && status != Lens.Status.QUIET) { Mark(status, pane?.attention?.kind, ink); Text(" ", maxLines = 1) }
                if (pane != null) PaneName(pane, ink, dimOf(status, ink), tab = true, mail = mailOf(status, ink), onMail = { model.mailOpen = MailTarget(k) })
                else Text(Keys.address(k).substringAfter('/'), color = ink, maxLines = 1, style = LocalTextStyle.current.copy(shadow = null))
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
            val item: @Composable (String, String?, String?, Lens.Agent?, () -> Unit) -> Unit = { label, place, reason, agent, act ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = reason == null, onClick = act).padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (agent != null) { AgentMark(agent); Text(" ", maxLines = 1) }
                    // The name keeps its width; the place after it takes the rest, cut with an ellipsis.
                    Text(label, color = if (reason == null) p.ink else p.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    (reason ?: place)?.let { Text("  $it", color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                }
            }
            for (row in rows) when (row) {
                is AddMenu.Row.NewPane -> item("+ pane", "beside ${row.pane.tab}", row.reason, null) {
                    onClose(); model.splitWindow(row.pane, into = addresses)
                }
                is AddMenu.Row.NewSession -> item("+ session", "on ${row.host}", row.reason, null) {
                    onClose(); model.createError = null; model.naming = Naming(row.hub, row.host, into = addresses)
                }
                is AddMenu.Row.Rule -> Rule(Modifier.padding(vertical = 2.dp))
                is AddMenu.Row.Heading -> Text(row.text, color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                is AddMenu.Row.Item -> {
                    val g = Lens.glyph(model.status(row.pane, now), row.pane.attention?.kind)?.let { "$it " } ?: ""
                    item("$g${row.pane.tab}", row.pane.place, null, Lens.agent(row.pane)) { onClose(); onPick(row.pane.key) }
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
    // The ghost word on the line: the rest of a known line after the text, for a shell pane,
    // taken by a tap on it or a swipe right; send sends the text as typed.
    val shellPane = state.pane?.let { Commands.isShell(it.command) && it.writable } == true
    val ghost = if (shellPane && model.settings.suggest && text.isNotEmpty() && '\n' !in text) {
        val rows = remember(state.content) { state.content.lines().map { Ansi.plain(it) } }
        Suggest.completion(text, model.recent + Suggest.screenCommands(rows) + Commands.SHELL.map { it.text }, state.listing?.let { Names.index(it.entries) }) ?: ""
    } else ""
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
    // The keys tinted by group, the commands by tone: border and ground, never the ink.
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Btn("Esc", { model.key(state.key, "Escape") }, enabled = !busy, tint = p.tint(Input.group("Escape")))
        LazyRow(Modifier.weight(1f)) {
            items(Input.KEYS.filter { it.first != "Escape" && it.first != "Enter" }, key = { it.first }) { (name, label) ->
                Btn(label, { model.key(state.key, name) }, enabled = !busy, tint = p.tint(Input.group(name)))
            }
        }
        Btn("Enter", { model.key(state.key, "Enter") }, enabled = !busy, tint = p.tint(Input.group("Enter")))
    }
    state.pane?.let { Commands.forPane(it, model.recent) }?.let { (kind, commands) ->
        val shell = kind == Commands.Kind.SHELL
        val fixed = if (shell) Commands.SHELL.size else commands.size
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (shell) Text(" $ ", color = p.dim, maxLines = 1)
            else { Text(" ", maxLines = 1); AgentMark(Lens.agent(state.pane!!)); Text(" ", maxLines = 1) }
            LazyRow(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(commands, key = { _, c -> c.text }) { i, c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (i == fixed) Text(" │ ", color = p.faint, maxLines = 1)
                        if (c.agent != null) { Text(" ", maxLines = 1); AgentMark(c.agent) }
                        Btn(c.label, {
                            if (c.send) model.type(state.key, c.text, enter = true)
                            else { line.setTextAndPlaceCursorAtEnd(c.text); fieldFocus.request() }
                        }, enabled = !busy, tint = p.tint(c.tone))
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
                    ghost = ghost,
                    onGhost = { line.setTextAndPlaceCursorAtEnd(text + ghost); fieldFocus.request() },
                    onSend = { send(true) },
                    onContent = ::files,
                )
            }
        }
        Btn("⌨\uFE0E", { board = !board }, selected = board, enabled = writable, modifier = Modifier.testTag("keyboard-open"))
        if (takes == true) Btn("⇪", { pick.launch(arrayOf("*/*")) }, enabled = !busy && sending == null, modifier = Modifier.testTag("files"))
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
    model: AppModel, address: String, focused: Boolean, several: Boolean, grouped: Boolean, fitAsked: Boolean, shared: Boolean, siblings: Boolean,
    windowFit: Boolean, expected: WindowLayout.Size?, fitAgain: Int, now: Long, slackPx: Int, modifier: Modifier,
    onMeasured: (WindowLayout.Size) -> Unit, onDrop: (String, Tiles.Zone) -> Unit, onZoom: () -> Unit, onClose: () -> Unit, onCloseTile: () -> Unit,
    onMinimise: (() -> Unit)?, onFile: (Shown) -> Unit, onChanges: () -> Unit, onName: () -> Unit, onShare: () -> Unit, onPrompt: () -> Unit, ghost: String? = null,
    menu: Boolean = false, onMenu: () -> Unit = {}, onMenuClose: () -> Unit = {}, onPartner: (String) -> Unit = {},
    onAcross: ((Boolean) -> Unit)? = null, acrossNow: (Boolean) -> Boolean = { false }, onFocus: () -> Unit,
) {
    // Another tile of this window carries the fit: this one is held. With the window fitted as
    // one ([windowFit]) the view sends the size and this tile is [expected] to be its share.
    val fit = fitAsked && !shared
    var asking by remember(address) { mutableStateOf(false) }
    val p = LocalPalette.current
    val state = model.view(address)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The focused tile reads at the setting, the others at half the pace.
    val every = model.settings.refreshSeconds * (if (focused) 1 else 2)
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
    // A writable pane on a host with preview roots is asked for its repository's changes every
    // ten seconds, for the count in the header.
    val tracks = pane?.writable == true && model.hostPreviews(address)
    LaunchedEffect(address, tracks) {
        if (!tracks) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                model.fetchChanges(address)
                delay(10_000)
            }
        }
    }
    // A writer's shell pane under a root has its directory listed, so the names on its screen
    // are links: every two seconds when focused, five otherwise, and at once when it changes.
    val cwd = if (model.settings.names && pane != null && Commands.isShell(pane.command) && model.hostLists(address)) model.cwdOf(address) else null
    LaunchedEffect(address, cwd, focused) {
        if (cwd == null) { model.dropListing(address); return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                model.fetchListing(address)
                delay(if (focused) 2_000L else 5_000L)
            }
        }
    }
    val names = remember(state.listing, cwd) { if (cwd != null) state.listing?.let { Names.index(it.entries) } ?: Names.Index.EMPTY else Names.Index.EMPTY }
    // A header or a tab dropped here: on the middle it swaps the two panes, on an edge it splits
    // this tile's place that way. The target outlives the view it was made for: it drops into
    // the latest one.
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val dropped by rememberUpdatedState(onDrop)
    val target = remember(address) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val e = event.toAndroidDragEvent()
                val from = dragged(e) ?: return false
                val b = bounds
                val zone = if (b == null || b.width <= 0f || b.height <= 0f) Tiles.Zone.SWAP else Tiles.zoneAt((e.x - b.left) / b.width, (e.y - b.top) / b.height)
                dropped(from, zone)
                return true
            }
        }
    }
    val waiting = status == Lens.Status.WAITING
    // A waiting pane's cell glows in the ink, outside its box: nothing around it moves.
    val glow = if (waiting) Modifier.glow(p.ink, pulse()) else Modifier
    // A tile of a group wears one thin dimmed edge in the talk hue; the focused one keeps the ink.
    val edgeColour = if (several && focused) p.ink else if (grouped) p.talk.copy(alpha = 0.4f) else p.faint
    Column(
        modifier.padding(2.dp).then(glow).border(edge, edgeColour).clickable(onClick = onFocus)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .dragAndDropTarget(shouldStartDragAndDrop = { it.mimeTypes().contains(MIME) }, target = target)
            .testTag("tile:${Keys.address(address)}"),
    ) {
        // The header drags by a long press, with another tile to drop it on, the whole painted
        // header under the finger; held and let go in place, it opens the pane's menu; a double tap zooms the pane.
        val held by rememberUpdatedState(onMenu)
        val header = Modifier.fillMaxWidth()
            .then(if (several) Modifier.dragAndDropSource {
                var moved = false
                detectDragGesturesAfterLongPress(
                    onDragStart = { moved = false },
                    onDragEnd = { if (!moved) held() },
                ) { _, _ -> if (!moved) { moved = true; startTransfer(DragAndDropTransferData(paneClip(address))) } }
            } else Modifier.pointerInput(address) {
                var moved = false
                detectDragGesturesAfterLongPress(onDragStart = { moved = false }, onDragEnd = { if (!moved) held() }) { _, _ -> moved = true }
            })
            .background(back).padding(horizontal = 4.dp, vertical = 2.dp)
            .pointerInput(address) { detectTapGestures(onDoubleTap = { onZoom() }, onTap = { onFocus() }) }
            .testTag("header")
        Row(header, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (pane != null) {
                    // The name, tapped on the focused tile, opens the name sheet; it keeps the header's double tap.
                    val name = Modifier.weight(1f, fill = false).pointerInput(address, focused) {
                        detectTapGestures(onDoubleTap = { onZoom() }, onTap = { if (focused) onName() else onFocus() })
                    }.testTag("name")
                    PaneName(pane, ink, dimOf(status, ink), bold = several && focused, mail = mailOf(status, ink), folder = Lens.folder(pane), italic = status == Lens.Status.LATER, onMail = { model.mailOpen = MailTarget(address) }, modifier = name)
                    if (!pane.writable) Text(" · read-only", color = ink, maxLines = 1, style = LocalTextStyle.current.copy(shadow = null))
                } else Text(address, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = LocalTextStyle.current.copy(shadow = null))
            }
            if (status != null && status != Lens.Status.QUIET) {
                val kind = pane?.attention?.kind
                Text(" ", maxLines = 1)
                Mark(status, kind, ink)
                Text(" ${Lens.word(status, kind)} ", color = ink, maxLines = 1, modifier = if (waiting) Modifier.alpha(pulse()) else Modifier)
                // What waits under a parked pane's mark, dimmed, so it is not lost.
                if (status == Lens.Status.LATER && kind != null) Text("· ${Lens.glyph(Lens.Status.WAITING, kind)} $kind ", color = dimOf(status, ink), maxLines = 1)
                // A question's or a permission's own words, in the room the name leaves.
                val said = pane?.attention?.text?.takeIf { waiting && (kind == "question" || kind == "permission") && it.isNotBlank() }
                if (said != null) Text("· $said ", color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("notice"))
                // A question asked with fixed answers: its choices, answered here.
                if (pane != null && waiting) Choices(model, pane)
            }
            if (menu) PaneMenu(model, address, onBeside = null, onClose = onMenuClose, onPartner = onPartner, onAcross = onAcross, acrossNow = acrossNow)
            if (fitAsked && pane?.writable == true) {
                // Alert colour when the host did not keep this phone's size: another terminal holds
                // it, or another tile of the window carries the fit.
                val held = when {
                    shared -> true
                    windowFit -> expected != null && (pane.width != expected.cols || pane.height != expected.rows)
                    else -> state.fitKey.isNotEmpty() && state.fitKey != "${pane.width}x${pane.height}"
                }
                Text(" ${pane.width}×${pane.height}${if (held) " held" else ""} ", color = if (held) p.alert else ink, maxLines = 1, modifier = Modifier.testTag("size"))
            }
            // The pane's live shares made by this token, counted and dim; a tap opens the share sheet.
            if (pane != null && pane.shared > 0) {
                Text(" ⇗ ${pane.shared} ", color = dimOf(status, ink), maxLines = 1, modifier = Modifier.clickable(onClick = onShare).testTag("share-count"))
            }
            // The repository's changes, counted, where the host shows one; a tap opens the sheet.
            state.changes?.let { c ->
                Text(" ⎇ ${Changes.summary(Changes.counts(c.entries))} ", color = ink, maxLines = 1, modifier = Modifier.clickable(onClick = onChanges).testTag("changes-count"))
            }
            // Minimising keeps the pane open in the row above the keys; off on the last tile.
            Text(" _ ", color = if (onMinimise != null) ink else p.faint, maxLines = 1, modifier = Modifier.clickable(enabled = onMinimise != null) { onMinimise?.invoke() }.testTag("minimise"))
            // Closing ends what runs in the pane on the host, where the agent allows it and the
            // pane is writable: the header asks first. Elsewhere × closes the tile.
            if (pane?.writable == true && model.hostCloses(address)) {
                if (asking) {
                    // The only pane of its session: closing it ends the session, and the question says so.
                    Text(if (pane.last) " end session ${pane.session} on ${pane.host}? " else " close the pane on ${pane.host}? ", color = ink, maxLines = 1, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("close-question"))
                    Text(" y ", color = ink, maxLines = 1, modifier = Modifier.border(1.dp, ink).clickable { asking = false; onClose() })
                    Text(" n ", color = ink, maxLines = 1, modifier = Modifier.padding(start = 4.dp).border(1.dp, ink).clickable { asking = false })
                } else {
                    Text(" × ", color = ink, maxLines = 1, modifier = Modifier.clickable { asking = true }.testTag("close"))
                }
            } else {
                Text(" × ", color = ink, maxLines = 1, modifier = Modifier.clickable(onClick = onCloseTile).testTag("close"))
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
            val dash = with(density) { 2.dp.toPx() }
            val style = base.copy(fontSize = sizeSp.sp, lineHeight = lineSp.sp)
            val sideways = !fitting && !reflow
            val horizontal = rememberScrollState()
            val body = Modifier.fillMaxHeight().then(if (sideways) Modifier.width(with(density) { (cols * colPx).toDp() } + 4.dp) else Modifier.fillMaxWidth())
            // A program that has the mouse gets the wheel on a vertical drag: a notch per row,
            // the rows since the last request counted into one. A full-screen program without
            // it (alternate screen) leaves nothing to scroll locally, so a drag pages it instead,
            // a third of the tile per PageUp or PageDown; anything else scrolls here, through the
            // history the hub sent. The list takes no drags that are the program's, and never
            // its stretch effect, so the gesture reaches us and sideways swipes reach the page.
            val wheel = pane?.mouse == true
            val scrolls = rows.size > visibleRows && !wheel
            val paging = pane?.writable == true && (wheel || !scrolls)
            val page = if (wheel) rowPx else heightPx / 3f
            // A tap is a click at the cell under the finger; the rows sit at the bottom, and the
            // screen is the content's last rows less the blank ones the host left out. A tap on
            // a link is the browser's: the text opens it and the host hears nothing; an inbox
            // file or, on a host with roots, a printed path opens over the pane. A tap on the
            // cursor's row (or the last row shown, when the cursor is on a blank one below) is a
            // wish to type: the line takes the focus, after the click the program gets. A long
            // press on a block copies it without its markers.
            val shown by rememberUpdatedState(rows)
            val screenRows = (pane?.height ?: 0) - state.trimmed
            val cursorRow = pane?.cursorY?.takeIf { it > 0 }?.coerceAtMost(screenRows) ?: screenRows
            // The rows as the list laid them out, by their index in [rows]: the finger is matched
            // against them, not against the font's nominal pitch, which the list does not keep to.
            // The gesture reads the pane's facts as they are now, so a repaint does not cancel it.
            val laid = remember(address) { HashMap<Int, LaidRow>() }
            val ghostMeasurer = rememberTextMeasurer()
            val facts by rememberUpdatedState(TapFacts(colPx, previews, names, clickable, wheel, screenRows, cursorRow, pane?.width ?: 0, pane?.height ?: 0, sideways))
            // The row under the finger, as an index into the rows shown and as a row of the pane.
            fun rowAt(py: Float): Pair<Int, Int>? {
                val index = laid.entries.firstOrNull { (_, r) -> py >= r.top && py < r.bottom }?.key ?: return null
                if (index !in shown.indices) return null
                return index to facts.screenRows - (shown.size - 1 - index)
            }
            // The column under the finger: the character the row's layout has there, else the cell.
            fun colAt(index: Int, px: Float): Int {
                val r = laid[index]
                val t = r?.layout
                if (t == null || px < r.left || px >= r.left + t.size.width) return (px / facts.colPx).toInt()
                val o = t.getOffsetForPosition(Offset(px - r.left, 1f))
                return if (o > 0 && t.getHorizontalPosition(o, true) > px - r.left) o - 1 else o
            }
            Box(Modifier.fillMaxSize().pointerInput(address) { pinch { z -> zoom = (zoom * z).coerceIn(0.5f, 4f) } }
                .pointerInput(address) {
                    detectTapGestures(
                        onLongPress = { at ->
                            val (index, _) = rowAt(at.y) ?: return@detectTapGestures
                            val f = facts
                            val px = at.x + if (f.sideways) horizontal.value else 0
                            // Held on a link a program named, the address shows: the label may say anything.
                            Ansi.hrefAt(shown[index], colAt(index, px))?.let { model.told = it.take(200); return@detectTapGestures }
                            // Held on a name of the directory, the quoted name lands on the line, nothing sent.
                            Names.at(shown[index].joinToString("") { it.text }, colAt(index, px), f.names)?.let { m ->
                                state.landed = state.landed + Names.shellQuote(m.name)
                                return@detectTapGestures
                            }
                            val plain = shown.map { r -> r.joinToString("") { it.text } }
                            val b = Blocks.at(Blocks.find(plain), index) ?: return@detectTapGestures
                            model.copyBlock(b.text, b.end - b.start + 1)
                        },
                    ) { at ->
                        val f = facts
                        val px = at.x + if (f.sideways) horizontal.value else 0
                        val (index, y) = rowAt(at.y) ?: return@detectTapGestures
                        val col = colAt(index, px)
                        val row = shown[index].joinToString("") { it.text }
                        // A link, printed or named by the program, opens through its annotation and sends nothing.
                        if (Links.at(row, col) != null || Ansi.hrefAt(shown[index], col) != null) return@detectTapGestures
                        Links.fileAt(row, col)?.let { onFile(Shown(address, name = it.name)); return@detectTapGestures }
                        if (f.previews) Links.pathAt(row, col)?.let { onFile(Shown(address, path = it.path)); return@detectTapGestures }
                        // A name of the pane's directory: a directory is entered, a file opened as set;
                        // the text typed is built from the host's listing, never from the screen.
                        Names.at(row, col, f.names)?.let { m ->
                            val s = model.settings
                            val line = Names.lineFor(m.kind, m.name, s.openWith, s.openCommand)
                            if (line == null) onFile(Shown(address, path = m.name))
                            else if (model.type(address, line, enter = true)) model.told = Names.titleFor(m.kind, m.name, s.openWith, s.openCommand)
                            return@detectTapGestures
                        }
                        if (!f.clickable) return@detectTapGestures
                        // A click only where the program asked for the mouse; elsewhere a tap focuses.
                        val x = (px / f.colPx).toInt() + 1
                        if (f.wheel && x in 1..f.cols && y in 1..f.rows) model.click(address, x, y)
                        if (y == f.cursorRow) onPrompt()
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
                            val l = lines[i]
                            val at = lines.size - 1 - i
                            val mine = remember { LaidRow() }
                            DisposableEffect(at) {
                                laid[at] = mine
                                onDispose { if (laid[at] === mine) laid.remove(at) }
                            }
                            // Links are underlined dotted, drawn under the row from its layout.
                            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                            // The ghost word, dim, from the cursor's cell on its row.
                            val ghostHere = ghost?.takeIf { it.isNotEmpty() && at == rows.size - screenRows + cursorRow - 1 && (pane?.cursorX ?: 0) >= 1 }
                            Text(
                                l.text, color = p.ink, style = style, softWrap = false, maxLines = 1,
                                onTextLayout = { layout = it; mine.layout = it },
                                modifier = Modifier.padding(horizontal = 2.dp).onGloballyPositioned { c ->
                                    val o = c.positionInParent()
                                    mine.top = o.y
                                    mine.bottom = o.y + c.size.height
                                    mine.left = o.x
                                }.drawBehind {
                                    if (ghostHere != null) drawText(ghostMeasurer, ghostHere, topLeft = Offset(((pane?.cursorX ?: 1) - 1) * colPx, 0f), style = style.copy(color = p.dim), softWrap = false, maxLines = 1)
                                    val t = layout ?: return@drawBehind
                                    if (t.lineCount == 0) return@drawBehind
                                    val y = t.getLineBottom(0) - dash / 2
                                    for (u in l.links) {
                                        val end = minOf(u.end, t.layoutInput.text.length)
                                        if (u.start >= end) continue
                                        drawLine(u.color, Offset(t.getHorizontalPosition(u.start, true), y), Offset(t.getHorizontalPosition(end, true), y), strokeWidth = dash / 2, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)))
                                    }
                                },
                            )
                        }
                    }
                }
            }
            // While the tile has nothing of its own to draw, the tube warms up over the box; the
            // first frame comes up under it. Never a borrowed screen, never a spinner.
            Warmup(hasOwn = state.read, Modifier.matchParentSize())
        }
    }
}

/** A dragged header or tab carries its pane's key under a type of this app's own, so no text from elsewhere is taken for a pane. */
private const val MIME = "application/x-muxway-pane"

private fun paneClip(address: String) = ClipData("pane", arrayOf(MIME), ClipData.Item(address))

private fun dragged(e: DragEvent): String? =
    e.clipData?.takeIf { it.description.hasMimeType(MIME) && it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

/** Where the list put a row, in its coordinates, and the row's text as laid out. */
private class LaidRow {
    var top = 0f
    var bottom = 0f
    var left = 0f
    var layout: TextLayoutResult? = null
}

/** What a tap on the pane needs to know, as it is at the moment of the tap. */
private data class TapFacts(
    val colPx: Float, val previews: Boolean, val names: Names.Index, val clickable: Boolean, val wheel: Boolean,
    val screenRows: Int, val cursorRow: Int, val cols: Int, val rows: Int, val sideways: Boolean,
)

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

private val linkStyles = TextLinkStyles(SpanStyle())

/** A row as drawn: its styled text and the ranges underlined as links, each in the colour the pane gave it. */
private class StyledRow(val text: AnnotatedString, val links: List<Underline>)

private class Underline(val start: Int, val end: Int, val color: Color)

// Colours as the pane gave them; a web address in the row, or a link the program named, opens
// through the system, and an inbox file or, where the host previews, a printed path is
// underlined the same way, dotted. The program's link comes first where they overlap.
private fun styled(row: List<Ansi.Span>, p: Palette, paths: Boolean): StyledRow {
    val links = ArrayList<Underline>()
    // The builder's own toString is not its text.
    val text = row.joinToString("") { it.text }
    val named = ArrayList<IntRange>()
    fun inNamed(at: Int) = named.any { at in it }
    fun colourAt(at: Int): Color {
        var off = 0
        for (s in row) {
            if (at < off + s.text.length) return (Colours.resolve(s.style.fg, p) ?: p.ink).let { if (s.style.dim) it.copy(alpha = 0.6f) else it }
            off += s.text.length
        }
        return p.ink
    }
    val built = buildAnnotatedString {
        for (s in row) {
            val st = spanStyle(s.style, p)
            val href = s.style.href
            if (href != null && s.text.isNotEmpty()) {
                val start = length
                named.add(start until start + s.text.length)
                addLink(LinkAnnotation.Url(href, linkStyles), start, start + s.text.length)
                links.add(Underline(start, start + s.text.length, colourAt(start)))
            }
            if (st == null) append(s.text) else withStyle(st) { append(s.text) }
        }
        for (l in Links.find(text)) if (!inNamed(l.start)) { addLink(LinkAnnotation.Url(l.href, linkStyles), l.start, l.end); links.add(Underline(l.start, l.end, colourAt(l.start))) }
        for (f in Links.findFiles(text)) if (Links.at(text, f.start) == null && !inNamed(f.start)) links.add(Underline(f.start, f.end, colourAt(f.start)))
        if (paths) for (l in Links.findPaths(text)) if (Links.at(text, l.start) == null && Links.fileAt(text, l.start) == null && !inNamed(l.start)) links.add(Underline(l.start, l.end, colourAt(l.start)))
        if (length == 0) append(" ")
    }
    return StyledRow(built, links)
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
