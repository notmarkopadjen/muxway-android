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
