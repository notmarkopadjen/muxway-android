package app.muxway.android.ui

import app.muxway.android.protocol.Pane
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
