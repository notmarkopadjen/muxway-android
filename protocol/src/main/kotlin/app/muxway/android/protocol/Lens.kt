package app.muxway.android.protocol

/**
 * The Claude Code lens: recognises a pane running Claude Code and says whether it is working
 * or waiting for its owner. Waiting comes from the pane's notification in the listing. Working
 * comes from the last event the hub sent for the pane (`working` is what Claude's hooks report
 * when a prompt is submitted) or, for a pane the app is reading, from its content changing.
 * The listing's `activity` is the window's, shared by every pane in it, so it is not used.
 */
object Lens {
    enum class Status { WAITING, WORKING, QUIET }

    /** How recent a content change must be to count as working. */
    const val RECENT_SECONDS = 10L

    /** How long after this client's own input or resize a change of content is its own doing. */
    const val OWN_SECONDS = 3L

    /** Claude Code's mark, and the sparks it leads its title with before what it is on. */
    const val MARK = "✳"
    private val SPARKS = Regex("^[✳✶✻✽✢]\\s*")

    fun isClaude(p: Pane) = p.command == "claude" || SPARKS.containsMatchIn(p.title)

    /** What a pane is, for a person: the program, and what it says about itself. */
    data class Description(val claude: Boolean, val name: String, val status: String)

    /**
     * Claude Code is named Claude, with the status it writes into the title (its "codename")
     * after; anything else is its command, with the title when that adds something.
     */
    fun describe(p: Pane): Description {
        val title = p.title.trim()
        if (isClaude(p)) {
            val status = title.replace(SPARKS, "").trim()
            return Description(true, "Claude", if (status == p.command) "" else status)
        }
        val name = p.command.ifEmpty { p.label }
        return Description(false, name, if (title == p.command) "" else title)
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
    fun status(p: Pane, lastKind: String?, changed: Boolean): Status = when {
        p.attention != null && !(p.attention.kind == "idle" && isClaude(p) && changed) -> Status.WAITING
        isClaude(p) && (lastKind == "working" || changed) -> Status.WORKING
        else -> Status.QUIET
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

    /** A tab's name: the codename alone for Claude Code, else the name. */
    fun tab(p: Pane): String = describe(p).let { if (it.claude) it.status.ifEmpty { it.name } else name(p) }
}
