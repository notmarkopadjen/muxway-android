package app.muxway.android.protocol

/**
 * [sessions]: the host's agent lets clients start sessions; [close]: and close writable panes;
 * [preview]: it serves files under roots its owner named, to writers, when a pane prints their paths.
 */
data class Host(val name: String, val online: Boolean, val sessions: Boolean = false, val close: Boolean = false, val keys: Int = 0, val preview: Boolean = false, val hub: String = "")

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
    /** The cursor's cell, 1-based, where the program waits for input; 0 when the hub does not say. */
    val cursorX: Int = 0,
    val cursorY: Int = 0,
    /** The app's id for the hub that listed the pane; "" before the app set it. */
    val hub: String = "",
) {
    val writable get() = mode == "write"

    /** What the app knows the pane by: the hub and the address (see [Keys]). */
    val key get() = Keys.make(hub, address)

    /** The alias when there is one, else session/window.pane. */
    val label get() = alias.ifEmpty { "$session/$windowIndex.$paneIndex" }

    /** The name with its status: the command and what the program says of itself, or Claude and its codename. */
    val name get() = Lens.name(this)

    /** A tab's name: Claude's codename alone, else [name]. */
    val tab get() = Lens.tab(this)

    /** The tmux address, `host/session/window.pane`, second to the name. */
    val place get() = "$host/$session/$windowIndex.$paneIndex"
}

/** A resize's answer: the pane as listed, and every pane of its window when the host moved them. */
data class Resized(val pane: Pane, val panes: List<Pane> = emptyList())

/** [trimmed]: blank rows at the bottom of the screen the host left out of [content]. */
data class PaneRead(val pane: Pane, val content: String, val etag: String, val trimmed: Int = 0) {
    /** How many of [content]'s last rows are the screen: the pane's rows less the blank ones left out. */
    val screenRows get() = (pane.height - trimmed).coerceAtLeast(0)
}

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
            "unsupported" -> "Not a type the host takes"
            "inbox_full" -> "The host's inbox is full"
            "bad_json", "bad_input", "bad_parameter" -> "The hub refused the request"
            "no_mouse_hub" -> "This hub has no mouse support yet"
            "no_mouse_pane" -> "No mouse in this pane"
            "offline" -> "No connection"
            "keystore" -> "The keystore did not open the token, try again"
            "bad_answer" -> "The hub's answer made no sense"
            else -> if (status > 0) "Unexpected answer $status" else "No connection"
        }
    }
}
