package app.muxway.android.protocol

/**
 * Completion of the first word on the input line: Claude Code's slash commands, the command
 * row's first, or for a shell its starters and the lines sent before. The same rules as the
 * browser's `term/complete.ts`. Nothing a pane printed and nothing the hub said is ever
 * offered, so a pane cannot put a command under its owner's thumb.
 */
object Complete {
    /** How many are offered at once. */
    const val MAX = 8

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

    /** What the pane's program offers for the word being typed; empty when it offers nothing. */
    fun forPane(p: Pane, text: String, caret: Int, recent: List<String>): List<String> {
        val word = firstWord(text, caret) ?: return emptyList()
        val pool = when {
            Lens.isClaude(p) -> Commands.CLAUDE.map { it.text.trim() } + SLASH
            Commands.isShell(p.command) -> Commands.SHELL.map { it.text } + recent
            else -> return emptyList()
        }
        val out = ArrayList<String>()
        for (c in pool) if (c.startsWith(word) && c !in out && out.size < MAX) out.add(c)
        return out
    }

    /** [text] with its first word replaced by [pick] and a space after it, and where the caret goes. */
    fun take(text: String, caret: Int, pick: String): Pair<String, Int> {
        val before = text.substring(0, caret.coerceIn(0, text.length))
        val lead = before.length - before.trimStart().length
        val rest = text.substring(caret.coerceIn(0, text.length)).replaceFirst(blank, "")
        return text.substring(0, lead) + pick + " " + rest to lead + pick.length + 1
    }
}
