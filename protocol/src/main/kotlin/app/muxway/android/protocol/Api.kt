package app.muxway.android.protocol

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The hub's client calls, protocol v1, JSON. Blocking; callers pick the thread.
 * The token travels in Authorization only and never in a URL, a log or an error.
 */
class Api(private val baseUrl: String, private val token: String, private val userAgent: String) {
    companion object {
        const val MAX_BODY = 2 * 1024 * 1024
        const val MAX_ETAG = 256
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 40_000
        /** The hub gives the transcriber ninety seconds. */
        private const val TRANSCRIBE_TIMEOUT = 95_000
        /** The hub holds an upload while the agent writes the file. */
        private const val FILE_TIMEOUT = 60_000
        /** The largest file the inbox or a preview root serves. */
        private const val MAX_FILE = 8 * 1024 * 1024
        /** What a client may name a session it starts. */
        val SESSION_NAME = Regex("^[a-z0-9_-]{1,32}$")

        /** A layout string as tmux writes it: the checksum, then cells of digits, `x,{}[]` and ids. */
        val LAYOUT = Regex("^[0-9a-f]{4},[0-9x,{}\\[\\]]+$")
        private val SESSION_ID = Regex("^[A-Za-z0-9_-]{1,16}$")
    }

    fun hosts(): List<Host> = Json.hosts(get("hosts").body)

    fun panes(): List<Pane> = Json.panes(get("panes").body)

    /** Null when the pane has not changed since [etag]. Colour sequences are kept for [Ansi]. */
    fun read(address: String, lines: Int, etag: String?): PaneRead? {
        val r = get("panes/${encode(address)}?lines=$lines&ansi=1", etag)
        if (r.status == 304) return null
        return Json.read(r.body, r.etag)
    }

    fun type(address: String, text: String) {
        val clean = Input.typed(text)
        require(clean.isNotEmpty() && Input.utf8Length(clean) <= Input.MAX_TEXT_BYTES) { "text length" }
        post("panes/${encode(address)}/input", Json.text(clean))
    }

    fun key(address: String, name: String) {
        require(Input.isKey(name)) { "unknown key" }
        post("panes/${encode(address)}/input", Json.key(name))
    }

    /**
     * A mouse button at a cell (1-based) of the pane, the wheel turned [count] notches; a hub
     * without mouse input answers bad_json, one from before the count bad_input to a count.
     */
    fun mouse(address: String, button: String, x: Int, y: Int, count: Int = 1) {
        require(Input.isMouse(button, x, y, count)) { "mouse" }
        post("panes/${encode(address)}/input", Json.mouse(button, x, y, count))
    }

    /**
     * Sizes the pane on the host, as a terminal would; answers the pane as listed afterwards.
     * With [window] the whole window takes the size, and with [zoom] the pane is shown alone in
     * it, as tmux's own zoom does, so no neighbour is squeezed. A [layout], with the window
     * scope only, lays the window's panes out as the tiles are: a tmux layout string naming
     * exactly the window's panes; when it moves them the answer lists them all.
     */
    fun resize(address: String, cols: Int, rows: Int, window: Boolean = false, zoom: Boolean = false, layout: String? = null): Resized {
        require(cols in 20..500 && rows in 5..300) { "size" }
        if (layout != null) {
            require(window && layout.length <= WindowLayout.MAX_BYTES && LAYOUT.matches(layout)) { "layout" }
        }
        return Json.resized(post("panes/${encode(address)}/resize", Json.size(cols, rows, window, zoom, layout)).body)
    }

    /** Lays the pane's window out as [layout] at the size it has, with fit off; the window's panes when they moved. */
    fun arrange(address: String, layout: String): Resized {
        require(layout.length <= WindowLayout.MAX_BYTES && LAYOUT.matches(layout)) { "layout" }
        return Json.resized(post("panes/${encode(address)}/resize", Json.arrange(layout)).body)
    }

    /** One more pane in a window, beside ("h") or below ("v") a pane there you may type into; the new pane. */
    fun split(host: String, session: String, window: Int, direction: String): Pane {
        require(direction == "h" || direction == "v") { "direction" }
        return Json.resized(post("windows/${encode("$host/$session")}/$window/panes", Json.direction(direction)).body).pane
    }

    /** A session on a host whose agent allows it, named [name]; its first pane. */
    fun newSession(host: String, name: String): Pane {
        require(SESSION_NAME.matches(name)) { "session name" }
        return Json.resized(post("hosts/${encode(host)}/sessions", Json.name(name)).body).pane
    }

    /** Closes the pane on its host, ending what runs in it; where the agent allows it. */
    fun closePane(address: String) {
        post("panes/${encode(address)}/close", "{}")
    }

    /** Gives the pane and its window back to tmux's own sizing. */
    fun release(address: String) {
        post("panes/${encode(address)}/resize", Json.fitAuto())
    }

    /** Whether the hub has a transcriber: 204 says yes, 404 no. */
    fun hasTranscriber(): Boolean = try {
        get("transcribe").status == 204
    } catch (e: ApiException) {
        if (e.code == "not_found") false else throw e
    }

    /** A spoken clip, as [Clip.wav] makes it, to the hub's transcriber; the text it heard. */
    fun transcribe(wav: ByteArray): String {
        require(wav.size in 45..(4 shl 20)) { "clip size" }
        return Json.transcript(call("POST", "transcribe", wav, "audio/wav", null, TRANSCRIBE_TIMEOUT).body)
    }

    /** Whether the pane takes files: 204 says yes; a refusal (no inbox, read only, no such pane) no. */
    fun takesFiles(address: String): Boolean = try {
        get("panes/${encode(address)}/files").status == 204
    } catch (e: ApiException) {
        if (e.status > 0) false else throw e
    }

    /** A file for the pane's program, into its host's inbox; where it went. The phone's name for it is not sent. */
    fun sendFile(address: String, data: ByteArray, type: String): Sent {
        val part = Files.multipart(data, type)
        return Json.sent(call("POST", "panes/${encode(address)}/files", part.body, part.contentType, null, FILE_TIMEOUT).body)
    }

    /** One file of the host's inbox back, by the name the agent gave it. */
    fun readFile(address: String, name: String): Fetched = fetch("panes/${encode(address)}/files/${encode(name)}")

    /** A file a pane printed the path of, from under the host's preview roots; a writer's call. */
    fun previewFile(address: String, path: String): Fetched = fetch("panes/${encode(address)}/preview?path=${query(path)}")

    private fun fetch(path: String): Fetched {
        val c = try {
            URL("$baseUrl/v1/$path").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ApiException(0, "offline")
        }
        try {
            c.connectTimeout = CONNECT_TIMEOUT
            c.readTimeout = FILE_TIMEOUT
            c.instanceFollowRedirects = false
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("User-Agent", userAgent)
            val status = c.responseCode
            if (status != 200) throw ApiException(status, Json.error(c.errorStream?.let { readCapped(it) } ?: ""))
            val type = (c.contentType ?: "application/octet-stream").substringBefore(';').trim().lowercase()
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { s ->
                val buf = ByteArray(64 * 1024)
                while (out.size() <= MAX_FILE) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            if (out.size() > MAX_FILE) throw ApiException(0, "bad_answer")
            return Fetched(type, out.toByteArray())
        } catch (e: IOException) {
            throw ApiException(0, "offline")
        } finally {
            c.disconnect()
        }
    }

    /** A query value, percent-encoded but for the unreserved characters. */
    private fun query(s: String): String {
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val ch = b.toInt() and 0xff
            val k = ch.toChar()
            if (k.isLetterOrDigit() && ch < 0x80 || k in "-._~") out.append(k)
            else out.append('%').append("0123456789ABCDEF"[ch shr 4]).append("0123456789ABCDEF"[ch and 15])
        }
        return out.toString()
    }

    /** Whether this token carries the admin grant. */
    fun isAdmin(): Boolean = Json.admin(get("me").body)

    /** The hub's latest log lines; admin only. */
    fun adminLog(limit: Int = 200): List<LogLine> {
        require(limit in 1..500) { "limit" }
        return Json.log(get("admin/log?limit=$limit").body)
    }

    /** Who is connected; admin only. */
    fun adminClients(): List<AdminClient> = Json.clients(get("admin/overview").body)

    /** Ends one browser session; admin only. */
    fun endSession(id: String) {
        require(SESSION_ID.matches(id)) { "session id" }
        call("DELETE", "admin/sessions/$id", null, null, null)
    }

    fun events(after: Long?, wait: Int, limit: Int = 50): Events {
        val q = StringBuilder("events?wait=$wait&limit=$limit")
        if (after != null) q.append("&after=$after")
        return Json.events(get(q.toString()).body)
    }

    private class Response(val status: Int, val body: String, val etag: String)

    /** A file's bytes as the hub serves them, with the type its name says. */
    class Fetched(val type: String, val bytes: ByteArray)

    private fun get(path: String, etag: String? = null) = call("GET", path, null, null, etag)

    private fun post(path: String, json: String) = call("POST", path, json.toByteArray(Charsets.UTF_8), "application/json", null)

    private fun call(method: String, path: String, body: ByteArray?, contentType: String?, etag: String?, readTimeout: Int = READ_TIMEOUT): Response {
        val c = try {
            URL("$baseUrl/v1/$path").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ApiException(0, "offline")
        }
        try {
            c.connectTimeout = CONNECT_TIMEOUT
            c.readTimeout = readTimeout
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", userAgent)
            if (!etag.isNullOrEmpty()) c.setRequestProperty("If-None-Match", etag)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val body = if (status == 204 || status == 304 || stream == null) "" else readCapped(stream)
            if (status == 200 || status == 201 || status == 204 || status == 304) {
                return Response(status, body, etagOf(c.getHeaderField("ETag")))
            }
            throw ApiException(status, Json.error(body))
        } catch (e: IOException) {
            throw ApiException(0, "offline")
        } catch (e: IllegalArgumentException) {
            throw ApiException(0, "bad_answer")
        } finally {
            c.disconnect()
        }
    }

    /** An ETag goes back to the hub as a header, so only printable ASCII of a sane length is kept. */
    private fun etagOf(raw: String?): String {
        val t = raw ?: return ""
        if (t.length > MAX_ETAG || t.any { it < ' ' || it > '~' }) return ""
        return t
    }

    /** Reads at most MAX_BODY bytes; whatever follows is dropped unread. */
    private fun readCapped(stream: InputStream): String = stream.use { s ->
        val buf = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < MAX_BODY) {
            val n = s.read(buf, 0, minOf(buf.size, MAX_BODY - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        out.toString("UTF-8")
    }

    /** Percent-encodes every path segment of an address, keeping / and @. */
    private fun encode(address: String): String = address.split('/').joinToString("/") { seg ->
        val out = StringBuilder()
        for (b in seg.toByteArray(Charsets.UTF_8)) {
            val ch = b.toInt() and 0xff
            val k = ch.toChar()
            if (k.isLetterOrDigit() && ch < 0x80 || k in "-._~@") out.append(k)
            else out.append('%').append("0123456789ABCDEF"[ch shr 4]).append("0123456789ABCDEF"[ch and 15])
        }
        out.toString()
    }
}
