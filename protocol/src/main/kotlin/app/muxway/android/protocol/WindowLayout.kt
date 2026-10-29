package app.muxway.android.protocol

/**
 * The tiles' arrangement as a tmux layout for their window, when the open tiles are every pane
 * of it: a checksum, then cells `WxH,X,Y` that are a pane (`,id`) or a split with children in
 * `{...}` (side by side) or `[...]` (stacked). Sizes are exact, each pane as many cells as its
 * tile holds, so no pane is given rows its tile cannot show. The browser builds the same.
 */
object WindowLayout {
    /** A tile by index, or a split of tiles side by side ([horizontal]) or stacked; a split's [key] names its shares. */
    sealed class Node {
        data class Leaf(val index: Int) : Node()
        data class Split(val horizontal: Boolean, val children: List<Node>, val key: String = "") : Node()
    }

    /** A host's layout as tmux wrote it: cells with their sizes and places, panes by id. */
    sealed class Host {
        abstract val w: Int
        abstract val h: Int
        data class Pane(val id: String, override val w: Int, override val h: Int, val x: Int, val y: Int) : Host()
        data class Split(val horizontal: Boolean, override val w: Int, override val h: Int, val x: Int, val y: Int, val children: List<Host>) : Host()
    }

    /** tmux's layout string as a tree, or null when it is not one. */
    fun parse(layout: String): Host? {
        val comma = layout.indexOf(',')
        if (comma < 0 || layout.length > MAX_BYTES) return null
        val s = layout.substring(comma + 1)
        var i = 0
        fun number(): Int? {
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            return if (i == start || i - start > 9) null else s.substring(start, i).toInt()
        }
        fun take(c: Char): Boolean = if (i < s.length && s[i] == c) { i++; true } else false
        fun cell(): Host? {
            val w = number() ?: return null
            if (!take('x')) return null
            val h = number() ?: return null
            if (!take(',')) return null
            val x = number() ?: return null
            if (!take(',')) return null
            val y = number() ?: return null
            val open = s.getOrNull(i)
            if (open == '{' || open == '[') {
                i++
                val kids = ArrayList<Host>()
                kids.add(cell() ?: return null)
                while (take(',')) kids.add(cell() ?: return null)
                if (!take(if (open == '{') '}' else ']')) return null
                return Host.Split(open == '{', w, h, x, y, kids)
            }
            if (!take(',')) return null
            val id = number() ?: return null
            return Host.Pane("%$id", w, h, x, y)
        }
        val root = cell() ?: return null
        return if (i == s.length) root else null
    }

    fun panes(h: Host): List<Host.Pane> = when (h) {
        is Host.Pane -> listOf(h)
        is Host.Split -> h.children.flatMap { panes(it) }
    }

    data class Size(val cols: Int, val rows: Int)

    /** The window's size and layout, and the cells each tile's pane was given, by tile index. */
    data class Exact(val cols: Int, val rows: Int, val layout: String, val sizes: List<Size>)

    /** The hub takes a layout of at most this many bytes. */
    const val MAX_BYTES = 2048

    /** A pane id as tmux names it. */
    val PANE_ID = Regex("^%\\d+$")

    /** tmux's checksum over a layout body: a rotating 16-bit sum. */
    fun checksum(body: String): String {
        var c = 0
        for (ch in body) {
            c = ((c shr 1) + ((c and 1) shl 15)) and 0xffff
            c = (c + ch.code) and 0xffff
        }
        return c.toString(16).padStart(4, '0')
    }

    /** The tiles along the bottom of the view: every tile of a row, the last of a stack. */
    fun bottomLeaves(node: Node): List<Int> = when (node) {
        is Node.Leaf -> listOf(node.index)
        is Node.Split -> if (node.horizontal) node.children.flatMap { bottomLeaves(it) } else bottomLeaves(node.children.last())
    }

    /**
     * The layout for [node] with [sizes] per tile index and [ids] the panes' ids in the same
     * order. A split side by side is as wide as its children together with a divider between
     * each two and as tall as the shortest; stacked, as tall as its children together and as
     * wide as the narrowest. The window's size is the root's.
     */
    fun exact(node: Node, sizes: List<Size>, ids: List<String>): Exact {
        require(ids.all { PANE_ID.matches(it) }) { "pane id" }
        val given = HashMap<Int, Size>()
        fun natural(n: Node): Size = when (n) {
            is Node.Leaf -> sizes[n.index]
            is Node.Split -> {
                val kids = n.children.map { natural(it) }
                val gaps = kids.size - 1
                if (n.horizontal) Size(kids.sumOf { it.cols } + gaps, kids.minOf { it.rows })
                else Size(kids.minOf { it.cols }, kids.sumOf { it.rows } + gaps)
            }
        }
        fun cell(n: Node, w: Int, h: Int): String = when (n) {
            is Node.Leaf -> {
                given[n.index] = Size(w, h)
                "${w}x$h,0,0,${ids[n.index].drop(1)}"
            }
            is Node.Split -> {
                val kids = n.children.map { natural(it) }
                val gaps = kids.size - 1
                val parts = if (n.horizontal) shares(kids.map { it.cols }, w - gaps).mapIndexed { i, cw -> cell(n.children[i], cw, h) }
                else shares(kids.map { it.rows }, h - gaps).mapIndexed { i, ch -> cell(n.children[i], w, ch) }
                "${w}x$h,0,0${if (n.horizontal) "{" else "["}${parts.joinToString(",")}${if (n.horizontal) "}" else "]"}"
            }
        }
        val root = natural(node)
        val body = cell(node, root.cols, root.rows)
        return Exact(root.cols, root.rows, "${checksum(body)},$body", sizes.indices.map { given[it] ?: sizes[it] })
    }

    /** Whole shares of [total] in the proportions of [wants], summing to it, each at least one. */
    private fun shares(wants: List<Int>, total: Int): List<Int> {
        val sum = wants.sum().coerceAtLeast(1)
        val out = wants.map { maxOf(1, it * total / sum) }.toMutableList()
        var left = total - out.sum()
        var i = 0
        while (left > 0) { out[i]++; left--; i = (i + 1) % out.size }
        i = 0
        while (left < 0 && i < out.size) { if (out[i] > 1) { out[i]--; left++ } else i++ }
        return out
    }
}
