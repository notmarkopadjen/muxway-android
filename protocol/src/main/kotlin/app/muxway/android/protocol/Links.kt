package app.muxway.android.protocol

/**
 * Web addresses in a row's plain text, so the screen can make them links. Only http and
 * https: whatever else a pane prints is text.
 */
object Links {
    data class Link(val start: Int, val end: Int, val href: String)

    // A run stops at blank space and at the quotes and brackets that wrap an address in
    // prose; punctuation at its end belongs to the sentence, except a closing bracket that
    // an opening one inside the address accounts for.
    private val run = Regex("""https?://[^\s\p{Z}<>"'`]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?)]}>'\""
    private val opens = mapOf(')' to '(', ']' to '[', '}' to '{')

    fun find(line: String): List<Link> {
        val out = ArrayList<Link>()
        for (m in run.findAll(line)) {
            val start = m.range.first
            val body = start + m.value.indexOf("://") + 3
            var end = m.range.last + 1
            while (end > body) {
                val c = line[end - 1]
                if (c !in TRAILING) break
                if (c in opens && balanced(line, start, end, c)) break
                end--
            }
            if (end > body) out.add(Link(start, end, line.substring(start, end)))
        }
        return out
    }

    /** The link, if any, that column [col] (0-based) of [line] falls in. */
    fun at(line: String, col: Int): Link? = find(line).firstOrNull { col >= it.start && col < it.end }

    private fun balanced(s: String, start: Int, end: Int, close: Char): Boolean {
        val open = opens.getValue(close)
        var n = 0
        for (i in start until end) {
            when (s[i]) {
                open -> n++
                close -> n--
            }
        }
        return n >= 0
    }
}
