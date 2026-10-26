package app.muxway.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.muxway.android.AppModel
import app.muxway.android.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun App(model: AppModel) {
    // While connected and in the foreground: a long poll on events, and a re-list every 15 s.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connected = model.screen !is Screen.Connect
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch { model.followEvents() }
            while (true) {
                model.refreshList()
                delay(15_000)
            }
        }
    }
    val effects = model.settings.effects
    MuxwayTheme(model.settings.theme, model.settings.textSp, effects) {
        val p = LocalPalette.current
        // The tube powers on once: a line that opens up to the full picture.
        val power = remember { Animatable(if (effects) 0.004f else 1f) }
        LaunchedEffect(Unit) { power.animateTo(1f, tween(900, easing = CubicBezierEasing(0.1f, 1f, 0.3f, 1f))) }
        Surface(Modifier.fillMaxSize(), color = p.bg) {
            Box(Modifier.fillMaxSize().background(p.bg).then(if (effects) Modifier.crt(p) else Modifier)) {
                Box(Modifier.fillMaxSize().graphicsLayer { scaleY = power.value }.safeDrawingPadding()) {
                    BackHandler(enabled = model.screen !is Screen.Panes) { model.back() }
                    when (val s = model.screen) {
                        is Screen.Connect -> ConnectScreen(model)
                        is Screen.Panes -> PanesScreen(model)
                        is Screen.Scan -> ScanScreen(model)
                        is Screen.Share -> ShareScreen(model)
                        is Screen.View -> ViewScreen(model, s.addresses)
                    }
                }
            }
        }
    }
}

/** Scanlines and a vignette over everything, drawn, not composed, so they cost nothing to lay out. */
private fun Modifier.crt(p: Palette): Modifier = drawWithContent {
    drawContent()
    val period = 3 * density
    drawRect(
        Brush.verticalGradient(
            colorStops = arrayOf(0f to Color.Transparent, 0.66f to Color.Transparent, 0.67f to p.scan, 1f to p.scan),
            startY = 0f, endY = period, tileMode = TileMode.Repeated,
        ),
    )
    drawRect(Brush.radialGradient(colorStops = arrayOf(0.55f to Color.Transparent, 1f to p.vignette), center = center, radius = size.maxDimension * 0.75f))
}
