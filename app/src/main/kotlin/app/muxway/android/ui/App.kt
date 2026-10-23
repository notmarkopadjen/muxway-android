package app.muxway.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
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
    MuxwayTheme(model.settings.theme, model.settings.textSp) {
        val p = LocalPalette.current
        Surface(Modifier.fillMaxSize(), color = p.bg) {
            Box(Modifier.fillMaxSize().background(p.bg).safeDrawingPadding()) {
                BackHandler(enabled = model.screen !is Screen.Panes) { model.back() }
                when (val s = model.screen) {
                    is Screen.Connect -> ConnectScreen(model)
                    is Screen.Panes -> PanesScreen(model)
                    is Screen.Scan -> ScanScreen(model)
                    is Screen.View -> ViewScreen(model, s.addresses)
                }
            }
        }
    }
}
