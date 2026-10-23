package app.muxway.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** `┌─ title ────┐`: a frame's top edge with the title in it. */
@Composable
fun Frame(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("┌─ $title ", color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Box(Modifier.weight(1f).height(1.dp).background(p.dim))
        actions()
        Text("─┐", color = p.ink, maxLines = 1)
    }
}

/** A thin rule between rows. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.dim))
}

/** `[label]`: a bordered, inverted-on-select button. */
@Composable
fun Btn(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true) {
    val p = LocalPalette.current
    val fg = if (selected) p.bg else if (enabled) p.ink else p.dim
    val bg = if (selected) p.ink else p.bg
    Text(
        label,
        color = fg,
        maxLines = 1,
        modifier = modifier
            .padding(2.dp)
            .border(1.dp, if (enabled) p.ink else p.dim)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** A labelled single-line field with a bottom rule; the cursor is the ink colour. */
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
            if (value.isEmpty()) Text(hint, color = p.dim, maxLines = 1)
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
