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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.muxway.android.store.Theme

/** A terminal palette: ink on a background, a dim ink for chrome, two signal colours. */
data class Palette(
    val bg: Color,
    val ink: Color,
    val dim: Color,
    val panel: Color,
    val attention: Color,
    val alert: Color,
    val light: Boolean,
)

val Green = Palette(Color(0xFF000000), Color(0xFF33FF33), Color(0xFF1E8F1E), Color(0xFF071007), Color(0xFFFFB000), Color(0xFFFF5555), false)
val Amber = Palette(Color(0xFF000000), Color(0xFFFFB000), Color(0xFF8A6000), Color(0xFF100C00), Color(0xFF33FF33), Color(0xFFFF5555), false)
val Paper = Palette(Color(0xFFF4EFE4), Color(0xFF1A1A1A), Color(0xFF7A7367), Color(0xFFE9E2D3), Color(0xFFB35C00), Color(0xFFA40000), true)

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
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = textSp.sp, lineHeight = (textSp * 1.25f).sp, color = p.ink)
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(square, square, square, square, square),
            typography = Typography(bodyLarge = body, bodyMedium = body, bodySmall = body, labelLarge = body, titleMedium = body),
            content = content,
        )
    }
}
