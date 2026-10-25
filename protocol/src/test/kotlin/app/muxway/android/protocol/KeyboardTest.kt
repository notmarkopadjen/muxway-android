package app.muxway.android.protocol

import app.muxway.android.protocol.Keyboard.Key
import app.muxway.android.protocol.Keyboard.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTest {
    private fun cap(label: String) = Keyboard.CAPS.first { it.label == label }.act
    private fun pad(label: String) = Keyboard.CAPS.first { it.label == label && it.act is Keyboard.Kp }.act

    private fun run(acts: List<Keyboard.Act>, grammar: Boolean = true, start: Keyboard.State = Keyboard.State()): List<Any> {
        var s = start
        val out = ArrayList<Any>()
        for (a in acts) {
            val r = Keyboard.press(a, s, grammar)
            r.send?.let { out.add(it) }
            r.refuse?.let { out.add("refused") }
            s = r.state
        }
        return out
    }

    @Test
    fun typesCharactersShiftedByShiftAndLettersByCaps() {
        assertEquals(listOf(Text("a"), Text("A"), Text("1"), Text("!")), run(listOf(cap("A"), cap("⇧ Shift"), cap("A"), cap("1"), cap("⇧ Shift"), cap("1"))))
        assertEquals(listOf(Text("A"), Text("1")), run(listOf(cap("Caps"), cap("A"), cap("1"))))
    }

    @Test
    fun sendsChordsInTheGrammarsOrderAndLetsGoAfterOneKey() {
        assertEquals(
            listOf(Key("C-M-Delete"), Key("Delete"), Key("S-Up"), Key("C-a")),
            run(listOf(cap("Alt"), cap("Ctrl"), cap("Del"), cap("Del"), cap("⇧ Shift"), cap("↑"), cap("Ctrl"), cap("A"))),
        )
        val locked = Keyboard.State(mods = Keyboard.Mod.entries.associateWith { if (it == Keyboard.Mod.C) Keyboard.Latch.LOCK else Keyboard.Latch.OFF })
        assertEquals(listOf(Key("C-a"), Key("C-e")), run(listOf(cap("A"), cap("E")), start = locked))
    }

    @Test
    fun functionKeysKeypadAndCommandKeys() {
        assertEquals(listOf(Key("S-F1"), Key("S-F12"), Key("BTab")), run(listOf(cap("F13"), cap("F24"), cap("⇧ Shift"), cap("Tab ⇥"))))
        assertEquals(listOf(Key("KP7"), Key("Home"), Key("KPEnter")), run(listOf(pad("7"), cap("Num"), pad("7"), pad("Ent"))))
        assertEquals(listOf(Key("C-c"), Key("BTab")), run(listOf(cap("Attn"), cap("Back"))))
    }

    @Test
    fun refusesCtrlWithSemicolonAndNewNamesOnAnOlderHost() {
        assertEquals(listOf("refused"), run(listOf(cap("Ctrl"), cap(";"))))
        assertEquals(listOf(Key("C-c"), "refused", "refused"), run(listOf(cap("Ctrl"), cap("C"), cap("Alt"), cap("X"), cap("F13")), grammar = false))
    }

    @Test
    fun everyKeySentIsInTheGrammar() {
        for (c in Keyboard.CAPS) for (mods in listOf(emptyList(), listOf("Ctrl"), listOf("Alt"), listOf("⇧ Shift"), listOf("Ctrl", "Alt"))) {
            val sent = run(mods.map { cap(it) } + c.act)
            for (k in sent.filterIsInstance<Key>()) assertTrue("${c.label} with $mods: ${k.name}", Input.isKey(k.name))
        }
    }

    @Test
    fun noTwoCapsShareAPlace() {
        val taken = HashSet<Pair<Float, Float>>()
        for (c in Keyboard.CAPS) {
            assertTrue(c.x + c.w <= Keyboard.WIDTH && c.y + c.h <= Keyboard.HEIGHT)
            var x = c.x
            while (x < c.x + c.w) {
                var y = c.y
                while (y < c.y + c.h) {
                    assertFalse("${c.label} at $x,$y", !taken.add(x to y))
                    y += 0.5f
                }
                x += 0.25f
            }
        }
        assertTrue(Keyboard.CAPS.size > 110)
    }
}
