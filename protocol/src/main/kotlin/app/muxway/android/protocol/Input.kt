package app.muxway.android.protocol

/** What a client may type: text without control characters, or one of these keys. */
object Input {
    const val MAX_TEXT_BYTES = 4096

    /** Protocol name to label, in the order the key row shows them. */
    val KEYS: List<Pair<String, String>> = listOf(
        "Enter" to "Enter", "Tab" to "Tab", "BTab" to "S-Tab", "Escape" to "Esc", "C-c" to "^C",
        "Up" to "↑", "Down" to "↓", "Left" to "←", "Right" to "→",
        "BSpace" to "BS", "C-d" to "^D", "C-z" to "^Z", "C-l" to "^L",
        "PageUp" to "PgUp", "PageDown" to "PgDn", "Home" to "Home", "End" to "End",
    )

    private val names = KEYS.map { it.first }.toSet()

    /** Mouse buttons the protocol names. */
    val BUTTONS = setOf("left", "middle", "right", "wheelup", "wheeldown")

    fun isKey(name: String) = name in names

    /** Drops C0, DEL and C1; the hub refuses them. */
    fun printable(s: String): String = clean(s, keepNewline = false, tabs = false)

    /** What the input line sends: printable, the newline kept; the hub pastes a text with one. */
    fun typed(s: String): String = clean(s, keepNewline = true, tabs = false)

    /** Keeps newlines, turns tabs into spaces, drops every other control character. */
    fun screen(s: String): String = clean(s, keepNewline = true, tabs = true)

    private fun clean(s: String, keepNewline: Boolean, tabs: Boolean): String {
        val out = StringBuilder(s.length)
        var col = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\u001b' -> { i = escapeEnd(s, i); continue }
                c == '\n' && keepNewline -> { out.append(c); col = 0 }
                c == '\t' && tabs -> { val n = 8 - col % 8; repeat(n) { out.append(' ') }; col += n }
                c >= ' ' && c != '\u007f' && (c < '\u0080' || c > '\u009f') -> { out.append(c); col++ }
            }
            i++
        }
        return out.toString()
    }

    /** Index after an escape sequence starting at [i]: CSI to its final byte, OSC to BEL or ST, else past the final byte. */
    internal fun escapeEnd(s: String, i: Int): Int {
        if (i + 1 >= s.length) return s.length
        return when (s[i + 1]) {
            '[' -> {
                var j = i + 2
                while (j < s.length && s[j] !in '@'..'~') j++
                j + 1
            }
            ']' -> {
                var j = i + 2
                while (j < s.length && s[j] != '\u0007' && !(s[j] == '\u001b' && j + 1 < s.length && s[j + 1] == '\\')) j++
                if (j < s.length && s[j] == '\u001b') j + 2 else j + 1
            }
            else -> {
                // ESC, any intermediates (0x20..0x2F), one final byte.
                var j = i + 1
                while (j < s.length && s[j] in ' '..'/') j++
                j + 1
            }
        }
    }

    /** Cuts rows longer than cols into pieces; cols of 0 leaves them. */
    fun wrap(rows: List<String>, cols: Int): List<String> {
        if (cols <= 0) return rows
        val out = ArrayList<String>(rows.size)
        for (row in rows) {
            var r = row
            while (r.length > cols) {
                out.add(r.substring(0, cols))
                r = r.substring(cols)
            }
            out.add(r)
        }
        return out
    }

    fun utf8Length(s: String) = s.toByteArray(Charsets.UTF_8).size
}
