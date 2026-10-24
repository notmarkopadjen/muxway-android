package app.muxway.android.protocol

/** [sessions]: the host's agent lets clients start sessions. */
data class Host(val name: String, val online: Boolean, val sessions: Boolean = false)

data class Attention(val kind: String, val at: Long, val text: String)

data class Pane(
    val host: String,
    val address: String,
    val session: String,
    val windowIndex: Int,
    val window: String,
    val paneIndex: Int,
    val paneId: String,
    val width: Int,
    val height: Int,
    val title: String,
    val command: String,
    val mode: String,
    val alias: String,
    val activity: Long,
    val attention: Attention?,
    /** Whether the program asked its terminal for the mouse; null when the hub does not say. */
    val mouse: Boolean? = null,
) {
    val writable get() = mode == "write"

    /** The alias when there is one, else session/window.pane. */
    val label get() = alias.ifEmpty { "$session/$windowIndex.$paneIndex" }

    /** What tabs and headers show: the title the program set (without Claude Code's mark), else the label. */
    val name get() = Lens.name(this)
}

data class PaneRead(val pane: Pane, val content: String, val etag: String)

data class Event(
    val seq: Long,
    val at: Long,
    val host: String,
    val address: String,
    val paneId: String,
    val alias: String,
    val kind: String,
    val text: String,
)

data class Events(val events: List<Event>, val next: Long)

/** One line of the hub's log, for an admin. */
data class LogLine(val seq: Long, val at: Long, val level: String, val msg: String, val attrs: Map<String, String>) {
    val warning get() = level != "INFO"
}

/**
 * Someone connected to the hub: a browser session, which [id] names for ending it, or a
 * bearer client. [who] is the token's name or the user's address.
 */
data class AdminClient(val kind: String, val id: String, val client: String, val who: String, val addr: String, val since: Long, val seen: Long) {
    val endable get() = kind == "session" && id.isNotEmpty()
}

/** The hub said no, or did not answer. [code] is the protocol's error code. */
class ApiException(val status: Int, val code: String) : Exception(messageFor(status, code)) {
    companion object {
        fun messageFor(status: Int, code: String): String = when (code) {
            "unauthorized" -> "Token refused"
            "read_only" -> "Read only"
            "forbidden" -> "Not allowed"
            "not_found" -> "Not found"
            "too_large" -> "Too long"
            "locked_out" -> "Too many failed logins from this network. Wait ten minutes"
            "rate_limited" -> "Too many requests, slow down"
            "host_offline" -> "Host offline"
            "exists" -> "That name is taken"
            "too_many" -> "Too many already: 8 panes a window, 32 sessions"
            "provider_unavailable" -> "Transcriber unavailable"
            "bad_json", "bad_input", "bad_parameter" -> "The hub refused the request"
            "no_mouse_hub" -> "This hub has no mouse support yet"
            "no_mouse_pane" -> "No mouse in this pane"
            "offline" -> "No connection"
            "bad_answer" -> "The hub's answer made no sense"
            else -> if (status > 0) "Unexpected answer $status" else "No connection"
        }
    }
}
