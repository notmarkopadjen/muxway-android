package app.muxway.android.protocol

/**
 * Pane content with colour. The hub sends tmux's escape sequences when asked (`ansi=1`);
 * this keeps the SGR ones (colours and attributes), drops every other sequence and control
 * character, and turns the result into rows of styled spans. Nothing else is interpreted.
 */
object Ansi {
    /** The terminal's default colour. */
    const val DEFAULT = -1

    /** Set on a 24-bit colour; below it the value is a palette index 0..255. */
    const val RGB = 1 shl 24

    data class Style(
        val fg: Int = DEFAULT,
        val bg: Int = DEFAULT,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val inverse: Boolean = false,
    ) {
        companion object {
            val PLAIN = Style()
        }
    }

    data class Span(val text: String, val style: Style)

    /** Printable text, newlines, expanded tabs and SGR sequences; everything else is dropped. */
    fun keep(s: String): String {
        val out = StringBuilder(s.length)
        var col = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\u001b' -> {
                    val end = Input.escapeEnd(s, i).coerceAtMost(s.length)
                    if (isSgr(s, i, end)) out.append(s, i, end)
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
                    if (isSgr(s, i, end)) {
                        val next = apply(style, s.substring(i + 2, end - 1))
                        if (next != style) { flush(); style = next }
                    }
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
                0 -> st = Style.PLAIN
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
