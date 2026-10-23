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
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 40_000
    }

    fun hosts(): List<Host> = Json.hosts(get("hosts").body)

    fun panes(): List<Pane> = Json.panes(get("panes").body)

    /** Null when the pane has not changed since [etag]. */
    fun read(address: String, lines: Int, etag: String?): PaneRead? {
        val r = get("panes/${encode(address)}?lines=$lines", etag)
        if (r.status == 304) return null
        return Json.read(r.body, r.etag)
    }

    fun type(address: String, text: String) {
        val clean = Input.printable(text)
        require(clean.isNotEmpty() && Input.utf8Length(clean) <= Input.MAX_TEXT_BYTES) { "text length" }
        post("panes/${encode(address)}/input", Json.text(clean))
    }

    fun key(address: String, name: String) {
        require(Input.isKey(name)) { "unknown key" }
        post("panes/${encode(address)}/input", Json.key(name))
    }

    fun events(after: Long?, wait: Int, limit: Int = 50): Events {
        val q = StringBuilder("events?wait=$wait&limit=$limit")
        if (after != null) q.append("&after=$after")
        return Json.events(get(q.toString()).body)
    }

    private class Response(val status: Int, val body: String, val etag: String)

    private fun get(path: String, etag: String? = null) = call("GET", path, null, etag)

    private fun post(path: String, json: String) = call("POST", path, json, null)

    private fun call(method: String, path: String, json: String?, etag: String?): Response {
        val c = try {
            URL("$baseUrl/v1/$path").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ApiException(0, "offline")
        }
        try {
            c.connectTimeout = CONNECT_TIMEOUT
            c.readTimeout = READ_TIMEOUT
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", userAgent)
            if (etag != null) c.setRequestProperty("If-None-Match", etag)
            if (json != null) {
                val data = json.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(data.size)
                c.outputStream.use { it.write(data) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val body = if (status == 204 || status == 304 || stream == null) "" else readCapped(stream)
            if (status == 200 || status == 204 || status == 304) {
                return Response(status, body, c.getHeaderField("ETag") ?: "")
            }
            throw ApiException(status, Json.error(body))
        } catch (e: IOException) {
            throw ApiException(0, "offline")
        } finally {
            c.disconnect()
        }
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
