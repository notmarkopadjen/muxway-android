package app.muxway.android.protocol

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The hub's JSON, read defensively: a missing field is a default, a wrong type is an error. */
object Json {
    fun hosts(body: String): List<Host> = parse(body) { o ->
        list(o.optJSONArray("hosts")) { Host(it.optString("name"), it.optBoolean("online"), it.optBoolean("sessions"), it.optBoolean("close"), it.optInt("keys"), it.optBoolean("preview")) }
    }

    fun panes(body: String): List<Pane> = parse(body) { o -> list(o.optJSONArray("panes")) { pane(it) } }

    fun read(body: String, etag: String): PaneRead = parse(body) { o ->
        PaneRead(pane(o.getJSONObject("pane")), Ansi.keep(o.optString("content")), etag, o.optInt("trimmed").coerceAtLeast(0))
    }

    fun events(body: String): Events = parse(body) { o ->
        Events(list(o.optJSONArray("events")) {
            Event(
                it.optLong("seq"), it.optLong("at"), it.optString("host"), it.optString("address"),
                it.optString("pane_id"), it.optString("alias"), it.optString("kind"), Input.printable(it.optString("text")),
            )
        }, o.optLong("next"))
    }

    /** Whether `/v1/me` names an admin. */
    fun admin(body: String): Boolean = parse(body) { o -> o.optBoolean("admin") }

    fun log(body: String): List<LogLine> = parse(body) { o ->
        list(o.optJSONArray("records")) {
            val attrs = LinkedHashMap<String, String>()
            it.optJSONObject("attrs")?.let { a -> for (k in a.keys()) attrs[Input.printable(k)] = Input.printable(a.optString(k)) }
            LogLine(it.optLong("seq"), it.optLong("at"), it.optString("level"), Input.printable(it.optString("msg")), attrs)
        }
    }

    /** The overview's clients, each with its token's or user's name. */
    fun clients(body: String): List<AdminClient> = parse(body) { o ->
        val names = HashMap<String, String>()
        list(o.optJSONArray("tokens")) { names[it.optString("id")] = it.optString("name") }
        list(o.optJSONArray("users")) { names[it.optString("id")] = it.optString("email") }
        list(o.optJSONArray("clients")) {
            val principal = it.optString("principal")
            AdminClient(
                it.optString("kind"), it.optString("id"), Input.printable(it.optString("client")),
                Input.printable(names[principal] ?: principal), it.optString("addr"), it.optLong("since"), it.optLong("seen"),
            )
        }
    }

    /** Where a file went: the path, cleaned as everything shown is, and the bytes the hub took. */
    fun sent(body: String): Sent = parse(body) { o ->
        val path = Input.printable(o.getString("path"))
        if (path.isEmpty() || path.length > 1024) throw JSONException("path")
        Sent(path, o.optLong("bytes"))
    }

    /** The transcriber's text, cleaned as typed text is. */
    fun transcript(body: String): String = parse(body) { o -> Input.typed(o.optString("text")) }

    fun error(body: String): String = try {
        JSONObject(body).optString("error")
    } catch (e: JSONException) {
        ""
    }

    fun text(s: String): String = JSONObject().put("text", s).toString()

    fun key(name: String): String = JSONObject().put("key", name).toString()

    /** The count only above one, so a single notch is the body an older hub knows. */
    fun mouse(button: String, x: Int, y: Int, count: Int = 1): String =
        JSONObject().put("mouse", JSONObject().put("button", button).put("x", x).put("y", y)
            .apply { if (count > 1) put("count", count) }).toString()

    /**
     * A resize: the pane alone, or its whole window, with the pane zoomed over its siblings or
     * laid out as [layout] says.
     */
    fun size(cols: Int, rows: Int, window: Boolean = false, zoom: Boolean = false, layout: String? = null): String =
        JSONObject().put("cols", cols).put("rows", rows)
            .apply { if (window) { put("scope", "window"); if (zoom) put("zoom", true); if (layout != null) put("layout", layout) } }
            .toString()

    fun fitAuto(): String = JSONObject().put("fit", "auto").toString()

    fun direction(d: String): String = JSONObject().put("direction", d).toString()

    fun name(n: String): String = JSONObject().put("name", n).toString()

    /** The pane an answer carries, after a resize, a split or a new session, and the window's panes when it lists them. */
    fun resized(body: String): Resized = parse(body) { o -> Resized(pane(o.getJSONObject("pane")), list(o.optJSONArray("panes")) { pane(it) }) }

    private fun pane(o: JSONObject): Pane {
        val a = o.optJSONObject("attention")
        return Pane(
            host = o.optString("host"),
            address = o.optString("address"),
            session = o.optString("session"),
            windowIndex = o.optInt("window_index"),
            window = o.optString("window"),
            paneIndex = o.optInt("pane_index"),
            paneId = o.optString("pane_id"),
            width = o.optInt("width"),
            height = o.optInt("height"),
            title = Input.printable(o.optString("title")),
            command = Input.printable(o.optString("command")),
            mode = o.optString("mode"),
            alias = o.optString("alias"),
            activity = o.optLong("activity"),
            attention = a?.let { Attention(it.optString("kind"), it.optLong("at"), Input.printable(it.optString("text"))) },
            mouse = if (o.has("mouse")) o.optBoolean("mouse") else null,
            cursorX = o.optInt("cursor_x").coerceAtLeast(0),
            cursorY = o.optInt("cursor_y").coerceAtLeast(0),
        )
    }

    private fun <T> list(arr: JSONArray?, item: (JSONObject) -> T): List<T> {
        if (arr == null) return emptyList()
        val out = ArrayList<T>(arr.length())
        for (i in 0 until arr.length()) out.add(item(arr.getJSONObject(i)))
        return out
    }

    private fun <T> parse(body: String, read: (JSONObject) -> T): T = try {
        read(JSONObject(body))
    } catch (e: JSONException) {
        throw ApiException(200, "bad_answer")
    }
}
