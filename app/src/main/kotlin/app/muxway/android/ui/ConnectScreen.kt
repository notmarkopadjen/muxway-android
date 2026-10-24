package app.muxway.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.muxway.android.AppModel
import app.muxway.android.Screen
import app.muxway.android.store.Theme

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Btn(if (on) "[x]" else "[ ]", { onChange(!on) })
        Text(" $label", color = LocalPalette.current.ink)
    }
}

@Composable
fun ConnectScreen(model: AppModel) {
    val p = LocalPalette.current
    val saved = model.settings
    var url by rememberSaveable { mutableStateOf(saved.hubUrl) }
    var token by rememberSaveable { mutableStateOf("") }
    var refresh by rememberSaveable { mutableStateOf(saved.refreshSeconds.toString()) }
    var lines by rememberSaveable { mutableStateOf(saved.lines.toString()) }
    var textSp by rememberSaveable { mutableStateOf(saved.textSp.toString()) }
    var theme by rememberSaveable { mutableStateOf(saved.theme) }
    var block by rememberSaveable { mutableStateOf(saved.blockScreenshots) }
    var hardware by rememberSaveable { mutableStateOf(saved.hardwareKeyboard) }
    var effects by rememberSaveable { mutableStateOf(saved.effects) }
    var reflow by rememberSaveable { mutableStateOf(saved.reflow) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }

    fun save() {
        val s = saved.copy(
            hubUrl = url,
            refreshSeconds = refresh.toIntOrNull()?.coerceIn(1, 60) ?: saved.refreshSeconds,
            lines = lines.toIntOrNull()?.coerceIn(10, 2000) ?: saved.lines,
            textSp = textSp.toIntOrNull()?.coerceIn(6, 24) ?: saved.textSp,
            theme = theme,
            blockScreenshots = block,
            hardwareKeyboard = hardware,
            effects = effects,
            reflow = reflow,
        )
        problem = model.save(s, token)
        if (problem == null) token = ""
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp)) {
        Frame("muxway", crumb = "connect") {
            if (model.hasToken && saved.hubUrl.isNotEmpty()) Btn("panes", { model.back() })
        }
        Spacer(Modifier.height(8.dp))
        model.notice?.let { Text("! $it", color = p.alert, modifier = Modifier.padding(vertical = 4.dp)) }
        Field("hub   ", url, { url = it }, hint = "https://hub.example.com", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Field(
            "token ", token, { token = it }, secret = true,
            hint = if (model.hasToken) "stored · paste to replace" else "mxt_…",
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Btn("scan code", { model.open(Screen.Scan) })
            Text(" or paste a token from the hub's owner", color = p.dim)
        }
        Text(
            "  muxway hub token create --name phone \\\n    --read 'alice-laptop/*' --write 'alice-laptop/@build' \\\n    | scripts/connect-qr.py https://hub.example.com",
            color = p.dim,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Rule(Modifier.padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth()) {
            Field("every ", refresh, { refresh = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), hint = "2 s", keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
            Field(" lines", lines, { lines = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), hint = "200", keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
            Field(" text ", textSp, { textSp = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), hint = "12", keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("theme  ", color = p.dim)
            for (t in Theme.entries) Btn(t.name.lowercase(), { theme = t }, selected = theme == t)
        }
        Toggle("block screenshots of panes", block) { block = it }
        Toggle("assume a hardware keyboard: the on-screen one stays down until asked", hardware) { hardware = it }
        Toggle("effects: glow, scanlines, motion", effects) { effects = it }
        Toggle("reflow long rows to the screen when fit is off (else scale the pane's width)", reflow) { reflow = it }
        Rule(Modifier.padding(vertical = 6.dp))
        problem?.let { Text("! $it", color = p.alert, modifier = Modifier.padding(vertical = 4.dp)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Btn("save and connect", ::save)
            if (model.hasToken) Btn("forget token", { model.forgetToken() })
        }
        Spacer(Modifier.height(12.dp))
        Text("  https only. plain http reaches localhost alone.\n  the token is kept in the keystore and never shown again.", color = p.dim)
    }
}
