package app.muxway.android.protocol

/**
 * The command row under the key row: what the program in the focused pane is driven with, one
 * button each. Claude Code takes slash commands; a shell takes the commands a person starts
 * things with, then the lines sent to a shell lately. A command goes at once, followed by Enter,
 * or fills the text line and waits for the rest.
 */
object Commands {
    enum class Kind { CLAUDE, SHELL }

    /** A command's tone tints its button: danger throws work away, start begins something, info only tells, act is the rest. */
    enum class Tone { DANGER, START, INFO, ACT }

    data class Command(val label: String, val text: String, val send: Boolean, val tone: Tone = Tone.ACT)

    private fun at(text: String, tone: Tone = Tone.ACT) = Command(text, text, true, tone)
    private fun fill(text: String, tone: Tone = Tone.ACT) = Command(text, "$text ", false, tone)

    val CLAUDE: List<Command> = listOf(
        at("/clear", Tone.DANGER), at("/exit", Tone.DANGER), at("/compact"), fill("/rename"), at("/model", Tone.INFO), at("/resume", Tone.START),
        at("/usage", Tone.INFO), at("/context", Tone.INFO), at("/help", Tone.INFO), at("/status", Tone.INFO), at("/cost", Tone.INFO),
        at("/todos", Tone.INFO), at("/plan", Tone.START), at("/review"), at("/memory", Tone.INFO), at("/init", Tone.START), at("/config", Tone.INFO),
        at("/permissions", Tone.INFO), at("/mcp", Tone.INFO), at("/agents", Tone.INFO), at("/hooks", Tone.INFO), at("/doctor", Tone.INFO),
        at("/fast"), at("/export"),
    )

    val SHELL: List<Command> = listOf(
        at("exit", Tone.DANGER), at("htop", Tone.INFO), at("claude", Tone.START), at("claude -c", Tone.START), at("claude --resume", Tone.START),
        at("codex", Tone.START), at("gemini", Tone.START), at("git status", Tone.INFO), at("git diff", Tone.INFO), at("git log --oneline -20", Tone.INFO),
        at("git pull"), at("git push"), at("git stash"), at("git stash pop"), at("make"), at("make test"), at("npm test"), at("npm run build"),
        at("docker ps", Tone.INFO), at("tmux ls", Tone.INFO), at("ls", Tone.INFO), at("ls -la", Tone.INFO), at("pwd", Tone.INFO), at("cd .."),
        at("clear"), at("history", Tone.INFO), at("df -h", Tone.INFO), at("free -h", Tone.INFO),
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
