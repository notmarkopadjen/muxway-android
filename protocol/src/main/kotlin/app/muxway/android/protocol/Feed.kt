package app.muxway.android.protocol

/**
 * The messages sheet's model: every hub's talk feed as one list, the names its rows show, and
 * the mark of the newest entry looked at, per hub, which the count in the headers counts above.
 */
object Feed {
    /** Every hub's entries as one list, oldest first. */
    fun merge(perHub: Collection<List<TalkEntry>>): List<TalkEntry> =
        perHub.flatten().sortedWith(compareBy<TalkEntry> { it.at }.thenBy { it.hub }.thenBy { it.seq })

    /**
     * A pane's name as its tab shows it, by its current address or, after the address moved, by
     * its id on the same host; the address without its host when the listing lacks it.
     */
    fun nameOf(panes: List<Pane>, hub: String, address: String, paneId: String = ""): String {
        val p = panes.firstOrNull { it.hub == hub && it.address == address }
            ?: paneId.takeIf { it.isNotEmpty() }?.let { id -> panes.firstOrNull { it.hub == hub && it.paneId == id && it.host == address.substringBefore('/') } }
        return p?.tab ?: address.substringAfter('/')
    }

    /** How many entries are newer than their hub's mark. */
    fun unseen(entries: List<TalkEntry>, seen: Map<String, Long>): Int = entries.count { it.seq > (seen[it.hub] ?: 0L) }

    /** The marks moved up to the newest entry of each hub. */
    fun seen(entries: List<TalkEntry>, seen: Map<String, Long>): Map<String, Long> {
        val out = HashMap(seen)
        for (e in entries) out[e.hub] = maxOf(out[e.hub] ?: 0L, e.seq)
        return out
    }

    /** The panes a row lays side by side: the two, or the one alone when a client wrote to it. */
    fun panesOf(e: TalkEntry): List<String> =
        if (e.from == null || e.from.address == e.to.address) listOf(e.to.address) else listOf(e.from.address, e.to.address)

    /** The row's second line: a mail or typed text as it is, a key or an answer with its kind before it. */
    fun excerpt(e: TalkEntry): String = if (e.kind == "text" || e.kind == "mail") e.text else "${e.kind}: ${e.text}"
}
