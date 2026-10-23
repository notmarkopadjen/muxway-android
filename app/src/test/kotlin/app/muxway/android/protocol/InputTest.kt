package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTest {
    @Test
    fun printableDropsEveryControlCharacter() {
        assertEquals("echo hi", Input.printable("echo\u0000 \u001b[31mhi\r\n\t\u007f\u0085"))
        assertEquals("ünïcödé ok", Input.printable("ünïcödé ok"))
    }

    @Test
    fun screenKeepsNewlinesAndExpandsTabs() {
        assertEquals("a       b\nc", Input.screen("a\tb\r\n\u0007c\u001b[0m"))
        assertEquals("title and text", Input.screen("\u001b]0;window\u0007title \u001b]2;x\u001b\\and \u001b(Btext"))
        assertEquals("cut", Input.screen("cut\u001b["))
        assertEquals("abcdefgh        x", Input.screen("abcdefgh\tx"))
    }

    @Test
    fun wrapCutsLongRowsAndKeepsEmptyOnes() {
        assertEquals(listOf("abcd", "ef", "", "gh"), Input.wrap(listOf("abcdef", "", "gh"), 4))
        assertEquals(listOf("abcdef"), Input.wrap(listOf("abcdef"), 0))
    }

    @Test
    fun onlyTheProtocolsKeysAreKeys() {
        for ((name, _) in Input.KEYS) assertTrue(name, Input.isKey(name))
        assertFalse(Input.isKey("C-a"))
        assertFalse(Input.isKey("S-Tab"))
        assertFalse(Input.isKey(""))
    }

    @Test
    fun jsonBodiesEscapeWhatTheUserTyped() {
        assertEquals("{\"text\":\"say \\\"hi\\\" \\\\ done\"}", Json.text("say \"hi\" \\ done"))
        assertEquals("{\"key\":\"C-c\"}", Json.key("C-c"))
    }
}
