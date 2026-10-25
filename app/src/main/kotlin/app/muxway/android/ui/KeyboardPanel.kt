package app.muxway.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.muxway.android.AppModel
import app.muxway.android.protocol.Keyboard
import kotlinx.coroutines.delay

/**
 * The keyboard: the web client's board over the bottom of the pane view, sending each tap to the
 * focused pane. A box-drawn bar names the pane; `_` folds the board, `×` closes it. The board
 * scrolls sideways on a narrow screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyboardPanel(model: AppModel, address: String, host: String, onClose: () -> Unit) {
    val p = LocalPalette.current
    var state by remember { mutableStateOf(Keyboard.State()) }
    var folded by rememberSaveable { mutableStateOf(false) }
    var lit by remember { mutableStateOf<Keyboard.Cap?>(null) }
    var shaken by remember { mutableStateOf<Keyboard.Cap?>(null) }
    val grammar = model.hosts.firstOrNull { it.name == host }?.keys == 2
    LaunchedEffect(lit) { if (lit != null) { delay(140); lit = null } }
    LaunchedEffect(shaken) { if (shaken != null) { delay(320); shaken = null } }
    fun hit(c: Keyboard.Cap) {
        val r = Keyboard.press(c.act, state, grammar)
        state = r.state
        lit = c
        val ok = when (val s = r.send) {
            is Keyboard.Text -> model.type(address, s.text, enter = false)
            is Keyboard.Key -> model.key(address, s.name)
            null -> r.refuse == null
        }
        r.refuse?.let { model.sendError = it }
        if (!ok) shaken = c
    }
    Column(Modifier.fillMaxWidth().border(1.dp, p.ink).background(p.bg).testTag("keyboard")) {
        Row(Modifier.fillMaxWidth().background(p.panel).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("┌─ keyboard ─ ", color = p.dim, maxLines = 1, fontSize = 12.sp)
            Text(address, color = p.accent, maxLines = 1, fontSize = 12.sp, modifier = Modifier.weight(1f).testTag("keyboard-target"))
            Btn("_", { folded = !folded })
            Btn("×", onClose, modifier = Modifier.testTag("keyboard-close"))
        }
        if (folded) return@Column
        val k = 30.dp
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(6.dp)) {
            Box(Modifier.size(k * Keyboard.WIDTH, k * Keyboard.HEIGHT)) {
                for (c in Keyboard.CAPS) {
                    val latch = when (val a = c.act) {
                        is Keyboard.ModKey -> state.mods[a.m]
                        Keyboard.Caps -> if (state.caps) Keyboard.Latch.LOCK else Keyboard.Latch.OFF
                        Keyboard.Num -> if (state.num) Keyboard.Latch.LOCK else Keyboard.Latch.OFF
                        else -> Keyboard.Latch.OFF
                    }
                    val dim = (!grammar && Keyboard.needsGrammar(c)) ||
                        ((c.act as? Keyboard.Ch)?.c == ';' && (state.mods[Keyboard.Mod.C] != Keyboard.Latch.OFF || state.mods[Keyboard.Mod.M] != Keyboard.Latch.OFF))
                    val on = lit == c
                    val shift = if (shaken == c) 2.dp else 0.dp
                    Column(
                        Modifier
                            .offset(k * c.x + shift, k * c.y)
                            .size(k * c.w, k * c.h)
                            .padding(2.dp)
                            .alpha(if (dim) 0.35f else 1f)
                            .background(if (on) p.ink else if (latch == Keyboard.Latch.LOCK) p.faint else p.panel)
                            .border(1.dp, if (shaken == c) p.alert else if (latch != Keyboard.Latch.OFF) p.ink else p.dim)
                            .combinedClickable(
                                onClick = { hit(c) },
                                onDoubleClick = (c.act as? Keyboard.ModKey)?.let { a -> { state = state.copy(mods = state.mods + (a.m to Keyboard.Latch.LOCK)) } },
                            )
                            .padding(horizontal = 3.dp, vertical = 2.dp)
                            .testTag("cap:${c.label}"),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom,
                    ) {
                        val style = TextStyle(fontFamily = Pixel, fontSize = if (c.label.length > 3) 5.sp else 7.sp)
                        c.up?.let { Text(it, color = if (on) p.bg else p.dim, style = style, maxLines = 1) }
                        Text(c.label, color = if (on) p.bg else p.ink, style = style, maxLines = 1)
                    }
                }
            }
        }
    }
}
