package app.muxway.android.protocol

/**
 * The Claude Code lens: recognises a pane running Claude Code and says whether it is working
 * or waiting for its owner, from the listing alone. Waiting comes from the pane's notification;
 * working from recent activity in a recognised pane. Anything else is quiet.
 */
object Lens {
    enum class Status { WAITING, WORKING, QUIET }

    /** How recent the pane's last activity must be to count as working. */
    const val RECENT_SECONDS = 30L

    /** Claude Code sets the pane title to a star and the session's name. */
    const val MARK = "✳"

    fun isClaude(p: Pane) = p.command == "claude" || p.title.startsWith(MARK)

    fun status(p: Pane, now: Long): Status = when {
        p.attention != null -> Status.WAITING
        isClaude(p) && p.activity > 0 && now - p.activity in 0..RECENT_SECONDS -> Status.WORKING
        else -> Status.QUIET
    }

    /** One character for tabs and headers; null when there is nothing to say. */
    fun glyph(p: Pane, now: Long): String? = when (status(p, now)) {
        Status.WAITING -> when (p.attention?.kind) {
            "permission" -> "!"
            "question" -> "?"
            "idle" -> "z"
            "done" -> "✓"
            else -> "!"
        }
        Status.WORKING -> "⟳"
        Status.QUIET -> null
    }

    /** The title without Claude Code's own mark, so the glyph can take its place. */
    fun name(p: Pane): String = p.title.removePrefix(MARK).trim().ifEmpty { p.label }
}
