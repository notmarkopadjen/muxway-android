package app.muxway.android.protocol

import java.net.URI

/** The hub's base URL as the user typed it, checked and normalised. */
object HubUrl {
    private val loopback = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")

    /** Returns the normalised URL or null with a reason. */
    fun check(raw: String): Pair<String?, String?> {
        var s = raw.trim()
        if (s.isEmpty()) return null to "Hub URL is empty"
        if (!s.contains("://")) s = "https://$s"
        val uri = try {
            URI(s)
        } catch (e: Exception) {
            return null to "Not a URL"
        }
        val host = uri.host ?: return null to "Not a URL"
        if (uri.rawUserInfo != null) return null to "No user name in the URL"
        if (uri.rawQuery != null || uri.rawFragment != null) return null to "No query in the URL"
        when (uri.scheme?.lowercase()) {
            "https" -> {}
            "http" -> if (host.lowercase() !in loopback) return null to "Plain HTTP only to localhost"
            else -> return null to "Only https"
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.rawPath.trimEnd('/')
        return "${uri.scheme.lowercase()}://$host$port$path" to null
    }
}
