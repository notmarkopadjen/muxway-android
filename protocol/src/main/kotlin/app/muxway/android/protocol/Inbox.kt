package app.muxway.android.protocol

/**
 * The inbox: every pane that waits for its owner, on every hub, oldest first, then the panes
 * the owner parked for later, oldest parking first, as the hub orders its own. The count in
 * the headers comes from the listings the app already holds; the sheet asks each hub's
 * `/v1/inbox` while it is open and merges what they answer the same way.
 */
object Inbox {
    fun waiting(panes: List<Pane>): List<Pane> =
        panes.filter { it.attention != null || it.later }
            .sortedWith(compareBy<Pane> { it.later }.thenBy { it.attention?.at ?: it.laterAt }.thenBy { it.key })

    /** The row *oldest* opens: the first awake one; null when every row is parked. */
    fun oldest(rows: List<Pane>): Pane? = rows.firstOrNull { !it.later }

    /** How long ago, in a word or two: `12s`, `3m`, `2h`, `1d`. */
    fun ago(at: Long, now: Long): String {
        val s = (now - at).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86400 -> "${s / 3600}h"
            else -> "${s / 86400}d"
        }
    }
}
