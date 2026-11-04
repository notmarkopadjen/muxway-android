package app.muxway.android.protocol

/**
 * Groups: panes that talked to each other in the last ten minutes, the partners of partners
 * too, from the listing's `talk` alone, as the web draws them. Keys are the app's (see [Keys]),
 * a partner joined with its pane's hub, so two hubs never form one group. A group is numbered
 * by its first member in the panes' order, so numbers are stable while the order is.
 */
object Groups {
    const val MAX_PARTNERS = 64
    val WAYS = setOf("to", "from", "both")

    /** One stretch of rows that share a group ([group] null outside any), for one frame per stretch. */
    data class Run<T>(val group: Int?, val rows: List<T>)

    fun of(panes: List<Pane>): Map<String, Int> {
        val parent = HashMap<String, String>()
        fun find(a: String): String {
            var r = a
            while (true) {
                val p = parent[r] ?: return r
                if (p == r) return r
                r = p
            }
        }
        fun union(a: String, b: String) {
            parent.putIfAbsent(a, a)
            parent.putIfAbsent(b, b)
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }
        for (p in panes) for (t in p.talk) union(p.key, Keys.make(p.hub, t.address))
        if (parent.isEmpty()) return emptyMap()
        // A pane whose partners are all out of the listing forms no group.
        val members = HashMap<String, Int>()
        for (p in panes) if (p.key in parent) { val r = find(p.key); members[r] = (members[r] ?: 0) + 1 }
        val numbers = LinkedHashMap<String, Int>()
        val groups = LinkedHashMap<String, Int>()
        for (p in panes) {
            if (p.key !in parent) continue
            val root = find(p.key)
            if ((members[root] ?: 0) < 2) continue
            groups[p.key] = numbers.getOrPut(root) { numbers.size + 1 }
        }
        return groups
    }

    /** The members of a group side by side after the group's first member; everything else keeps its place. */
    fun <T> pullTogether(rows: List<T>, groups: Map<String, Int>, key: (T) -> String): List<T> {
        val out = ArrayList<T>(rows.size)
        val done = HashSet<String>()
        for (r in rows) {
            val k = key(r)
            if (!done.add(k)) continue
            out.add(r)
            val g = groups[k] ?: continue
            for (s in rows) {
                val sk = key(s)
                if (sk !in done && groups[sk] == g) { out.add(s); done.add(sk) }
            }
        }
        return out
    }

    /** The strip: the awake rows pulled together and cut into runs, then the parked rows, each a run of its own outside any frame. */
    fun <T> strip(rows: List<T>, groups: Map<String, Int>, key: (T) -> String, parked: (T) -> Boolean): List<Run<T>> =
        runs(pullTogether(rows.filterNot(parked), groups, key), groups, key) + rows.filter(parked).map { Run(null, listOf(it)) }

    /** The rows cut into stretches that share a group; a row outside any group is a stretch of its own. */
    fun <T> runs(rows: List<T>, groups: Map<String, Int>, key: (T) -> String): List<Run<T>> {
        val out = ArrayList<Run<T>>()
        for (r in rows) {
            val g = groups[key(r)]
            val last = out.lastOrNull()
            if (last != null && g != null && last.group == g) out[out.size - 1] = Run(g, last.rows + r)
            else out.add(Run(g, listOf(r)))
        }
        return out
    }
}
