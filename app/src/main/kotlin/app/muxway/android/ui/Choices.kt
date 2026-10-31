package app.muxway.android.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.muxway.android.AppModel
import app.muxway.android.protocol.Pane

/**
 * The fixed answers of a question a pane asked with `muxway ask`, as buttons: a tap sends the
 * choice to the asking process and the pane's attention goes with it; nothing is typed into
 * the pane. Dim and dead where this token may not write, or while an answer is on its way.
 */
@Composable
fun Choices(model: AppModel, pane: Pane, modifier: Modifier = Modifier) {
    val choices = pane.attention?.choices.orEmpty()
    if (choices.isEmpty()) return
    val busy = model.view(pane.key).busy
    Row(modifier.testTag("choices"), verticalAlignment = Alignment.CenterVertically) {
        for (c in choices) Btn(c, { model.answer(pane.key, c) }, enabled = pane.writable && !busy, modifier = Modifier.testTag("choice:$c"))
    }
}
