package app.muxway.android.protocol

/** One changed path of the repository, as `GET …/git` lists it; [from] only on a rename. */
data class Change(val path: String, val kind: String, val staged: Boolean = false, val from: String = "") {
    /** The name in the tree: the last part of the path. */
    val name get() = path.substringAfterLast('/')
}

/** Git's view of the pane's repository: its root, branch, distance from the upstream and changes. */
data class ChangeSet(val root: String, val branch: String, val ahead: Int, val behind: Int, val truncated: Boolean, val entries: List<Change>) {
    val repo get() = root.substringAfterLast('/')
}

/** One change's diff against the last commit; [binary] when git has no text, [cut] past a MiB. */
data class Diff(val diff: String, val binary: Boolean, val cut: Boolean)

/**
 * The changes sheet's tree: the changed paths laid out by directory, directories first, each
 * sorted by name; and the marks and counts its bar and rows show. The browser's `changes.ts`.
 */
object Changes {
    /** A directory of the tree; [count] is how many changes lie below, for a folded row. */
    class Dir(val name: String, val path: String) {
        val dirs = ArrayList<Dir>()
        val files = ArrayList<Change>()
        var count = 0
    }

    val KINDS = setOf("added", "modified", "deleted", "renamed", "untracked", "conflict")

    fun tree(entries: List<Change>): Dir {
        val root = Dir("", "")
        for (e in entries) {
            val parts = e.path.split('/')
            var dir = root
            dir.count++
            for (i in 0 until parts.size - 1) {
                val name = parts[i]
                val next = dir.dirs.firstOrNull { it.name == name } ?: Dir(name, parts.take(i + 1).joinToString("/")).also { dir.dirs.add(it) }
                next.count++
                dir = next
            }
            dir.files.add(e)
        }
        sort(root)
        return root
    }

    private fun sort(dir: Dir) {
        dir.dirs.sortBy { it.name }
        dir.files.sortBy { it.path }
        for (d in dir.dirs) sort(d)
    }

    fun mark(kind: String): String = when (kind) {
        "added", "untracked" -> "+"
        "modified" -> "~"
        "renamed" -> "→"
        "deleted" -> "−"
        "conflict" -> "!"
        else -> "?"
    }

    data class Counts(val added: Int, val changed: Int, val deleted: Int, val conflict: Int) {
        val clean get() = added + changed + deleted + conflict == 0
    }

    fun counts(entries: List<Change>): Counts {
        var added = 0; var changed = 0; var deleted = 0; var conflict = 0
        for (e in entries) when (e.kind) {
            "added", "untracked" -> added++
            "deleted" -> deleted++
            "conflict" -> conflict++
            else -> changed++
        }
        return Counts(added, changed, deleted, conflict)
    }

    /** The bar's summary: the counts that are not zero, in their marks; `✓` when clean. */
    fun summary(c: Counts): String {
        val parts = ArrayList<String>()
        if (c.added > 0) parts.add("+${c.added}")
        if (c.changed > 0) parts.add("~${c.changed}")
        if (c.deleted > 0) parts.add("−${c.deleted}")
        if (c.conflict > 0) parts.add("!${c.conflict}")
        return if (parts.isEmpty()) "✓" else parts.joinToString(" ")
    }

    /** What the file sheet opens for a change: its diff, the file, or both, the first one first. */
    fun views(kind: String): List<String> = when (kind) {
        "untracked" -> listOf("file")
        "deleted" -> listOf("diff")
        else -> listOf("diff", "file")
    }

    /** A diff row's kind: `+` added, `-` removed, `@` a hunk header, `h` a file header, ` ` context. */
    fun rowKind(line: String): Char = when {
        line.startsWith("+++") || line.startsWith("---") -> 'h'
        line.startsWith("+") -> '+'
        line.startsWith("-") -> '-'
        line.startsWith("@@") -> '@'
        HEADER.containsMatchIn(line) -> 'h'
        else -> ' '
    }

    private val HEADER = Regex("^(diff |index |similarity |rename |new file|deleted file|old mode|new mode|Binary files)")

    /** Whether a path may be asked for: as the status listed it, at most 1024 bytes, no control characters. */
    fun askable(path: String): Boolean = path.isNotEmpty() && path.toByteArray(Charsets.UTF_8).size <= 1024 && path.none { it.code < 0x20 || it.code == 0x7f }
}
