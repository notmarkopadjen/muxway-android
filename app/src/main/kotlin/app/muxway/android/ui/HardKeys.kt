package app.muxway.android.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint
import app.muxway.android.protocol.Input

/**
 * Hardware keys (keyboard, D-pad, a glasses touchpad) that become named keys for the pane, and,
 * with direct typing on, every other key as the terminal would send it.
 */
object HardKeys {
    val MAP: Map<Key, String> = mapOf(
        Key.DirectionUp to "Up", Key.DirectionDown to "Down", Key.DirectionLeft to "Left", Key.DirectionRight to "Right",
        Key.PageUp to "PageUp", Key.PageDown to "PageDown", Key.MoveHome to "Home", Key.MoveEnd to "End",
        Key.Tab to "Tab", Key.Escape to "Escape", Key.Enter to "Enter", Key.NumPadEnter to "Enter",
        Key.DirectionCenter to "Enter", Key.Backspace to "BSpace", Key.Insert to "Insert", Key.Delete to "Delete",
        Key.F1 to "F1", Key.F2 to "F2", Key.F3 to "F3", Key.F4 to "F4", Key.F5 to "F5", Key.F6 to "F6",
        Key.F7 to "F7", Key.F8 to "F8", Key.F9 to "F9", Key.F10 to "F10", Key.F11 to "F11", Key.F12 to "F12",
    )

    /** The named key, with `C-`, `M-` and `S-` as held, `BTab` for Shift+Tab; null for any other key. */
    fun name(e: KeyEvent): String? {
        val base = MAP[e.key] ?: return null
        if (base == "Tab" && e.isShiftPressed && !e.isCtrlPressed && !e.isAltPressed) return "BTab"
        val name = (if (e.isCtrlPressed) "C-" else "") + (if (e.isAltPressed) "M-" else "") + (if (e.isShiftPressed) "S-" else "") + base
        return if (Input.isKey(name)) name else null
    }

    fun name(key: Key): String? = MAP[key]

    /** What direct typing sends for a key that is not named: text, or a chord the grammar has; null when nothing. */
    sealed class Direct {
        data class Text(val text: String) : Direct()
        data class Chord(val name: String) : Direct()
    }

    fun direct(e: KeyEvent): Direct? {
        if (e.isCtrlPressed || e.isAltPressed) {
            val c = e.nativeKeyEvent.getUnicodeChar(0).takeIf { it in 0x21..0x7e }?.toChar()?.lowercaseChar() ?: return null
            val name = (if (e.isCtrlPressed) "C-" else "") + (if (e.isAltPressed) "M-" else "") + c
            return if (Input.isKey(name)) Direct.Chord(name) else null
        }
        val cp = e.utf16CodePoint
        if (cp <= 0x20 || cp == 0x7f) return null
        val s = Input.typed(String(Character.toChars(cp)))
        return if (s.isEmpty()) null else Direct.Text(s)
    }
}
