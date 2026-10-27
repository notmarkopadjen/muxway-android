package app.muxway.android.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Text
import app.muxway.android.protocol.Keys
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The tap target of a row: asks the phone to open the pane, says how it went, and leaves. */
class OpenPaneActivity : ComponentActivity() {
    private var line by mutableStateOf("opening…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val address = intent.getStringExtra(ADDRESS)?.takeIf(Keys::valid)
        setContent {
            Box(Modifier.fillMaxSize().background(Palette.bg).padding(16.dp), contentAlignment = Alignment.Center) {
                Text(line, color = Palette.ink, fontFamily = Palette.mono, textAlign = TextAlign.Center)
            }
        }
        lifecycleScope.launch {
            line = if (address == null) "no such pane" else Phone.open(this@OpenPaneActivity, address)
            delay(1400)
            finish()
        }
    }

    companion object {
        const val ADDRESS = "address"
    }
}
