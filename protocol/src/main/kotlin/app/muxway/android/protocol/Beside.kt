package app.muxway.android.protocol

import kotlin.math.abs

/**
 * Laying the pane a tile talks with beside it, the browser's rule: swapped next to the tile
 * when open, opened after it when there is room, in the place of the tile focused longest ago
 * (never the tile itself) when the view is full.
 */
object Beside {
    const val MAX_TILES = 4

    /** [open] with [partner] next to [at]; [focusedAt] says when each open pane was last focused. */
    fun lay(open: List<String>, at: String, partner: String, focusedAt: Map<String, Long>, max: Int = MAX_TILES): List<String> {
        val i = open.indexOf(at)
        if (i < 0 || partner == at) return open
        val slot = if (i + 1 < open.size) i + 1 else i - 1
        val j = open.indexOf(partner)
        if (j >= 0) {
            if (abs(j - i) == 1) return open
            val out = open.toMutableList()
            out[j] = open[slot]
            out[slot] = partner
            return out
        }
        if (open.size < max) return open.take(i + 1) + partner + open.drop(i + 1)
        // The neighbour's slot gives way unless another tile was focused longer ago.
        var victim = slot
        var oldest = focusedAt[open[slot]] ?: 0L
        for ((k, x) in open.withIndex()) {
            val t = focusedAt[x] ?: 0L
            if (k != i && t < oldest) { oldest = t; victim = k }
        }
        val out = open.toMutableList()
        out[victim] = partner
        return out
    }

    /** The panes [pane] talked with that the listing has, each once, in the order the listing names them. */
    fun partners(pane: Pane, panes: List<Pane>): List<Pane> =
        pane.talk.mapNotNull { t -> panes.firstOrNull { it.key == Keys.make(pane.hub, t.address) } }.distinctBy { it.key }
}
