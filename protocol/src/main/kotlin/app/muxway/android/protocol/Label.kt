package app.muxway.android.protocol

/**
 * This client's label for the fits it sends, which the other clients show as *held by phone ·
 * Pixel 9*: the device's model as Android names it, kept to what the hub takes (printable, no
 * blank at either end, [MAX] characters) and to ASCII, so every client's font has it; "phone"
 * when nothing is left.
 */
object Label {
    const val MAX = 32

    fun of(model: String?): String {
        val kept = (model ?: "").replace(Regex("\\s+"), " ").filter { it in ' '..'~' }.trim().take(MAX).trim()
        return kept.ifEmpty { "phone" }
    }
}
