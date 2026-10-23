package app.muxway.android.ui

import androidx.compose.ui.input.key.Key

/** Hardware keys (keyboard, D-pad, a glasses touchpad) that become named keys for the pane. */
object HardKeys {
    val MAP: Map<Key, String> = mapOf(
        Key.DirectionUp to "Up", Key.DirectionDown to "Down", Key.DirectionLeft to "Left", Key.DirectionRight to "Right",
        Key.PageUp to "PageUp", Key.PageDown to "PageDown", Key.MoveHome to "Home", Key.MoveEnd to "End",
        Key.Tab to "Tab", Key.Escape to "Escape", Key.Enter to "Enter", Key.NumPadEnter to "Enter",
        Key.DirectionCenter to "Enter", Key.Backspace to "BSpace",
    )

    fun name(key: Key): String? = MAP[key]
}
