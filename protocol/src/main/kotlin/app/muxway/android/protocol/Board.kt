package app.muxway.android.protocol

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One pane as a glanceable client shows it. [state] is one of [Board.QUIET], [Board.WORKING], [Board.WAITING]. */
data class Row(val address: String, val name: String, val state: Int, val kind: String, val at: Long, val text: String) {
    val status: Lens.Status
        get() = when (state) {
            Board.WAITING -> Lens.Status.WAITING
            Board.WORKING -> Lens.Status.WORKING
            else -> Lens.Status.QUIET
        }
    val glyph: String? get() = Lens.glyph(status, kind.ifEmpty { null })
    val word: String? get() = Lens.word(status, kind.ifEmpty { null })
}

/**
 * What the watch shows: every pane the phone lists, ranked by how much it needs its owner.
 * Waiting panes come first, the one that has waited longest at the top, then working, then
 * quiet. [at] is when the phone computed it; [live] is false when the phone stopped on purpose.
 * The phone serialises it as JSON for the Data Layer and the watch reads it defensively.
 */
data class Board(val rows: List<Row>, val at: Long, val live: Boolean) {
    val waiting: Int get() = rows.count { it.state == WAITING }

    /** Rows for a display with [n] lines: all of them, or [n]-1 and room for the overflow count. */
    fun shown(n: Int): List<Row> = if (rows.size <= n) rows else rows.take(n - 1)

    /** How many panes a display with [n] lines leaves out. */
    fun hidden(n: Int): Int = rows.size - shown(n).size

    /** Older than ten minutes, or the phone said it stopped: the display must not look current. */
    fun stale(now: Long): Boolean = !live || now - at > STALE_SECONDS || at > now + 60

    fun json(): String {
        val arr = JSONArray()
        for (r in rows) {
            arr.put(JSONObject().put("address", r.address).put("name", r.name).put("state", r.state).put("kind", r.kind).put("at", r.at).put("text", r.text))
        }
        return JSONObject().put("v", 1).put("at", at).put("live", live).put("rows", arr).toString()
    }

    companion object {
        const val QUIET = 0
        const val WORKING = 1
        const val WAITING = 2

        const val STALE_SECONDS = 600L

        /** The most rows any display asks for; more panes than this are counted, not listed. */
        const val MAX_ROWS = 64

        val EMPTY = Board(emptyList(), 0, false)

        /** Ranks [panes]; [status] is the lens on the phone, [withText] whether notification text travels. */
        fun rank(panes: List<Pane>, at: Long, withText: Boolean, status: (Pane) -> Lens.Status): Board {
            val rows = panes.map { p ->
                val s = status(p)
                val a = p.attention
                Row(
                    address = p.address,
                    name = p.name,
                    state = when (s) { Lens.Status.WAITING -> WAITING; Lens.Status.WORKING -> WORKING; Lens.Status.QUIET -> QUIET },
                    kind = if (s == Lens.Status.WAITING) a?.kind.orEmpty() else "",
                    at = a?.at ?: 0L,
                    text = if (withText && s == Lens.Status.WAITING) a?.text.orEmpty() else "",
                )
            }
            val sorted = rows.sortedWith(compareByDescending<Row> { it.state }.thenBy { if (it.state == WAITING) it.at else 0L }.thenBy { it.address })
            return Board(sorted.take(MAX_ROWS), at, true)
        }

        /** The phone's JSON; anything that does not fit is dropped, a broken document is [EMPTY]. */
        fun parse(json: String?): Board {
            if (json == null || json.length > 1 shl 20) return EMPTY
            return try {
                val o = JSONObject(json)
                val arr = o.optJSONArray("rows") ?: JSONArray()
                val rows = ArrayList<Row>()
                for (i in 0 until minOf(arr.length(), MAX_ROWS)) {
                    val r = arr.optJSONObject(i) ?: continue
                    val address = clean(r.optString("address"))
                    if (!Address.valid(address)) continue
                    rows.add(Row(address, clean(r.optString("name")), r.optInt("state").coerceIn(QUIET, WAITING), clean(r.optString("kind")), r.optLong("at"), clean(r.optString("text"))))
                }
                Board(rows, o.optLong("at"), o.optBoolean("live"))
            } catch (e: JSONException) {
                EMPTY
            }
        }

        private fun clean(s: String): String = Input.printable(s).take(200)
    }
}

/** Pane addresses as the protocol spells them: `host/session/window.pane` or `host/@alias`. */
object Address {
    private val full = Regex("^[a-z0-9-]{1,32}/[A-Za-z0-9._-]{1,64}/([A-Za-z0-9._-]{1,64}|[0-9]{1,4})\\.[0-9]{1,4}$")
    private val alias = Regex("^[a-z0-9-]{1,32}/@[a-z0-9-]{1,32}$")

    fun valid(s: String): Boolean = s.length <= 200 && (full.matches(s) || alias.matches(s))
}
