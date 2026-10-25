package app.muxway.android.protocol

import app.muxway.android.e2e.Request
import app.muxway.android.e2e.Response
import app.muxway.android.e2e.TinyServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's gate in front of the hub, against a server that records what reaches it. */
class WatchGateTest {
    private val seen = java.util.Collections.synchronizedList(ArrayList<Request>())
    private var answer: (Request) -> Response = { r ->
        when {
            r.path.endsWith("/input") -> Response(204)
            r.path == "/v1/transcribe" && r.method == "GET" -> Response(204)
            r.path == "/v1/transcribe" -> json(200, """{"text":"yes deploy it"}""")
            r.header("If-None-Match") == "\"a1\"" -> Response(304)
            else -> json(200, """{"pane":$paneJson,"content":"$ make deploy\n\u001b[33mDeploy to prod? (y/N)\u001b[0m "}""", mapOf("ETag" to "\"a1\""))
        }
    }
    private val server = TinyServer { seen.add(it); answer(it) }
    private val token = "mxt_" + "B".repeat(43)
    private val address = "alice-laptop/work/0.1"
    private val paneJson = """{"host":"alice-laptop","address":"$address","session":"work","window_index":0,"pane_index":1,"pane_id":"%5","mode":"write","title":"deploy","command":"bash"}"""
    private var allowed = true
    private var now = 1_767_225_600_000L
    private var panes = listOf(pane(address, "write"), pane("alice-laptop/work/0.2", "read"))

    @After
    fun stop() = server.close()

    private fun pane(address: String, mode: String) = Pane(
        host = "alice-laptop", address = address, session = "work", windowIndex = 0, window = "main", paneIndex = 1, paneId = "%5",
        width = 80, height = 24, title = "deploy", command = "bash", mode = mode, alias = "", activity = 0, attention = null,
    )

    private fun json(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
        Response(status, body.toByteArray(), mapOf("Content-Type" to "application/json") + headers)

    private fun gate() = WatchGate(Api(server.url, token, "test"), { panes }, { allowed }, { now })

    private fun bodies() = seen.filter { it.path.endsWith("/input") }.map { String(it.body) }

    @Test
    fun aKeyOutsideTheWhitelistOrAnUnknownPaneNeverReachesTheHub() {
        val g = gate()
        for (bad in listOf("Up", "C-d", "F1", "\u001b[A", "", "x".repeat(10_000))) {
            assertEquals(WatchGate.REFUSED, g.key(Wire.Key("k-$bad", address, bad)).error)
        }
        assertEquals(WatchGate.REFUSED, g.key(Wire.Key("k-unknown", "alice-laptop/work/9.9", "Enter")).error)
        assertEquals(WatchGate.READ_ONLY, g.key(Wire.Key("k-ro", "alice-laptop/work/0.2", "Enter")).error)
        assertEquals(WatchGate.REFUSED, g.read(Wire.Read("r-unknown", "alice-laptop/work/9.9", "")).error)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun textIsTwoLettersOrWhatTheHubHeardNeverTheWatchsOwn() {
        val g = gate()
        for (bad in listOf("rm -rf /", "yes", "", "\u0007", "y\n", "a".repeat(5000))) {
            assertEquals(WatchGate.REFUSED, g.text(Wire.Text("t-${bad.hashCode()}", address, bad, "", true)).error)
        }
        assertEquals(WatchGate.REFUSED, g.text(Wire.Text("t-fake", address, "", "never-heard", true)).error)
        assertTrue(seen.isEmpty())
        assertTrue(g.text(Wire.Text("t-y", address, "y", "", true)).ok)
        assertEquals(listOf("""{"text":"y"}""", """{"key":"Enter"}"""), bodies())
    }

    @Test
    fun writesAreRefusedWhileTheOwnerHasNotAllowedThem() {
        allowed = false
        val g = gate()
        assertEquals(WatchGate.NOT_ALLOWED, g.key(Wire.Key("k1", address, "Enter")).error)
        assertEquals(WatchGate.NOT_ALLOWED, g.text(Wire.Text("t1", address, "y", "", false)).error)
        assertEquals(WatchGate.NOT_ALLOWED, g.transcribe("c1", Clip.wav(ByteArray(Clip.SAMPLE_RATE * 2))).error)
        assertTrue(seen.none { it.method == "POST" })
        // The tail still comes: the screen shows, it cannot answer.
        val t = g.read(Wire.Read("r1", address, ""))
        assertEquals("", t.error)
        assertEquals(listOf("$ make deploy", "Deploy to prod? (y/N) "), t.lines)
        assertTrue(t.writable)
    }

    @Test
    fun theRateLimitHoldsAtOneASecondAndTenAMinute() {
        val g = gate()
        assertTrue(g.key(Wire.Key("k0", address, "Enter")).ok)
        assertEquals(WatchGate.REFUSED, g.key(Wire.Key("k0b", address, "Enter")).error)
        for (i in 1 until WatchGate.PER_MINUTE) {
            now += 1_000
            assertTrue("write $i", g.key(Wire.Key("k$i", address, "Enter")).ok)
        }
        now += 1_000
        assertEquals(WatchGate.REFUSED, g.key(Wire.Key("k-over", address, "Enter")).error)
        assertEquals(WatchGate.PER_MINUTE, bodies().size)
        now += 60_000
        assertTrue(g.key(Wire.Key("k-later", address, "Enter")).ok)
    }

    @Test
    fun aRepeatedIdWritesOnceAndAnswersTheSame() {
        val g = gate()
        val first = g.key(Wire.Key("k1", address, "Enter"))
        now += 5_000
        assertEquals(first, g.key(Wire.Key("k1", address, "Enter")))
        assertEquals(1, bodies().size)
    }

    @Test
    fun theTailIsPlainAndUnchangedMeansNoLines() {
        val g = gate()
        val t = g.read(Wire.Read("r1", address, ""))
        assertEquals("\"a1\"", t.etag)
        assertEquals(listOf("$ make deploy", "Deploy to prod? (y/N) "), t.lines)
        assertTrue(t.writable)
        assertTrue(t.transcriber)
        val same = g.read(Wire.Read("r2", address, "\"a1\""))
        assertNull(same.lines)
        assertEquals("\"a1\"", same.etag)
        // The transcriber was asked once.
        assertEquals(1, seen.count { it.path == "/v1/transcribe" })
        val ro = g.read(Wire.Read("r3", "alice-laptop/work/0.2", ""))
        assertFalse(ro.writable)
        assertFalse(ro.transcriber)
    }

    @Test
    fun aClipIsSizeCheckedBeforeTheHubAndItsTextTypedOnlyByItsId() {
        val g = gate()
        assertEquals(WatchGate.TOO_SHORT, g.transcribe("c-short", ByteArray(100)).error)
        assertEquals(WatchGate.REFUSED, g.transcribe("c-big", ByteArray(Wire.MAX_CLIP_BYTES + 1)).error)
        assertTrue(seen.none { it.method == "POST" })
        val h = g.transcribe("c1", Clip.wav(ByteArray(Clip.SAMPLE_RATE * 2)))
        assertEquals("yes deploy it", h.text)
        assertTrue(g.text(Wire.Text("t1", address, "", "c1", true)).ok)
        assertEquals(listOf("""{"text":"yes deploy it"}""", """{"key":"Enter"}"""), bodies())
        // Heard once, typed once.
        now += 2_000
        assertEquals(WatchGate.REFUSED, g.text(Wire.Text("t2", address, "", "c1", true)).error)
        assertEquals(2, bodies().size)
        val clip = seen.first { it.path == "/v1/transcribe" && it.method == "POST" }
        assertEquals("audio/wav", clip.header("Content-Type"))
    }

    @Test
    fun hubErrorsBecomeShortReasonsWithoutTheirText() {
        answer = { json(403, """{"error":"read_only","message":"secret-detail mxt_zzz"}""") }
        val g = gate()
        val d = g.key(Wire.Key("k1", address, "Enter"))
        assertEquals(WatchGate.READ_ONLY, d.error)
        assertFalse(String(Wire.done(d)).contains("secret"))
        answer = { json(429, """{"error":"rate_limited"}""") }
        now += 2_000
        assertEquals(WatchGate.RATE_LIMITED, g.key(Wire.Key("k2", address, "Enter")).error)
    }
}
