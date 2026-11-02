package app.muxway.android.protocol

/**
 * The inbox: every pane that waits for its owner, on every hub, oldest first. The count in
 * the headers comes from the listings the app already holds; the sheet asks each hub's
 * `/v1/inbox` while it is open and merges what they answer the same way.
 */
object Inbox {
    fun waiting(panes: List<Pane>): List<Pane> =
        panes.filter { it.attention != null }.sortedWith(compareBy<Pane> { it.attention!!.at }.thenBy { it.key })

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
