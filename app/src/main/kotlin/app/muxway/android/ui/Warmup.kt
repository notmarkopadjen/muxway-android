package app.muxway.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** How long the warm-up plays once the tile's first frame is in, the web's. */
const val WARMUP_MS = 600

/**
 * A tile with nothing of its own to draw yet warms up like the tube: a bright line across the
 * middle of the box holds, lit, until the first frame; then it opens into the box with a bloom
 * and settles through scanlines, fading over [WARMUP_MS], the frame coming up under it. In the
 * theme's ink; nothing with effects off, where the box is blank until the frame.
 */
@Composable
fun Warmup(hasOwn: Boolean, modifier: Modifier = Modifier) {
    if (!LocalEffects.current) return
    val p = LocalPalette.current
    val t = remember { Animatable(0f) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(hasOwn) {
        if (!hasOwn) return@LaunchedEffect
        t.animateTo(1f, tween(WARMUP_MS, easing = CubicBezierEasing(0.1f, 1f, 0.3f, 1f)))
        done = true
    }
    if (done) return
    val v = t.value
    Canvas(modifier.testTag("warmup").semantics { contentDescription = if (hasOwn) "warm:0" else "warm:1" }) {
        val row = 2.dp.toPx()
        // The band opens to the box by 30 %; its alpha falls from 1 to 0.9, then to 0.45 at 60 %, to 0 at the end.
        val open = (v / 0.3f).coerceIn(0f, 1f)
        val alpha = when {
            v < 0.3f -> 1f - 0.1f * open
            v < 0.6f -> 0.9f - 0.45f * ((v - 0.3f) / 0.3f)
            else -> 0.45f * (1f - (v - 0.6f) / 0.4f)
        }.coerceIn(0f, 1f)
        // The bloom: the ink lit toward white at the start, settling to the ink itself.
        val lit = lerp(p.ink, Color.White, (0.75f * (1f - v / 0.6f)).coerceIn(0f, 0.75f))
        val h = max(row, size.height * open)
        val top = (size.height - h) / 2
        var y = top
        var i = 0
        while (y < top + h) {
            val share = if (i % 2 == 0) 0.55f else 0.25f
            drawRect(lit.copy(alpha = alpha * share), Offset(0f, y), Size(size.width, min(row, top + h - y)))
            y += row
            i++
        }
        // The line itself, brightest while the band is still a line.
        if (open < 1f) drawRect(lit.copy(alpha = alpha), Offset(0f, (size.height - row) / 2), Size(size.width, row))
    }
}
