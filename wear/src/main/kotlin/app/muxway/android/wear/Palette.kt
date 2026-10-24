package app.muxway.android.wear

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

/** The phone app's green palette, the only one the watch app draws in; the face has all three. */
object Palette {
    val bg = Color(0xFF000000)
    val ink = Color(0xFF39FF14)
    val dim = Color(0xFF1F8F14)
    val faint = Color(0xFF0F4A0C)
    val attention = Color(0xFFFFB000)
    val alert = Color(0xFFFF3B3B)

    const val INK = 0xFF39FF14.toInt()
    const val DIM = 0xFF1F8F14.toInt()
    const val FAINT = 0xFF0F4A0C.toInt()
    const val ATTENTION = 0xFFFFB000.toInt()

    val mono = FontFamily(Font(R.font.dejavu_sans_mono))
    val pixel = FontFamily(Font(R.font.press_start_2p))

    fun forValue(value: Int): Color = when (value) { 2 -> attention; 1 -> ink; else -> dim }
    fun argbForValue(value: Int): Int = when (value) { 2 -> ATTENTION; 1 -> INK; else -> DIM }
}
