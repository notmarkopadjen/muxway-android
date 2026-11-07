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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

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
    fun splitAndNewSessionSendTheirBodiesAndTakeThePaneBack() {
        answer = { json(201, """{"pane":{"host":"alice-laptop","address":"alice-laptop/work/0.1","session":"work","window_index":0,"pane_index":1,"pane_id":"%5","mode":"write","title":"test-pane","command":"bash"}}""") }
        val p = api().split("alice-laptop", "work", 0, "h")
        assertEquals("alice-laptop/work/0.1", p.address)
        assertTrue(p.writable)
        api().newSession("alice-laptop", "scratch")
        assertEquals("/v1/windows/alice-laptop/work/0/panes", seen[0].path)
        assertEquals("""{"direction":"h"}""", String(seen[0].body))
        assertEquals("/v1/hosts/alice-laptop/sessions", seen[1].path)
        assertEquals("""{"name":"scratch"}""", String(seen[1].body))
        for (bad in listOf("Scratch", "a b", "a;b", "../x", "", "a".repeat(33), "=work")) {
            assertThrows(IllegalArgumentException::class.java) { api().newSession("alice-laptop", bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { api().split("alice-laptop", "work", 0, "x") }
        assertEquals(2, seen.size)
    }

    @Test
    fun theDeskIsAskedWithTheTagWrittenWithoutTheHubsFieldsAndReadDefensively() {
        val desk = """{"view":"panes","panes":["alice-laptop/work/0.0","alice-laptop/work/0.1"],"focused":"alice-laptop/work/0.1","arrangement":"row","minimised":[],"updated":1767225600,"by":"web","rev":12}"""
        answer = { r -> if (r.header("If-None-Match") == "\"abc\"") Response(304) else Response(200, """{"desk":$desk}""".toByteArray(), mapOf("Content-Type" to "application/json", "ETag" to "\"abc\"")) }
        val (d, etag) = api().desk(null)!!
        assertEquals("/v1/desk", seen[0].path)
        assertEquals(12L, d.rev)
        assertEquals("web", d.by)
        assertEquals("alice-laptop/work/0.1", d.focused)
        assertEquals("\"abc\"", etag)
        assertNull(api().desk(etag))
        assertEquals("\"abc\"", seen[1].header("If-None-Match"))
        // A write sends the view without updated, by and rev, and takes the stored desk back.
        answer = { json(200, """{"desk":$desk}""") }
        val mine = Desk(panes = listOf("alice-laptop/work/0.0", "alice-laptop/work/0.1"), focused = "alice-laptop/work/0.1", arrangement = "row", by = "phone", rev = 99, updated = 5)
        val stored = api().putDesk(mine)
        assertEquals("PUT", seen[2].method)
        val sent = org.json.JSONObject(String(seen[2].body))
        assertEquals("panes", sent.getString("view"))
        assertEquals(2, sent.getJSONArray("panes").length())
        assertFalse(sent.has("rev"))
        assertFalse(sent.has("by"))
        assertFalse(sent.has("updated"))
        assertEquals(12L, stored.rev)
        // A hostile answer: a fifth pane, an odd address, a wrong tree, a weight of two, a long key, a control character in by.
        answer = { json(200, """{"desk":{"view":"panes","panes":["alice-laptop/work/0.0","alice laptop/x/0.0"],"arrangement":"custom","tree":{"kind":"h","key":"r","children":[0]},"weights":{"${"k".repeat(40)}":[2,0.5]},"by":"we\u0007b","rev":3}}""") }
        val odd = api().desk(null)!!.first
        assertEquals(listOf("alice-laptop/work/0.0"), odd.panes)
        assertEquals("", odd.arrangement)
        assertNull(odd.tree)
        assertTrue(odd.weights.isEmpty())
        assertEquals("web", odd.by)
        // A read-only token's write is refused with the code, and a hub older than desks says not found.
        answer = { json(403, """{"error":"read_only"}""") }
        assertEquals("read_only", assertThrows(ApiException::class.java) { api().putDesk(mine) }.code)
        answer = { json(404, """{"error":"not_found"}""") }
        assertEquals("not_found", assertThrows(ApiException::class.java) { api().desk(null) }.code)
    }

    @Test
    fun resizeSendsColsAndRowsAndReleaseSendsFitAuto() {
        answer = { json(200, """{"pane":{"address":"h/w/0.0","width":40,"height":12,"mode":"write"}}""") }
        val p = api().resize("h/w/0.0", 40, 12).pane
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
    fun aPaneAloneSizesItsWindowAndIsZoomedOverItsSiblings() {
        answer = { json(200, """{"pane":{"address":"h/w/0.0","width":40,"height":12,"mode":"write","zoomed":true}}""") }
        api().resize("h/w/0.0", 40, 12, window = true, zoom = true)
        api().resize("h/w/0.0", 40, 12, window = true)
        val zoomed = org.json.JSONObject(String(seen[0].body))
        assertEquals("window", zoomed.getString("scope"))
        assertEquals(true, zoomed.getBoolean("zoom"))
        val plain = org.json.JSONObject(String(seen[1].body))
        assertEquals("window", plain.getString("scope"))
        assertEquals(false, plain.has("zoom"))
        // The size is checked here, whatever the scope, so nothing silly leaves the phone.
        assertThrows(IllegalArgumentException::class.java) { api().resize("h/w/0.0", 2, 12, window = true, zoom = true) }
    }

    @Test
    fun aLayoutGoesWithTheWindowScopeOnlyAndTheAnswersPanesAreRead() {
        answer = { json(200, """{"pane":{"address":"h/w/0.1","pane_id":"%3","width":60,"height":20,"mode":"write"},"panes":[{"address":"h/w/0.0","pane_id":"%4"},{"address":"h/w/0.1","pane_id":"%3"}]}""") }
        val layout = "1d6c,121x39,0,0{60x39,0,0,3,60x39,0,0,4}"
        val r = api().resize("h/w/0.0", 121, 39, window = true, layout = layout)
        assertEquals("h/w/0.1", r.pane.address)
        assertEquals(listOf("%4", "%3"), r.panes.map { it.paneId })
        val sent = org.json.JSONObject(String(seen.single().body))
        assertEquals("window", sent.getString("scope"))
        assertEquals(layout, sent.getString("layout"))
        assertEquals(false, sent.has("zoom"))
        // A layout without the window scope, over the hub's cap, or not a layout at all never leaves the phone.
        assertThrows(IllegalArgumentException::class.java) { api().resize("h/w/0.0", 121, 39, layout = layout) }
        assertThrows(IllegalArgumentException::class.java) { api().resize("h/w/0.0", 121, 39, window = true, layout = "0000," + "1x1,0,0,1,".repeat(300)) }
        assertThrows(IllegalArgumentException::class.java) { api().resize("h/w/0.0", 121, 39, window = true, layout = "1d6c,121x39,0,0,3;kill-server") }
        assertEquals(1, seen.size)
    }

    @Test
    fun theRepositorysChangesAndADiffAreAskedForAndAnOddPathNeverLeaves() {
        answer = { r ->
            if (r.path.endsWith("/git")) json(200, """{"root":"/home/alice/work/repo","branch":"main","entries":[{"path":"src/a.go","kind":"modified"}]}""")
            else json(200, """{"diff":"+a\n","binary":false,"cut":false}""")
        }
        val c = api().git("h/w/0.0")
        assertEquals("repo", c.repo)
        assertEquals("src/a.go", c.entries.single().path)
        val d = api().gitDiff("h/w/0.0", "with space/-rf")
        assertEquals("+a\n", d.diff)
        assertEquals(listOf("/v1/panes/h/w/0.0/git", "/v1/panes/h/w/0.0/git/diff?path=with%20space%2F-rf"), seen.map { it.uri })
        assertThrows(IllegalArgumentException::class.java) { api().gitDiff("h/w/0.0", "a\u0000b") }
        assertThrows(IllegalArgumentException::class.java) { api().gitDiff("h/w/0.0", "") }
        assertEquals(2, seen.size)
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
        assertThrows(IllegalArgumentException::class.java) { api().mouse("h/w/0.0", "left", 1, 1, 2) }
        assertThrows(IllegalArgumentException::class.java) { api().mouse("h/w/0.0", "wheelup", 1, 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { api().mouse("h/w/0.0", "wheelup", 1, 1, 51) }
        assertEquals(1, seen.size)
        api().mouse("h/w/0.0", "wheelup", 2, 2, 50)
        assertEquals(50, org.json.JSONObject(String(seen[1].body)).getJSONObject("mouse").getInt("count"))
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
    fun anEtagTheHubCannotHaveMeantIsDropped() {
        val pane = """{"pane":{"address":"h/w/0.0","mode":"read"},"content":"x"}"""
        answer = { Response(200, pane.toByteArray(), mapOf("Content-Type" to "application/json", "ETag" to "\"ok\"")) }
        assertEquals("\"ok\"", api().read("h/w/0.0", 50, null)!!.etag)
        answer = { Response(200, pane.toByteArray(), mapOf("Content-Type" to "application/json", "ETag" to "\"caf\u00e9\u007f\"")) }
        assertEquals("", api().read("h/w/0.0", 50, null)!!.etag)
        answer = { Response(200, pane.toByteArray(), mapOf("Content-Type" to "application/json", "ETag" to "\"" + "e".repeat(300) + "\"")) }
        assertEquals("", api().read("h/w/0.0", 50, null)!!.etag)
        // One that slipped through anyway is a bad answer, not a crash.
        assertEquals("bad_answer", assertThrows(ApiException::class.java) { api().read("h/w/0.0", 50, "\"a\nb\"") }.code)
    }

    @Test
    fun inputIsFilteredBeforeItLeaves() {
        answer = { Response(204) }
        api().type("h/w/0.0", "echo\u0000 hi\r\n")
        assertEquals("/v1/panes/h/w/0.0/input", seen.single().path)
        assertEquals("""{"text":"echo hi\n"}""", String(seen.single().body))
        assertThrows(IllegalArgumentException::class.java) { api().type("h/w/0.0", "\u0007\u001b") }
        assertThrows(IllegalArgumentException::class.java) { api().type("h/w/0.0", "x".repeat(4097)) }
        assertThrows(IllegalArgumentException::class.java) { api().key("h/w/0.0", "C-;") }
        assertEquals(1, seen.size)
    }

    @Test
    fun anAnswerSendsTheChoiceAsGivenAndRefusesWhatTheHubWould() {
        answer = { Response(204) }
        api().answer("h/w/0.0", "yes")
        assertEquals("/v1/panes/h/w/0.0/answer", seen.single().path)
        assertEquals("""{"choice":"yes"}""", String(seen.single().body))
        for (bad in listOf("", "x".repeat(41), "a|b", "yes\u0007")) assertThrows(IllegalArgumentException::class.java) { api().answer("h/w/0.0", bad) }
        assertEquals(1, seen.size)
        answer = { json(409, """{"error":"no_question"}""") }
        val e = assertThrows(ApiException::class.java) { api().answer("h/w/0.0", "yes") }
        assertEquals("no_question", e.code)
        assertEquals("Nothing waits there any more", e.message)
        answer = { json(200, """{"inbox":[{"session":"work","window_index":0,"pane_index":0,"attention":{"kind":"done","at":1,"text":""}}],"count":1}""") }
        assertEquals("done", api().inbox().single().attention?.kind)
        assertEquals("/v1/inbox", seen.last().path)
    }

    @Test
    fun addressesArePercentEncoded() {
        answer = { Response(204) }
        api().key("alice-laptop/@build", "Enter")
        api().key("alice-laptop/wo rk/1.0", "Enter")
        assertEquals("/v1/panes/alice-laptop/@build/input", seen[0].path)
        assertEquals("/v1/panes/alice-laptop/wo%20rk/1.0/input", seen[1].path)
    }

    // The dead server is a port held open but never listened on: every connection to it is
    // refused, and nothing else on the machine can take the port while the test runs, as it
    // could a closed server's.
    @Test
    fun aDeadServerIsOffline() {
        server.close()
        Socket().use { held ->
            held.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val e = assertThrows(ApiException::class.java) { Api("http://127.0.0.1:${held.localPort}", token, "test").hosts() }
            assertEquals("offline", e.code)
            assertEquals("No connection", e.message)
        }
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
    fun aFileGoesAsOneMultipartPartAndThePathComesBackClean() {
        answer = { json(201, """{"path":"/home/alice/.config/muxway/agent/inbox/20261025-102235-56fwge.png\u0007\u001b[31m","bytes":10}""") }
        val data = "\u0089PNG\r\n".toByteArray(Charsets.ISO_8859_1) + ByteArray(5)
        val sent = api().sendFile("alice-laptop/work/0.0", data, "image/png")
        assertEquals("/home/alice/.config/muxway/agent/inbox/20261025-102235-56fwge.png", sent.path)
        assertEquals(10L, sent.bytes)
        val r = seen.single()
        assertEquals("POST", r.method)
        assertEquals("/v1/panes/alice-laptop/work/0.0/files", r.path)
        assertEquals("Bearer $token", r.header("Authorization"))
        val boundary = r.header("Content-Type")!!.substringAfter("multipart/form-data; boundary=")
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"file\"\r\nContent-Type: image/png\r\n\r\n".toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        assertEquals(head.size + data.size + tail.size, r.body.size)
        assertTrue(head.contentEquals(r.body.copyOfRange(0, head.size)))
        assertTrue(data.contentEquals(r.body.copyOfRange(head.size, head.size + data.size)))
        assertTrue(tail.contentEquals(r.body.copyOfRange(head.size + data.size, r.body.size)))
        // Refused here, before any request: a type off the list, a file over the cap.
        assertThrows(IllegalArgumentException::class.java) { api().sendFile("h/w/0.0", data, "image/svg+xml") }
        assertThrows(IllegalArgumentException::class.java) { api().sendFile("h/w/0.0", ByteArray(Files.MAX_BYTES + 1), "text/plain") }
        assertEquals(1, seen.size)
        // An answer without a usable path is a bad answer, not a line to type.
        answer = { json(201, """{"bytes":10}""") }
        assertEquals("bad_answer", assertThrows(ApiException::class.java) { api().sendFile("h/w/0.0", data, "image/png") }.code)
        answer = { json(201, """{"path":"\u0000\u0001"}""") }
        assertEquals("bad_answer", assertThrows(ApiException::class.java) { api().sendFile("h/w/0.0", data, "image/png") }.code)
        answer = { json(415, """{"error":"unsupported"}""") }
        val e = assertThrows(ApiException::class.java) { api().sendFile("h/w/0.0", data, "image/png") }
        assertEquals("unsupported", e.code)
        assertEquals("Not a type the host takes", e.message)
    }

    @Test
    fun aPreviewSaysWhereTheHostFoundTheFileAndAnOddHeaderIsDropped() {
        val text = "plan a, from feature\n".toByteArray()
        val headers = { found: String? -> mapOf("Content-Type" to "text/plain; charset=utf-8") + (found?.let { mapOf("Muxway-File" to it) } ?: emptyMap()) }
        answer = { Response(200, text, headers("/home/alice/src/feature/docs/plans/a.md")) }
        val got = api().previewFile("alice-laptop/work/0.0", "docs/plans/a.md")
        assertEquals("/v1/panes/alice-laptop/work/0.0/preview?path=docs%2Fplans%2Fa.md", seen.single().uri)
        assertEquals("Bearer $token", seen.single().header("Authorization"))
        assertEquals("text/plain", got.type)
        assertTrue(text.contentEquals(got.bytes))
        assertEquals("/home/alice/src/feature/docs/plans/a.md", got.found)
        // The pane's own file carries no header; the inbox never does.
        answer = { Response(200, text, headers(null)) }
        assertEquals("", api().previewFile("alice-laptop/work/0.0", "docs/top.md").found)
        assertEquals("", api().readFile("alice-laptop/work/0.0", "20261025-102235-56fwge.txt").found)
        // A header the hub cannot have meant is shown as nothing: control characters and escapes go, a huge one is dropped whole.
        answer = { Response(200, text, headers("/home/alice/a\u0007\u001b[31m.md")) }
        assertEquals("/home/alice/a.md", api().previewFile("h/w/0.0", "a.md").found)
        answer = { Response(200, text, headers("/" + "a".repeat(1024))) }
        assertEquals("", api().previewFile("h/w/0.0", "a.md").found)
        answer = { Response(200, text, headers("/" + "a".repeat(1023))) }
        assertEquals(1024, api().previewFile("h/w/0.0", "a.md").found.length)
        // A refusal is its code, and never a path.
        answer = { json(404, """{"error":"not_found"}""") }
        assertEquals("not_found", assertThrows(ApiException::class.java) { api().previewFile("h/w/0.0", "docs/out.md") }.code)
    }

    @Test
    fun theFilesProbeIsTrueOnlyOn204() {
        answer = { Response(204) }
        assertTrue(api().takesFiles("alice-laptop/work/0.0"))
        assertEquals("/v1/panes/alice-laptop/work/0.0/files", seen.single().path)
        assertEquals("GET", seen.single().method)
        answer = { json(404, """{"error":"not_found"}""") }
        assertFalse(api().takesFiles("h/w/0.0"))
        answer = { json(403, """{"error":"read_only"}""") }
        assertFalse(api().takesFiles("h/w/0.0"))
        answer = { json(200, """{}""") }
        assertFalse(api().takesFiles("h/w/0.0"))
        // No connection is not a no: the probe is asked again later.
        val dead = Api("http://127.0.0.1:1", token, "test")
        assertEquals("offline", assertThrows(ApiException::class.java) { dead.takesFiles("h/w/0.0") }.code)
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

    @Test
    fun mailPostsTheTextAloneAndTheMailboxAndTheFeedAreRead() {
        answer = { r ->
            when {
                r.method == "POST" -> json(201, """{"mail":{"id":7,"at":1767225600}}""")
                r.path == "/v1/talk" -> json(200, """{"talk":[],"next":0}""")
                else -> json(200, """{"mail":[{"id":7,"at":1,"actor":{"name":"phone"},"from":null,"text":"hi"}]}""")
            }
        }
        assertEquals(7L, api().mail("alice-laptop/@review", "run the tests\u0007 now"))
        assertEquals("/v1/panes/alice-laptop/@review/mail", seen[0].path)
        assertEquals("""{"text":"run the tests now"}""", String(seen[0].body))
        assertEquals(1, api().mailbox("alice-laptop/work/0.0").size)
        assertEquals("/v1/panes/alice-laptop/work/0.0/mail", seen[1].path)
        assertTrue(api().talk().isEmpty())
        assertEquals("/v1/talk", seen[2].path)
        assertEquals("limit=100", seen[2].uri.substringAfter('?'))
        for (bad in listOf("", "\u0007", "x".repeat(4001))) assertThrows(IllegalArgumentException::class.java) { api().mail("alice-laptop/work/0.0", bad) }
        assertEquals(3, seen.size)
    }

    @Test
    fun aShareIsMadeListedAndRevokedWithTheHubsChecksRepeatedHere() {
        answer = { r ->
            when {
                r.method == "POST" -> json(201, """{"share":{"id":"3nK_abc","url":"https://hub.example.com/s/3nK_abc","password":"aaaa-bbbb-2222","expires":1767225600,"host":"alice-laptop","pane_id":"%3","address":"alice-laptop/work/0.0","watching":0}}""")
                r.method == "DELETE" -> Response(204)
                else -> json(200, """{"shares":[]}""")
            }
        }
        val s = api().share("alice-laptop/work/0.0", 3600, "aaaa-bbbb-2222")
        assertEquals("3nK_abc", s.id)
        assertEquals("aaaa-bbbb-2222", s.password)
        assertEquals("/v1/panes/alice-laptop/work/0.0/shares", seen[0].path)
        val sent = org.json.JSONObject(String(seen[0].body))
        assertEquals(3600, sent.getInt("ttl"))
        assertEquals("aaaa-bbbb-2222", sent.getString("password"))
        assertTrue(api().shares().isEmpty())
        assertEquals("/v1/shares", seen[1].path)
        api().revokeShare("3nK_abc")
        assertEquals("DELETE", seen[2].method)
        assertEquals("/v1/shares/3nK_abc", seen[2].path)
        for ((ttl, pw) in listOf(59 to "aaaa-bbbb-2222", 604801 to "aaaa-bbbb-2222", 3600 to "seven77", 3600 to "with\tab-in-it", 3600 to "x".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) { api().share("alice-laptop/work/0.0", ttl, pw) }
        }
        for (bad in listOf("", "../admin", "a b", "x".repeat(65))) assertThrows(IllegalArgumentException::class.java) { api().revokeShare(bad) }
        assertEquals(3, seen.size)
    }
}
