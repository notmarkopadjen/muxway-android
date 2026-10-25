package app.muxway.android.protocol

/**
 * The keyboard: a big terminal board of the old kind, every key a terminal can carry, sent one
 * tap at a time; the web client has the same board. Caps sit on a grid of quarter units; a press
 * turns into text or a key name of the protocol's grammar.
 */
object Keyboard {
    enum class Mod { C, M, S }
    enum class Latch { OFF, ONCE, LOCK }

    sealed interface Act
    data class Ch(val c: Char, val up: Char) : Act
    data class Named(val name: String, val shift: Boolean = false) : Act
    data class Kp(val kp: String, val nav: String? = null) : Act
    data class Chord(val key: String) : Act
    data class ModKey(val m: Mod) : Act
    data object Caps : Act
    data object Num : Act

    data class Cap(val label: String, val up: String?, val act: Act, val x: Float, val y: Float, val w: Float, val h: Float = 1f, val title: String? = null)

    data class State(
        val mods: Map<Mod, Latch> = Mod.entries.associateWith { Latch.OFF },
        val caps: Boolean = false,
        val num: Boolean = true,
    )

    sealed interface Send
    data class Text(val text: String) : Send
    data class Key(val name: String) : Send

    data class Result(val state: State, val send: Send? = null, val refuse: String? = null)

    /** What a tap sends, and the board after it: a latched modifier lets go after one key. */
    fun press(a: Act, s: State, grammar: Boolean): Result {
        when (a) {
            is ModKey -> return Result(s.copy(mods = s.mods + (a.m to if (s.mods[a.m] == Latch.OFF) Latch.ONCE else Latch.OFF)))
            Caps -> return Result(s.copy(caps = !s.caps))
            Num -> return Result(s.copy(num = !s.num))
            else -> {}
        }
        val c = s.mods[Mod.C] != Latch.OFF
        val m = s.mods[Mod.M] != Latch.OFF
        val sh = s.mods[Mod.S] != Latch.OFF
        val after = s.copy(mods = s.mods.mapValues { (_, l) -> if (l == Latch.LOCK) Latch.LOCK else Latch.OFF })
        fun prefix(shift: Boolean) = (if (c) "C-" else "") + (if (m) "M-" else "") + (if (shift) "S-" else "")
        val send: Send = when (a) {
            is Ch -> if (c || m) {
                if (a.c == ';') return Result(after, refuse = "Ctrl or Alt with ; cannot reach a pane")
                Key(prefix(false) + a.c)
            } else {
                val upper = if (a.c in 'a'..'z') sh != s.caps else sh
                Text((if (upper) a.up else a.c).toString())
            }
            is Chord -> Key(a.key)
            is Kp -> Key(prefix(sh) + if (s.num || a.nav == null) a.kp else a.nav)
            is Named -> {
                val shift = sh || a.shift
                Key(if (a.name == "Tab" && shift && !c && !m) "BTab" else prefix(shift) + a.name)
            }
            else -> error("unreachable")
        }
        if (!grammar && send is Key && send.name !in Input.OLD_KEYS)
            return Result(after, refuse = "This host's agent is older and takes only the key row's keys")
        return Result(after, send)
    }

    /** Whether a cap needs the grammar, to be dimmed on an older host. */
    fun needsGrammar(cap: Cap): Boolean = when (val a = cap.act) {
        is ModKey -> a.m != Mod.S
        is Kp -> true
        is Named -> a.shift
        else -> false
    }

    private const val MAIN = 2.5f
    private const val NAV = MAIN + 15.5f
    private const val PAD = NAV + 3.5f
    const val WIDTH = PAD + 4f
    const val HEIGHT = 7.5f

    private class Row(var x: Float, val y: Float) {
        val out = ArrayList<Cap>()
        fun add(label: String, act: Act, w: Float = 1f, up: String? = null, h: Float = 1f) {
            out.add(Cap(label, up, act, x, y, w, h)); x += w
        }
        fun ch(c: Char, up: Char, w: Float = 1f) =
            if (c in 'a'..'z') add(up.toString(), Ch(c, up), w) else add(c.toString(), Ch(c, up), w, up.toString())
        fun nm(label: String, name: String, w: Float = 1f) = add(label, Named(name), w)
        fun kp(k: String, nav: String? = null, w: Float = 1f) = add(k, Kp("KP$k", nav), w)
        fun gap(w: Float) { x += w }
    }

    private fun row(x: Float, y: Float, f: Row.() -> Unit) = Row(x, y).apply(f).out

    private fun Row.fkeys(from: Int) {
        for (g in 0..2) {
            for (i in 0..3) {
                val n = from + g * 4 + i
                if (n > 12) add("F$n", Named("F${n - 12}", shift = true)) else nm("F$n", "F$n")
            }
            if (g < 2) gap(0.5f)
        }
    }

    private val chords = listOf(
        Triple("Attn", "C-c", "interrupt (Ctrl+C)"), Triple("Clr", "C-l", "clear the screen (Ctrl+L)"),
        Triple("EOF", "C-d", "end of input (Ctrl+D)"), Triple("Susp", "C-z", "suspend (Ctrl+Z)"),
        Triple("Kill", "C-u", "kill the line (Ctrl+U)"), Triple("Word", "C-w", "rub out a word (Ctrl+W)"),
        Triple("Home", "C-a", "start of the line (Ctrl+A)"), Triple("End", "C-e", "end of the line (Ctrl+E)"),
        Triple("Rev", "C-r", "search the history (Ctrl+R)"), Triple("Back", "BTab", "back tab (Shift+Tab)"),
    )

    val CAPS: List<Cap> = buildList {
        addAll(row(MAIN + 2, 0f) { fkeys(13) })
        addAll(row(MAIN, 1f) { nm("Esc", "Escape"); gap(1f); fkeys(1) })
        chords.forEachIndexed { i, (label, key, title) -> add(Cap(label, null, Chord(key), (i % 2).toFloat(), 2.5f + i / 2, 1f, title = title)) }
        addAll(row(MAIN, 2.5f) {
            "`1234567890-=".zip("~!@#$%^&*()_+").forEach { (c, u) -> ch(c, u) }
            nm("⌫", "BSpace", 2f)
        })
        addAll(row(MAIN, 3.5f) {
            nm("Tab ⇥", "Tab", 1.5f); "qwertyuiop".forEach { ch(it, it.uppercaseChar()) }
            ch('[', '{'); ch(']', '}'); ch('\\', '|', 1.5f)
        })
        addAll(row(MAIN, 4.5f) {
            add("Caps", Caps, 1.75f); "asdfghjkl".forEach { ch(it, it.uppercaseChar()) }
            ch(';', ':'); ch('\'', '"'); nm("Enter ⏎", "Enter", 2.25f)
        })
        addAll(row(MAIN, 5.5f) {
            add("⇧ Shift", ModKey(Mod.S), 2.25f); "zxcvbnm".forEach { ch(it, it.uppercaseChar()) }
            ch(',', '<'); ch('.', '>'); ch('/', '?'); add("⇧ Shift", ModKey(Mod.S), 2.75f)
        })
        addAll(row(MAIN, 6.5f) {
            add("Ctrl", ModKey(Mod.C), 1.5f); add("Alt", ModKey(Mod.M), 1.5f); nm("Space", "Space", 9f)
            add("Alt", ModKey(Mod.M), 1.5f); add("Ctrl", ModKey(Mod.C), 1.5f)
        })
        addAll(row(NAV, 2.5f) { nm("Ins", "Insert"); nm("Home", "Home"); nm("PgUp", "PageUp") })
        addAll(row(NAV, 3.5f) { nm("Del", "Delete"); nm("End", "End"); nm("PgDn", "PageDown") })
        addAll(row(NAV + 1, 5.5f) { nm("↑", "Up") })
        addAll(row(NAV, 6.5f) { nm("←", "Left"); nm("↓", "Down"); nm("→", "Right") })
        addAll(row(PAD, 2.5f) { add("Num", Num); kp("/"); kp("*"); kp("-") })
        addAll(row(PAD, 3.5f) { kp("7", "Home"); kp("8", "Up"); kp("9", "PageUp") })
        addAll(row(PAD, 4.5f) { kp("4", "Left"); kp("5"); kp("6", "Right") })
        addAll(row(PAD, 5.5f) { kp("1", "End"); kp("2", "Down"); kp("3", "PageDown") })
        addAll(row(PAD, 6.5f) { kp("0", "Insert", 2f); kp(".", "Delete") })
        add(Cap("+", null, Kp("KP+"), PAD + 3, 3.5f, 1f, 2f))
        add(Cap("Ent", null, Kp("KPEnter"), PAD + 3, 5.5f, 1f, 2f))
    }
}
