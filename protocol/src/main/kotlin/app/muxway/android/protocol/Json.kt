package app.muxway.android.protocol

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The hub's JSON, read defensively: a missing field is a default, a wrong type is an error. */
object Json {
    fun hosts(body: String): List<Host> = parse(body) { o ->
        list(o.optJSONArray("hosts")) { Host(it.optString("name"), it.optBoolean("online"), it.optBoolean("sessions"), it.optBoolean("close"), it.optInt("keys"), it.optBoolean("preview"), ask = it.optBoolean("ask"), dir = it.optBoolean("dir"), rename = it.optBoolean("rename"), later = it.optBoolean("later"), mail = it.optBoolean("mail")) }
    }

    fun panes(body: String): List<Pane> = parse(body) { o -> list(o.optJSONArray("panes")) { pane(it) } }

    fun read(body: String, etag: String): PaneRead = parse(body) { o ->
        PaneRead(pane(o.getJSONObject("pane")), Ansi.keep(o.optString("content")), etag, o.optInt("trimmed").coerceAtLeast(0))
    }

    fun events(body: String): Events = parse(body) { o ->
        Events(list(o.optJSONArray("events")) {
            Event(
                it.optLong("seq"), it.optLong("at"), it.optString("host"), it.optString("address"),
                it.optString("pane_id"), it.optString("alias"), it.optString("kind"), Input.printable(it.optString("text")), choices(it),
            )
        }, o.optLong("next"))
    }

    /** The inbox: the panes that wait, as the hub orders them, oldest first, the parked ones after; a parked entry may carry no attention. */
    fun inbox(body: String): List<Pane> = parse(body) { o -> list(o.optJSONArray("inbox")) { pane(it) } }

    /** A question's choices, only when they fit what the hub itself allows. */
    private fun choices(o: JSONObject): List<String> {
        val arr = o.optJSONArray("choices") ?: return emptyList()
        if (arr.length() > Input.MAX_CHOICES) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return Input.choices(out)
    }

    /**
     * The mail a pane holds, oldest first, as the hub lists it: texts keep their lines, tabs made spaces,
     * and lose every other control character, an origin the protocol does not spell is dropped.
     */
    fun mailbox(body: String): List<Letter> = parse(body) { o ->
        list(o.optJSONArray("mail")) {
            Letter(
                it.optLong("id").coerceAtLeast(0), it.optLong("at"), actor(it), end(it.optJSONObject("from")),
                mailText(it.optString("text"), MailRows.MAX_TEXT), it.optLong("read_at").coerceAtLeast(0), it.optLong("reply_to").coerceAtLeast(0), it.optBoolean("replied"),
            )
        }.take(MailRows.MAX_LETTERS)
    }

    /** The talk feed, oldest first; an entry whose destination the protocol does not spell is left out. */
    fun talk(body: String): List<TalkEntry> = parse(body) { o ->
        list(o.optJSONArray("talk")) {
            val to = end(it.optJSONObject("to"))
            if (to == null) null
            else TalkEntry(
                it.optLong("seq"), it.optLong("at"), actor(it), end(it.optJSONObject("from")), to,
                Input.printable(it.optString("kind")).take(16), it.optInt("bytes").coerceAtLeast(0), mailText(it.optString("text"), MailRows.MAX_EXCERPT),
                it.optLong("id").coerceAtLeast(0), it.optLong("reply_to").coerceAtLeast(0),
            )
        }.filterNotNull().take(MailRows.MAX_ENTRIES)
    }

    /** The share the hub made, with its password, this once. */
    fun share(body: String): Share = parse(body) { o -> shareOf(o.getJSONObject("share")) ?: throw JSONException("share") }

    /** The token's live shares, soonest to end first; one with an id the protocol does not spell is left out. */
    fun shares(body: String): List<Share> = parse(body) { o -> list(o.optJSONArray("shares")) { shareOf(it) }.filterNotNull().take(200) }

    /** A share as the hub lists it: an id in the protocol's alphabet, a link only when it is a web address. */
    private fun shareOf(o: JSONObject): Share? {
        val id = o.optString("id")
        if (!Shares.ID.matches(id)) return null
        val url = Input.printable(o.optString("url")).take(Shares.MAX_URL).let { if (it.startsWith("https://") || it.startsWith("http://")) it else "" }
        return Share(
            id, url, Input.printable(o.optString("password")).take(Shares.MAX_PASSWORD), o.optLong("expires").coerceAtLeast(0),
            o.optString("host").take(32), o.optString("pane_id").take(16), o.optString("address").take(200), o.optInt("watching").coerceAtLeast(0),
        )
    }

    fun shareBody(ttl: Int, password: String): String = JSONObject().put("ttl", ttl).put("password", password).toString()

    /** The id of a mail the hub took. */
    fun mailed(body: String): Long = parse(body) { o -> o.getJSONObject("mail").optLong("id").coerceAtLeast(0) }

    private fun actor(o: JSONObject): String = Input.printable(o.optJSONObject("actor")?.optString("name") ?: "").take(64)

    /** A pane at one end of a write: only an address the protocol spells, else nothing. */
    private fun end(o: JSONObject?): End? {
        if (o == null) return null
        val address = o.optString("address")
        if (!Address.valid(address)) return null
        return End(o.optString("host").take(32), address, o.optString("pane_id").take(16), o.optString("alias").take(32))
    }

    /** A mail's text as it is shown: lines kept, tabs made spaces, the rest of the control characters gone, cut at [max]. */
    private fun mailText(s: String, max: Int): String = Input.screen(s).take(max)

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

    /** The repository's changes: names cleaned as everything shown is, a kind the client knows or `modified`. */
    fun changes(body: String): ChangeSet = parse(body) { o ->
        ChangeSet(
            root = Input.printable(o.optString("root")).take(1024),
            branch = Input.printable(o.optString("branch")).take(256),
            ahead = o.optInt("ahead").coerceAtLeast(0),
            behind = o.optInt("behind").coerceAtLeast(0),
            truncated = o.optBoolean("truncated"),
            entries = list(o.optJSONArray("entries")) {
                val path = Input.printable(it.optString("path")).take(1024)
                if (path.isEmpty()) throw JSONException("path")
                val kind = it.optString("kind")
                Change(path, if (kind in Changes.KINDS) kind else "modified", it.optBoolean("staged"), Input.printable(it.optString("from")).take(1024))
            }.take(2000),
        )
    }

    /** A diff as text, control characters but tabs and newlines dropped. */
    fun diff(body: String): Diff = parse(body) { o ->
        val text = o.optString("diff").take(2 shl 20).filter { it == '\n' || it == '\t' || (it.code >= 0x20 && it.code != 0x7f) }
        Diff(text, o.optBoolean("binary"), o.optBoolean("cut"))
    }

    /** The transcriber's text, cleaned as typed text is. */
    fun transcript(body: String): String = parse(body) { o -> Input.typed(o.optString("text")) }

    /**
     * The desk as the hub keeps it, read defensively: addresses by the grammar and bounded in
     * number, the focus among the panes, an arrangement of the six, a tree whose leaves are the
     * panes and whose keys are bounded (else none, and no `custom`), weights in range (else none),
     * `by` cleaned and bounded. A hub's answer that is no desk is a bad answer.
     */
    fun desk(body: String): Desk = parse(body) { o -> deskOf(o.getJSONObject("desk")) }

    private fun deskOf(o: JSONObject): Desk {
        fun addresses(name: String, max: Int): List<String> {
            val arr = o.optJSONArray(name) ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val a = arr.optString(i)
                if (Address.valid(a) && a !in out) out.add(a)
            }
            return if (out.size > max) emptyList() else out
        }
        val view = o.optString("view").takeIf { it == "panes" || it == "window" } ?: "panes"
        val panes = if (view == "panes") addresses("panes", Desks.MAX_PANES) else emptyList()
        var arrangement = o.optString("arrangement").takeIf { it in Desks.ARRANGEMENTS } ?: ""
        var tree = o.opt("tree")?.let { treeOf(it) }
        if (tree != null && !Desks.validTree(tree, panes.size)) tree = null
        if (tree == null && arrangement == "custom") arrangement = ""
        val weights = HashMap<String, List<Float>>()
        o.optJSONObject("weights")?.let { w ->
            for (k in w.keys()) {
                val arr = w.optJSONArray(k) ?: continue
                weights[k] = List(arr.length()) { arr.optDouble(it, -1.0).toFloat() }
            }
        }
        val window = o.optString("window").takeIf { view == "window" && WINDOW.matches(it) } ?: ""
        return Desk(
            view = view,
            panes = panes,
            focused = o.optString("focused").takeIf { Address.valid(it) && (view == "window" || it in panes) } ?: "",
            arrangement = if (panes.size > 1) arrangement else "",
            tree = tree,
            weights = if (Desks.validWeights(weights)) weights else emptyMap(),
            minimised = addresses("minimised", Desks.MAX_MINIMISED),
            order = addresses("order", Desks.MAX_ORDER),
            window = window,
            updated = o.optLong("updated").coerceAtLeast(0),
            by = Input.printable(o.optString("by")).take(Desks.MAX_BY),
            rev = o.optLong("rev").coerceAtLeast(0),
        )
    }

    /** A window address, `host/session/window`. */
    private val WINDOW = Regex("^[a-z0-9-]{1,32}/[A-Za-z0-9._-]{1,64}/[A-Za-z0-9._-]{1,64}$")

    /** The web's tile tree: a number for a leaf, `{kind: h|v, key, children}` for a split; null for anything else. */
    private fun treeOf(x: Any?): WindowLayout.Node? {
        return when (x) {
            is Int -> WindowLayout.Node.Leaf(x)
            is Number -> x.toDouble().let { if (it == Math.floor(it) && it in 0.0..3.0) WindowLayout.Node.Leaf(it.toInt()) else null }
            is JSONObject -> {
                val kind = x.optString("kind")
                val kids = x.optJSONArray("children")
                if ((kind != "h" && kind != "v") || kids == null || kids.length() < 2 || kids.length() > Desks.MAX_PANES) return null
                val children = ArrayList<WindowLayout.Node>()
                for (i in 0 until kids.length()) children.add(treeOf(kids.opt(i)) ?: return null)
                WindowLayout.Node.Split(kind == "h", children, x.optString("key"))
            }
            else -> null
        }
    }

    /** The desk the phone writes: the view without `updated`, `by` and `rev`, the fields the hub sets. */
    fun deskBody(d: Desk): String {
        val o = JSONObject().put("view", d.view)
        if (d.view == "window") {
            o.put("window", d.window)
        } else {
            o.put("panes", JSONArray(d.panes))
            if (d.arrangement.isNotEmpty()) o.put("arrangement", d.arrangement)
            d.tree?.let { o.put("tree", treeJson(it)) }
            if (d.weights.isNotEmpty()) o.put("weights", JSONObject().also { w -> for ((k, ws) in d.weights) w.put(k, JSONArray(ws.map { it.toDouble() })) })
            if (d.minimised.isNotEmpty()) o.put("minimised", JSONArray(d.minimised))
            if (d.order.isNotEmpty()) o.put("order", JSONArray(d.order))
        }
        if (d.focused.isNotEmpty()) o.put("focused", d.focused)
        return o.toString()
    }

    private fun treeJson(n: WindowLayout.Node): Any = when (n) {
        is WindowLayout.Node.Leaf -> n.index
        is WindowLayout.Node.Split -> JSONObject().put("kind", if (n.horizontal) "h" else "v").put("key", n.key).put("children", JSONArray(n.children.map { treeJson(it) }))
    }

    fun error(body: String): String = try {
        JSONObject(body).optString("error")
    } catch (e: JSONException) {
        ""
    }

    fun text(s: String): String = JSONObject().put("text", s).toString()

    fun key(name: String): String = JSONObject().put("key", name).toString()

    fun choice(c: String): String = JSONObject().put("choice", c).toString()

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

    /** The window laid out as [layout] at the size it has. */
    fun arrange(layout: String): String = JSONObject().put("scope", "window").put("layout", layout).toString()

    fun direction(d: String): String = JSONObject().put("direction", d).toString()

    fun name(n: String): String = JSONObject().put("name", n).toString()

    fun alias(a: String): String = JSONObject().put("alias", a).toString()

    /** Parks the pane for later, or wakes it. */
    fun later(on: Boolean): String = JSONObject().put("later", on).toString()

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
            paneId = Follow.id(o.optString("pane_id")),
            width = o.optInt("width"),
            height = o.optInt("height"),
            title = Input.printable(o.optString("title")),
            command = Input.printable(o.optString("command")),
            mode = o.optString("mode"),
            alias = o.optString("alias"),
            activity = o.optLong("activity"),
            attention = a?.let { Attention(it.optString("kind"), it.optLong("at"), Input.printable(it.optString("text")), choices(it)) },
            mouse = if (o.has("mouse")) o.optBoolean("mouse") else null,
            cursorX = o.optInt("cursor_x").coerceAtLeast(0),
            cursorY = o.optInt("cursor_y").coerceAtLeast(0),
            windowLayout = o.optString("window_layout").take(WindowLayout.MAX_BYTES),
            talk = talk(o.optJSONArray("talk")),
            mail = o.optJSONObject("mail")?.let { m -> m.optInt("unread").takeIf { it > 0 }?.let { Mail(it, m.optLong("at")) } },
            cwd = o.optString("cwd").takeIf { it.isNotEmpty() && it.length <= Names.MAX_PATH && it.none { c -> c < ' ' || c == '\u007f' } },
            later = o.optBoolean("later"),
            laterAt = o.optLong("later_at").coerceAtLeast(0),
            shared = o.optInt("shared").coerceAtLeast(0),
            last = o.optBoolean("last"),
        )
    }

    /** A directory's entries by name and kind; a name a shell could not take whole is dropped, as the agent drops it. */
    fun listing(body: String): Listing = parse(body) { o ->
        Listing(
            path = o.optString("path").take(Names.MAX_PATH),
            entries = list(o.optJSONArray("entries")) {
                val name = it.optString("name")
                val kind = it.optString("kind")
                if (name.isEmpty() || name.toByteArray(Charsets.UTF_8).size > Names.MAX_NAME || name.any { c -> c < ' ' || c == '\u007f' }) null
                else Entry(name, if (kind in Names.KINDS) kind else "other")
            }.filterNotNull().take(Names.MAX_ENTRIES),
            truncated = o.optBoolean("truncated"),
        )
    }

    /** The pane's talk partners: only addresses the protocol spells and ways it names, [Groups.MAX_PARTNERS] at most. */
    private fun talk(arr: JSONArray?): List<Talk> {
        if (arr == null) return emptyList()
        val out = ArrayList<Talk>()
        for (i in 0 until arr.length()) {
            if (out.size >= Groups.MAX_PARTNERS) break
            val t = arr.optJSONObject(i) ?: continue
            val address = t.optString("address")
            val way = t.optString("way")
            if (!Address.valid(address) || way !in Groups.WAYS) continue
            out.add(Talk(address, t.optString("pane_id").take(16), t.optLong("at"), way))
        }
        return out
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
