package app.muxway.android.protocol

import java.security.SecureRandom

/**
 * A file for the program in a pane, as the host's inbox takes it: any type, 8 MiB at most, one
 * multipart part. A known type is named by its mime or the name's extension and checked by the
 * host; anything else goes as bytes under the extension the name ends in, when that is one
 * short lowercase word, which is all of the phone's name that travels. The browser's rules.
 */
object Files {
    const val MAX_BYTES = 8 shl 20

    /** What the status line shows of a file's name. */
    const val MAX_NAME = 60

    /** What any other file goes as. */
    const val OCTET = "application/octet-stream"

    /** The types the host knows, by the name's extension. */
    val TYPES: Map<String, String> = linkedMapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "json" to "application/json",
        "zip" to "application/zip",
    )

    /** The known types, each once. */
    val MIMES: List<String> = TYPES.values.distinct()

    /** The extension a host takes from a name: one short lowercase word. */
    val EXT = Regex("[a-z0-9]{1,8}")

    /** The type to send: [mime] when it is a known one, else the one [name]'s extension names, else bytes. */
    fun type(mime: String?, name: String?): String {
        val m = mime?.substringBefore(';')?.trim()?.lowercase()
        if (m != null && m in MIMES) return m
        return ext(name)?.let { TYPES[it] } ?: OCTET
    }

    /** The extension [name] ends in, lowercased, when it is a word the host takes; null otherwise. */
    fun ext(name: String?): String? {
        val n = name ?: return null
        val dot = n.lastIndexOf('.')
        if (dot < 1 || dot == n.lastIndex) return null
        return n.substring(dot + 1).lowercase().takeIf { EXT.matches(it) }
    }

    fun fits(bytes: Long): Boolean = bytes in 0..MAX_BYTES.toLong()

    fun size(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1 shl 20 -> "%.1f KiB".format(n / 1024.0)
        else -> "%.1f MiB".format(n / (1 shl 20).toDouble())
    }

    /** A name fit for the status line: printable, cut. */
    fun shown(name: String?): String {
        val n = Input.printable(name ?: "").trim()
        if (n.isEmpty()) return "file"
        return if (n.length > MAX_NAME) n.take(MAX_NAME - 1) + "…" else n
    }

    fun progress(name: String, bytes: Long): String = "⇪ ${shown(name)} · ${size(bytes)}…"

    /** The path appended to the line, after a space when something is there, and a space after it. */
    fun onLine(line: String, path: String): String {
        val sep = if (line.isEmpty() || line.endsWith(' ') || line.endsWith('\n')) "" else " "
        return "$line$sep$path "
    }

    class Multipart(val contentType: String, val body: ByteArray)

    /**
     * The request body: one part named `file` of [type], called `file`, or `file.<ext>` for the
     * host to name a file of a kind it does not know; the phone's own name goes no further.
     */
    fun multipart(data: ByteArray, type: String, ext: String? = null, boundary: String = boundary()): Multipart {
        require(type in MIMES || type == OCTET) { "type" }
        require(ext == null || EXT.matches(ext)) { "ext" }
        require(data.size <= MAX_BYTES) { "too large" }
        require(boundary.length in 16..70 && boundary.all { it.isLetterOrDigit() }) { "boundary" }
        val name = if (ext == null) "file" else "file.$ext"
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: $type\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII)
        val body = ByteArray(head.size + data.size + tail.size)
        head.copyInto(body)
        data.copyInto(body, head.size)
        tail.copyInto(body, head.size + data.size)
        return Multipart("multipart/form-data; boundary=$boundary", body)
    }

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val random = SecureRandom()

    private fun boundary(): String = buildString(32) { repeat(32) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}

/** Where a file went on the host, and how many bytes the hub took. */
data class Sent(val path: String, val bytes: Long)
