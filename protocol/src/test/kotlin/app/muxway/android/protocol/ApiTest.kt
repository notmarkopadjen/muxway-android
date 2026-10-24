package app.muxway.android.protocol

import app.muxway.android.e2e.Request
import app.muxway.android.e2e.Response
import app.muxway.android.e2e.TinyServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The client against a server that misbehaves on purpose. */
class ApiTest {
    private val seen = java.util.Collections.synchronizedList(ArrayList<Request>())
    private var answer: (Request) -> Response = { json(200, "{}") }
    private val server = TinyServer { seen.add(it); answer(it) }
    private val token = "mxt_" + "A".repeat(43)

    @After
    fun stop() = server.close()

    private fun api() = Api(server.url, token, "test")

    private fun json(status: Int, body: String) = Response(status, body.toByteArray(), mapOf("Content-Type" to "application/json"))

    @Test
    fun tokenGoesInTheBearerHeaderOnly() {
        answer = { json(200, """{"hosts":[]}""") }
        api().hosts()
        val r = seen.single()
        assertEquals("Bearer $token", r.header("Authorization"))
        assertFalse(r.uri.contains(token))
        assertEquals("/v1/hosts", r.path)
        assertEquals("application/json", r.header("Accept"))
    }

    @Test
    fun resizeSendsColsAndRowsAndReleaseSendsFitAuto() {
        answer = { json(200, """{"pane":{"address":"h/w/0.0","width":40,"height":12,"mode":"write"}}""") }
        val p = api().resize("h/w/0.0", 40, 12)
        assertEquals(40, p.width)
        assertEquals(12, p.height)
        api().release("h/w/0.0")
        assertEquals(listOf("/v1/panes/h/w/0.0/resize", "/v1/panes/h/w/0.0/resize"), seen.map { it.path })
        val sent = org.json.JSONObject(String(seen[0].body))
        assertEquals(40, sent.getInt("cols"))
        assertEquals(12, sent.getInt("rows"))
        assertEquals(2, sent.length())
        assertEquals("auto", org.json.JSONObject(String(seen[1].body)).getString("fit"))
        assertThrows(IllegalArgumentException::class.java) { api().resize("h/w/0.0", 10, 12) }
        assertThrows(IllegalArgumentException::class.java) { api().resize("h/w/0.0", 40, 400) }
    }

    @Test
    fun mouseSendsButtonAndCellAndRefusesNonsenseLocally() {
        answer = { Response(204, ByteArray(0), emptyMap()) }
        api().mouse("h/w/0.0", "left", 39, 9)
        val sent = org.json.JSONObject(String(seen.single().body)).getJSONObject("mouse")
        assertEquals("left", sent.getString("button"))
        assertEquals(39, sent.getInt("x"))
        assertEquals(9, sent.getInt("y"))
        assertThrows(IllegalArgumentException::class.java) { api().mouse("h/w/0.0", "laser", 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { api().mouse("h/w/0.0", "left", 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { api().mouse("h/w/0.0", "left", 1, 301) }
    }

    @Test
    fun errorsBecomeCodesWithoutTheToken() {
        answer = { json(401, """{"error":"unauthorized"}""") }
        val e = assertThrows(ApiException::class.java) { api().panes() }
        assertEquals(401, e.status)
        assertEquals("unauthorized", e.code)
        assertEquals("Token refused", e.message)
        assertTrue(ApiException.messageFor(429, "locked_out").contains("Wait ten minutes"))
        assertTrue(ApiException.messageFor(429, "locked_out").contains("network"))
        assertFalse(e.toString().contains(token))
    }

    @Test
    fun aHugeBodyIsCapped() {
        val big = "{\"panes\":[]," + "\"x\":\"" + "y".repeat(10 * 1024 * 1024) + "\"}"
        answer = { json(200, big) }
        assertEquals("bad_answer", assertThrows(ApiException::class.java) { api().panes() }.code)
    }

    @Test
    fun notJsonIsABadAnswer() {
        answer = { Response(200, "<html>oops</html>".toByteArray(), mapOf("Content-Type" to "text/html")) }
        assertEquals("bad_answer", assertThrows(ApiException::class.java) { api().hosts() }.code)
    }

    @Test
    fun controlCharactersInContentAreRemoved() {
        answer = { json(200, """{"pane":{"address":"h/w/0.0","mode":"read"},"content":"ok \u001b[31mred\u0007\n"}""") }
        assertEquals("ok \u001b[31mred\n", api().read("h/w/0.0", 50, null)!!.content)
    }

    @Test
    fun notModifiedIsNull() {
        answer = { Response(304) }
        assertNull(api().read("h/w/0.0", 50, "\"etag\""))
        assertEquals("\"etag\"", seen.single().header("If-None-Match"))
        assertEquals("lines=50&ansi=1", seen.single().query)
    }

    @Test
    fun inputIsFilteredBeforeItLeaves() {
        answer = { Response(204) }
        api().type("h/w/0.0", "echo\u0000 hi\r\n")
        assertEquals("/v1/panes/h/w/0.0/input", seen.single().path)
        assertEquals("""{"text":"echo hi\n"}""", String(seen.single().body))
        assertThrows(IllegalArgumentException::class.java) { api().type("h/w/0.0", "\u0007\u001b") }
        assertThrows(IllegalArgumentException::class.java) { api().type("h/w/0.0", "x".repeat(4097)) }
        assertThrows(IllegalArgumentException::class.java) { api().key("h/w/0.0", "C-a") }
        assertEquals(1, seen.size)
    }

    @Test
    fun addressesArePercentEncoded() {
        answer = { Response(204) }
        api().key("alice-laptop/@build", "Enter")
        api().key("alice-laptop/wo rk/1.0", "Enter")
        assertEquals("/v1/panes/alice-laptop/@build/input", seen[0].path)
        assertEquals("/v1/panes/alice-laptop/wo%20rk/1.0/input", seen[1].path)
    }

    @Test
    fun aDeadServerIsOffline() {
        server.close()
        val e = assertThrows(ApiException::class.java) { api().hosts() }
        assertEquals("offline", e.code)
        assertEquals("No connection", e.message)
    }

    @Test
    fun redirectsAreNotFollowed() {
        answer = { Response(302, headers = mapOf("Location" to "http://192.0.2.10/")) }
        assertEquals(302, assertThrows(ApiException::class.java) { api().hosts() }.status)
        assertEquals(1, seen.size)
    }

    @Test
    fun eventsPassTheCursorAndWait() {
        answer = { json(200, """{"events":[],"next":7}""") }
        assertEquals(7L, api().events(5, 30).next)
        assertEquals("wait=30&limit=50&after=5", seen.single().query)
        assertTrue(seen.single().path.endsWith("/v1/events"))
    }

    @Test
    fun transcriberProbeReadsTheStatus() {
        answer = { Response(204, ByteArray(0), emptyMap()) }
        assertTrue(api().hasTranscriber())
        answer = { json(404, """{"error":"not_found"}""") }
        assertFalse(api().hasTranscriber())
        answer = { json(401, """{"error":"unauthorized"}""") }
        assertThrows(ApiException::class.java) { api().hasTranscriber() }
        assertEquals(listOf("/v1/transcribe", "/v1/transcribe", "/v1/transcribe"), seen.map { it.path })
        assertTrue(seen.all { it.method == "GET" })
    }

    @Test
    fun transcribeSendsTheWavAndReadsTheText() {
        answer = { json(200, """{"text":"run the\u0007 tests again"}""") }
        val wav = Clip.wav(ByteArray(16000))
        assertEquals("run the tests again", api().transcribe(wav))
        val r = seen.single()
        assertEquals("POST", r.method)
        assertEquals("/v1/transcribe", r.path)
        assertEquals("audio/wav", r.header("Content-Type"))
        assertEquals("Bearer $token", r.header("Authorization"))
        assertTrue(wav.contentEquals(r.body))
        answer = { json(502, """{"error":"provider_unavailable"}""") }
        val e = assertThrows(ApiException::class.java) { api().transcribe(wav) }
        assertEquals("Transcriber unavailable", e.message)
        assertThrows(IllegalArgumentException::class.java) { api().transcribe(ByteArray(10)) }
        assertThrows(IllegalArgumentException::class.java) { api().transcribe(ByteArray(5 shl 20)) }
    }
}
