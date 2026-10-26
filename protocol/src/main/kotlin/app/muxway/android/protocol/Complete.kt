package app.muxway.android.protocol

/**
 * Completion on the input line, the browser's `term/complete.ts`: the first word gets the
 * program's own, Claude Code's slash commands, the command row's first, or for a shell its
 * starters and the lines sent before; any word, two letters in, also gets the words on the
 * pane's screen, newest first, marked so a tap takes one and send sends the line as typed.
 * Nothing the hub said is ever offered.
 */
object Complete {
    /** How many are offered at once. */
    const val MAX = 8

    /** Screen words kept at most. */
    const val MAX_WORDS = 400

    /** An offer: a command the program takes, or a word from the screen. */
    data class Offer(val text: String, val word: Boolean)

    /** The word the caret is at the end of: the word, where it starts, whether it is the line's first. */
    data class At(val word: String, val start: Int, val first: Boolean)

    private val tail = Regex("""(\S+)$""")
    private val split = Regex("""[\s|│┃()\[\]{}<>"'`,;]+""")
    private val edge = Regex("""^[.:!?]+|[.:!?]+$""")
    private val punctuation = Regex("""^[\W_]+$""")

    fun wordAt(text: String, caret: Int): At? {
        if (caret < 0 || caret > text.length) return null
        val before = text.substring(0, caret)
        val m = tail.find(before) ?: return null
        val after = text.getOrNull(caret)
        if (after != null && !after.isWhitespace()) return null
        val start = caret - m.value.length
        return At(m.value, start, before.substring(0, start).isBlank())
    }

    /** The commands for a first word, then the screen's [words] for any word two letters in. */
    fun offers(p: Pane, text: String, caret: Int, recent: List<String>, words: List<String> = emptyList()): List<Offer> {
        val at = wordAt(text, caret) ?: return emptyList()
        val out = ArrayList<Offer>()
        if (at.first) {
            val pool = when {
                Lens.isClaude(p) -> Commands.CLAUDE.map { it.text.trim() } + SLASH
                Commands.isShell(p.command) -> Commands.SHELL.map { it.text } + recent
                else -> emptyList()
            }
            for (c in pool) if (c.startsWith(at.word) && out.none { it.text == c }) out.add(Offer(c, false))
        }
        if (at.word.length >= 2) {
            val low = at.word.lowercase()
            for (w in words) if (w != at.word && w.lowercase().startsWith(low) && out.none { it.text == w }) out.add(Offer(w, true))
        }
        return out.take(MAX)
    }

    /** The words on a screen worth offering: three characters or more, not punctuation alone, the last printed first, each once. */
    fun screenWords(content: String): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        val parts = content.split(split)
        for (i in parts.indices.reversed()) {
            if (out.size >= MAX_WORDS) break
            val w = parts[i].replace(edge, "")
            if (w.length < 3 || punctuation.matches(w) || !seen.add(w)) continue
            out.add(w)
        }
        return out
    }

    /** Claude Code's slash commands, beyond the ones the command row already carries. */
    val SLASH: List<String> = listOf(
        "/add-dir", "/agents", "/bug", "/clear", "/compact", "/config", "/context", "/cost",
        "/doctor", "/exit", "/export", "/help", "/hooks", "/ide", "/init", "/login", "/logout",
        "/mcp", "/memory", "/model", "/permissions", "/pr-comments", "/release-notes", "/rename",
        "/resume", "/review", "/sandbox", "/security-review", "/stats", "/status",
        "/terminal-setup", "/usage", "/vim",
    )

    private val first = Regex("^\\s*(\\S+)$")
    private val blank = Regex("^[^\\S\\n]")

    /** The first word of [text] when the caret is at its end and only blanks come before it. */
    fun firstWord(text: String, caret: Int): String? {
        if (caret < 0 || caret > text.length) return null
        val word = first.matchEntire(text.substring(0, caret))?.groupValues?.get(1) ?: return null
        val after = text.getOrNull(caret)
        return if (after == null || after.isWhitespace()) word else null
    }

    /** What the pane's program offers for the word being typed; the commands only, as before. */
    fun forPane(p: Pane, text: String, caret: Int, recent: List<String>): List<String> =
        offers(p, text, caret, recent).filter { !it.word }.map { it.text }

    /** [text] with the word at the caret replaced by [pick] and a space after it, and where the caret goes. */
    fun take(text: String, caret: Int, pick: String): Pair<String, Int> {
        val c = caret.coerceIn(0, text.length)
        val start = wordAt(text, c)?.start ?: c
        val rest = text.substring(c).replaceFirst(blank, "")
        return text.substring(0, start) + pick + " " + rest to start + pick.length + 1
    }
}
