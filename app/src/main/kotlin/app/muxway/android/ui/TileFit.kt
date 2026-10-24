package app.muxway.android.ui

/**
 * Tiles that are panes of one tmux window share one fit, because two fits on one row can only
 * undo each other: the focused tile's pane fits, or the first of them when the focus is in
 * another window; the rest are held.
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
}
