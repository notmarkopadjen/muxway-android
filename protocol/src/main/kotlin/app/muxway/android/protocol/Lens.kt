package app.muxway.android.protocol

/**
 * The lens: recognises a pane running an AI coding agent and says whether it is working or
 * waiting for its owner. Waiting comes from the pane's notification in the listing. Working
 * comes from the last event the hub sent for the pane (`working` is what the agent's hooks, or
 * the host's watcher, report) or, for a pane the app is reading, from its content changing.
 * The listing's `activity` is the window's, shared by every pane in it, so it is not used.
 */
object Lens {
    enum class Status { WAITING, WORKING, QUIET }

    /** The AI CLIs every client names, the browser's table. */
    enum class Agent(val label: String) {
        CLAUDE("Claude"), CODEX("Codex"), GEMINI("Gemini"), ANTIGRAVITY("Antigravity"), OPENCODE("OpenCode"),
        COPILOT("Copilot"), AIDER("aider"), GOOSE("goose"), AMP("Amp"), CURSOR("Cursor"),
    }

    /** How recent a content change must be to count as working. */
    const val RECENT_SECONDS = 10L

    /** How long after this client's own input or resize a change of content is its own doing. */
    const val OWN_SECONDS = 3L

    /** Claude Code's mark, and the sparks it leads its title with before what it is on. */
    const val MARK = "✳"
    private val SPARKS = Regex("^[✳✶✻✽✢]\\s*")

    private val commands = mapOf(
        "claude" to Agent.CLAUDE, "codex" to Agent.CODEX, "gemini" to Agent.GEMINI, "agy" to Agent.ANTIGRAVITY,
        "opencode" to Agent.OPENCODE, "copilot" to Agent.COPILOT, "aider" to Agent.AIDER, "goose" to Agent.GOOSE,
        "amp" to Agent.AMP, "cursor-agent" to Agent.CURSOR,
    )

    /** The ones that run under an interpreter, by their name in the title. */
    private val inTitle = listOf(
        Agent.GEMINI to Regex("\\bgemini\\b", RegexOption.IGNORE_CASE),
        Agent.ANTIGRAVITY to Regex("\\bantigravity\\b", RegexOption.IGNORE_CASE),
        Agent.COPILOT to Regex("\\bcopilot\\b", RegexOption.IGNORE_CASE),
    )
    private val interpreters = setOf("node", "bun", "deno", "python", "python3", "uv")

    /** The agent in the pane, by its command, Claude's spark, or its name in the title under an interpreter. */
    fun agent(p: Pane): Agent? {
        if (p.command == "claude" || SPARKS.containsMatchIn(p.title)) return Agent.CLAUDE
        commands[p.command]?.let { return it }
        if (p.command in interpreters) for ((a, re) in inTitle) if (re.containsMatchIn(p.title)) return a
        return null
    }

    fun isClaude(p: Pane) = agent(p) == Agent.CLAUDE

    /** What a pane is, for a person: the program, and what it says about itself. */
    data class Description(val agent: Agent?, val name: String, val status: String) {
        val claude get() = agent == Agent.CLAUDE
    }

    /**
     * An agent is named, with the status it writes into the title (Claude's "codename") after;
     * anything else is its command, with the title when that adds something.
     */
    fun describe(p: Pane): Description {
        val title = p.title.trim()
        val agent = agent(p)
        if (agent == Agent.CLAUDE) {
            val status = title.replace(SPARKS, "").trim()
            return Description(agent, agent.label, if (status == p.command) "" else status)
        }
        if (agent != null) {
            val status = if (title == p.command || title.equals(agent.label, ignoreCase = true)) "" else title
            return Description(agent, agent.label, status)
        }
        val name = p.command.ifEmpty { p.label }
        return Description(null, name, if (title == p.command) "" else title)
    }

    /**
     * Whether a change of content seen at [at] says the program is working: the first read of a
     * pane fills the screen and a change just after this client typed or resized is its own.
     */
    fun changeCounts(firstRead: Boolean, ownAt: Long, at: Long): Boolean = !firstRead && at - ownAt >= OWN_SECONDS

    /**
     * [changed]: the screen moved in the last [RECENT_SECONDS]. An idle notice while it still
     * moves is the program's own background work, not a pane waiting for its owner.
     */
    fun status(p: Pane, lastKind: String?, changed: Boolean): Status {
        val agent = agent(p) != null
        return when {
            p.attention != null && !(p.attention.kind == "idle" && agent && changed) -> Status.WAITING
            agent && (lastKind == "working" || changed) -> Status.WORKING
            else -> Status.QUIET
        }
    }

    /** One character for tabs and headers; null when there is nothing to say. */
    fun glyph(status: Status, kind: String?): String? = when (status) {
        Status.WAITING -> when (kind) {
            "permission" -> "!"
            "question" -> "?"
            "idle" -> "z"
            "done" -> "✓"
            else -> "!"
        }
        Status.WORKING -> "⟳"
        Status.QUIET -> null
    }

    /** The word next to the glyph. */
    fun word(status: Status, kind: String?): String? = when (status) {
        Status.WAITING -> kind ?: "waiting"
        Status.WORKING -> "working"
        Status.QUIET -> null
    }

    /** The name with the status after it: `Claude · fixing the build`, `vim · notes.md`. */
    fun name(p: Pane): String = describe(p).let { if (it.status.isEmpty()) it.name else "${it.name} · ${it.status}" }

    /** A tab's name: an agent's status alone (Claude's codename), else the name. */
    fun tab(p: Pane): String = describe(p).let { if (it.agent != null) it.status.ifEmpty { it.name } else name(p) }
}
