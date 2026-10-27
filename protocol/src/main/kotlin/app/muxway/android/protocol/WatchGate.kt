package app.muxway.android.protocol

/**
 * The phone's side of [Wire]: every request from the watch passes here before a hub sees
 * anything. The address is a key that must be in the current listing, and goes to its hub, a write needs the pane writable,
 * the owner's permission and room under the rate limit, and a text is cleaned as the phone
 * cleans its own; a heard clip is typed once, by its id. Nothing here logs.
 */
class WatchGate(
    private val apiFor: (hub: String) -> Api?,
    private val panes: () -> List<Pane>,
    private val allowed: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val done = LinkedHashMap<String, Wire.Done>()
    private val heard = LinkedHashMap<String, String>()
    private val writes = ArrayDeque<Long>()
    private val transcriber = HashMap<String, Boolean>()
    /** The hub of the pane the watch read last; its transcriber hears the next clip. */
    private var lastHub = ""

    fun read(r: Wire.Read): Wire.Tail {
        val pane = panes().firstOrNull { it.key == r.address }
        val api = pane?.let { apiFor(it.hub) }
        if (pane == null || api == null) return Wire.Tail(r.id, r.address, "", null, false, false, REFUSED)
        lastHub = pane.hub
        return try {
            val got = api.read(pane.address, Wire.TAIL_LINES, r.etag.ifEmpty { null })
            val lines = got?.let { Wire.lines(it.content) }
            Wire.Tail(r.id, r.address, got?.etag ?: r.etag, lines, pane.writable, pane.writable && hasTranscriber(pane.hub, api), "", pane.command)
        } catch (e: ApiException) {
            Wire.Tail(r.id, r.address, "", null, pane.writable, false, reason(e), pane.command)
        }
    }

    fun key(r: Wire.Key): Wire.Done = write(r, r.key.takeIf { it in Wire.KEYS }) { api, address, k -> api.key(address, k) }

    fun text(r: Wire.Text): Wire.Done {
        val text = when {
            r.heard.isNotEmpty() -> heard[r.heard]
            else -> Input.typed(r.text).takeIf { it.isNotEmpty() && Input.utf8Length(it) <= Input.MAX_TEXT_BYTES }
        }
        return write(r, text) { api, address, t ->
            api.type(address, t)
            heard.remove(r.heard)
            if (r.enter) api.key(address, "Enter")
        }
    }

    /** A clip to the hub's transcriber; the text is kept under [id] for one [Wire.Text] that names it. */
    fun transcribe(id: String, wav: ByteArray): Wire.Heard {
        if (!allowed()) return Wire.Heard(id, "", NOT_ALLOWED)
        if (wav.size > Wire.MAX_CLIP_BYTES) return Wire.Heard(id, "", REFUSED)
        if (wav.size < 44 + Clip.SAMPLE_RATE) return Wire.Heard(id, "", TOO_SHORT)
        val api = apiFor(lastHub) ?: return Wire.Heard(id, "", NO_TRANSCRIBER)
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
    private fun write(r: Wire.Request, what: String?, send: (Api, String, String) -> Unit): Wire.Done {
        done[r.id]?.let { return it }
        val pane = panes().firstOrNull { it.key == r.address }
        val api = pane?.let { apiFor(it.hub) }
        val answer = when {
            what == null || pane == null || api == null -> Wire.Done(r.id, false, REFUSED)
            !allowed() -> Wire.Done(r.id, false, NOT_ALLOWED)
            !pane.writable -> Wire.Done(r.id, false, READ_ONLY)
            !underLimit() -> Wire.Done(r.id, false, REFUSED)
            else -> try {
                send(api, pane.address, what)
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

    private fun hasTranscriber(hub: String, api: Api): Boolean {
        transcriber[hub]?.let { return it }
        return try {
            api.hasTranscriber().also { transcriber[hub] = it }
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
        /** The owner has not switched *watch may answer* on. */
        const val NOT_ALLOWED = "not_allowed"
        /** The clip did not arrive whole. */
        const val NO_CLIP = "no_clip"
        const val READ_ONLY = "read_only"
        const val TOO_SHORT = "too_short"
        const val RATE_LIMITED = "rate_limited"
        const val NO_TRANSCRIBER = "no_transcriber"
        const val NO_CONNECTION = "no_connection"
        const val PAUSED = "paused"
    }
}
