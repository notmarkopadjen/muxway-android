package app.muxway.android.protocol

/**
 * The ghost word: while a shell pane is typed into, the rest of what the client thinks is being
 * typed, drawn dim after the cursor and taken on purpose. The client does not know where the
 * shell's prompt ends, so it keeps a shadow of the line from the keys it sent and shows a ghost
 * only where the screen's cursor row confirms it; without a shadow it takes the longest
 * word-boundary suffix of the row that some known line starts with. Nothing is sent until the
 * ghost is taken. The browser's `term/suggest.ts`.
 */
object Suggest {
    /** One body sent to the pane: text, or a named key with a count. */
    sealed class Body {
        data class Text(val text: String) : Body()
        data class Key(val name: String, val count: Int = 1) : Body()
    }

    /**
     * The shadow after one body sent: text appends, Backspace takes a character, Enter and the
     * keys that clear the line start it afresh, and a key the client cannot follow (history,
     * completion, a move) makes it unknown (null) until the next line starts.
     */
    fun nextShadow(shadow: String?, b: Body): String? = when (b) {
        is Body.Text -> if ('\n' in b.text || shadow == null) null else shadow + b.text
        is Body.Key -> when (b.name) {
            "Enter", "C-c", "C-u" -> ""
            "C-l" -> shadow
            "Space" -> shadow?.let { it + " ".repeat(b.count) }
            "BSpace" -> shadow?.let { it.take((it.length - b.count).coerceAtLeast(0)) }
            else -> null
        }
    }

    // A row that starts with a prompt sign after at most a short prompt, with something after it.
    private val prompt = Regex("""^.{0,80}?[$#%>❯›]\s+(\S.*?)\s*$""")
    private val choice = Regex("""^\d{1,2}[.)]\s""")

    /**
     * The commands the screen shows were typed at a prompt, newest first, each once, the row
     * [skip] (the cursor's) left out; a numbered choice is no command, and a line that looks like
     * it carries a secret is never offered.
     */
    fun screenCommands(rows: List<String>, skip: Int = -1): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (i in rows.indices.reversed()) {
            if (i == skip) continue
            val m = prompt.find(rows[i]) ?: continue
            val c = m.groupValues[1]
            if (choice.containsMatchIn(c) || Commands.looksSecret(c) || !seen.add(c)) continue
            out.add(c)
        }
        return out
    }

    // Commands that take a file: after them one typed character is enough for a name; elsewhere
    // a name needs two, so a lone letter stays quiet.
    private val fileCommands = "cd ls cat less more head tail vim vi nvim nano bat open code rm cp mv diff wc".split(' ').toSet()

    /**
     * A line of the pool that goes on from [typed], or a name of the directory that goes on from
     * its last word, after a space; a directory gets its slash. Null when nothing does.
     */
    fun completion(typed: String, pool: List<String>, names: Names.Index? = null): String? {
        if (typed.isEmpty()) return null
        for (line in pool) if (line.startsWith(typed) && line.length > typed.length) return line.substring(typed.length)
        val at = typed.lastIndexOf(' ')
        if (names != null && at > 0) {
            val word = typed.substring(at + 1)
            val least = if (typed.trimStart().split(Regex("""\s+"""))[0] in fileCommands) 1 else 2
            if (word.length >= least) {
                for ((name, kind) in names.kinds) {
                    if (name.startsWith(word) && name != word && ' ' !in name) return name.substring(word.length) + if (kind == "dir") "/" else ""
                }
            }
        }
        return null
    }

    /**
     * The ghost for a cursor row: the text to draw after the cursor, or null. [row] is the row's
     * text, [cursorX] the cursor's cell, 1-based.
     */
    fun suggest(row: String, cursorX: Int, shadow: String?, pool: List<String>, names: Names.Index? = null): String? {
        if (cursorX < 1) return null
        // The row comes without its trailing blanks: the cursor may sit past its end.
        val before = row.take(cursorX - 1).padEnd(cursorX - 1, ' ')
        if (row.drop(cursorX - 1).isNotBlank()) return null
        if (shadow != null) {
            if (shadow.isEmpty() || !before.endsWith(shadow)) return null
            return completion(shadow, pool, names)
        }
        // Unknown: the longest suffix that starts a word, two characters at least.
        for (i in 0..before.length - 2) {
            if (i > 0 && before[i - 1] != ' ') continue
            if (before[i] == ' ') continue
            completion(before.substring(i), pool, names)?.let { return it }
        }
        return null
    }

    private val word = Regex("""^\s*\S*\s?""")

    /** What a word-accept takes: the ghost up to and including the next blank. */
    fun firstWord(ghost: String): String = word.find(ghost)?.value ?: ghost
}
