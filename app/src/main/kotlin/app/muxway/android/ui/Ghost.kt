package app.muxway.android.ui

import app.muxway.android.protocol.Suggest

/**
 * What a key takes of the ghost drawn on the input line: `Right` the whole, `Ctrl+Right` the
 * first word, both only while a ghost is drawn and the caret stands at the end of the text; null
 * for any other key, a caret elsewhere, or no ghost, and the key goes on as it would. The key
 * row's `→` and a hardware keyboard's arrows on the line both decide here. Nothing is sent by
 * taking a ghost: the text lands in the field, and send sends what the field holds.
 */
object Ghost {
    fun take(name: String?, atEnd: Boolean, ghost: String): String? = when {
        ghost.isEmpty() || !atEnd -> null
        name == "Right" -> ghost
        name == "C-Right" -> Suggest.firstWord(ghost).takeIf { it.isNotEmpty() }
        else -> null
    }
}
