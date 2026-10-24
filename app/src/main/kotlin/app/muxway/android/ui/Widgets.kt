package app.muxway.android.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** `┌─ muxway ─┐ crumb ──── actions`: the header, brand in the pixel font. */
@Composable
fun Frame(title: String, modifier: Modifier = Modifier, crumb: String = "", actions: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("┌─ ", color = p.dim, maxLines = 1)
        Text(title, color = p.ink, maxLines = 1, style = LocalTextStyle.current.copy(fontFamily = Pixel, fontSize = 10.sp, letterSpacing = 1.sp, shadow = textGlow(strong = true)))
        Text(" ─┐", color = p.dim, maxLines = 1)
        if (crumb.isNotEmpty()) Text("  $crumb", color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Box(Modifier.weight(1f).height(1.dp).background(p.faint))
        actions()
    }
}

/** A box-drawn panel with its title sitting on the top edge, as the web client draws them. */
@Composable
fun Panel(title: String, modifier: Modifier = Modifier, active: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Box(modifier.fillMaxWidth().padding(top = 7.dp)) {
        Column(
            Modifier.fillMaxWidth().border(1.dp, if (active) p.ink else p.dim).background(p.panel).padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            content = content,
        )
        Row(Modifier.offset(x = 12.dp, y = (-7).dp).background(p.panel).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("─ ", color = p.dim, maxLines = 1)
            Text(title, color = p.ink, maxLines = 1, style = LocalTextStyle.current.copy(fontFamily = Pixel, fontSize = 9.sp, letterSpacing = 1.sp, shadow = textGlow(strong = true)))
            Text(" ─", color = p.dim, maxLines = 1)
        }
    }
}

/** A thin rule between rows. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.faint))
}

/** `[label]`: a bordered button; selected is inverted and glows. */
@Composable
fun Btn(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true) {
    val p = LocalPalette.current
    val fg = if (selected) p.bg else if (enabled) p.ink else p.faint
    val bg = if (selected) p.ink else p.bg
    Text(
        label,
        color = fg,
        maxLines = 1,
        style = LocalTextStyle.current.copy(shadow = if (selected) null else textGlow()),
        modifier = modifier
            .padding(2.dp)
            .border(1.dp, if (selected) p.ink else if (enabled) p.dim else p.faint)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** A labelled single-line field with a border; the cursor is the ink colour. */
@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    hint: String = "",
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", color = p.dim, maxLines = 1)
        Box(Modifier.weight(1f).border(1.dp, p.dim).padding(horizontal = 6.dp, vertical = 6.dp)) {
            if (value.isEmpty()) Text(hint, color = p.faint, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = p.ink),
                cursorBrush = SolidColor(p.ink),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = keyboard,
                keyboardActions = actions,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A slow pulse for marks that want attention; steady when effects are off. */
@Composable
fun pulse(): Float {
    if (!LocalEffects.current) return 1f
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(1f, 0.4f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    return a
}
