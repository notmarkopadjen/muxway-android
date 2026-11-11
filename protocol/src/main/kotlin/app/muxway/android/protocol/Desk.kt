package app.muxway.android.protocol

import app.muxway.android.protocol.WindowLayout.Node

/**
 * The desk: the view the owner's clients share, kept by the hub (`GET` and `PUT /v1/desk`).
 * [view] is `panes`, with the tiles in [panes], the [focused] one, the [arrangement] (`row`,
 * `column`, `grid`, `main-left`, `main-top`, or `custom` with a [tree] and [weights]) and the
 * tab [order]; or `window`, with the [window] the window view shows; or `list`, nothing open
 * (the last tile minimised with no other pane awake). The minimised row is not the desk's: it
 * is the panes parked for later, which the listing carries (`later`).
 * [updated], [by] and [rev] are the hub's: [rev] counts the writes and orders two desks.
 */
data class Desk(
    val view: String = "panes",
    val panes: List<String> = emptyList(),
    val focused: String = "",
    val arrangement: String = "",
    val tree: Node? = null,
    val weights: Map<String, List<Float>> = emptyMap(),
    val order: List<String> = emptyList(),
    val window: String = "",
    val updated: Long = 0,
    val by: String = "",
    val rev: Long = 0,
)

object Desks {
    val ARRANGEMENTS = listOf("row", "column", "grid", "main-left", "main-top", "custom")
    const val MAX_PANES = 4
    const val MAX_ORDER = 64
    const val MAX_KEY = 16
    const val MAX_BY = 64
    /** How long after the last change the view's desk is written, the web's. */
    const val DEBOUNCE_MS = 500L
    const val MAX_DEPTH = 8

    /** What a desk says, without who and when: equal desks need no write and no following. */
    fun body(d: Desk): String = listOf(
        d.view, d.panes.joinToString(","), d.focused, d.arrangement, d.tree?.toString() ?: "",
        d.weights.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value.joinToString(",")}" },
        d.order.joinToString(","), d.window,
    ).joinToString("\u001f")

    fun same(a: Desk?, b: Desk?): Boolean = a != null && b != null && body(a) == body(b)

    /** A panes desk's tiles; nothing for a window desk or an empty one. */
    fun panesOf(d: Desk?): List<String> = if (d != null && d.view == "panes") d.panes else emptyList()

    /** Whether the desk opens anything: tiles, or a window. */
    fun holds(d: Desk?): Boolean = panesOf(d).isNotEmpty() || (d?.view == "window" && d.window.isNotEmpty())

    /** How a desk reads on its row, the web's words. */
    fun words(d: Desk?): String {
        if (d == null) return "desk"
        if (d.view == "window" && d.window.isNotEmpty()) return "desk · the window ${d.window}"
        if (d.view == "list") return "desk · the list"
        val n = panesOf(d).size
        if (n == 0) return "desk · empty"
        val parts = ArrayList<String>()
        parts.add("$n pane${if (n == 1) "" else "s"}")
        if (d.arrangement.isNotEmpty() && n > 1) parts.add(d.arrangement)
        if (d.by.isNotEmpty()) parts.add("by ${d.by}")
        return "desk · ${parts.joinToString(" · ")}"
    }

    /**
     * Whether a tree is well shaped for [n] tiles: splits of two to four children with keys of
     * 1 to [MAX_KEY] characters, nested at most [MAX_DEPTH] deep, and the leaves 0 until n, each once.
     */
    fun validTree(node: Node, n: Int): Boolean {
        val seen = HashSet<Int>()
        fun walk(x: Node, depth: Int): Boolean = when (x) {
            is Node.Leaf -> x.index in 0 until n && seen.add(x.index)
            is Node.Split -> depth <= MAX_DEPTH && x.children.size in 2..MAX_PANES && x.key.length in 1..MAX_KEY && x.children.all { walk(it, depth + 1) }
        }
        return walk(node, 0) && seen.size == n
    }

    /** Whether the weights are the hub's shape: 2 to 4 shares each strictly between 0 and 1, under keys of the bound, eight lists at most. */
    fun validWeights(w: Map<String, List<Float>>): Boolean =
        w.size <= MAX_PANES * 2 && w.all { (k, ws) -> k.length in 1..MAX_KEY && ws.size in 2..MAX_PANES && ws.all { it > 0f && it < 1f } }
}
