package app.muxway.android.protocol

/**
 * Pane content with colour. The hub sends tmux's escape sequences when asked (`ansi=1`);
 * this keeps the SGR ones (colours and attributes) and the links a program names (OSC 8),
 * drops every other sequence and control character, and turns the result into rows of
 * styled spans. Nothing else is interpreted.
 */
object Ansi {
    /** The terminal's default colour. */
    const val DEFAULT = -1

    /** Set on a 24-bit colour; below it the value is a palette index 0..255. */
    const val RGB = 1 shl 24

    /** The longest address a program may name for a link. */
    const val MAX_HREF = 2048

    data class Style(
        val fg: Int = DEFAULT,
        val bg: Int = DEFAULT,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val inverse: Boolean = false,
        /** The address a program named for this run of text (OSC 8); null for text. */
        val href: String? = null,
    ) {
        companion object {
            val PLAIN = Style()
        }
    }

    data class Span(val text: String, val style: Style)

    /**
     * Printable text, newlines, expanded tabs and SGR sequences, and with [links] the OSC 8
     * links whose address may be followed (each put back in one shape); everything else is dropped.
     */
    fun keep(s: String, links: Boolean = true): String {
        val out = StringBuilder(s.length)
        var col = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\u001b' -> {
                    val end = Input.escapeEnd(s, i).coerceAtMost(s.length)
                    if (isSgr(s, i, end)) out.append(s, i, end)
                    else if (links) link(s, i, end)?.let { out.append("\u001b]8;;").append(it).append("\u001b\\") }
                    i = end
                    continue
                }
                c == '\n' -> { out.append(c); col = 0 }
                c == '\t' -> { val n = 8 - col % 8; repeat(n) { out.append(' ') }; col += n }
                c >= ' ' && c != '\u007f' && (c < '\u0080' || c > '\u009f') -> { out.append(c); col++ }
            }
            i++
        }
        return out.toString()
    }

    /** The text without any sequence, for places that cannot show colour. */
    fun plain(s: String): String = Input.screen(s)

    /** Rows of spans; a row longer than [cols] (when above 0) is cut as the terminal shows it. */
    fun rows(s: String, cols: Int): List<List<Span>> {
        val rows = ArrayList<List<Span>>()
        var row = ArrayList<Span>()
        var run = StringBuilder()
        var style = Style.PLAIN
        var col = 0
        fun flush() {
            if (run.isNotEmpty()) { row.add(Span(run.toString(), style)); run = StringBuilder() }
        }
        fun endRow() {
            flush()
            rows.add(row)
            row = ArrayList()
            col = 0
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\u001b' -> {
                    val end = Input.escapeEnd(s, i).coerceAtMost(s.length)
                    val next = if (isSgr(s, i, end)) apply(style, s.substring(i + 2, end - 1)) else link(s, i, end)?.let { style.copy(href = it.ifEmpty { null }) } ?: style
                    if (next != style) { flush(); style = next }
                    i = end
                    continue
                }
                c == '\n' -> endRow()
                c >= ' ' && c != '\u007f' && (c < '\u0080' || c > '\u009f') -> {
                    if (cols > 0 && col >= cols) endRow()
                    run.append(c)
                    col++
                }
            }
            i++
        }
        flush()
        if (row.isNotEmpty() || rows.isEmpty()) rows.add(row)
        return rows
    }

    /** The address the span at column [col] (0-based) of [row] carries, if a program named one. */
    fun hrefAt(row: List<Span>, col: Int): String? {
        var at = 0
        for (s in row) {
            if (col < at + s.text.length) return if (col >= at) s.style.href else null
            at += s.text.length
        }
        return null
    }

    /**
     * An OSC 8 sequence's address: `ESC ] 8 ; <params> ; <address>` ended by BEL or ST. The
     * address when it is one the phone may open, "" when the sequence closes a link or names an
     * address it may not, null when this is no OSC 8 at all.
     */
    private fun link(s: String, start: Int, end: Int): String? {
        if (end - start < 5 || s[start + 1] != ']' || s[start + 2] != '8' || s[start + 3] != ';') return null
        var stop = end
        if (stop > start && s[stop - 1] == '\u0007') stop--
        else if (stop - 1 > start && s[stop - 1] == '\\' && s[stop - 2] == '\u001b') stop -= 2
        val semi = s.indexOf(';', start + 4)
        if (semi < 0 || semi >= stop) return ""
        return target(s.substring(semi + 1, stop)) ?: ""
    }

    /** [uri] when it is http or https, not overlong and free of control and space characters; else null. */
    fun target(uri: String): String? {
        if (uri.length > MAX_HREF || uri.any { it <= ' ' || it == '\u007f' || it in '\u0080'..'\u009f' }) return null
        val scheme = uri.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        return if (uri.length > scheme.length + 3) uri else null
    }

    /** `ESC [ <digits ; :> m` and nothing else. */
    private fun isSgr(s: String, start: Int, end: Int): Boolean {
        if (end - start < 3 || s[start + 1] != '[' || s[end - 1] != 'm') return false
        for (j in start + 2 until end - 1) {
            val c = s[j]
            if (c !in '0'..'9' && c != ';' && c != ':') return false
        }
        return true
    }

    private fun apply(style: Style, params: String): Style {
        var st = style
        val parts = if (params.isEmpty()) listOf("") else params.split(';')
        var i = 0
        while (i < parts.size) {
            val part = parts[i]
            if (part.contains(':')) {
                // Colon form: 38:5:n, 38:2:r:g:b or 38:2::r:g:b (with a colour space id).
                val sub = part.split(':').map { num(it) }
                val code = sub[0]
                if ((code == 38 || code == 48) && sub.size >= 2) {
                    val colour = when {
                        sub[1] == 5 && sub.size >= 3 -> sub[2].coerceIn(0, 255)
                        sub[1] == 2 && sub.size >= 6 -> rgb(sub[3], sub[4], sub[5])
                        sub[1] == 2 && sub.size >= 5 -> rgb(sub[2], sub[3], sub[4])
                        else -> null
                    }
                    if (colour != null) st = if (code == 38) st.copy(fg = colour) else st.copy(bg = colour)
                }
                i++
                continue
            }
            val n = num(part)
            when (n) {
                0 -> st = Style.PLAIN.copy(href = st.href)
                1 -> st = st.copy(bold = true)
                2 -> st = st.copy(dim = true)
                3 -> st = st.copy(italic = true)
                4 -> st = st.copy(underline = true)
                7 -> st = st.copy(inverse = true)
                22 -> st = st.copy(bold = false, dim = false)
                23 -> st = st.copy(italic = false)
                24 -> st = st.copy(underline = false)
                27 -> st = st.copy(inverse = false)
                in 30..37 -> st = st.copy(fg = n - 30)
                39 -> st = st.copy(fg = DEFAULT)
                in 40..47 -> st = st.copy(bg = n - 40)
                49 -> st = st.copy(bg = DEFAULT)
                in 90..97 -> st = st.copy(fg = n - 90 + 8)
                in 100..107 -> st = st.copy(bg = n - 100 + 8)
                38, 48 -> {
                    val mode = parts.getOrNull(i + 1)?.let { num(it) }
                    val colour = when (mode) {
                        5 -> parts.getOrNull(i + 2)?.let { num(it).coerceIn(0, 255) }.also { i += 2 }
                        2 -> if (i + 4 < parts.size) rgb(num(parts[i + 2]), num(parts[i + 3]), num(parts[i + 4])).also { i += 4 } else { i = parts.size; null }
                        else -> null
                    }
                    if (colour != null) st = if (n == 38) st.copy(fg = colour) else st.copy(bg = colour)
                }
            }
            i++
        }
        return st
    }

    private fun rgb(r: Int, g: Int, b: Int) = RGB or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** A parameter as a small non-negative number; empty is 0, anything odd is 0. */
    private fun num(s: String): Int = if (s.isEmpty() || s.length > 5 || !s.all { it in '0'..'9' }) 0 else s.toInt()
}
