package app.muxway.android.protocol

/**
 * The rows of the `+` menu after the tabs, from the listing the client holds: one more pane
 * beside the focused one, a session on its host, the minimised panes, then every other
 * readable pane not on screen, by hub (with several), host and session in listing order.
 */
object AddMenu {
    const val MAX_TILES = 4

    sealed class Row {
        /** `+ pane`, beside [pane]; [reason] says why it is off. */
        data class NewPane(val pane: Pane, val reason: String? = null) : Row()
        /** `+ session` on the focused pane's host. */
        data class NewSession(val hub: String, val host: String, val reason: String? = null) : Row()
        data object Rule : Row()
        data class Heading(val text: String) : Row()
        /** An existing pane; [minimised] when it is in the row above the keys. */
        data class Item(val pane: Pane, val minimised: Boolean) : Row()
    }

    /**
     * [open] are the tiles on screen, [minimised] the ones in the row below, [focused] the
     * focused tile's key; [hubName] names a hub for its heading, drawn only with several hubs.
     */
    fun rows(
        panes: List<Pane>,
        hosts: List<Host>,
        open: List<String>,
        minimised: List<String>,
        focused: String,
        hubName: (String) -> String = { it },
    ): List<Row> {
        val out = ArrayList<Row>()
        val pane = panes.firstOrNull { it.key == focused }
        if (pane != null) {
            val host = hosts.firstOrNull { it.hub == pane.hub && it.name == pane.host }
            out.add(Row.NewPane(pane, if (!pane.writable) "read only" else null))
            out.add(Row.NewSession(pane.hub, pane.host, if (host?.sessions != true) "no sessions here" else null))
        }
        if (open.size >= MAX_TILES) out.add(Row.Heading("four open: a pick replaces ${pane?.name ?: "the focused pane"}"))
        val below = minimised.mapNotNull { k -> panes.firstOrNull { it.key == k } }
        if (below.isNotEmpty()) {
            out.add(Row.Rule)
            for (p in below) out.add(Row.Item(p, minimised = true))
        }
        val rest = panes.filter { it.key !in open && it.key !in minimised && it.key != focused }
        if (rest.isNotEmpty()) {
            out.add(Row.Rule)
            val hubs = rest.map { it.hub }.distinct()
            for (hub in hubs) {
                for ((host, inHost) in rest.filter { it.hub == hub }.groupBy { it.host }) {
                    for ((session, inSession) in inHost.groupBy { it.session }) {
                        out.add(Row.Heading(if (hubs.size > 1) "${hubName(hub)} · $host · $session" else "$host · $session"))
                        for (p in inSession) out.add(Row.Item(p, minimised = false))
                    }
                }
            }
        }
        return out
    }
}
