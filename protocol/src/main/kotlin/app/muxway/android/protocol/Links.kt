package app.muxway.android.protocol

/**
 * What a row's plain text links to, the browser's rules: a web address (http and https only),
 * a file of the host's inbox by the name the agent gave it, and, on a host with preview roots,
 * a path of a file the host shows. Whatever else a pane prints is text.
 */
object Links {
    data class Link(val start: Int, val end: Int, val href: String)

    /** A file of the inbox: [name] is the whole of what travels. */
    data class FileLink(val start: Int, val end: Int, val name: String)

    /** A path as printed; where it leads is the host's to say. */
    data class PathLink(val start: Int, val end: Int, val path: String)

    private val file = Regex("""/(?:\S*?/)?(\d{8}-\d{6}-[a-z0-9]{6}\.(?:png|jpg|gif|webp|pdf|txt|md|json|zip))(?!\.?[A-Za-z0-9_])""")
    private val path = Regex(
        """(?<![\w./~-])((?:~|\.{1,2})?(?:/[\w.@+-]+)+|(?:[\w.@+-]+/)+[\w.@+-]+)\.(?:txt|md|markdown|csv|tsv|json|log|ya?ml|toml|ini|xml|html|png|jpe?g|gif|webp|pdf)(?!\.?[\w/-])""",
        RegexOption.IGNORE_CASE,
    )
    private val image = Regex("""\.(png|jpe?g|gif|webp)$""", RegexOption.IGNORE_CASE)

    fun findFiles(line: String): List<FileLink> = file.findAll(line).map { FileLink(it.range.first, it.range.last + 1, it.groupValues[1]) }.toList()

    fun findPaths(line: String): List<PathLink> = path.findAll(line).map { PathLink(it.range.first, it.range.last + 1, it.value) }.toList()

    /** Images show in the sheet; the rest goes to the system. */
    fun isImage(name: String): Boolean = image.containsMatchIn(name)

    /** The inbox file, if any, that column [col] (0-based) falls in, outside a web address. */
    fun fileAt(line: String, col: Int): FileLink? = findFiles(line).firstOrNull { col >= it.start && col < it.end && at(line, col) == null }

    /** The path, if any, that column [col] falls in, outside a web address and an inbox name. */
    fun pathAt(line: String, col: Int): PathLink? =
        findPaths(line).firstOrNull { col >= it.start && col < it.end && at(line, col) == null && fileAt(line, col) == null }

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
