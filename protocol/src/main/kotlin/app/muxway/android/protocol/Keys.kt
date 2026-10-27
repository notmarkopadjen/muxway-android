package app.muxway.android.protocol

/**
 * A pane's key on the phone: `<hub id>:<address>`, the hub being the app's own id for it.
 * The address alone is the key of the first hub, so links and watch rows from before the
 * hub part still open something. Keys never reach a hub; the address does.
 */
object Keys {
    private val hub = Regex("^[a-z0-9]{1,16}$")

    fun make(hub: String, address: String): String = if (hub.isEmpty()) address else "$hub:$address"

    /** The hub part, or "" for a bare address. */
    fun hub(key: String): String = if (':' in key) key.substringBefore(':') else ""

    fun address(key: String): String = key.substringAfter(':')

    fun valid(key: String): Boolean {
        val h = hub(key)
        return (h.isEmpty() || hub.matches(h)) && Address.valid(address(key))
    }
}
