package app.muxway.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

/**
 * The hubs this phone knows, every one with a token in use, and the form that adds or changes one; then the
 * preferences. With no hub yet it is the form alone, as the first screen.
 */
@Composable
fun ConnectScreen(model: AppModel) {
    val p = LocalPalette.current
    val saved = model.settings
    // The form edits one hub, or with null adds a new one.
    var editing by rememberSaveable { mutableStateOf(saved.hubs.firstOrNull()?.id) }
    val hub = saved.hubs.firstOrNull { it.id == editing }
    var name by rememberSaveable(editing) { mutableStateOf(hub?.name ?: "") }
    var url by rememberSaveable(editing) { mutableStateOf(hub?.url ?: "") }
    // Not saveable: a half-pasted token must not land in the instance state Bundle.
    var token by remember(editing) { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val stored = hub != null && model.hasTokenFor(hub.id)

    var refresh by rememberSaveable { mutableStateOf(saved.refreshSeconds.toString()) }
    var lines by rememberSaveable { mutableStateOf(saved.lines.toString()) }
    var textSp by rememberSaveable { mutableStateOf(saved.textSp.toString()) }
    var theme by rememberSaveable { mutableStateOf(saved.theme) }
    var block by rememberSaveable { mutableStateOf(saved.blockScreenshots) }
    var hardware by rememberSaveable { mutableStateOf(saved.hardwareKeyboard) }
    var effects by rememberSaveable { mutableStateOf(saved.effects) }
    var reflow by rememberSaveable { mutableStateOf(saved.reflow) }
    var watch by rememberSaveable { mutableStateOf(saved.watch) }
    var notifyText by rememberSaveable { mutableStateOf(saved.notifyText) }
    var watchAnswer by rememberSaveable { mutableStateOf(saved.watchAnswer) }
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun saveHub() {
        problem = model.saveHub(editing, name, url, token)
        if (problem == null) {
            token = ""
            editing = editing ?: model.settings.hubs.lastOrNull()?.id
        }
    }

    fun saveSettings() {
        model.saveSettings(
            saved.copy(
                refreshSeconds = refresh.toIntOrNull()?.coerceIn(1, 60) ?: saved.refreshSeconds,
                lines = lines.toIntOrNull()?.coerceIn(10, 2000) ?: saved.lines,
                textSp = textSp.toIntOrNull()?.coerceIn(6, 24) ?: saved.textSp,
                theme = theme,
                blockScreenshots = block,
                hardwareKeyboard = hardware,
                effects = effects,
                reflow = reflow,
                watch = watch,
                notifyText = notifyText,
                watchAnswer = watchAnswer,
            ),
        )
        if (model.hasToken) model.back()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp)) {
        Frame("muxway", crumb = "connect") {
            if (model.hasToken) Btn("panes", { model.back() })
        }
        Spacer(Modifier.height(8.dp))
        model.notice?.let { Text("! $it", color = p.alert, modifier = Modifier.padding(vertical = 4.dp)) }
        if (saved.hubs.isNotEmpty()) {
            Panel("hubs", Modifier.padding(vertical = 4.dp), actions = {
                Text("+ hub", color = p.dim, maxLines = 1, modifier = Modifier.clickable { editing = null; problem = null })
            }) {
                for (h in saved.hubs) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (model.hasTokenFor(h.id)) "▸ " else "  ", color = p.accent, maxLines = 1)
                        Text(h.name, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (model.hasTokenFor(h.id)) h.url else "${h.url} · no token", color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f))
                        Btn("edit", { editing = h.id; problem = null }, selected = editing == h.id)
                        Btn("×", { if (editing == h.id) editing = null; model.removeHub(h.id) })
                    }
                }
            }
        }
        Text(if (hub == null) "new hub" else "hub ${hub.name}", color = p.dim, modifier = Modifier.padding(top = 6.dp))
        Field(
            "name  ", name, { name = it.lowercase().take(32) }, hint = "home (from the address when empty)",
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
        )
        Field("hub   ", url, { url = it }, hint = "https://hub.example.com", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Field(
            "token ", token, { token = it }, secret = true,
            hint = if (stored) "stored · paste to replace" else "mxt_…",
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
        problem?.let { Text("! $it", color = p.alert, modifier = Modifier.padding(vertical = 4.dp)) }
        Btn("save and connect", ::saveHub)
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
        Toggle("watch and notifications: follow the panes in the background, tell the watch, notify when one waits", watch) {
            watch = it
            if (it && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        Toggle("notification text: show what a pane asks on the phone and the watch (bystanders can read it)", notifyText) { notifyText = it }
        Toggle("watch may answer: the watch can send Enter, Esc, y, n, ^C and a spoken line to a pane while this phone is locked", watchAnswer) { watchAnswer = it }
        Rule(Modifier.padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Btn("save settings", ::saveSettings)
        }
        for (c in model.adminHubs) AdminSection(model, c)
        Spacer(Modifier.height(12.dp))
        Text("  https only. plain http reaches localhost alone.\n  each hub's token is kept in the keystore and never shown again.", color = p.dim)
    }
}
