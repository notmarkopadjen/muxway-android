package app.muxway.android.protocol

/**
 * The phone's side of [Wire]: every request from the watch passes here before the hub sees
 * anything. The address must be in the current listing, a write needs the pane writable,
 * the owner's permission and room under the rate limit, and the text typed is only what
 * the hub's transcriber heard or one of two letters. Nothing here logs.
 */
class WatchGate(
    private val api: Api,
    private val panes: () -> List<Pane>,
    private val allowed: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val done = LinkedHashMap<String, Wire.Done>()
    private val heard = LinkedHashMap<String, String>()
    private val writes = ArrayDeque<Long>()
    private var transcriber: Boolean? = null

    fun read(r: Wire.Read): Wire.Tail {
        val pane = panes().firstOrNull { it.address == r.address }
            ?: return Wire.Tail(r.id, r.address, "", null, false, false, REFUSED)
        return try {
            val got = api.read(r.address, Wire.TAIL_LINES, r.etag.ifEmpty { null })
            val lines = got?.let { Wire.lines(it.content) }
            Wire.Tail(r.id, r.address, got?.etag ?: r.etag, lines, pane.writable, pane.writable && hasTranscriber())
        } catch (e: ApiException) {
            Wire.Tail(r.id, r.address, "", null, pane.writable, false, reason(e))
        }
    }

    fun key(r: Wire.Key): Wire.Done = write(r, r.key.takeIf { it in Wire.KEYS }) { api.key(r.address, it) }

    fun text(r: Wire.Text): Wire.Done {
        val text = when {
            r.heard.isNotEmpty() -> heard[r.heard]
            r.text in Wire.SHORT_TEXTS -> r.text
            else -> null
        }
        return write(r, text) {
            api.type(r.address, it)
            heard.remove(r.heard)
            if (r.enter) api.key(r.address, "Enter")
        }
    }

    /** A clip to the hub's transcriber; the text is kept under [id] for one [Wire.Text] that names it. */
    fun transcribe(id: String, wav: ByteArray): Wire.Heard {
        if (!allowed()) return Wire.Heard(id, "", REFUSED)
        if (wav.size > Wire.MAX_CLIP_BYTES) return Wire.Heard(id, "", REFUSED)
        if (wav.size < 44 + Clip.SAMPLE_RATE) return Wire.Heard(id, "", TOO_SHORT)
        return try {
            val text = api.transcribe(wav)
            if (text.isEmpty()) return Wire.Heard(id, "", TOO_SHORT)
            heard[id] = text
            while (heard.size > 8) heard.remove(heard.keys.first())
            Wire.Heard(id, text)
        } catch (e: ApiException) {
            Wire.Heard(id, "", clipReason(e))
        }
    }

    /** [what] is null when the request itself is not one the watch may make. */
    private fun write(r: Wire.Request, what: String?, send: (String) -> Unit): Wire.Done {
        done[r.id]?.let { return it }
        val pane = panes().firstOrNull { it.address == r.address }
        val answer = when {
            what == null || pane == null || !allowed() -> Wire.Done(r.id, false, REFUSED)
            !pane.writable -> Wire.Done(r.id, false, READ_ONLY)
            !underLimit() -> Wire.Done(r.id, false, REFUSED)
            else -> try {
                send(what)
                Wire.Done(r.id, true)
            } catch (e: ApiException) {
                Wire.Done(r.id, false, reason(e))
            }
        }
        done[r.id] = answer
        while (done.size > 64) done.remove(done.keys.first())
        return answer
    }

    /** One write a second, ten a minute. */
    private fun underLimit(): Boolean {
        val now = clock()
        while (writes.isNotEmpty() && now - writes.first() >= 60_000) writes.removeFirst()
        if (writes.size >= PER_MINUTE || (writes.isNotEmpty() && now - writes.last() < 1_000)) return false
        writes.addLast(now)
        return true
    }

    private fun hasTranscriber(): Boolean {
        transcriber?.let { return it }
        return try {
            api.hasTranscriber().also { transcriber = it }
        } catch (e: ApiException) {
            false
        }
    }

    private fun reason(e: ApiException): String = when (e.code) {
        "read_only", "forbidden" -> READ_ONLY
        "bad_input", "not_found" -> REFUSED
        "rate_limited" -> RATE_LIMITED
        else -> NO_CONNECTION
    }

    private fun clipReason(e: ApiException): String = when (e.code) {
        "read_only", "forbidden" -> READ_ONLY
        "bad_input" -> TOO_SHORT
        "too_large" -> REFUSED
        "rate_limited" -> RATE_LIMITED
        "not_found", "provider_unavailable" -> NO_TRANSCRIBER
        else -> NO_CONNECTION
    }

    companion object {
        const val PER_MINUTE = 10
        const val REFUSED = "refused"
        const val READ_ONLY = "read_only"
        const val TOO_SHORT = "too_short"
        const val RATE_LIMITED = "rate_limited"
        const val NO_TRANSCRIBER = "no_transcriber"
        const val NO_CONNECTION = "no_connection"
        const val PAUSED = "paused"
    }
}
