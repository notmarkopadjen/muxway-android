package app.muxway.android.protocol

/**
 * The connect code: `muxway://connect?hub=<url>&token=<token>`, as `scripts/connect-qr.py`
 * prints it. Parsing only; the URL and token still go through the same checks as typed ones.
 */
object Setup {
    const val PREFIX = "muxway://connect?"
    private const val MAX = 1024

    data class Code(val hub: String, val token: String)

    /** Null unless this is a connect code with a hub and a printable token. */
    fun parse(text: String): Code? {
        if (text.length > MAX || !text.startsWith(PREFIX)) return null
        var hub: String? = null
        var token: String? = null
        for (part in text.substring(PREFIX.length).split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val value = decode(part.substring(eq + 1)) ?: return null
            when (part.substring(0, eq)) {
                "hub" -> hub = value
                "token" -> token = value
            }
        }
        if (hub.isNullOrEmpty() || token.isNullOrEmpty()) return null
        if (Input.printable(token) != token || Input.printable(hub) != hub) return null
        return Code(hub, token)
    }

    /** Percent-decoding into UTF-8; a broken escape or invalid byte sequence is null. */
    private fun decode(s: String): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                if (c.code > 0x7f) return null
                bytes.write(c.code)
                i++
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
    }
}
