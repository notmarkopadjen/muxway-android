package app.muxway.android.wear

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

/** The phone app's amber palette, the only one the watch app draws in; the face has all three. */
object Palette {
    val bg = Color(0xFF000000)
    val ink = Color(0xFFFFB000)
    val dim = Color(0xFFA06C00)
    val faint = Color(0xFF4A3200)
    val attention = Color(0xFFFFD36B)
    val alert = Color(0xFFFF3B3B)

    const val INK = 0xFFFFB000.toInt()
    const val DIM = 0xFFA06C00.toInt()
    const val FAINT = 0xFF4A3200.toInt()
    const val ATTENTION = 0xFFFFD36B.toInt()

    val mono = FontFamily(Font(R.font.dejavu_sans_mono))
    val pixel = FontFamily(Font(R.font.press_start_2p))

    fun forValue(value: Int): Color = when (value) { 2 -> attention; 1 -> ink; else -> dim }
    fun argbForValue(value: Int): Int = when (value) { 2 -> ATTENTION; 1 -> INK; else -> DIM }
}
