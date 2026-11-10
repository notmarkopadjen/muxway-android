package app.muxway.android.ui

import app.muxway.android.protocol.Pane
import app.muxway.android.protocol.SizedBy
import app.muxway.android.protocol.WindowLayout

/**
 * Tiles that are panes of one tmux window share one fit, because two fits on one row can only
 * undo each other: the focused tile's pane fits, or the first of them when the focus is in
 * another window; the rest are held. When the open tiles are every pane of one window, the
 * window fits as one, laid out as the tiles are, and no tile is held.
 */
object TileFit {
    fun held(open: List<String>, focused: String, windowOf: (String) -> String): Set<String> {
        val out = HashSet<String>()
        for (window in open.map(windowOf).distinct()) {
            val mates = open.filter { windowOf(it) == window }
            if (mates.size < 2) continue
            val lead = if (focused in mates) focused else mates[0]
            out.addAll(mates.filter { it != lead })
        }
        return out
    }

    /**
     * The panes' ids in tile order when [open] (two or more) are exactly the panes of one window
     * in [panes]; null otherwise. Sizing such a window needs one writable pane, moving its panes
     * every one ([movable]).
     */
    fun oneWindow(open: List<String>, panes: List<Pane>, windowOf: (String) -> String): List<String>? {
        if (open.size < 2) return null
        val window = windowOf(open[0])
        val mates = panes.filter { windowOf(it.key) == window }
        if (mates.size != open.size) return null
        val ids = open.map { k -> mates.firstOrNull { it.key == k } ?: return null }
        if (ids.any { !WindowLayout.PANE_ID.matches(it.paneId) }) return null
        return ids.map { it.paneId }
    }

    fun movable(open: List<String>, panes: List<Pane>): Boolean = open.all { k -> panes.firstOrNull { it.key == k }?.writable == true }

    /**
     * Whether the pane at [key] has siblings in its tmux window: another listed pane of the
     * window, or the window's layout in the listing naming more than one pane. The layout counts
     * the panes the token cannot see; a window resized without the zoom is unzoomed by the host,
     * which leaves the pane its share beside neighbours this phone never heard of, half the
     * width beside one, and the tile reads held.
     */
    fun siblings(key: String, panes: List<Pane>, windowOf: (String) -> String): Boolean {
        if (panes.any { it.key != key && windowOf(it.key) == windowOf(key) }) return true
        val layout = panes.firstOrNull { it.key == key }?.windowLayout?.takeIf { it.isNotEmpty() } ?: return false
        val host = WindowLayout.parse(layout) ?: return false
        return WindowLayout.panes(host).size > 1
    }
}

/**
 * How a tile draws its pane, from whether it fits, whether the host kept the fit and the
 * settings. A tile the host sized as asked is drawn at the text setting and its rows cut at the
 * tile's columns, which are the pane's. A tile the host did not size as asked ([held]: another
 * terminal holds the window, its floor of 20 columns a pane is wider than the tile, or another
 * tile of the window carries the fit) is drawn like a tile with fit off: scaled until the pane's
 * columns fit the tile, nothing re-cut, as the guideline's *fit* row says. The *reflow* setting
 * re-cuts the rows at the tile instead of scaling, for a tile this phone does not size. Whatever
 * the tile is drawn at, the fit is measured at the text setting ([Drawn.cellSp]), so a tile
 * scaled to the host's columns never asks the host for them.
 */
/**
 * Who holds a tile's size, for its header and for the touched-last rule: [NONE] when the host
 * took this phone's size; [HOST] when it did not and nobody is named (the host's floor, a
 * terminal, an older hub), or the holder is this very client (this token from this device);
 * [OTHER] when another client's fit holds the window: another token, or this token from
 * another device, which its label tells apart.
 */
sealed class Held {
    data object NONE : Held()
    data object HOST : Held()
    data class OTHER(val token: String, val client: String) : Held()

    companion object {
        fun of(heldSize: Boolean, sized: SizedBy?, label: String): Held = when {
            !heldSize -> NONE
            sized == null -> HOST
            sized.mine && sized.client == label -> HOST
            else -> OTHER(sized.token, sized.client)
        }

        /** The header's word: `held`, or `held by <token> · <client>`. */
        fun words(h: Held): String = when (h) {
            is OTHER -> if (h.client.isEmpty()) "held by ${h.token}" else "held by ${h.token} · ${h.client}"
            else -> "held"
        }
    }
}

/**
 * The client touched last wins: an interaction with a pane another client holds sends this
 * phone's fit again, two seconds apart at most; nobody's hold, or this phone's own, sends nothing.
 */
object Refit {
    const val GAP_MS = 2_000L

    fun wanted(held: Held, lastAt: Long, now: Long): Boolean = held is Held.OTHER && now - lastAt >= GAP_MS
}

object TileScale {
    /** [drawSp] draws the rows; [cellSp] measures the cells the fit asks for; [paneCols] cuts the rows at the pane's columns, else the tile's. */
    data class Drawn(val drawSp: Float, val cellSp: Float, val paneCols: Boolean)

    fun of(fitting: Boolean, held: Boolean, reflow: Boolean, textSp: Float, fitSp: Float, zoom: Float): Drawn {
        val cell = (textSp * zoom).coerceAtLeast(2f)
        val own = fitting && !held
        return when {
            own || reflow -> Drawn(cell, cell, paneCols = false)
            else -> Drawn((minOf(textSp, fitSp) * zoom).coerceAtLeast(2f), cell, paneCols = true)
        }
    }
}
