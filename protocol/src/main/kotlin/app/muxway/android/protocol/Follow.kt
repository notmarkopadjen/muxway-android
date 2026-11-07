package app.muxway.android.protocol

/**
 * A tile shows its own pane or nothing. An address is a position (`work/1.1`), and the host
 * renumbers panes when one of their window closes, so a tile knows its pane by the host's id,
 * takes no read that names another pane at its address, follows its pane to its new address
 * when a listing shows it there, and closes when its host is listed without it.
 */
object Follow {
    /** A pane's id as tmux writes it, bounded as every field of a listing is: 16 characters at most. */
    val ID = Regex("^%\\d{1,15}$")

    /** An id as the hub listed it: the grammar's, or empty, which is no id. */
    fun id(raw: String): String = if (ID.matches(raw)) raw else ""

    /** Whether a read at the tile's address is the tile's pane: the same id, or none learnt yet. */
    fun own(tile: String, read: String): Boolean = tile.isEmpty() || tile == read

    /** What one listing says about the open tiles: the keys that moved, old to new, and the keys whose pane is gone. */
    data class Outcome(val moved: Map<String, String>, val gone: List<String>) {
        val empty get() = moved.isEmpty() && gone.isEmpty()
    }

    /**
     * One pass over [hub]'s listing for the view's [open] keys: [idOf] is a tile's id ("" for none),
     * [missing] the keys whose read found no pane at the address, [panes] the hub's listing with
     * the hub set on every pane, [hostOnline] what the hosts listing says. A tile without an id is
     * left to its reads, unless they found nothing. A tile whose pane is listed elsewhere moves; one
     * whose pane is in no listing is gone when its host is there without it (online, or any pane
     * of it listed, or the read found nothing), and kept while the host itself is away.
     */
    fun reconcile(hub: String, open: List<String>, idOf: (String) -> String, missing: Set<String>, panes: List<Pane>, hostOnline: (String) -> Boolean): Outcome {
        val moved = LinkedHashMap<String, String>()
        val gone = ArrayList<String>()
        val mine = panes.filter { it.hub == hub }
        for (k in open) {
            if (Keys.hub(k) != hub) continue
            val host = Keys.address(k).substringBefore('/')
            val id = idOf(k)
            if (id.isEmpty()) {
                if (k in missing) gone.add(k)
                continue
            }
            val now = mine.firstOrNull { it.host == host && it.paneId == id }
            if (now != null) {
                if (now.key != k) moved[k] = now.key
                continue
            }
            if (hostOnline(host) || mine.any { it.host == host } || k in missing) gone.add(k)
        }
        return Outcome(moved, gone)
    }
}
