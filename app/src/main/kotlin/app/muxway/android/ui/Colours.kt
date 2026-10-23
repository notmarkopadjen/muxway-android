package app.muxway.android.ui

import androidx.compose.ui.graphics.Color
import app.muxway.android.protocol.Ansi

/** ANSI colour codes to colours: the theme's 16, then xterm's cube and grey ramp, then true colour. */
object Colours {
    /** Null for the terminal's default. */
    fun resolve(code: Int, p: Palette): Color? = when {
        code == Ansi.DEFAULT -> null
        code and Ansi.RGB != 0 -> Color(0xFF000000.toInt() or (code and 0xFFFFFF))
        code < 16 -> p.ansi[code]
        code < 232 -> {
            val n = code - 16
            Color(level(n / 36), level(n / 6 % 6), level(n % 6))
        }
        else -> (8 + (code - 232) * 10).let { Color(it, it, it) }
    }

    private fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
}
