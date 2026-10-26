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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.muxway.android.R
import app.muxway.android.store.Theme

/**
 * A terminal palette, the same tokens as the web client: ink on a background, a raised panel,
 * dim and faint inks for chrome, an accent, two signal colours, the glow, the overlays and the
 * 16 ANSI colours.
 */
data class Palette(
    val bg: Color,
    val panel: Color,
    val ink: Color,
    val dim: Color,
    val faint: Color,
    val accent: Color,
    val attention: Color,
    val alert: Color,
    val claude: Color,
    val glow: Color,
    val scan: Color,
    val vignette: Color,
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

val Amber = Palette(
    bg = Color(0xFF0A0603), panel = Color(0xFF140B05), ink = Color(0xFFFFB000), dim = Color(0xFFA06C00), faint = Color(0xFF4A3200),
    accent = Color(0xFFFFD36B), attention = Color(0xFFFFB000), alert = Color(0xFFFF3B3B), claude = Color(0xFFD97757), glow = Color(0xFFFFB000),
    scan = Color(0f, 0f, 0f, 0.22f), vignette = Color(0f, 0f, 0f, 0.55f), light = false, ansi = darkAnsi,
)
val Green = Palette(
    bg = Color(0xFF050806), panel = Color(0xFF0A120C), ink = Color(0xFF39FF14), dim = Color(0xFF1F8F14), faint = Color(0xFF0F4A0C),
    accent = Color(0xFFB6FF9E), attention = Color(0xFF39FF14), alert = Color(0xFFFF3B3B), claude = Color(0xFFD97757), glow = Color(0xFF39FF14),
    scan = Color(0f, 0f, 0f, 0.22f), vignette = Color(0f, 0f, 0f, 0.55f), light = false, ansi = darkAnsi,
)
val Paper = Palette(
    bg = Color(0xFFF1EAD8), panel = Color(0xFFE8DFC8), ink = Color(0xFF1F2A1F), dim = Color(0xFF5A6A5A), faint = Color(0xFFB9B39F),
    accent = Color(0xFF0B5D1E), attention = Color(0xFF1F2A1F), alert = Color(0xFFA3161B), claude = Color(0xFFD97757), glow = Color(0xFF1F2A1F),
    scan = Color(0f, 0f, 0f, 0.04f), vignette = Color(0f, 0f, 0f, 0.08f), light = true, ansi = paperAnsi,
)

val LocalPalette = staticCompositionLocalOf { Amber }

/** Whether glow, scanlines and motion are drawn. */
val LocalEffects = staticCompositionLocalOf { true }

fun Theme.palette() = when (this) {
    Theme.AMBER -> Amber
    Theme.GREEN -> Green
    Theme.PAPER -> Paper
}

/** Bundled, so box drawing and block glyphs line up; what it lacks falls back to the system. */
val Mono = FontFamily(Font(R.font.dejavu_sans_mono))

/** The web client's pixel font, for the brand and panel titles. */
val Pixel = FontFamily(Font(R.font.press_start_2p))

/** The soft text glow of a phosphor screen; none on paper or with effects off. */
@Composable
fun textGlow(strong: Boolean = false): Shadow? {
    val p = LocalPalette.current
    if (!LocalEffects.current || p.light) return null
    return Shadow(p.glow.copy(alpha = if (strong) 0.9f else 0.45f), blurRadius = if (strong) 10f else 4f)
}

private val square = RoundedCornerShape(CornerSize(0))

@Composable
fun MuxwayTheme(theme: Theme, textSp: Int, effects: Boolean, content: @Composable () -> Unit) {
    val p = theme.palette()
    val scheme: ColorScheme = (if (p.light) lightColorScheme() else darkColorScheme()).copy(
        primary = p.ink, onPrimary = p.bg, secondary = p.dim, onSecondary = p.bg,
        background = p.bg, onBackground = p.ink, surface = p.bg, onSurface = p.ink,
        surfaceVariant = p.panel, onSurfaceVariant = p.ink, outline = p.dim, error = p.alert,
    )
    val glow = if (effects && !p.light) Shadow(p.glow.copy(alpha = 0.35f), blurRadius = 3f) else null
    val body = TextStyle(fontFamily = Mono, fontSize = textSp.sp, lineHeight = (textSp * 1.25f).sp, color = p.ink, shadow = glow)
    CompositionLocalProvider(LocalPalette provides p, LocalEffects provides effects) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(square, square, square, square, square),
            typography = Typography(bodyLarge = body, bodyMedium = body, bodySmall = body, labelLarge = body, titleMedium = body),
            content = content,
        )
    }
}
