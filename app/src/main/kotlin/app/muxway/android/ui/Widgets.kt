package app.muxway.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ContentResolver
import android.net.Uri
import app.muxway.android.protocol.Input
import app.muxway.android.protocol.Lens
import app.muxway.android.protocol.Pane

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

/**
 * A box-drawn panel with its title sitting on the top edge, as the web client draws them;
 * [actions] sit on the same edge at the right.
 */
@Composable
fun Panel(title: String, modifier: Modifier = Modifier, active: Boolean = false, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
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
        Row(Modifier.align(Alignment.TopEnd).offset(x = (-12).dp, y = (-7).dp).background(p.panel).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}

/** A thin rule between rows. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.faint))
}

/**
 * `[label]`: a bordered button; selected is inverted and glows. A long press may do something
 * else. A [tint] touches the border and the ground, as the key and command rows are grouped.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Btn(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onUp: ((held: Boolean) -> Unit)? = null,
    tint: Color? = null,
) {
    val p = LocalPalette.current
    val fg = if (selected) p.bg else if (enabled) p.ink else p.faint
    val tinted = tint != null && enabled && !selected
    val bg = if (selected) p.ink else if (tinted) tint!!.copy(alpha = 0.09f).compositeOver(p.bg) else p.bg
    val edge = if (selected) p.ink else if (tinted) lerp(p.faint, tint!!, 0.45f) else if (enabled) p.dim else p.faint
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    Text(
        label,
        color = fg,
        maxLines = 1,
        style = LocalTextStyle.current.copy(shadow = if (selected) null else textGlow()),
        modifier = modifier
            .padding(2.dp)
            .border(1.dp, edge)
            .background(bg)
            .then(
                when {
                    // The press itself is the gesture: the caller is told when it starts and
                    // whether the finger was held down when it ended.
                    onDown != null -> Modifier.pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown()
                            val at = System.currentTimeMillis()
                            down?.invoke()
                            if (waitForUpOrCancellation() != null) up?.invoke(System.currentTimeMillis() - at >= HOLD_MS)
                        }
                    }
                    onLongClick == null -> Modifier.clickable(enabled = enabled, onClick = onClick)
                    else -> Modifier.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** A press longer than this is a hold, not a tap. */
const val HOLD_MS = 400L

/**
 * A labelled field with a border; the cursor is the ink colour. With [maxLines] above one it grows
 * with its text to that many rows, then scrolls inside; [onEnter] takes a hardware Enter, and
 * Shift+Enter then starts a new line at the caret.
 */
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
    focusRequester: FocusRequester? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    maxLines: Int = 1,
    onEnter: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    fun newLine() {
        val s = field.selection
        val text = field.text.replaceRange(s.min, s.max, "\n")
        field = TextFieldValue(text, TextRange(s.min + 1))
        onChange(text)
    }
    val align = if (maxLines > 1) Alignment.Top else Alignment.CenterVertically
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = align) {
        Text("$label ", color = p.dim, maxLines = 1)
        Box(Modifier.weight(1f).border(1.dp, p.dim).padding(horizontal = 6.dp, vertical = 6.dp)) {
            if (value.isEmpty()) Text(hint, color = p.faint, maxLines = 1)
            BasicTextField(
                value = field,
                onValueChange = { field = it; if (it.text != value) onChange(it.text) },
                singleLine = maxLines == 1,
                maxLines = maxLines,
                textStyle = LocalTextStyle.current.copy(color = p.ink),
                cursorBrush = SolidColor(p.ink),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = keyboard,
                keyboardActions = actions,
                modifier = Modifier.fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { onFocus?.invoke(it.isFocused) }
                    .onPreviewKeyEvent { e ->
                        val enter = e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)
                        when {
                            !enter || onEnter == null -> false
                            e.isShiftPressed -> { newLine(); true }
                            else -> { onEnter(); true }
                        }
                    },
            )
        }
    }
}

/**
 * The pane's input line: [Field]'s look on the text field that takes content, so an image the
 * keyboard inserts, or a file in a pasted clip, reaches [onContent] while text pastes as text.
 * Enter and the keyboard's action call [onSend]; Shift+Enter starts a new line at the caret.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Line(
    label: String,
    state: TextFieldState,
    modifier: Modifier = Modifier,
    hint: String = "",
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    focusRequester: FocusRequester,
    onFocus: (Boolean) -> Unit,
    maxLines: Int = 6,
    onSend: () -> Unit,
    onContent: (List<Uri>) -> Unit,
) {
    val p = LocalPalette.current
    val send by rememberUpdatedState(onSend)
    val content by rememberUpdatedState(onContent)
    // Items that are files go to the caller; whatever is left, text, is pasted.
    val receiver = remember {
        object : ReceiveContentListener {
            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                val uris = ArrayList<Uri>()
                val rest = transferableContent.consume { item ->
                    val u = item.uri
                    if (u != null && u.scheme == ContentResolver.SCHEME_CONTENT) { uris.add(u); true } else false
                }
                if (uris.isNotEmpty()) content(uris)
                return rest
            }
        }
    }
    val action = remember { object : KeyboardActionHandler { override fun onKeyboardAction(performDefaultAction: () -> Unit) { send() } } }
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text("$label ", color = p.dim, maxLines = 1)
        Box(Modifier.weight(1f).border(1.dp, p.dim).padding(horizontal = 6.dp, vertical = 6.dp)) {
            if (state.text.isEmpty()) Text(hint, color = p.faint, maxLines = 1)
            BasicTextField(
                state = state,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { onFocus(it.isFocused) }
                    .contentReceiver(receiver)
                    .onPreviewKeyEvent { e ->
                        val enter = e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)
                        when {
                            !enter -> false
                            e.isShiftPressed -> {
                                state.edit { val at = selection.min; replace(selection.min, selection.max, "\n"); placeCursorAfterCharAt(at) }
                                true
                            }
                            else -> { send(); true }
                        }
                    },
                inputTransformation = Typed,
                textStyle = LocalTextStyle.current.copy(color = p.ink),
                keyboardOptions = keyboard,
                onKeyboardAction = action,
                lineLimits = TextFieldLineLimits.MultiLine(1, maxLines),
                cursorBrush = SolidColor(p.ink),
            )
        }
    }
}

/** Control characters never reach the line, whichever way they come in. */
private object Typed : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val s = asCharSequence().toString()
        val clean = Input.typed(s)
        if (clean != s) replace(0, length, clean)
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

/** The lens's mark: the kind's letter, pulsing, while the pane waits; a turning ring while it works. */
@Composable
fun Mark(status: Lens.Status, kind: String?, color: Color) {
    when (status) {
        Lens.Status.WAITING -> Text(Lens.glyph(status, kind) ?: "!", color = color, maxLines = 1, modifier = Modifier.alpha(pulse()))
        Lens.Status.WORKING -> WorkingRing(color)
        Lens.Status.QUIET -> {}
    }
}

/** A ring with a gap, drawn rather than typed so it is the same in every font; it turns with effects on. */
@Composable
fun WorkingRing(color: Color, modifier: Modifier = Modifier) {
    val fontSize = LocalTextStyle.current.fontSize
    val size = with(LocalDensity.current) { if (fontSize.isSp) fontSize.toDp() * 0.8f else 10.dp }
    val angle = if (LocalEffects.current) {
        val t = rememberInfiniteTransition(label = "ring")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ring").value
    } else 0f
    Canvas(modifier.size(size)) {
        val stroke = this.size.width / 6f
        drawArc(color, angle, 270f, false, topLeft = Offset(stroke / 2, stroke / 2), size = Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke))
    }
}

/**
 * The pane's name, Claude's mark before Claude, and its status after it in italics and dimmer;
 * as a [tab], Claude's codename alone.
 */
@Composable
fun PaneName(pane: Pane, ink: Color, dim: Color, bold: Boolean = false, tab: Boolean = false, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val d = Lens.describe(pane)
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    val text = buildAnnotatedString {
        if (tab && d.claude) withStyle(SpanStyle(fontWeight = weight)) { append(d.status.ifEmpty { d.name }) }
        else {
            withStyle(SpanStyle(fontWeight = weight)) { append(d.name) }
            if (d.status.isNotEmpty()) withStyle(SpanStyle(color = dim, fontStyle = FontStyle.Italic)) { append(" · ${d.status}") }
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (d.claude) Text("${Lens.MARK} ", color = p.claude, maxLines = 1, style = LocalTextStyle.current.copy(shadow = null))
        Text(text, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = LocalTextStyle.current.copy(shadow = null))
    }
}

/** A shadow in [color] around the box, outside it, so no neighbour moves; [strength] fades it. */
fun Modifier.glow(color: Color, strength: Float): Modifier = drawBehind {
    val step = 3.dp.toPx()
    for (i in 1..4) {
        val d = step * i
        drawRect(color.copy(alpha = strength * 0.28f / i), topLeft = Offset(-d, -d), size = Size(size.width + 2 * d, size.height + 2 * d), style = Stroke(step))
    }
}
