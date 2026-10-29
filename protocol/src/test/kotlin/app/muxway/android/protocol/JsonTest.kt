package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("work/2.1", p.name)
        assertEquals(false, p.writable)
        assertNull(p.attention)
    }

    @Test
    fun theCursorTheTrimmedRowsAndThePreviewFlagAreReadWhenGiven() {
        val p = Json.panes("""{"panes":[{"session":"work","width":80,"height":24,"cursor_x":3,"cursor_y":24}]}""").single()
        assertEquals(3, p.cursorX)
        assertEquals(24, p.cursorY)
        assertEquals(0, Json.panes("""{"panes":[{"session":"work"}]}""").single().cursorY)
        val r = Json.read("""{"pane":{"session":"work","height":30},"content":"$ ","trimmed":29}""", "")
        assertEquals(29, r.trimmed)
        assertEquals(1, r.screenRows)
        assertEquals(0, Json.read("""{"pane":{"session":"work","height":30},"content":"$ "}""", "").trimmed)
        assertEquals(true, Json.hosts("""{"hosts":[{"name":"alice-laptop","online":true,"preview":true}]}""").single().preview)
        assertEquals(false, Json.hosts("""{"hosts":[{"name":"alice-laptop","online":true}]}""").single().preview)
    }

    @Test
    fun theMouseFlagIsNullUntilTheHubSaysIt() {
        assertNull(Json.panes("""{"panes":[{"session":"work"}]}""").single().mouse)
        assertEquals(true, Json.panes("""{"panes":[{"session":"work","mouse":true}]}""").single().mouse)
        assertEquals(false, Json.panes("""{"panes":[{"session":"work","mouse":false}]}""").single().mouse)
    }

    @Test
    fun theNameIsTheCleanedTitleWhenThereIsOne() {
        val p = Json.panes("""{"panes":[{"session":"work","window_index":0,"pane_index":1,"alias":"logs","title":"\u001b]0;x\u0007\u2733 build \u001b[1mserver\u0007"}]}""").single()
        assertEquals("logs", p.label)
        assertEquals("Claude · build server", p.name)
        assertEquals("build server", p.tab)
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

    @Test
    fun adminLogAndClientsAreReadAndCleaned() {
        assertTrue(Json.admin("""{"id":"a","admin":true}"""))
        assertFalse(Json.admin("""{"id":"a"}"""))
        val log = Json.log("""{"records":[{"seq":7,"at":1767229300,"level":"WARN","msg":"refused\u001b[31m","attrs":{"addr":"203.0.113.9"}}],"next":7}""")
        assertEquals("refused", log.single().msg)
        assertEquals("203.0.113.9", log.single().attrs["addr"])
        assertTrue(log.single().warning)
        val clients = Json.clients(
            """{"tokens":[{"id":"0ddba4b5","name":"phone"}],"users":[{"id":"1c6eb20e","email":"alice@example.com"}],
               "clients":[{"kind":"session","id":"Xq3kP0aZ","client":"web","principal":"1c6eb20e","addr":"198.51.100.7","since":1,"seen":2},
                          {"kind":"bearer","client":"muxway-android","principal":"0ddba4b5","addr":"198.51.100.8","since":1,"seen":2}]}""",
        )
        assertEquals(listOf("alice@example.com", "phone"), clients.map { it.who })
        assertEquals(listOf(true, false), clients.map { it.endable })
    }
}
