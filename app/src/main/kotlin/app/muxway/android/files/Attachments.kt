package app.muxway.android.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import app.muxway.android.protocol.Files
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** A file the phone was handed, read into memory, or why it was not. [name] is for the status line only. */
sealed class Attachment(val name: String) {
    class Ready(name: String, val type: String, val bytes: ByteArray) : Attachment(name)
    class Refused(name: String, val why: String) : Attachment(name)
}

/**
 * Reads what the keyboard, the picker or a share hands over: a content URI, through the system
 * resolver with whatever grant the sender gave. The type comes from the resolver or the name's
 * extension, against the inbox's list; the bytes stop at the cap, so a big file costs no request.
 */
object Attachments {
    private const val TOO_LARGE = "too large, 8 MiB at most"

    fun read(resolver: ContentResolver, uri: Uri): Attachment {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return Attachment.Refused("file", "not a file this app can take")
        val (rawName, declared) = describe(resolver, uri)
        val name = Files.shown(rawName)
        val mime = try { resolver.getType(uri) } catch (e: Exception) { null }
        val type = Files.type(mime, rawName) ?: return Attachment.Refused(name, "not a type the host takes")
        if (declared != null && declared > 0 && !Files.fits(declared)) return Attachment.Refused(name, TOO_LARGE)
        val stream = try { resolver.openInputStream(uri) } catch (e: Exception) { null } ?: return Attachment.Refused(name, "cannot be read")
        val bytes = try {
            stream.use { readCapped(it) }
        } catch (e: Exception) {
            return Attachment.Refused(name, "cannot be read")
        } ?: return Attachment.Refused(name, TOO_LARGE)
        if (bytes.isEmpty()) return Attachment.Refused(name, "empty")
        return Attachment.Ready(name, type, bytes)
    }

    /** The display name and size the provider gives, when it does. */
    private fun describe(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return uri.lastPathSegment to null
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { if (c.isNull(it)) null else c.getString(it) }
                val s = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (c.isNull(it)) null else c.getLong(it) }
                return (n ?: uri.lastPathSegment) to s
            }
        } catch (e: Exception) {
            // A provider that will not say; the name falls back to the URI's end.
        }
        return uri.lastPathSegment to null
    }

    /** At most the cap; null when there is more. */
    private fun readCapped(s: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            if (out.size() + n > Files.MAX_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
