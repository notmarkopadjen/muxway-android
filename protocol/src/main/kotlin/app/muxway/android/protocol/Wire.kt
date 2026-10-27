package app.muxway.android.protocol

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What the watch and the phone say to each other about one pane: a request for the tail,
 * a key, a short text or a heard clip to type, and the answers. One JSON document per
 * message, read defensively on both ends; anything that does not fit is dropped.
 */
object Wire {
    const val READ = "/pane/read"
    const val KEY = "/pane/key"
    const val TEXT = "/pane/text"
    const val TAIL = "/pane/tail"
    const val DONE = "/pane/done"
    const val CLIP = "/clip"
    const val HEARD = "/clip/heard"

    const val MAX_BYTES = 64 * 1024
    const val TAIL_LINES = 40
    const val MAX_LINES = 60
    /** Characters per line, colour sequences included. */
    const val MAX_LINE = 800
    const val MAX_CLIP_BYTES = 1 shl 20
    const val MAX_CLIP_SECONDS = 30

    /** Keys the watch may send as keys; letters and digits go as text. */
    val KEYS = setOf("Enter", "Escape", "C-c", "Tab", "BTab", "Up", "Down")

    sealed class Request(val id: String, val address: String)
    class Read(id: String, address: String, val etag: String) : Request(id, address)
    class Key(id: String, address: String, val key: String) : Request(id, address)
    /** [text] as typed or chosen on the watch, or empty with [heard] naming a clip the phone transcribed. */
    class Text(id: String, address: String, val text: String, val heard: String, val enter: Boolean) : Request(id, address)

    /** [lines] keep their colour sequences; [command] is the pane's program, for the lens. */
    data class Tail(val id: String, val address: String, val etag: String, val lines: List<String>?, val writable: Boolean, val transcriber: Boolean, val error: String = "", val command: String = "")
    data class Done(val id: String, val ok: Boolean, val error: String = "")
    data class Heard(val id: String, val text: String, val error: String = "")

    fun request(path: String, bytes: ByteArray): Request? {
        val o = obj(bytes) ?: return null
        val id = short(o, "id")
        val address = o.optString("address")
        if (id.isEmpty() || !Keys.valid(address)) return null
        return when (path) {
            READ -> Read(id, address, short(o, "etag"))
            KEY -> Key(id, address, short(o, "key"))
            TEXT -> Text(id, address, Input.typed(o.optString("text")).take(Input.MAX_TEXT_BYTES), short(o, "heard"), o.optBoolean("enter"))
            else -> null
        }
    }

    fun tail(bytes: ByteArray): Tail? {
        val o = obj(bytes) ?: return null
        val id = short(o, "id")
        val address = o.optString("address")
        if (id.isEmpty() || !Keys.valid(address)) return null
        val arr = o.optJSONArray("lines")
        val lines = arr?.let { a ->
            val out = ArrayList<String>(minOf(a.length(), MAX_LINES))
            for (i in maxOf(0, a.length() - MAX_LINES) until a.length()) out.add(Ansi.keep(a.optString(i)).replace("\n", "").take(MAX_LINE))
            out
        }
        return Tail(id, address, short(o, "etag"), lines, o.optBoolean("writable"), o.optBoolean("transcriber"), short(o, "error"), short(o, "command").take(64))
    }

    fun done(bytes: ByteArray): Done? {
        val o = obj(bytes) ?: return null
        val id = short(o, "id")
        if (id.isEmpty()) return null
        return Done(id, o.optBoolean("ok"), short(o, "error"))
    }

    fun heard(bytes: ByteArray): Heard? {
        val o = obj(bytes) ?: return null
        val id = short(o, "id")
        if (id.isEmpty()) return null
        return Heard(id, Input.typed(o.optString("text")).take(Input.MAX_TEXT_BYTES), short(o, "error"))
    }

    fun read(id: String, address: String, etag: String): ByteArray = bytes(JSONObject().put("id", id).put("address", address).put("etag", etag))

    fun key(id: String, address: String, key: String): ByteArray = bytes(JSONObject().put("id", id).put("address", address).put("key", key))

    fun text(id: String, address: String, text: String, enter: Boolean): ByteArray =
        bytes(JSONObject().put("id", id).put("address", address).put("text", text).put("enter", enter))

    fun heardText(id: String, address: String, heard: String, enter: Boolean): ByteArray =
        bytes(JSONObject().put("id", id).put("address", address).put("heard", heard).put("enter", enter))

    fun tail(t: Tail): ByteArray {
        val o = JSONObject().put("id", t.id).put("address", t.address).put("etag", t.etag).put("writable", t.writable).put("transcriber", t.transcriber).put("command", t.command)
        if (t.lines != null) o.put("lines", JSONArray(t.lines.takeLast(MAX_LINES).map { it.take(MAX_LINE) }))
        if (t.error.isNotEmpty()) o.put("error", t.error)
        return bytes(o)
    }

    fun done(d: Done): ByteArray {
        val o = JSONObject().put("id", d.id).put("ok", d.ok)
        if (d.error.isNotEmpty()) o.put("error", d.error)
        return bytes(o)
    }

    fun heard(h: Heard): ByteArray {
        val o = JSONObject().put("id", h.id).put("text", h.text)
        if (h.error.isNotEmpty()) o.put("error", h.error)
        return bytes(o)
    }

    /** The pane's content as the watch shows it: the last [TAIL_LINES] lines, colour kept. */
    fun lines(content: String): List<String> {
        val all = Ansi.keep(content).trimEnd('\n').split('\n')
        return all.takeLast(TAIL_LINES).map { it.take(MAX_LINE) }
    }

    private fun obj(bytes: ByteArray): JSONObject? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: JSONException) {
            null
        }
    }

    private fun short(o: JSONObject, key: String): String = Input.printable(o.optString(key)).take(200)

    private fun bytes(o: JSONObject): ByteArray = o.toString().toByteArray(Charsets.UTF_8)
}
