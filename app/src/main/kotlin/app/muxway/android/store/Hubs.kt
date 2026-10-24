package app.muxway.android.store

import app.muxway.android.protocol.HubUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom

/** One hub the app knows: its token sits in the vault under the id. */
data class Hub(val id: String, val name: String, val url: String)

/** The hubs list as it is kept in preferences, and the rules an entry follows. */
object Hubs {
    const val MAX = 32
    val NAME = Regex("[a-z0-9-]{1,32}")
    private val ID = Regex("[a-z0-9]{1,16}")

    /** The id the one hub of an older install gets; its token moves with it. */
    const val LEGACY_ID = "hub"

    fun encode(hubs: List<Hub>): String {
        val arr = JSONArray()
        for (h in hubs) arr.put(JSONObject().put("id", h.id).put("name", h.name).put("url", h.url))
        return arr.toString()
    }

    /** Whatever is well shaped, one per id and per URL, in the order kept. */
    fun decode(json: String?): List<Hub> {
        if (json.isNullOrEmpty()) return emptyList()
        val arr = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return emptyList()
        }
        val out = ArrayList<Hub>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val name = o.optString("name")
            val url = HubUrl.check(o.optString("url")).first ?: continue
            if (!ID.matches(id) || !NAME.matches(name)) continue
            if (out.any { it.id == id || it.url == url }) continue
            if (out.size < MAX) out.add(Hub(id, name, url))
        }
        return out
    }

    fun newId(taken: List<Hub>): String {
        val rnd = SecureRandom()
        while (true) {
            val id = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[rnd.nextInt(36)] }.joinToString("")
            if (taken.none { it.id == id }) return id
        }
    }

    /** A name from the host when the person gives none: `hub.example.com` → `hub-example-com`. */
    fun defaultName(url: String, taken: List<Hub>): String {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        val base = host.replace(Regex("[^a-z0-9-]"), "-").trim('-').take(32).ifEmpty { "hub" }
        if (taken.none { it.name == base }) return base
        var n = 2
        while (true) {
            val name = base.take(32 - n.toString().length - 1) + "-" + n
            if (taken.none { it.name == name }) return name
            n++
        }
    }
}
