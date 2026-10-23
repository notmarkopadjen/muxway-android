package app.muxway.android.ui

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.muxway.android.R
import app.muxway.android.store.Theme

/** A terminal palette: ink on a background, a dim ink for chrome, two signal colours, and the 16 ANSI colours. */
data class Palette(
    val bg: Color,
    val ink: Color,
    val dim: Color,
    val panel: Color,
    val attention: Color,
    val alert: Color,
    val light: Boolean,
    val ansi: List<Color>,
)

private fun colours(vararg rgb: Long) = rgb.map { Color(0xFF000000 or it) }

/** The 16 ANSI colours for a dark background. */
private val darkAnsi = colours(
    0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
    0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
)

/** The same for paper: darker, so they stay readable. */
private val paperAnsi = colours(
    0x000000, 0xCD3131, 0x107C10, 0x949800, 0x0451A5, 0xBC05BC, 0x0598BC, 0x555555,
    0x666666, 0xCD3131, 0x1A8A1A, 0xB5BA00, 0x0451A5, 0xBC05BC, 0x0598BC, 0xA5A5A5,
)

val Green = Palette(Color(0xFF000000), Color(0xFF33FF33), Color(0xFF1E8F1E), Color(0xFF071007), Color(0xFFFFB000), Color(0xFFFF5555), false, darkAnsi)
val Amber = Palette(Color(0xFF000000), Color(0xFFFFB000), Color(0xFF8A6000), Color(0xFF100C00), Color(0xFF33FF33), Color(0xFFFF5555), false, darkAnsi)
val Paper = Palette(Color(0xFFF4EFE4), Color(0xFF1A1A1A), Color(0xFF7A7367), Color(0xFFE9E2D3), Color(0xFFB35C00), Color(0xFFA40000), true, paperAnsi)

/** Bundled, so box drawing and block glyphs line up; what it lacks falls back to the system. */
val Mono = FontFamily(Font(R.font.dejavu_sans_mono))

val LocalPalette = staticCompositionLocalOf { Green }

fun Theme.palette() = when (this) {
    Theme.GREEN -> Green
    Theme.AMBER -> Amber
    Theme.PAPER -> Paper
}

private val square = RoundedCornerShape(CornerSize(0))

@Composable
fun MuxwayTheme(theme: Theme, textSp: Int, content: @Composable () -> Unit) {
    val p = theme.palette()
    val scheme: ColorScheme = (if (p.light) lightColorScheme() else darkColorScheme()).copy(
        primary = p.ink, onPrimary = p.bg, secondary = p.dim, onSecondary = p.bg,
        background = p.bg, onBackground = p.ink, surface = p.bg, onSurface = p.ink,
        surfaceVariant = p.panel, onSurfaceVariant = p.ink, outline = p.dim, error = p.alert,
    )
    val body = TextStyle(fontFamily = Mono, fontSize = textSp.sp, lineHeight = (textSp * 1.25f).sp, color = p.ink)
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(square, square, square, square, square),
            typography = Typography(bodyLarge = body, bodyMedium = body, bodySmall = body, labelLarge = body, titleMedium = body),
            content = content,
        )
    }
}
