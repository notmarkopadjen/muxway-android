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

    /** Claude Code sets the pane title to a star and the session's name. */
    const val MARK = "✳"

    fun isClaude(p: Pane) = p.command == "claude" || p.title.startsWith(MARK)

    fun status(p: Pane, lastKind: String?, changed: Boolean): Status = when {
        p.attention != null -> Status.WAITING
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

    /** The title without Claude Code's own mark, so the glyph can take its place. */
    fun name(p: Pane): String = p.title.removePrefix(MARK).trim().ifEmpty { p.label }
}
