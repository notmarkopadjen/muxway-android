package app.muxway.android.protocol

/**
 * The command row under the key row: what the program in the focused pane is driven with, one
 * button each. Claude Code takes slash commands; a shell takes the commands a person starts
 * things with, then the lines sent to a shell lately. A command goes at once, followed by Enter,
 * or fills the text line and waits for the rest.
 */
object Commands {
    enum class Kind { CLAUDE, SHELL }

    data class Command(val label: String, val text: String, val send: Boolean)

    private fun at(text: String) = Command(text, text, true)
    private fun fill(text: String) = Command(text, "$text ", false)

    val CLAUDE: List<Command> = listOf(
        at("/clear"), at("/compact"), fill("/rename"), at("/model"), at("/resume"), at("/usage"), at("/context"), at("/help"),
    )

    val SHELL: List<Command> = listOf(
        at("exit"), at("htop"), at("claude"), at("claude -c"), at("codex"), at("gemini"), at("git status"), at("ls"), at("clear"),
    )

    private val shells = setOf("bash", "zsh", "fish", "sh", "dash", "ash", "ksh", "tcsh", "csh")

    /** How many recent lines are kept, and how long a line may be to count as a command. */
    const val RECENT_MAX = 8
    const val LINE_MAX = 200
    private const val LABEL = 24

    fun isShell(command: String) = command in shells

    /** The row for [p], or null when its program has none. [recent] follows the fixed shell commands. */
    fun forPane(p: Pane, recent: List<String>): Pair<Kind, List<Command>>? = when {
        Lens.isClaude(p) -> Kind.CLAUDE to CLAUDE
        isShell(p.command) -> Kind.SHELL to SHELL + recent
            .filter { r -> SHELL.none { it.text == r } }
            .map { Command(if (it.length > LABEL) it.take(LABEL - 1) + "…" else it, it, true) }
        else -> null
    }

    private val secretWords = Regex("""(?i)authorization|token|passw(or)?d|secret|mxt_|api[_-]?key""")
    private val assignment = Regex("""(^|\s)(export\s+)?[A-Z][A-Z0-9_]*=""")

    /** A line that may carry a credential: a known word, a token prefix, or an upper-case NAME=value. */
    fun looksSecret(line: String) = secretWords.containsMatchIn(line) || assignment.containsMatchIn(line)

    /** [recent] with [line] first, once; unchanged when the line is empty, too long or looks like a secret. */
    fun remembered(recent: List<String>, line: String): List<String> {
        val t = line.trim()
        if (t.isEmpty() || t.length > LINE_MAX || looksSecret(t)) return recent
        return (listOf(t) + recent.filter { it != t }).take(RECENT_MAX)
    }
}
