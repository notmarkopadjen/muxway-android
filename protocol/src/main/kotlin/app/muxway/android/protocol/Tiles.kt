package app.muxway.android.protocol

import app.muxway.android.protocol.WindowLayout.Host
import app.muxway.android.protocol.WindowLayout.Node
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The tiles' arrangement as a tree of splits, each with a key its shares are kept under, as
 * the browser has it: a header dropped on a tile's middle swaps the two, on its edge splits
 * that tile's place, on a divider lays the pane across that split; a closed tile is pruned;
 * the host's layout of a window becomes the tree when the tiles are its panes.
 */
object Tiles {
    const val FLOOR = 0.15f

    enum class Zone { SWAP, TOP, BOTTOM, LEFT, RIGHT }

    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float)

    data class FromHost(val node: Node, val weights: Map<String, List<Float>>)

    fun equal(n: Int): List<Float> = List(n) { 1f / n }

    /** Where on a tile a dragged header lands: its middle swaps, a part near an edge splits that way. */
    fun zoneAt(x: Float, y: Float): Zone {
        if (x > 1f / 3 && x < 2f / 3 && y > 1f / 3 && y < 2f / 3) return Zone.SWAP
        val edges = listOf(Zone.TOP to y, Zone.BOTTOM to 1 - y, Zone.LEFT to x, Zone.RIGHT to 1 - x)
        return edges.minBy { it.second }.first
    }

    /** Keys by place in the tree, so a made tree's shares have names. */
    fun relabel(node: Node, key: String = "r"): Node = when (node) {
        is Node.Leaf -> node
        is Node.Split -> Node.Split(node.horizontal, node.children.mapIndexed { i, c -> relabel(c, "$key.$i") }, key)
    }

    // Takes leaf `at` out; a split left with one child gives way to it, and one that runs the
    // same way as its parent joins it.
    private fun without(node: Node, at: Int): Node? {
        if (node is Node.Leaf) return if (node.index == at) null else node
        val s = node as Node.Split
        val kept = s.children.mapNotNull { without(it, at) }
        if (kept.isEmpty()) return null
        if (kept.size == 1) return kept[0]
        val flat = kept.flatMap { c -> if (c is Node.Split && c.horizontal == s.horizontal) c.children else listOf(c) }
        return Node.Split(s.horizontal, flat, s.key)
    }

    /** A split inside one that runs the same way joins it. */
    private fun flatten(node: Node): Node {
        if (node is Node.Leaf) return node
        val s = node as Node.Split
        val kids = s.children.map { flatten(it) }.flatMap { c -> if (c is Node.Split && c.horizontal == s.horizontal) c.children else listOf(c) }
        return Node.Split(s.horizontal, kids, s.key)
    }

    /** Drops tile [from] onto an edge of tile [to]: `to`'s place splits, the dragged tile on that side; its old place closes up. */
    fun dropOn(node: Node, from: Int, to: Int, zone: Zone): Node {
        if (from == to || zone == Zone.SWAP) return node
        val rest = without(node, from) ?: Node.Leaf(to)
        val horizontal = zone == Zone.LEFT || zone == Zone.RIGHT
        val first = zone == Zone.TOP || zone == Zone.LEFT
        fun put(n: Node): Node = when (n) {
            is Node.Leaf -> if (n.index == to) Node.Split(horizontal, if (first) listOf(Node.Leaf(from), n) else listOf(n, Node.Leaf(from))) else n
            is Node.Split -> Node.Split(n.horizontal, n.children.map { put(it) }, n.key)
        }
        return relabel(flatten(put(rest)))
    }

    /** Takes tile [at] out for good: the tiles after it move up one. */
    fun prune(node: Node, at: Int): Node {
        fun shift(n: Node): Node = when (n) {
            is Node.Leaf -> if (n.index > at) Node.Leaf(n.index - 1) else n
            is Node.Split -> Node.Split(n.horizontal, n.children.map { shift(it) }, n.key)
        }
        return relabel(shift(without(node, at) ?: Node.Leaf(0)))
    }

    /** Moves [delta] of the whole between children [k] and k+1, keeping each at least the floor. */
    fun drag(weights: List<Float>, k: Int, delta: Float): List<Float> {
        val w = weights.toMutableList()
        if (k < 0 || k + 1 >= w.size) return w
        val move = delta.coerceIn(-(w[k] - FLOOR), w[k + 1] - FLOOR)
        w[k] += move
        w[k + 1] -= move
        return w
    }

    /** Same splits with the same tiles in the same places, shares aside. */
    fun sameTree(a: Node, b: Node): Boolean = when {
        a is Node.Leaf || b is Node.Leaf -> a is Node.Leaf && b is Node.Leaf && a.index == b.index
        else -> (a as Node.Split).horizontal == (b as Node.Split).horizontal && a.children.size == b.children.size &&
            a.children.indices.all { sameTree(a.children[it], b.children[it]) }
    }

    /** Whether every split of [node] has shares within [by] of the other's. */
    fun nearWeights(node: Node, a: Map<String, List<Float>>, b: Map<String, List<Float>>, by: Float): Boolean {
        if (node is Node.Leaf) return true
        val s = node as Node.Split
        val n = s.children.size
        val wa = a[s.key] ?: equal(n)
        val wb = b[s.key] ?: equal(n)
        return wa.indices.all { abs(wa[it] - (wb.getOrNull(it) ?: 0f)) <= by } && s.children.all { nearWeights(it, a, b, by) }
    }

    /**
     * Lays tile [at] across the place of the split keyed [key]: its old place closes up and the
     * split's place is wrapped in a split, stacked ([horizontal] false, the tile the whole width)
     * or side by side, with the tile first or last.
     */
    fun across(node: Node, at: Int, key: String, horizontal: Boolean, first: Boolean): Node {
        val gone = -1
        fun mark(n: Node): Node = when (n) {
            is Node.Leaf -> if (n.index == at) Node.Leaf(gone) else n
            is Node.Split -> Node.Split(n.horizontal, n.children.map { mark(it) }, n.key)
        }
        var found = false
        fun put(n: Node): Node {
            if (n is Node.Leaf) return n
            val s = n as Node.Split
            if (s.key == key && !found) {
                found = true
                val rest = without(s, gone) ?: s
                return Node.Split(horizontal, if (first) listOf(Node.Leaf(at), rest) else listOf(rest, Node.Leaf(at)))
            }
            return Node.Split(s.horizontal, s.children.map { put(it) }, s.key)
        }
        val made = put(mark(node))
        if (!found) return node
        return relabel(flatten(without(made, gone) ?: Node.Leaf(at)))
    }

    /** Where each tile is in the area, in fractions of it. */
    fun rects(node: Node, weights: Map<String, List<Float>>): Map<Int, Rect> {
        val out = HashMap<Int, Rect>()
        fun walk(n: Node, r: Rect) {
            if (n is Node.Leaf) { out[n.index] = r; return }
            val s = n as Node.Split
            val f = weights[s.key] ?: equal(s.children.size)
            var at = 0f
            s.children.forEachIndexed { i, c ->
                walk(c, if (s.horizontal) Rect(r.x + at * r.w, r.y, f[i] * r.w, r.h) else Rect(r.x, r.y + at * r.h, r.w, f[i] * r.h))
                at += f[i]
            }
        }
        walk(node, Rect(0f, 0f, 1f, 1f))
        return out
    }

    fun rootKey(node: Node): String? = (node as? Node.Split)?.key

    fun leaves(node: Node): List<Int> = when (node) {
        is Node.Leaf -> listOf(node.index)
        is Node.Split -> node.children.flatMap { leaves(it) }
    }

    // Shares of a split taken from the host. Children within a cell of one another are equal
    // shares: the host could not split its cells more evenly.
    private fun evenShares(s: Host.Split): List<Float> {
        val sizes = s.children.map { if (s.horizontal) it.w else it.h }
        if (sizes.max() - sizes.min() <= 1) return equal(sizes.size)
        val total = sizes.sum().coerceAtLeast(1)
        return sizes.map { it.toFloat() / total }
    }

    /** The host's layout of a window as the tiles' tree, when the tiles ([ids]) are its panes; null when it names another pane. */
    fun fromHost(layout: String, ids: List<String>): FromHost? {
        val root = WindowLayout.parse(layout) ?: return null
        val weights = HashMap<String, List<Float>>()
        fun walk(h: Host, key: String): Node? = when (h) {
            is Host.Pane -> ids.indexOf(h.id).takeIf { it >= 0 }?.let { Node.Leaf(it) }
            is Host.Split -> {
                val kids = h.children.mapIndexed { i, c -> walk(c, "$key.$i") ?: return null }
                weights[key] = evenShares(h)
                Node.Split(h.horizontal, kids, key)
            }
        }
        val node = walk(root, "r") ?: return null
        if (leaves(node).size != ids.size) return null
        return FromHost(node, weights)
    }

    /**
     * The tree with its shares as a tmux layout the host scales to the window's size: only the
     * proportions matter, so each split hands its children a thousand units.
     */
    fun layoutFor(node: Node, weights: Map<String, List<Float>>, ids: List<String>): String {
        require(ids.all { WindowLayout.PANE_ID.matches(it) }) { "pane id" }
        fun cell(n: Node, w: Int, h: Int): String = when (n) {
            is Node.Leaf -> "${w}x$h,0,0,${ids[n.index].drop(1)}"
            is Node.Split -> {
                val f = weights[n.key] ?: equal(n.children.size)
                val parts = n.children.mapIndexed { i, c ->
                    val size = maxOf(1, (f[i] * 1000).roundToInt())
                    if (n.horizontal) cell(c, size, h) else cell(c, w, size)
                }
                "${w}x$h,0,0${if (n.horizontal) "{" else "["}${parts.joinToString(",")}${if (n.horizontal) "}" else "]"}"
            }
        }
        val body = cell(node, 1000, 1000)
        return "${WindowLayout.checksum(body)},$body"
    }
}
