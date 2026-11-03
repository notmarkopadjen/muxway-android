package app.muxway.android.protocol

/** One entry of a directory as the host listed it: [kind] is `dir`, `file` or `other`. */
data class Entry(val name: String, val kind: String)

/** A directory the host listed: its resolved [path], its [entries] in order, whether some were left out. */
data class Listing(val path: String, val entries: List<Entry>, val truncated: Boolean = false)

/** How a tapped file opens: the file sheet, `cat`, or the person's own command with `{}` for the quoted name. */
enum class OpenMode { PREVIEW, CAT, COMMAND }

/**
 * Names of the pane's directory, as the host listed them, found on the screen so a tap can act
 * on them: a directory is entered, a file opened the way the person set. Only a name the host
 * listed is ever a link, so what a program prints cannot invent one; a name whose entry is
 * neither a directory nor a file stays text. The browser's `term/names.ts`.
 */
object Names {
    const val MAX_ENTRIES = 2000
    const val MAX_NAME = 255
    const val MAX_PATH = 1024
    val KINDS = setOf("dir", "file", "other")

    /** A name on a row: where it is, what it is, and the entry's kind. */
    data class Mark(val start: Int, val end: Int, val name: String, val kind: String)

    /** The index of a listing: the names that are directories or files, and among them [odd], the ones a split at the boundaries would cut, longest first. */
    class Index(val kinds: Map<String, String>, val odd: List<String>) {
        val isEmpty get() = kinds.isEmpty()

        companion object {
            val EMPTY = Index(emptyMap(), emptyList())
        }
    }

    // Where a name may begin and end on a row: the row's edge, blank space, a bar, a quote or
    // a bracket, as a listing, a tree, a git status or prose set them off; after it also the
    // mark `ls -F` adds and the punctuation of a sentence.
    private val before = setOf(' ', '\t', '│', '┃', '|', '\'', '"', '`', '(', '[', '{', '<')
    private val after = setOf(' ', '\t', '│', '┃', '|', '\'', '"', '`', ')', ']', '}', '>')
    private val punct = setOf(',', ';', ':', '.', '!', '?')
    private val classifier = setOf('/', '*', '@', '=')

    fun index(entries: List<Entry>): Index {
        val kinds = LinkedHashMap<String, String>()
        val odd = ArrayList<String>()
        for (e in entries) {
            if (e.kind != "dir" && e.kind != "file") continue
            if (e.name.isEmpty() || e.name == "." || e.name == ".." || kinds.containsKey(e.name)) continue
            kinds[e.name] = e.kind
            if (e.name.any { it in before || it in after }) odd.add(e.name)
        }
        odd.sortByDescending { it.length }
        return Index(kinds, odd)
    }

    private fun endsWell(line: String, end: Int): Boolean {
        if (end >= line.length) return true
        val c = line[end]
        if (c in after || c in punct) return true
        if (c in classifier) {
            val d = line.getOrNull(end + 1)
            return d == null || d in after || d in punct
        }
        return false
    }

    private fun startsWell(line: String, start: Int) = start == 0 || line[start - 1] in before

    /** The names found on one row, in order, none overlapping another. */
    fun find(line: String, index: Index): List<Mark> {
        if (index.isEmpty) return emptyList()
        val out = ArrayList<Mark>()
        fun free(start: Int, end: Int) = out.none { it.start < end && start < it.end }
        // The odd names first, by their own scan.
        for (name in index.odd) {
            val kind = index.kinds.getValue(name)
            var at = line.indexOf(name)
            while (at >= 0) {
                val end = at + name.length
                if (startsWell(line, at) && endsWell(line, end) && free(at, end)) out.add(Mark(at, end, name, kind))
                at = line.indexOf(name, at + 1)
            }
        }
        // Then every run between boundaries, with a classifier or the sentence's punctuation
        // after it taken off.
        var at = 0
        while (at < line.length) {
            if (line[at] in before || line[at] in after) { at++; continue }
            var end = at
            while (end < line.length && line[end] !in before && line[end] !in after) end++
            var stop = end
            while (stop > at && line[stop - 1] in punct) stop--
            if (stop > at && line[stop - 1] in classifier && stop - 1 > at && index.kinds.containsKey(line.substring(at, stop - 1))) stop--
            val name = line.substring(at, stop)
            val kind = index.kinds[name]
            if (kind != null && free(at, stop)) out.add(Mark(at, stop, name, kind))
            at = end
        }
        out.sortBy { it.start }
        return out
    }

    /** The name, if any, that column [col] (0-based) of [line] falls in. */
    fun at(line: String, col: Int, index: Index): Mark? = find(line, index).firstOrNull { col >= it.start && col < it.end }

    /** A name as a shell takes it whole, whatever is in it. */
    fun shellQuote(name: String): String = "'" + name.replace("'", "'\\''") + "'"

    /** The line a tap types, or null when the sheet opens the file instead. */
    fun lineFor(kind: String, name: String, mode: OpenMode, command: String): String? {
        val q = shellQuote(name)
        if (kind == "dir") return "cd -- $q"
        return when (mode) {
            OpenMode.CAT -> "cat -- $q"
            OpenMode.COMMAND -> {
                val c = command.trim()
                if (c.isEmpty()) null else if ("{}" in c) c.replace("{}", q) else "$c $q"
            }
            OpenMode.PREVIEW -> null
        }
    }

    /** What a tap does, in words. */
    fun titleFor(kind: String, name: String, mode: OpenMode, command: String): String {
        if (kind == "dir") return "cd $name"
        if (mode == OpenMode.CAT) return "cat $name"
        if (mode == OpenMode.COMMAND && command.isNotBlank()) return lineFor(kind, name, mode, command)?.replace(shellQuote(name), name) ?: name
        return "preview $name"
    }
}
