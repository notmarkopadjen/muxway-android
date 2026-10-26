package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class InputTest {
    @Test
    fun printableDropsEveryControlCharacter() {
        assertEquals("echo hi", Input.printable("echo\u0000 \u001b[31mhi\r\n\t\u007f\u0085"))
        assertEquals("ünïcödé ok", Input.printable("ünïcödé ok"))
    }

    @Test
    fun typedKeepsOnlyTheNewline() {
        assertEquals("echo hi\n", Input.typed("echo\u0000 \u001b[31mhi\r\n\t\u007f\u0085"))
        assertEquals("one\ntwo", Input.typed("one\r\ntwo"))
        assertEquals("", Input.typed("\u001b[200~\u001b[201~"))
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
    fun keysAreTheProtocolsGrammar() {
        for ((name, _) in Input.KEYS) assertTrue(name, Input.isKey(name))
        for (name in listOf("C-a", "C-M-Delete", "S-Up", "M-S-F5", "KP7", "C-KPEnter", "M-x", "C-[", "M-\\", "M-'", "S-Tab"))
            assertTrue(name, Input.isKey(name))
        for (name in listOf("", "a", "A", "C-", "C-;", "M-;", "F13", "S-a", "M-C-a", "C-C-a", "KP10", "C-\u001b",
            "C-a;kill-server", "Up\n", "enter", "IC", "C-é", "M-#", "C-".repeat(9) + "a"))
            assertFalse(name, Input.isKey(name))
    }

    @Test
    fun jsonBodiesEscapeWhatTheUserTyped() {
        assertEquals("{\"text\":\"say \\\"hi\\\" \\\\ done\"}", Json.text("say \"hi\" \\ done"))
        assertEquals("{\"key\":\"C-c\"}", Json.key("C-c"))
    }

    @Test
    fun aWheelCountGoesInTheBodyOnlyAboveOne() {
        val counted = JSONObject(Json.mouse("wheeldown", 5, 3, 10)).getJSONObject("mouse")
        assertEquals(listOf("wheeldown", 5, 3, 10), listOf(counted.getString("button"), counted.getInt("x"), counted.getInt("y"), counted.getInt("count")))
        assertFalse(JSONObject(Json.mouse("wheelup", 5, 3, 1)).getJSONObject("mouse").has("count"))
        assertFalse(JSONObject(Json.mouse("left", 5, 3)).getJSONObject("mouse").has("count"))
        assertTrue(Input.isMouse("wheelup", 1, 1, 50))
        assertTrue(Input.isMouse("left", 1, 1, 1))
        for ((button, count) in listOf("wheelup" to 0, "wheeldown" to 51, "left" to 2, "right" to 2, "laser" to 1))
            assertFalse("$button $count", Input.isMouse(button, 1, 1, count))
    }
}
