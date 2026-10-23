package app.muxway.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.muxway.android.AppModel
import app.muxway.android.Screen

@Composable
fun App(model: AppModel) {
    MuxwayTheme(model.settings.theme, model.settings.textSp) {
        val p = LocalPalette.current
        Surface(Modifier.fillMaxSize(), color = p.bg) {
            Box(Modifier.fillMaxSize().background(p.bg).safeDrawingPadding()) {
                BackHandler(enabled = model.screen !is Screen.Panes) { model.back() }
                when (val s = model.screen) {
                    is Screen.Connect -> ConnectScreen(model)
                    is Screen.Panes -> PanesScreen(model)
                    is Screen.View -> ViewScreen(model, s.addresses)
                }
            }
        }
    }
}
