package app.muxway.android.wear

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import app.muxway.android.protocol.Ansi

/** ANSI colour codes to colours, as the phone's dark palette has them: 16, xterm's cube and greys, true colour. */
object Colours {
    private val ansi = listOf(
        0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
        0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
    ).map { Color(0xFF000000 or it.toLong()) }

    /** Null for the terminal's default. */
    fun resolve(code: Int): Color? = when {
        code == Ansi.DEFAULT -> null
        code and Ansi.RGB != 0 -> Color(0xFF000000.toInt() or (code and 0xFFFFFF))
        code < 16 -> ansi[code]
        code < 232 -> {
            val n = code - 16
            Color(level(n / 36), level(n / 6 % 6), level(n % 6))
        }
        else -> (8 + (code - 232) * 10).let { Color(it, it, it) }
    }

    /** A span's style on the watch; the default ink is the palette's, dimmed rows stay dim. */
    fun style(s: Ansi.Style, ink: Color): SpanStyle {
        var fg = resolve(s.fg) ?: ink
        var bg = resolve(s.bg)
        if (s.inverse) { val t = fg; fg = bg ?: Palette.bg; bg = t }
        if (s.dim) fg = fg.copy(alpha = 0.6f)
        return SpanStyle(
            color = fg,
            background = bg ?: Color.Unspecified,
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            textDecoration = if (s.underline) TextDecoration.Underline else null,
        )
    }

    private fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
}
