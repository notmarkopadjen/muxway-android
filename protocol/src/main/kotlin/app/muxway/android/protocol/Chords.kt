package app.muxway.android.protocol

/**
 * The pane chords of a hardware keyboard, the web's: Ctrl+] goes to the next pane, Ctrl+[ to the
 * previous, from anywhere, direct typing included, and neither is ever sent to the pane; the
 * keys themselves come from the virtual keyboard.
 */
object Chords {
    /**
     * `1` for Ctrl+], `-1` for Ctrl+[, `0` for anything else. [code] is the key as the layout
     * names it (`]`, `[`, or another character), [char] what the key produces, for a layout where
     * the bracket sits elsewhere; either tells.
     */
    fun pane(ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean, code: Char?, char: Char?): Int {
        if (!ctrl || alt || shift || meta) return 0
        if (code == ']' || char == ']') return 1
        if (code == '[' || char == '[') return -1
        return 0
    }
}
