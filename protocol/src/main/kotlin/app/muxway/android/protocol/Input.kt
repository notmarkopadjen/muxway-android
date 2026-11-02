package app.muxway.android.protocol

/** What a client may type: text without control characters, or one of these keys. */
object Input {
    const val MAX_TEXT_BYTES = 4096

    /** Protocol name to label, in the order the key row shows them. */
    val KEYS: List<Pair<String, String>> = listOf(
        "Enter" to "Enter", "Tab" to "Tab", "BTab" to "S-Tab", "Escape" to "Esc", "C-c" to "^C",
        "Up" to "↑", "Down" to "↓", "Left" to "←", "Right" to "→",
        "BSpace" to "BS", "C-d" to "^D", "C-z" to "^Z", "C-l" to "^L",
        "PageUp" to "PgUp", "PageDown" to "PgDn", "Home" to "Home", "End" to "End",
    )

    /** The key row's groups, each tinted on its own: editing, direction, control, function. */
    enum class Group { EDIT, NAV, CTRL, FN }

    private val NAV_KEYS = setOf("Up", "Down", "Left", "Right", "PageUp", "PageDown", "Home", "End")
    private val FN_KEY = Regex("^F\\d+$")

    fun group(name: String): Group = when {
        name.startsWith("C-") -> Group.CTRL
        FN_KEY.matches(name) -> Group.FN
        name in NAV_KEYS -> Group.NAV
        else -> Group.EDIT
    }

    /** Mouse buttons the protocol names; the wheel ones take a count. */
    val BUTTONS = setOf("left", "middle", "right", "wheelup", "wheeldown")
    val WHEEL = setOf("wheelup", "wheeldown")
    const val MAX_WHEEL = 50

    /** A button at a cell with a notch count the hub takes: the count above one with the wheel only. */
    fun isMouse(button: String, x: Int, y: Int, count: Int): Boolean =
        button in BUTTONS && x in 1..500 && y in 1..300 && count in 1..MAX_WHEEL && (count == 1 || button in WHEEL)

    private val NAMED = setOf(
        "Enter", "Tab", "BTab", "BSpace", "Escape", "Space", "Up", "Down", "Left", "Right",
        "PageUp", "PageDown", "Home", "End", "Insert", "Delete",
    ) + (1..12).map { "F$it" } + (0..9).map { "KP$it" } + listOf("KP/", "KP*", "KP-", "KP+", "KP.", "KPEnter")

    // Characters that go with Ctrl or Alt: unshifted, no ';', which tmux reads as a command's end.
    private const val CHORD_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789`-=[]\\',./"

    /**
     * The protocol's key grammar: C-, M-, S- in that order, each once, then a named key, a keypad
     * key, or a character with Ctrl or Alt. The hub and the agent check it again.
     */
    fun isKey(name: String): Boolean {
        if (name.length > 16) return false
        var rest = name
        val ctrl = rest.startsWith("C-").also { if (it) rest = rest.drop(2) }
        val meta = rest.startsWith("M-").also { if (it) rest = rest.drop(2) }
        val shift = rest.startsWith("S-").also { if (it) rest = rest.drop(2) }
        if (rest in NAMED) return true
        return rest.length == 1 && rest[0] in CHORD_CHARS && (ctrl || meta) && !shift
    }

    /** The names an agent took before the grammar; a host without `keys: 2` refuses the rest. */
    val OLD_KEYS: Set<String> = setOf(
        "Enter", "Tab", "BTab", "BSpace", "Escape", "Space", "Up", "Down", "Left", "Right",
        "PageUp", "PageDown", "Home", "End", "Insert", "Delete",
        "C-c", "C-d", "C-z", "C-l", "C-a", "C-e", "C-u", "C-k", "C-w", "C-r", "C-x", "C-n", "C-p", "C-f",
    ) + (1..12).map { "F$it" }

    /** Drops C0, DEL and C1; the hub refuses them. */
    fun printable(s: String): String = clean(s, keepNewline = false, tabs = false)

    /** What the input line sends: printable, the newline kept; the hub pastes a text with one. */
    fun typed(s: String): String = clean(s, keepNewline = true, tabs = false)

    /** Keeps newlines, turns tabs into spaces, drops every other control character. */
    fun screen(s: String): String = clean(s, keepNewline = true, tabs = true)

    private fun clean(s: String, keepNewline: Boolean, tabs: Boolean): String {
        val out = StringBuilder(s.length)
        var col = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\u001b' -> { i = escapeEnd(s, i); continue }
                c == '\n' && keepNewline -> { out.append(c); col = 0 }
                c == '\t' && tabs -> { val n = 8 - col % 8; repeat(n) { out.append(' ') }; col += n }
                c >= ' ' && c != '\u007f' && (c < '\u0080' || c > '\u009f') -> { out.append(c); col++ }
            }
            i++
        }
        return out.toString()
    }

    /** Index after an escape sequence starting at [i]: CSI to its final byte, OSC to BEL or ST, else past the final byte. */
    internal fun escapeEnd(s: String, i: Int): Int {
        if (i + 1 >= s.length) return s.length
        return when (s[i + 1]) {
            '[' -> {
                var j = i + 2
                while (j < s.length && s[j] !in '@'..'~') j++
                j + 1
            }
            ']' -> {
                var j = i + 2
                while (j < s.length && s[j] != '\u0007' && !(s[j] == '\u001b' && j + 1 < s.length && s[j + 1] == '\\')) j++
                if (j < s.length && s[j] == '\u001b') j + 2 else j + 1
            }
            else -> {
                // ESC, any intermediates (0x20..0x2F), one final byte.
                var j = i + 1
                while (j < s.length && s[j] in ' '..'/') j++
                j + 1
            }
        }
    }

    /** Cuts rows longer than cols into pieces; cols of 0 leaves them. */
    fun wrap(rows: List<String>, cols: Int): List<String> {
        if (cols <= 0) return rows
        val out = ArrayList<String>(rows.size)
        for (row in rows) {
            var r = row
            while (r.length > cols) {
                out.add(r.substring(0, cols))
                r = r.substring(cols)
            }
            out.add(r)
        }
        return out
    }

    fun utf8Length(s: String) = s.toByteArray(Charsets.UTF_8).size

    const val MIN_CHOICES = 2
    const val MAX_CHOICES = 8
    const val MAX_CHOICE = 40

    /** One answer of a question: 1 to 40 printable characters without `|`, as the hub validates it. */
    fun isChoice(s: String) = s.length in 1..MAX_CHOICE && printable(s) == s && '|' !in s

    /** The hub's list of choices, or nothing when it does not fit: two to eight distinct choices. */
    fun choices(list: List<String>): List<String> =
        if (list.size in MIN_CHOICES..MAX_CHOICES && list.all(::isChoice) && list.toSet().size == list.size) list else emptyList()
}
