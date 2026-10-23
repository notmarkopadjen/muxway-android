package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    private val pane = """{"host":"alice-laptop","address":"alice-laptop/work/1.0","session":"work",
        "window_index":1,"window":"logs","pane_index":0,"pane_id":"%3","width":100,"height":30,
        "title":"bu\u0007ild","command":"bash","mode":"write","alias":"logs",
        "attention":{"kind":"permission","at":1767225600,"text":"Claude needs \u001b[1myou"}}"""

    @Test
    fun panesCarryModeAliasAndCleanAttention() {
        val p = Json.panes("""{"panes":[$pane]}""").single()
        assertTrue(p.writable)
        assertEquals("logs", p.label)
        assertEquals("build", p.title)
        assertEquals("permission", p.attention?.kind)
        assertEquals("Claude needs you", p.attention?.text)
    }

    @Test
    fun aPaneWithoutAliasIsNamedByItsPlace() {
        val p = Json.panes("""{"panes":[{"session":"work","window_index":2,"pane_index":1,"mode":"read"}]}""").single()
        assertEquals("work/2.1", p.label)
        assertEquals(false, p.writable)
        assertNull(p.attention)
    }

    @Test
    fun readContentKeepsColourAndLosesOtherControlCharacters() {
        val r = Json.read("""{"pane":$pane,"content":"$ make\u001b[0m\n\u0007\u001b]0;t\u0007ok\n"}""", "\"abc\"")
        assertEquals("$ make\u001b[0m\nok\n", r.content)
        assertEquals("\"abc\"", r.etag)
    }

    @Test
    fun eventsAndCursor() {
        val e = Json.events("""{"events":[{"seq":12,"at":1,"host":"alice-laptop","address":"alice-laptop/work/1.0",
            "pane_id":"%3","alias":"","kind":"done","text":"tests: 0"}],"next":12}""")
        assertEquals(12L, e.next)
        assertEquals("done", e.events.single().kind)
        assertEquals(0, Json.events("""{"events":[],"next":0}""").events.size)
    }

    @Test
    fun garbageIsABadAnswerNotACrash() {
        for (body in listOf("", "x", "[]", "{\"panes\":[1]}", "{\"panes\":{}}", "{\"pane\":\"s\"}")) {
            val e = assertThrows(ApiException::class.java) { Json.panes(body); Json.read(body, "") }
            assertEquals("bad_answer", e.code)
        }
        assertEquals("", Json.error("not json"))
        assertEquals("unauthorized", Json.error("""{"error":"unauthorized"}"""))
    }
}
