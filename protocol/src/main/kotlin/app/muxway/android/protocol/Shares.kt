package app.muxway.android.protocol

import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Random

/**
 * A pane shared read-only by this token: the link, when it ends, how many watch. [password] is
 * in the create answer alone and empty everywhere else; the hub keeps only its hash.
 */
data class Share(val id: String, val url: String, val password: String, val expires: Long, val host: String, val paneId: String, val address: String, val watching: Int)

/**
 * Sharing a pane read-only, the web's helpers: the password the form suggests, the lifetimes
 * offered, which shares are a pane's, and when one ends.
 */
object Shares {
    /** A share's id as the hub mints it, the only shape that goes into a path. */
    val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    const val MIN_PASSWORD = 8
    const val MAX_PASSWORD = 64
    const val MIN_TTL = 60
    const val MAX_TTL = 604800
    const val MAX_URL = 1024

    /** The lifetimes offered, label and seconds. */
    val LIFETIMES = listOf("1 h" to 3600, "24 h" to 86400, "7 d" to 604800)

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private val random = SecureRandom()

    /** Twelve letters and digits from `a-z2-7` in three groups, as the hub would make them: 60 bits. */
    fun suggest(rnd: Random = random): String {
        val out = StringBuilder()
        for (i in 0 until 12) {
            if (i > 0 && i % 4 == 0) out.append('-')
            out.append(ALPHABET[rnd.nextInt(ALPHABET.length)])
        }
        return out.toString()
    }

    /** Whether the hub would take the password: 8 to 64 printable characters. */
    fun validPassword(p: String): Boolean = p.length in MIN_PASSWORD..MAX_PASSWORD && Input.printable(p) == p

    /** The live shares of one pane, by host and pane id, so they follow it when it moves. */
    fun of(shares: List<Share>, pane: Pane): List<Share> = shares.filter { it.host == pane.host && it.paneId == pane.paneId }

    /** When a share ends, in the device's zone: `Nov 5, 10:12`. */
    fun until(expires: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ROOT).format(Instant.ofEpochSecond(expires).atZone(zone))
}
