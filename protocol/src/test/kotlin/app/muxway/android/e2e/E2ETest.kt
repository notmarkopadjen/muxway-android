package app.muxway.android.e2e

import app.muxway.android.protocol.Ansi
import app.muxway.android.protocol.Api
import app.muxway.android.protocol.Wheel
import app.muxway.android.protocol.WheelQueue
import app.muxway.android.protocol.ApiException
import app.muxway.android.protocol.Commands
import app.muxway.android.protocol.Suggest
import app.muxway.android.protocol.OpenMode
import app.muxway.android.protocol.Names
import app.muxway.android.protocol.Board
import app.muxway.android.protocol.Changes
import app.muxway.android.protocol.Clip
import app.muxway.android.protocol.Files
import app.muxway.android.protocol.Inbox
import app.muxway.android.protocol.Json
import app.muxway.android.protocol.Links
import app.muxway.android.protocol.Keyboard
import app.muxway.android.protocol.Feed
import app.muxway.android.protocol.Lens
import app.muxway.android.protocol.MailRows
import app.muxway.android.protocol.Shares
import app.muxway.android.protocol.WatchGate
import app.muxway.android.protocol.WindowLayout
import app.muxway.android.protocol.Wire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runners.MethodSorters
import java.io.File

/**
 * The protocol layer against a real hub, agent and tmux, through a recording
 * relay. Security tests first (s), then features (f), then the log check (z).
 * Skipped unless scripts/e2e.sh provides MUXWAY_BIN.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class E2ETest {
    @get:Rule
    val name = TestName()
    private lateinit var core: Core
    private lateinit var relay: Relay
    private val cleanups = ArrayList<() -> Unit>()

    @Before
    fun start() {
        assumeTrue("MUXWAY_BIN not set: end-to-end tests need the core binary", Env.bin != null)
    }

    @After
    fun stop() {
        cleanups.reversed().forEach { runCatching(it) }
    }

    private fun setup(expose: String? = "--write", write: String? = "$HOST/*", revoke: Boolean = false, allowSessions: Boolean = false, sttUrl: String? = null, allowClose: Boolean = false, inbox: Boolean = false, preview: File? = null): Pair<Api, String> {
        val logs = Env.logs(name.methodName)
        core = Core(logs).start(expose, allowSessions, sttUrl, allowClose, inbox, preview)
        cleanups.add(core::close)
        val tok = core.token(write = write)
        if (revoke) core.revokeAll()
        relay = Relay(core.url, logs)
        cleanups.add(relay::close)
        return Api(relay.url, tok, "muxway-android-e2e") to tok
    }

    private fun waitPane(contains: String) = waitFor("pane shows $contains") { core.pane().contains(contains) }

    /** A 2×2 PNG, made for the test. */
    private val png = ("89504e470d0a1a0a0000000d49484452000000020000000208060000007" +
        "2b60d24000000164944415478" + "9c63f8cfc0f09f81e13f03c37f06001af605fd958bd81d0000000049454e44ae426082").hexBytes()

    private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun inboxFiles(): List<File> = core.inbox.listFiles()?.toList().orEmpty()

    /** Null when this hub has mouse input, else the reason to skip: an older hub answers bad_json. */
    private fun mouseUnsupported(api: Api, address: String): Boolean = try {
        api.mouse(address, "left", 1, 1)
        false
    } catch (e: ApiException) {
        e.code == "bad_json"
    }

    /** A raw body with the bearer token, as a hostile or odd client would send it; the status. */
    private fun post(tok: String, address: String, body: String, op: String = "input"): Int {
        val c = java.net.URL("${core.url}/v1/panes/$address/$op").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $tok")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        return c.responseCode
    }

    /** A request without a token, as an observer's browser sends it: the status, and the share cookie it set. */
    private fun observer(method: String, path: String, body: String? = null, cookie: String? = null): Pair<Int, String?> {
        val c = java.net.URL("${core.url}$path").openConnection() as java.net.HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("X-Muxway-Client", "e2e")
        cookie?.let { c.setRequestProperty("Cookie", "muxway_share=$it") }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = c.responseCode
        val set = c.getHeaderField("Set-Cookie")?.substringAfter("muxway_share=", "")?.substringBefore(';')?.takeIf { it.isNotEmpty() }
        return status to set
    }

    /** Runs `cat -v` with mouse reporting on, so every mouse byte shows as text. */
    private fun catWithMouse(api: Api, address: String) {
        api.type(address, "printf '\\033[?1000h\\033[?1006h'; cat -v")
        api.key(address, "Enter")
        waitFor("cat running") { core.command() == "cat" }
    }

    // --- security ---

    @Test
    fun s01_revokedTokenIsRefusedWithoutEchoingIt() {
        val (api, tok) = setup(revoke = true)
        val e = assertThrows(ApiException::class.java) { api.panes() }
        assertEquals(401, e.status)
        assertEquals("unauthorized", e.code)
        assertFalse("token in the error", e.toString().contains(tok.substring(4, 20)))
    }

    @Test
    fun s02_tokenTravelsInTheBearerHeaderNeverInAUrl() {
        val (api, tok) = setup()
        api.hosts(); api.panes(); api.read("$HOST/work/0.0", 50, null); api.key("$HOST/work/0.0", "Enter"); api.events(null, 0)
        assertEquals(5, relay.seen.size)
        // Messages never quote the token: a failure would land in results.xml.
        for (r in relay.seen) {
            assertFalse("token in ${r.path.take(40)}", r.path.contains(tok))
            assertTrue("Authorization is not Bearer <token>", r.headers["authorization"] == "Bearer $tok")
            assertNull(r.headers["x-muxway-token"])
        }
    }

    @Test
    fun s03_readOnlyTokenSeesTheModeAndCannotType() {
        val (api, _) = setup(write = null)
        val pane = api.panes().single()
        assertEquals("read", pane.mode)
        assertFalse(pane.writable)
        val before = core.pane()
        val e = assertThrows(ApiException::class.java) { api.type("$HOST/work/0.0", "echo nope") }
        assertEquals(403, e.status)
        assertEquals("read_only", e.code)
        assertEquals("Read only", e.message)
        Thread.sleep(300)
        assertEquals(before, core.pane())
    }

    @Test
    fun s04_readOnlyMarkRefusesWriteEvenWithAWriteGrant() {
        val (api, _) = setup(expose = null)
        assertFalse(api.panes().single().writable)
        assertEquals("read_only", assertThrows(ApiException::class.java) { api.key("$HOST/work/0.0", "Enter") }.code)
    }

    @Test
    fun s04b_readOnlyPaneRefusesAResize() {
        val (api, _) = setup(expose = null)
        val before = api.panes().single().width
        assertEquals("read_only", assertThrows(ApiException::class.java) { api.resize("$HOST/work/0.0", 40, 12) }.code)
        assertEquals(before, api.panes().single().width)
    }

    @Test
    fun s04c_mouseIsRefusedWhereTheProgramHasNoMouseAndOnReadOnlyPanes() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        assumeTrue("hub without mouse input", !mouseUnsupported(api, address))
        val before = core.pane()
        assertEquals("bad_input", assertThrows(ApiException::class.java) { api.mouse(address, "left", 2, 2) }.code)
        Thread.sleep(300)
        assertEquals(before, core.pane())
        assertFalse(core.pane().contains("[<"))
        val ro = Api(relay.url, core.token(write = null), "muxway-android-e2e")
        assertEquals("read_only", assertThrows(ApiException::class.java) { ro.mouse(address, "left", 2, 2) }.code)
    }

    @Test
    fun s04d_aWheelCountIsCappedAndGoesWithTheWheelOnly() {
        val (api, tok) = setup()
        val address = "$HOST/work/0.0"
        assumeTrue("hub without mouse input", !mouseUnsupported(api, address))
        catWithMouse(api, address)
        assumeTrue("hub without wheel count", post(tok, address, """{"mouse":{"button":"wheeldown","x":1,"y":1,"count":2}}""") == 204)
        waitPane("^[[<65;1;1M^[[<65;1;1M")
        assertEquals(400, post(tok, address, """{"mouse":{"button":"left","x":1,"y":1,"count":2}}"""))
        assertEquals(400, post(tok, address, """{"mouse":{"button":"wheeldown","x":1,"y":1,"count":51}}"""))
        assertEquals(400, post(tok, address, """{"mouse":{"button":"wheelup","x":1,"y":1,"count":-1}}"""))
        assertEquals(400, post(tok, address, """{"mouse":{"button":"wheelup","x":1,"y":1,"count":"9"}}"""))
        assertEquals(2, Regex("\\^\\[\\[<").findAll(core.pane()).count())
        assertThrows(IllegalArgumentException::class.java) { api.mouse(address, "wheelup", 1, 1, 51) }
        assertThrows(IllegalArgumentException::class.java) { api.mouse(address, "left", 1, 1, 2) }
        api.key(address, "C-c")
        waitFor("shell back") { core.command() == "bash" }
    }

    @Test
    fun s04e_aWindowLayoutNamesExactlyTheWindowsPanesAndMovingThemNeedsWriteOnAll() {
        val (api, tok) = setup()
        val address = "$HOST/work/0.0"
        api.split(HOST, "work", 0, "h")
        waitFor("two panes") { api.panes().size == 2 }
        val ids = api.panes().map { it.paneId }
        val stacked = WindowLayout.Node.Split(false, listOf(WindowLayout.Node.Leaf(0), WindowLayout.Node.Leaf(1)))
        val sizes = listOf(WindowLayout.Size(60, 20), WindowLayout.Size(60, 15))
        val before = core.layout()
        // A stranger's id (the agent's bad_input), a pane left out (a move the hub reads as not
        // allowed), the pane scope, a layout past the cap: all refused, nothing moved.
        val stranger = WindowLayout.exact(stacked, sizes, listOf(ids[0], "%999")).layout
        assertEquals(400, post(tok, address, """{"cols":60,"rows":36,"scope":"window","layout":"$stranger"}""", "resize"))
        val one = WindowLayout.exact(WindowLayout.Node.Leaf(0), sizes.take(1), ids.take(1)).layout
        assertEquals(403, post(tok, address, """{"cols":60,"rows":20,"scope":"window","layout":"$one"}""", "resize"))
        val both = WindowLayout.exact(stacked, sizes, ids)
        assertEquals(400, post(tok, address, """{"cols":60,"rows":36,"layout":"${both.layout}"}""", "resize"))
        assertEquals(400, post(tok, address, """{"cols":60,"rows":36,"scope":"window","layout":"0000,${"1x1,0,0,1,".repeat(300)}"}""", "resize"))
        assertEquals(before, core.layout())
        // A token that may write only one pane of the window sizes it but does not move its panes.
        val partial = Api(relay.url, core.token(write = address), "muxway-android-e2e")
        val e = assertThrows(ApiException::class.java) { partial.resize(address, both.cols, both.rows, window = true, layout = both.layout) }
        assertEquals("read_only", e.code)
        assertEquals(before, core.layout())
    }

    @Test
    fun s05_hostileAnswersAreCappedCleanedOrRefused() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        relay.mode = "big"
        assertEquals("bad_answer", assertThrows(ApiException::class.java) { api.read(address, 50, null) }.code)
        relay.mode = "garbage"
        assertEquals("bad_answer", assertThrows(ApiException::class.java) { api.read(address, 50, null) }.code)
        relay.mode = "html"
        assertEquals("bad_answer", assertThrows(ApiException::class.java) { api.read(address, 50, null) }.code)
        relay.mode = "control"
        val r = api.read(address, 50, null)!!
        assertEquals("ok \u001b[31mred nul\nend\n", r.content)
        relay.mode = "normal"
        assertTrue(api.read(address, 50, null)!!.content.contains("$"))
    }

    @Test
    fun s06_controlCharactersNeverReachThePane() {
        val (api, _) = setup()
        api.type("$HOST/work/0.0", "echo \u001b[31m\u0007a\u0000b\r\n")
        api.key("$HOST/work/0.0", "Enter")
        waitPane("echo ab")
        assertFalse(core.pane().contains("\u001b"))
        assertEquals("unknown key", assertThrows(IllegalArgumentException::class.java) { api.key("$HOST/work/0.0", "C-;") }.message)
    }

    // --- features ---

    @Test
    fun f07_listHostsAndPanes() {
        val (api, _) = setup()
        val host = api.hosts().single()
        assertEquals(HOST, host.name)
        assertTrue(host.online)
        val pane = api.panes().single()
        assertEquals("$HOST/work/0.0", pane.address)
        assertEquals("work/0.0", pane.label)
        assertEquals("write", pane.mode)
        assertEquals("bash", pane.command)
        assertEquals("test-pane", pane.title)
        assertNull(pane.attention)
    }

    @Test
    fun f08_readAPaneThenTypeAndSendEnter() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        waitFor("the prompt") { core.pane().contains("$") }
        val r = api.read(address, 20, null)!!
        // Rows come as the terminal shows them: the prompt's trailing blank is not among them.
        assertTrue(r.content, r.content.lines().any { it.trimEnd() == "$" })
        assertTrue(r.etag.startsWith("\""))
        api.type(address, "echo hi there")
        api.key(address, "Enter")
        waitPane("hi there")
        waitFor("read shows the output") { api.read(address, 20, null)!!.content.contains("hi there") }
    }

    @Test
    fun f08b_twoLinesArePastedAsOneBlockThatEnterRuns() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        api.type(address, "echo first-\$((1+1))\necho second-\$((2+2))")
        waitPane("echo second-")
        api.key(address, "Enter")
        waitPane("first-2")
        waitPane("second-4")
        assertEquals("", core.tmux("list-buffers").trim())
    }

    @Test
    fun f09_controlCStopsARunningCommand() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        api.type(address, "sleep 100")
        api.key(address, "Enter")
        waitFor("sleep running") { core.command() == "sleep" }
        api.key(address, "C-c")
        waitFor("shell back") { core.command() == "bash" }
    }

    @Test
    fun f10_unchangedPaneAnswersNotModified() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        val r = api.read(address, 20, null)!!
        assertNull(api.read(address, 20, r.etag))
        api.type(address, "echo changed")
        api.key(address, "Enter")
        waitPane("echo changed")
        assertNotNull(api.read(address, 20, r.etag))
    }

    @Test
    fun f11_aNotificationArrivesOnTheLongPollAndMarksThePane() {
        val (api, _) = setup()
        val cursor = api.events(null, 0).next
        val t = Thread { Thread.sleep(500); core.notify("question", "deploy to staging?") }
        t.start()
        val ev = api.events(cursor, 10)
        t.join()
        val e = ev.events.single()
        assertEquals("question", e.kind)
        assertEquals("deploy to staging?", e.text)
        assertEquals("$HOST/work/0.0", e.address)
        assertTrue(ev.next >= e.seq)
        val pane = api.panes().single()
        assertEquals("question", pane.attention?.kind)
        api.key("$HOST/work/0.0", "Enter")
        waitFor("attention cleared") { api.panes().single().attention == null }
    }

    @Test
    fun f12_hostOfflineIsSaidSo() {
        val (api, _) = setup()
        core.stop("agent")
        waitFor("host offline") { !api.hosts().single().online }
        assertEquals(0, api.panes().size)
        val e = assertThrows(ApiException::class.java) { api.read("$HOST/work/0.0", 20, null) }
        assertEquals("host_offline", e.code)
        assertEquals("Host offline", e.message)
        core.startAgent()
        waitFor("host back") { api.hosts().single().online }
        assertEquals(1, api.panes().size)
    }

    // --- the run's own hygiene ---

    @Test
    fun f13_colourReachesTheClientAsAStyledSpan() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        api.type(address, "printf '\\033[32mgreen-word\\033[0m\\n'")
        api.key(address, "Enter")
        waitPane("green-word")
        waitFor("read shows the word") { api.read(address, 20, null)!!.content.contains("green-word") }
        val content = api.read(address, 20, null)!!.content
        val spans = Ansi.rows(content, 0).flatten()
        val green = spans.filter { it.text.contains("green-word") }
        assertTrue("one span with the word", green.size >= 1)
        assertEquals(2, green.last().style.fg)
        assertTrue("the prompt is plain", spans.any { it.style == Ansi.Style.PLAIN && it.text.contains("$") })
    }

    @Test
    fun f14_shiftTabIsANamedKey() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        api.type(address, "cat -v")
        api.key(address, "Enter")
        waitFor("cat running") { core.command() == "cat" }
        api.key(address, "BTab")
        waitPane("^[[Z")
        api.key(address, "C-c")
        waitFor("shell back") { core.command() == "bash" }
    }

    @Test
    fun f15_fitSizesThePaneOnTheHostAndReleasesIt() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        val before = api.panes().single()
        val fitted = api.resize(address, 40, 12).pane
        assertEquals(40, fitted.width)
        assertEquals(12, fitted.height)
        waitFor("tmux shows 40 columns") { core.width() == 40 }
        api.type(address, "echo " + "x".repeat(50))
        api.key(address, "Enter")
        waitPane("xxxxx")
        assertEquals(40, api.panes().single().width)
        // A line the terminal wrapped at 40 columns stays on its rows in JSON.
        val rows = api.read(address, 20, null)!!.content.lines().map { it.trimEnd() }
        assertTrue(rows.toString(), rows.contains("x".repeat(40)) && rows.contains("x".repeat(10)))
        assertFalse(rows.any { it.length > 40 })
        api.release(address)
        waitFor("tmux is back to its own size") { core.width() == before.width }
    }

    @Test
    fun f16_aClickReachesAProgramThatAskedForTheMouse() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        assumeTrue("hub without mouse input", !mouseUnsupported(api, address))
        catWithMouse(api, address)
        api.mouse(address, "left", 5, 3)
        waitPane("^[[<0;5;3M")
        waitPane("^[[<0;5;3m")
        api.key(address, "C-c")
        waitFor("shell back") { core.command() == "bash" }
    }

    @Test
    fun f16b_aCountedWheelTurnsThatManyNotches() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        assumeTrue("hub without mouse input", !mouseUnsupported(api, address))
        catWithMouse(api, address)
        try {
            api.mouse(address, "wheeldown", 5, 3, 3)
        } catch (e: ApiException) {
            assumeTrue("hub without wheel count", e.code != "bad_input")
            throw e
        }
        waitPane("^[[<65;5;3M^[[<65;5;3M^[[<65;5;3M")
        // The queue's shape, delivered: an older hub is not in the test environment, so the counted path.
        val q = WheelQueue()
        q.add(true, 6, 2, 2)
        assertTrue(Wheel.deliver(api, address, q.next()!!, counted = true))
        waitPane("^[[<64;6;2M^[[<64;6;2M")
        api.key(address, "C-c")
        waitFor("shell back") { core.command() == "bash" }
    }

    @Test
    fun f17_theBoardRanksANotifiedPaneFirstUntilItIsAnswered() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        core.tmux("split-window", "-t", "work:0", "-h")
        core.cli("expose", "--tmux-name", core.tmux, "-t", "work:0.1", "--write")
        waitFor("two panes listed") { api.panes().size == 2 }
        val quiet = Board.rank(api.panes(), 1, false) { Lens.status(it, null, false) }
        assertEquals(listOf(Board.QUIET, Board.QUIET), quiet.rows.map { it.state })
        assertEquals(address, quiet.rows[0].address)
        core.notify("question", "deploy to staging?")
        waitFor("attention listed") { api.panes().any { it.attention != null } }
        val asked = Board.rank(api.panes(), 2, true) { Lens.status(it, null, false) }
        assertEquals(address, asked.rows[0].address)
        assertEquals(Board.WAITING, asked.rows[0].state)
        assertEquals("question", asked.rows[0].kind)
        assertEquals("deploy to staging?", asked.rows[0].text)
        assertEquals(1, asked.waiting)
        assertEquals(asked, Board.parse(asked.json()))
        api.key(address, "Enter")
        waitFor("attention cleared") { api.panes().all { it.attention == null } }
        assertEquals(0, Board.rank(api.panes(), 3, false) { Lens.status(it, null, false) }.waiting)
    }

    @Test
    fun f18_aSplitMakesAWritableSiblingAndSessionsNeedTheAgentsFlag() {
        val (api, _) = setup()
        assertFalse(api.hosts().single().sessions)
        val e = assertThrows(ApiException::class.java) { api.newSession(HOST, "scratch") }
        assertEquals(404, e.status)
        assertEquals("not_found", e.code)
        // The mark sits on the pane alone; the split carries it over.
        val made = api.split(HOST, "work", 0, "v")
        assertEquals("$HOST/work/0.1", made.address)
        assertTrue(made.writable)
        assertEquals(2, api.panes().size)
        waitFor("the new shell") { core.tmux("capture-pane", "-p", "-t", made.paneId).contains("$") }
        assertThrows(IllegalArgumentException::class.java) { api.split(HOST, "work", 0, "x") }
        assertThrows(IllegalArgumentException::class.java) { api.newSession(HOST, "Scratch!") }
        assertEquals("work", core.tmux("list-sessions", "-F", "#{session_name}"))
    }

    @Test
    fun f19_aSessionStartsWhereTheAgentAllowsItAndNotForAReadOnlyToken() {
        val (api, _) = setup(allowSessions = true)
        assertTrue(api.hosts().single().sessions)
        val readOnly = Api(relay.url, core.token(write = null), "muxway-android-e2e")
        val e = assertThrows(ApiException::class.java) { readOnly.newSession(HOST, "scratch") }
        assertEquals(403, e.status)
        assertEquals("read_only", e.code)
        val made = api.newSession(HOST, "scratch")
        assertEquals("$HOST/scratch/0.0", made.address)
        assertTrue(made.writable)
        waitFor("the session's shell") { core.tmux("capture-pane", "-p", "-t", made.paneId).contains("$") }
        // The name is taken now; another token, since one creation per two seconds is each one's.
        val again = assertThrows(ApiException::class.java) { Api(relay.url, core.token(), "muxway-android-e2e").newSession(HOST, "scratch") }
        assertEquals(409, again.status)
        assertEquals("exists", again.code)
        assertEquals(2, api.panes().size)
    }

    @Test
    fun s16_anAnswerNeedsAWriteGrantAndAChoiceTheQuestionOfferedAndAQuestionStillWaiting() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        val ask = core.ask(core.paneId(), "deploy to staging?", "yes", "no")
        cleanups.add { ask.kill() }
        waitFor("the question listed with its choices") { api.panes().single().attention?.choices == listOf("yes", "no") }
        // A read grant alone sees the question and cannot answer it.
        val reader = Api(relay.url, core.token(write = null), "muxway-android-e2e")
        assertEquals(listOf("yes", "no"), reader.inbox().single().attention?.choices)
        val e1 = assertThrows(ApiException::class.java) { reader.answer(address, "yes") }
        assertEquals(403, e1.status)
        // A choice the question did not offer never reaches the asking process.
        val e2 = assertThrows(ApiException::class.java) { api.answer(address, "maybe") }
        assertEquals(400, e2.status)
        assertEquals("bad_input", e2.code)
        assertTrue(ask.alive)
        assertEquals(listOf("yes", "no"), api.panes().single().attention?.choices)
        // The right one ends the ask with the choice on its stdout and clears the attention.
        api.answer(address, "yes")
        assertEquals(0 to "yes", ask.wait())
        waitFor("attention cleared") { api.panes().single().attention == null }
        val e3 = assertThrows(ApiException::class.java) { api.answer(address, "yes") }
        assertEquals(409, e3.status)
        assertEquals("no_question", e3.code)
        assertEquals("Nothing waits there any more", e3.message)
        // A read-only pane cannot ask: the mark decides on the host.
        core.tmux("split-window", "-t", "work:0", "-h")
        core.cli("expose", "--tmux-name", core.tmux, "-t", "work:0.1")
        val second = core.tmux("display-message", "-p", "-t", "work:0.1", "#{pane_id}")
        val refused = core.ask(second, "deploy?", "yes", "no")
        cleanups.add { refused.kill() }
        assertEquals(1, refused.wait().first)
        assertTrue(api.panes().all { it.attention == null })
    }

    @Test
    fun s16b_theInboxFollowsTheTokensReadGrants() {
        val (api, _) = setup()
        core.tmux("split-window", "-t", "work:0", "-h")
        core.cli("expose", "--tmux-name", core.tmux, "-t", "work:0.1", "--write")
        val second = core.tmux("display-message", "-p", "-t", "work:0.1", "#{pane_id}")
        core.notify("done", "finished", second)
        val ask = core.ask(core.paneId(), "deploy to staging?", "yes", "no")
        cleanups.add { ask.kill() }
        waitFor("both wait") { api.inbox().size == 2 }
        val narrow = Api(relay.url, core.token(read = "$HOST/work/0.1", write = null), "muxway-android-e2e")
        val rows = narrow.inbox()
        assertEquals(listOf("$HOST/work/0.1"), rows.map { it.address })
        assertEquals("done", rows.single().attention?.kind)
        assertTrue(narrow.panes().none { it.attention?.choices?.isNotEmpty() == true })
    }

    @Test
    fun f27_aQuestionsChoicesRideTheEventsTheInboxComesOldestFirstAndTheGateAnswersOnlyWhatWasOffered() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        val cursor = api.events(null, 0).next
        core.tmux("split-window", "-t", "work:0", "-h")
        core.cli("expose", "--tmux-name", core.tmux, "-t", "work:0.1", "--write")
        val second = core.tmux("display-message", "-p", "-t", "work:0.1", "#{pane_id}")
        core.notify("done", "finished", second)
        // Attention is timed in seconds: the question must come a tick later to be the newer.
        Thread.sleep(1_100)
        val ask = core.ask(core.paneId(), "deploy to staging?", "yes", "no")
        cleanups.add { ask.kill() }
        waitFor("both wait") { api.inbox().size == 2 }
        val inbox = api.inbox()
        assertEquals(listOf("$HOST/work/0.1", address), inbox.map { it.address })
        assertEquals(listOf("yes", "no"), inbox[1].attention?.choices)
        assertEquals(inbox.map { it.address }, Inbox.waiting(api.panes()).map { it.address })
        val ev = api.events(cursor, 0).events
        val q = ev.single { it.kind == "question" }
        assertEquals(listOf("yes", "no"), q.choices)
        assertEquals("deploy to staging?", q.text)
        assertTrue(ev.single { it.kind == "done" }.choices.isEmpty())
        // The watch answers through the phone's gate, which takes only a choice the listing carries.
        val gate = WatchGate({ api }, { api.panes() }, { true })
        assertEquals(WatchGate.REFUSED, gate.answer(Wire.Answer("a1", address, "later")).error)
        assertEquals(WatchGate.REFUSED, gate.answer(Wire.Answer("a2", "$HOST/work/0.1", "yes")).error)
        assertTrue(ask.alive)
        assertTrue(gate.answer(Wire.Answer("a3", address, "no")).ok)
        assertEquals(0 to "no", ask.wait())
        waitFor("the question gone") { api.inbox().size == 1 }
        assertEquals("$HOST/work/0.1", api.inbox().single().address)
        // The board carries the choices to the wrist while the question waits, and nothing after.
        val board = Board.rank(api.panes(), 1, true) { Lens.status(it, null, false) }
        assertTrue(board.rows.all { it.choices.isEmpty() })
    }

    @Test
    fun f20_anAdminReadsTheLogAndEndsASessionAndNoOneElseDoes() {
        val (api, _) = setup()
        assertFalse(api.isAdmin())
        for (call in listOf({ api.adminLog() }, { api.adminClients() }, { api.endSession("Xq3kP0aZ") })) {
            val e = assertThrows(ApiException::class.java) { call() }
            assertEquals(403, e.status)
            assertEquals("forbidden", e.code)
        }
        val tok = core.cli("hub", "token", "create", "--dir", core.hubDir.path, "--name", "owner", "--read", "*", "--admin")
        Env.secrets.add(tok)
        val admin = Api(relay.url, tok, "muxway-android-e2e")
        assertTrue(admin.isAdmin())
        val log = admin.adminLog()
        assertTrue(log.any { it.msg == "agent connected" && it.attrs["host"] == HOST })
        assertTrue(log.any { it.msg == "admin refused" && it.warning })
        assertTrue(log.none { line -> line.attrs.values.any { it.contains(tok) } })

        // A browser signs in with the first token; the admin sees it and ends it.
        val signIn = java.net.URL("${core.url}/v1/auth/token").openConnection() as java.net.HttpURLConnection
        signIn.requestMethod = "POST"
        signIn.doOutput = true
        signIn.setRequestProperty("Content-Type", "application/json")
        signIn.setRequestProperty("X-Muxway-Client", "web")
        signIn.outputStream.use { it.write("""{"token":"${core.token()}"}""".toByteArray()) }
        assertEquals(204, signIn.responseCode)
        val cookie = signIn.getHeaderField("Set-Cookie").substringBefore(';')
        Env.secrets.add(cookie.substringAfter('='))
        val clients = admin.adminClients()
        assertTrue(clients.any { it.client == "muxway-android-e2e" && !it.endable })
        val session = clients.single { it.endable }
        assertEquals("web", session.client)
        assertEquals("phone", session.who)
        admin.endSession(session.id)
        val me = java.net.URL("${core.url}/v1/me").openConnection() as java.net.HttpURLConnection
        me.setRequestProperty("Cookie", cookie)
        me.setRequestProperty("X-Muxway-Client", "web")
        assertEquals(401, me.responseCode)
        assertTrue(admin.adminClients().none { it.endable })
        assertThrows(IllegalArgumentException::class.java) { admin.endSession("../tokens/x") }
        assertThrows(IllegalArgumentException::class.java) { admin.adminLog(501) }
    }

    @Test
    fun f20_theWatchGateAnswersAWaitingPaneWithEnterAndShowsTheTail() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        var allowed = false
        val gate = WatchGate({ api }, { api.panes() }, { allowed })
        api.type(address, "read -p 'Deploy to prod? (y/N) ' a; echo \"answer=\$a\"")
        api.key(address, "Enter")
        waitPane("Deploy to prod?")
        core.notify("question", "Deploy to prod?")
        waitFor("attention listed") { api.panes().any { it.attention != null } }
        val tail = gate.read(Wire.Read("r1", address, ""))
        assertEquals("", tail.error)
        assertTrue(tail.lines!!.joinToString("\n"), tail.lines!!.any { it.contains("Deploy to prod?") })
        assertTrue(tail.writable)
        assertFalse(tail.transcriber)
        assertNull(gate.read(Wire.Read("r2", address, tail.etag)).lines)
        // Not allowed yet: nothing reaches the pane.
        assertEquals(WatchGate.NOT_ALLOWED, gate.text(Wire.Text("t0", address, "y", "", true)).error)
        assertEquals(WatchGate.NOT_ALLOWED, gate.key(Wire.Key("k0", address, "Enter")).error)
        assertFalse(core.pane().contains("answer=y"))
        allowed = true
        assertEquals(WatchGate.REFUSED, gate.key(Wire.Key("k1", address, "F1")).error)
        assertEquals(WatchGate.REFUSED, gate.text(Wire.Text("t1", address, "\u0007", "", true)).error)
        assertTrue(gate.text(Wire.Text("t2", address, "y", "", true)).ok)
        waitPane("answer=y")
        waitFor("attention cleared") { api.panes().all { it.attention == null } }
        val after = gate.read(Wire.Read("r3", address, tail.etag))
        assertTrue(after.lines!!.any { it.contains("answer=y") })
    }

    @Test
    fun f21_aClipHeardByTheHubIsTypedOnlyAsTheGateHeardIt() {
        val stt = TinyServer { r ->
            if (r.path == "/v1/audio/transcriptions" && r.method == "POST") Response(200, """{"text":"echo heard-on-the-wrist"}""".toByteArray(), mapOf("Content-Type" to "application/json"))
            else Response(404)
        }
        cleanups.add(stt::close)
        val (api, _) = setup(sttUrl = stt.url + "/v1/audio/transcriptions")
        val address = "$HOST/work/0.0"
        val gate = WatchGate({ api }, { api.panes() }, { true })
        assertTrue(gate.read(Wire.Read("r1", address, "")).transcriber)
        val heard = gate.transcribe("c1", Clip.wav(ByteArray(Clip.SAMPLE_RATE * 2)))
        assertEquals("", heard.error)
        assertEquals("echo heard-on-the-wrist", heard.text)
        assertEquals(WatchGate.REFUSED, gate.text(Wire.Text("t0", address, "", "c9", true)).error)
        assertTrue(gate.text(Wire.Text("t1", address, "", "c1", true)).ok)
        waitPane("heard-on-the-wrist")
        assertEquals(WatchGate.TOO_SHORT, gate.transcribe("c2", Clip.wav(ByteArray(100))).error)
    }

    @Test
    fun f22_aPaneAloneSizesItsWindowAndTmuxZoomsItOverItsSiblings() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        api.split(HOST, "work", 0, "v")
        waitFor("two panes") { api.panes().size == 2 }
        assertFalse(core.zoomed())
        val fitted = api.resize(address, 60, 20, window = true, zoom = true).pane
        assertEquals(60, fitted.width)
        assertEquals(20, fitted.height)
        // Zoomed, the pane has the whole window: its sibling is not squeezed to make room.
        waitFor("tmux zooms the pane") { core.zoomed() }
        waitFor("the pane fills the window") { core.width() == 60 }
        api.release(address)
        waitFor("the window is unzoomed and back to its own size") { !core.zoomed() && core.width() != 60 }
    }

    @Test
    fun f25_tilesThatAreEveryPaneOfAWindowFitItAsOneLaidOutAsTheTiles() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        api.split(HOST, "work", 0, "h")
        waitFor("two panes") { api.panes().size == 2 }
        val ids = api.panes().map { it.paneId }
        assertFalse(core.manual())
        assertTrue(core.layout(), core.layout().contains("{"))
        // The phone stacks the two tiles: the host's window takes that shape at the tiles' exact sizes.
        val stacked = WindowLayout.Node.Split(false, listOf(WindowLayout.Node.Leaf(0), WindowLayout.Node.Leaf(1)))
        val sizes = listOf(WindowLayout.Size(60, 20), WindowLayout.Size(60, 15))
        val exact = WindowLayout.exact(stacked, sizes, ids)
        assertEquals(60, exact.cols)
        assertEquals(36, exact.rows)
        val r = api.resize(address, exact.cols, exact.rows, window = true, layout = exact.layout)
        assertEquals(60, r.pane.width)
        assertEquals(20, r.pane.height)
        waitFor("tmux stacks the panes") { core.layout().contains("[") }
        assertTrue(core.manual())
        assertEquals("60x20", core.size("work:0.0"))
        assertEquals("60x15", core.size("work:0.1"))
        // The tiles swapped: the panes move on the host, and the answer lists them at their new addresses.
        Thread.sleep(1_100)
        val swapped = WindowLayout.exact(stacked, sizes, ids.reversed())
        val m = api.resize(address, swapped.cols, swapped.rows, window = true, layout = swapped.layout)
        assertEquals(2, m.panes.size)
        assertEquals(ids[1], m.panes.first { it.address == address }.paneId)
        waitFor("the listing follows") { api.panes().first().paneId == ids[1] }
        // Released, the window is its clients' to size again (none here, so it keeps the size it
        // has); panes that were moved stay where they are.
        api.release(address)
        waitFor("the window is back to automatic sizing") { !core.manual() }
        assertTrue(core.layout(), core.layout().contains("["))
    }

    /** A preview root with a repository under it, and one outside; the pane's shell in the repository's `src`. */
    @Test
    fun s26_theDirectoryIsListedForWritersOnHostsWithRootsAndOnlyUnderThemWithNoNameAShellCannotTake() {
        val (root, repo) = repoUnderRoot()
        File(repo, "bad\u0007name").writeText("x\n")
        val (api, _) = setup(preview = root)
        val address = "$HOST/work/0.0"
        core.cdTo("work:0.0", repo)
        waitFor("cwd listed") { api.panes().single().cwd != null }
        // A reader has no cwd and is refused; so is a writer of a read-only pane.
        val reader = Api(relay.url, core.token(write = null), "muxway-android-e2e")
        assertNull(reader.panes().single().cwd)
        assertEquals("read_only", assertThrows(ApiException::class.java) { reader.dir(address, null) }.code)
        core.tmux("split-window", "-t", "work:0", "-v", "-c", repo.path)
        core.cli("expose", "--tmux-name", core.tmux, "-t", "work:0.1")
        waitFor("two panes listed") { api.panes().size == 2 }
        assertNull(api.panes().first { it.address == "$HOST/work/0.1" }.cwd)
        assertEquals("read_only", assertThrows(ApiException::class.java) { api.dir("$HOST/work/0.1", null) }.code)
        // Names a shell cannot take whole, and the hidden ones, are not listed.
        val names = api.dir(address, null)!!.first.entries.map { it.name }
        assertTrue(names.toString(), names.none { it.startsWith("bad") })
        assertFalse(names.toString(), ".git" in names)
        // Outside the roots there is no cwd and one refusal; without roots the same, and no dir flag.
        core.cdTo("work:0.0", File(root.parentFile, "elsewhere/repo"))
        waitFor("cwd gone") { api.panes().first { it.address == address }.cwd == null }
        assertEquals("not_found", assertThrows(ApiException::class.java) { api.dir(address, null) }.code)
        core.stop("agent")
        core.startAgent()
        waitFor("no roots listed") { !api.hosts().single().dir }
        core.cdTo("work:0.0", repo)
        assertNull(api.panes().first { it.address == address }.cwd)
        assertEquals("not_found", assertThrows(ApiException::class.java) { api.dir(address, null) }.code)
    }

    @Test
    fun f28_aWritersShellPaneCarriesItsDirectoryWhoseListingNamesTheEntriesAndFollowsACd() {
        val (root, repo) = repoUnderRoot()
        val (api, _) = setup(preview = root)
        val address = "$HOST/work/0.0"
        assertTrue(api.hosts().single().dir)
        core.cdTo("work:0.0", repo)
        waitFor("cwd listed") { api.panes().single().cwd != null }
        assertEquals(repo.canonicalPath, File(api.panes().single().cwd!!).canonicalPath)
        val (listing, etag) = api.dir(address, null)!!
        assertEquals(repo.canonicalPath, File(listing.path).canonicalPath)
        val kinds = listing.entries.associate { it.name to it.kind }
        assertEquals("dir", kinds["src"])
        assertEquals("file", kinds["notes.txt"])
        assertEquals("file", kinds["with space.txt"])
        assertFalse(listing.truncated)
        // The same again with the tag is no body.
        assertTrue(etag.isNotEmpty())
        assertNull(api.dir(address, etag))
        // The names on a screen, as the tile would find them, and what a tap on one types.
        val index = Names.index(listing.entries)
        assertEquals(listOf("notes.txt", "src", "with space.txt"), Names.find("notes.txt  src  'with space.txt'", index).map { it.name })
        assertEquals("cd -- 'src'", Names.lineFor("dir", "src", OpenMode.PREVIEW, ""))
        // A cd changes the directory, and the listing with it.
        api.type(address, "cd -- 'src'")
        api.key(address, "Enter")
        waitFor("cwd follows") { api.panes().single().cwd?.let { File(it).canonicalPath } == File(repo, "src").canonicalPath }
        val inside = api.dir(address, etag)!!.first
        assertTrue(inside.entries.any { it.name == "a.go" && it.kind == "file" })
    }

    @Test
    fun f29_theGhostWordIsTheClientsDrawingAndReachesThePaneOnlyWhenTakenAsText() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        api.type(address, "echo one two")
        api.key(address, "Enter")
        waitPane("one two")
        val pool = Commands.remembered(emptyList(), "echo one two")
        assertEquals(listOf("echo one two"), pool)
        // Typed direct: the shadow follows the keys, and the ghost shows once the screen confirms it.
        var shadow: String? = ""
        api.type(address, "echo o")
        shadow = Suggest.nextShadow(shadow, Suggest.Body.Text("echo o"))
        waitPane("$ echo o")
        val pane = api.panes().single()
        val read = api.read(address, 50, null)!!
        val rows = read.content.lines().map { Ansi.plain(it) }
        val row = rows[rows.size - (pane.height - read.trimmed) + pane.cursorY - 1]
        assertTrue(row, row.endsWith("echo o"))
        assertEquals("ne two", Suggest.suggest(row, pane.cursorX, shadow, pool))
        // The host shows what was typed and nothing more: the ghost is the client's alone.
        assertEquals(core.pane(), 1, core.pane().lines().count { it.contains("echo one two") })
        // Taken: ordinary text, then Enter runs the whole line.
        api.type(address, "ne two")
        api.key(address, "Enter")
        waitFor("the line ran twice") { core.pane().lines().count { it.trim() == "one two" } == 2 }
        // The hub audits bytes, never the text.
        val log = File(Env.logs(name.methodName), "hub.log")
        waitFor("audit lines") { log.exists() && log.readText().contains("text_bytes=6") }
        assertFalse(log.readText().contains("one two"))
    }

    private fun repoUnderRoot(): Pair<File, File> {
        val base = java.nio.file.Files.createTempDirectory("mxw-git-").toFile()
        val root = File(base, "work").also { it.mkdirs() }
        val repo = File(root, "repo").also { it.mkdirs() }
        gitRepo(repo)
        val outside = File(base, "elsewhere/repo").also { it.mkdirs() }
        gitRepo(outside)
        cleanups.add { base.deleteRecursively() }
        return root to repo
    }

    @Test
    fun s15_theRepositoryIsForWritersOnHostsWithRootsAndOnlyUnderThem() {
        val (root, repo) = repoUnderRoot()
        val (api, _) = setup(preview = root)
        val address = "$HOST/work/0.0"
        core.cdTo("work:0.0", File(repo, "src"))
        // A reader gets nothing, nor does a writer of a read-only pane.
        val reader = Api(relay.url, core.token(write = null), "muxway-android-e2e")
        assertEquals("read_only", assertThrows(ApiException::class.java) { reader.git(address) }.code)
        core.tmux("split-window", "-t", "work:0", "-v")
        core.cli("expose", "--tmux-name", core.tmux, "-t", "work:0.1")
        waitFor("two panes listed") { api.panes().size == 2 }
        assertEquals("read_only", assertThrows(ApiException::class.java) { api.git("$HOST/work/0.1") }.code)
        // Outside the roots, one refusal for everything; a path the status did not list is nothing too.
        core.cdTo("work:0.0", File(root.parentFile, "elsewhere/repo"))
        assertEquals("not_found", assertThrows(ApiException::class.java) { api.git(address) }.code)
        core.cdTo("work:0.0", File(repo, "src"))
        waitFor("the repository again") { runCatching { api.git(address) }.isSuccess }
        assertEquals("not_found", assertThrows(ApiException::class.java) { api.gitDiff(address, "src/same.go") }.code)
        assertEquals("not_found", assertThrows(ApiException::class.java) { api.gitDiff(address, "../elsewhere/repo/src/a.go") }.code)
        assertThrows(IllegalArgumentException::class.java) { api.gitDiff(address, "src/a.go\u0007") }
        // No roots: the host answers as a host without git.
        core.stop("agent")
        core.startAgent()
        waitFor("no roots listed") { !api.hosts().single().preview }
        assertEquals("not_found", assertThrows(ApiException::class.java) { api.git(address) }.code)
    }

    @Test
    fun f26_theRepositorysChangesAreListedAndADiffComesBackAsText() {
        val (root, repo) = repoUnderRoot()
        val (api, _) = setup(preview = root)
        val address = "$HOST/work/0.0"
        assertTrue(api.hosts().single().preview)
        core.cdTo("work:0.0", File(repo, "src"))
        val c = api.git(address)
        assertEquals("repo", c.repo)
        assertEquals("main", c.branch)
        val byPath = c.entries.associateBy { it.path }
        assertEquals("modified", byPath.getValue("src/a.go").kind)
        assertEquals("added", byPath.getValue("src/new.go").kind)
        assertTrue(byPath.getValue("src/new.go").staged)
        assertEquals("renamed", byPath.getValue("src/moved.go").kind)
        assertEquals("src/old.go", byPath.getValue("src/moved.go").from)
        assertEquals("deleted", byPath.getValue("src/gone.go").kind)
        assertEquals("untracked", byPath.getValue("notes.txt").kind)
        assertEquals("untracked", byPath.getValue("with space.txt").kind)
        assertEquals("+3 ~2 −1", Changes.summary(Changes.counts(c.entries)))
        val tree = Changes.tree(c.entries)
        assertEquals(listOf("src"), tree.dirs.map { it.name })
        val d = api.gitDiff(address, "src/a.go")
        assertFalse(d.binary)
        assertTrue(d.diff, d.diff.lines().contains("+func A() {}"))
        assertEquals("not_found", assertThrows(ApiException::class.java) { api.gitDiff(address, "notes.txt") }.code)
    }

    @Test
    fun s13_closingAPaneNeedsTheAgentsFlagAWriteGrantAndAWritablePane() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        assertFalse("the agent does not close panes", api.hosts().single().close)
        val refused = assertThrows(ApiException::class.java) { api.closePane(address) }
        assertEquals(404, refused.status)
        assertEquals("not_found", refused.code)
        assertEquals(1, api.panes().size)
    }

    @Test
    fun s13b_aPaneClosesWhereTheAgentAllowsItAndNotForAReadOnlyToken() {
        val (api, _) = setup(allowClose = true)
        assertTrue("the agent closes panes", api.hosts().single().close)
        val made = api.split(HOST, "work", 0, "v")
        waitFor("two panes") { api.panes().size == 2 }
        val reader = Api(relay.url, core.token(write = null), "muxway-android-e2e")
        val readOnly = assertThrows(ApiException::class.java) { reader.closePane(made.address) }
        assertEquals(403, readOnly.status)
        assertEquals("read_only", readOnly.code)
        assertEquals(2, api.panes().size)
        // The creation rate limit is shared, so the split just made is let go of first.
        Thread.sleep(2_100)
        api.closePane(made.address)
        waitFor("the pane is gone from the host") { core.tmux("list-panes", "-t", "work", "-F", "#{pane_id}").lines().size == 1 }
        waitFor("and from the listing") { api.panes().size == 1 }
    }

    @Test
    fun s14_filesNeedAnInboxAWriteGrantAndAWritablePane() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        // No --inbox: the probe says no, a file is not found, and no inbox directory appears.
        assertFalse(api.takesFiles(address))
        val none = assertThrows(ApiException::class.java) { api.sendFile(address, png, "image/png") }
        assertEquals(404, none.status)
        assertEquals("not_found", none.code)
        assertFalse("an inbox without --inbox", core.inbox.exists())
        core.stop("agent")
        core.startAgent(inbox = true)
        assertTrue(api.takesFiles(address))
        // A read-only token sees no button and is refused.
        val reader = Api(relay.url, core.token(write = null), "muxway-android-e2e")
        assertFalse(reader.takesFiles(address))
        val ro = assertThrows(ApiException::class.java) { reader.sendFile(address, png, "image/png") }
        assertEquals(403, ro.status)
        assertEquals("read_only", ro.code)
        // A pane marked read only is refused even with a write grant.
        core.tmux("split-window", "-t", "work:0", "-v")
        core.cli("expose", "--tmux-name", core.tmux, "-t", "work:0.1")
        waitFor("two panes listed") { api.panes().size == 2 }
        assertFalse(api.takesFiles("$HOST/work/0.1"))
        assertEquals("read_only", assertThrows(ApiException::class.java) { api.sendFile("$HOST/work/0.1", png, "image/png") }.code)
        assertEquals("not_found", assertThrows(ApiException::class.java) { api.sendFile("$HOST/work/7.0", png, "image/png") }.code)
        assertEquals(0, inboxFiles().size)
        // The token never travels in the body or the URL of an upload.
        val up = relay.seen.filter { it.method == "POST" && it.path.endsWith("/files") }
        assertTrue(up.isNotEmpty())
        for (r in up) assertTrue(r.headers["content-type"]!!.startsWith("multipart/form-data; boundary="))
    }

    @Test
    fun s14b_aFileMustBeWhatItsTypeSaysAndOneGoesEveryTwoSeconds() {
        val (api, _) = setup(inbox = true)
        val address = "$HOST/work/0.0"
        val lie = assertThrows(ApiException::class.java) { api.sendFile(address, "\u007fELF not a picture".toByteArray(), "image/png") }
        assertEquals(415, lie.status)
        assertEquals("unsupported", lie.code)
        assertEquals("Not a type the host takes", lie.message)
        val control = assertThrows(ApiException::class.java) { api.sendFile(address, "red \u001b[31m text".toByteArray(), "text/plain") }
        assertEquals("unsupported", control.code)
        assertEquals(0, inboxFiles().size)
        // The hub takes one file every two seconds per token; a refused one costs no slot.
        api.sendFile(address, "one\n".toByteArray(), "text/plain")
        val fast = assertThrows(ApiException::class.java) { api.sendFile(address, "two\n".toByteArray(), "text/plain") }
        assertEquals(429, fast.status)
        assertEquals("rate_limited", fast.code)
        Thread.sleep(2_100)
        api.sendFile(address, "three\n".toByteArray(), "text/plain")
        assertEquals(2, inboxFiles().size)
        // Over the cap, the client refuses before any request.
        val before = relay.seen.size
        assertThrows(IllegalArgumentException::class.java) { api.sendFile(address, ByteArray(Files.MAX_BYTES + 1), "text/plain") }
        assertThrows(IllegalArgumentException::class.java) { api.sendFile(address, png, "image/svg+xml") }
        assertEquals(before, relay.seen.size)
    }

    @Test
    fun f24_aPngLandsInTheInboxAndItsPathComesBackForTheLine() {
        val (api, _) = setup(inbox = true)
        val address = "$HOST/work/0.0"
        assertTrue(api.takesFiles(address))
        val sent = api.sendFile(address, png, "image/png")
        assertEquals(png.size.toLong(), sent.bytes)
        val f = File(sent.path)
        assertEquals(core.inbox.canonicalPath, f.parentFile!!.canonicalPath)
        assertTrue(f.name, Regex("^\\d{8}-\\d{6}-[a-z0-9]{6}\\.png$").matches(f.name))
        assertTrue(png.contentEquals(f.readBytes()))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(f.toPath())))
        // What the line shows afterwards: the path after what was typed, a space after it.
        assertEquals("what is wrong in ${sent.path} ", Files.onLine("what is wrong in", sent.path))
        // Nothing was typed into the pane.
        Thread.sleep(300)
        assertFalse(core.pane().contains("inbox"))
        Thread.sleep(2_100)
        val text = api.sendFile(address, "{\"a\": 1}".toByteArray(), "application/json")
        assertTrue(text.path.endsWith(".json"))
        assertEquals(2, inboxFiles().size)
    }

    @Test
    fun f23_theKeyboardsKeysReachThePaneAsTheHostTakesThem() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        assertEquals(2, api.hosts().single().keys)
        // What the board sends for a few taps: text, then Ctrl latched and U kills the line.
        var s = Keyboard.State()
        fun tap(label: String) {
            val cap = Keyboard.CAPS.first { it.label == label }
            val r = Keyboard.press(cap.act, s, grammar = true)
            s = r.state
            when (val x = r.send) {
                is Keyboard.Text -> api.type(address, x.text)
                is Keyboard.Key -> api.key(address, x.name)
                null -> {}
            }
        }
        api.type(address, "echo gone")
        tap("Ctrl"); tap("U")
        "echo kbd".forEach { tap(if (it == ' ') "Space" else it.uppercaseChar().toString()) }
        tap("Enter ⏎")
        waitPane("kbd")
        assertFalse(core.pane().contains("gone"))
        // A chord the earlier list never had goes through too.
        api.key(address, "C-M-Delete")
    }

    @Test
    fun s30_namingNeedsAWriteGrantAndAWellFormedNameNoOtherPaneHas() {
        val (api, tok) = setup()
        val address = "$HOST/work/0.0"
        assertTrue("the agent names panes", api.hosts().single().rename)
        val reader = Api(relay.url, core.token(write = null), "muxway-android-e2e")
        val readOnly = assertThrows(ApiException::class.java) { reader.rename(address, "review") }
        assertEquals(403, readOnly.status)
        assertEquals("read_only", readOnly.code)
        // The phone refuses what the hub would before any request leaves it.
        for (bad in listOf("Review", "a b", "a".repeat(33), "../x", "\$(id)")) {
            assertThrows(bad, IllegalArgumentException::class.java) { api.rename(address, bad) }
        }
        // A raw client sending them gets the hub's refusal, and nothing reaches tmux.
        for (bad in listOf("\"Review\"", "\"a b\"", "\"\$(id)\"", "\"${"a".repeat(33)}\"", "1")) {
            assertEquals(bad, 400, post(tok, address, "{\"alias\":$bad}", op = "alias"))
        }
        assertEquals("", api.panes().single().alias)
        // A name another pane of the host has is refused, so a grant by alias cannot be shadowed.
        val made = api.split(HOST, "work", 0, "v")
        waitFor("two panes") { api.panes().size == 2 }
        assertEquals("review", api.rename(made.address, "review").alias)
        val taken = assertThrows(ApiException::class.java) { api.rename(address, "review") }
        assertEquals(409, taken.status)
        assertEquals("alias_taken", taken.code)
        assertEquals("", api.panes().first { it.address == address }.alias)
    }

    @Test
    fun s30b_aReadOnlyPaneIsNotNamedEvenWithAWriteGrant() {
        val (api, _) = setup(expose = null)
        val refused = assertThrows(ApiException::class.java) { api.rename("$HOST/work/0.0", "review") }
        assertEquals(403, refused.status)
        assertEquals("read_only", refused.code)
        assertEquals("", api.panes().single().alias)
    }

    @Test
    fun f31_aNamedPaneGoesByItsAliasAndAnyProgramUnderARootCarriesItsFolder() {
        val (root, repo) = repoUnderRoot()
        val (api, _) = setup(preview = root)
        val address = "$HOST/work/0.0"
        assertEquals("review", api.rename(address, "review").alias)
        waitFor("the listing names it") { api.panes().single().alias == "review" }
        val listed = api.panes().single()
        assertEquals("review", listed.tab)
        assertTrue(listed.name, listed.name.startsWith("review · bash"))
        // The alias is an address.
        assertEquals(address, api.read("$HOST/@review", 24, null)!!.pane.address)
        // A program that is no shell keeps the folder for a writer, as the header shows it.
        core.cdTo("work:0.0", repo)
        api.type(address, "cat")
        api.key(address, "Enter")
        waitFor("cat running") { core.command() == "cat" }
        waitFor("cwd listed") { api.panes().single().cwd != null }
        val inCat = api.panes().single()
        assertEquals("cat", inCat.command)
        assertEquals(repo.canonicalPath, File(inCat.cwd!!).canonicalPath)
        assertEquals("repo", Lens.folder(inCat))
        api.key(address, "C-c")
        // Empty takes the name away.
        assertEquals("", api.rename(address, "").alias)
        waitFor("unnamed") { api.panes().single().alias.isEmpty() }
        assertEquals("bash · test-pane", api.panes().single().tab)
    }

    @Test
    fun s32_parkingNeedsAWriteGrantAndOneBoolean() {
        val (api, tok) = setup()
        val address = "$HOST/work/0.0"
        assertTrue("the agent parks panes", api.hosts().single().later)
        val reader = Api(relay.url, core.token(write = null), "muxway-android-e2e")
        val readOnly = assertThrows(ApiException::class.java) { reader.later(address, true) }
        assertEquals(403, readOnly.status)
        assertEquals("read_only", readOnly.code)
        // A raw client sending anything but one boolean gets the hub's refusal.
        for (bad in listOf("""{"later":"yes"}""", """{"later":true,"text":"x"}""", "{}", "[]")) {
            assertEquals(bad, 400, post(tok, address, bad, op = "later"))
        }
        val listed = api.panes().single()
        assertFalse(listed.later)
        assertEquals(0L, listed.laterAt)
        assertEquals(Lens.Status.QUIET, Lens.status(listed, null, false))
    }

    @Test
    fun s32b_aReadOnlyPaneIsNotParkedEvenWithAWriteGrant() {
        val (api, _) = setup(expose = null)
        val refused = assertThrows(ApiException::class.java) { api.later("$HOST/work/0.0", true) }
        assertEquals(403, refused.status)
        assertEquals("read_only", refused.code)
        assertFalse(api.panes().single().later)
    }

    @Test
    fun f33_aParkedPaneIsLaterEverywhereComesLastInTheInboxAndWakesWhenItsOwnerComesBack() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        core.notify("done", "finished")
        waitFor("attention listed") { api.panes().single().attention?.kind == "done" }
        val before = System.currentTimeMillis() / 1000 - 1
        api.later(address, true)
        waitFor("parked in the listing") { api.panes().single().later }
        val parked = api.panes().single()
        assertTrue(parked.laterAt >= before)
        assertEquals("done", parked.attention?.kind)
        // The lens says later over the notification; the board counts it as done, with the mark.
        assertEquals(Lens.Status.LATER, Lens.status(parked, "working", true))
        assertEquals("☾", Lens.glyph(Lens.Status.LATER, "done"))
        val board = Board.rank(listOf(parked), 1, true) { Lens.status(it, null, false) }
        assertEquals(Board.QUIET, board.rows.single().state)
        assertTrue(board.rows.single().later)
        assertEquals("☾", board.rows.single().glyph)
        assertEquals("", board.rows.single().text)
        assertEquals(0, board.waiting)
        assertEquals(board, Board.parse(board.json()))
        // A second pane with a question comes before the parked one in the inbox, whatever their ages.
        core.tmux("split-window", "-t", "work:0", "-h")
        core.cli("expose", "--tmux-name", core.tmux, "-t", "work:0.1", "--write")
        waitFor("two panes listed") { api.panes().size == 2 }
        val second = core.tmux("display-message", "-p", "-t", "work:0.1", "#{pane_id}").trim()
        core.notify("question", "deploy to staging?", pane = second)
        waitFor("both in the inbox") { api.inbox().size == 2 }
        val inbox = api.inbox()
        assertEquals(listOf("$HOST/work/0.1", address), inbox.map { it.address })
        assertTrue(inbox[1].later)
        assertEquals(inbox.map { it.address }, Inbox.waiting(api.panes()).map { it.address })
        assertEquals("$HOST/work/0.1", Inbox.oldest(inbox)?.address)
        // A notification arriving while parked leaves it parked, with the new kind underneath.
        core.notify("permission", "Claude needs your permission")
        waitFor("the new kind listed") { api.panes().first { it.address == address }.attention?.kind == "permission" }
        assertTrue(api.panes().first { it.address == address }.later)
        // The watch wakes it through the gate; typing into it wakes it again after a second parking.
        val gate = WatchGate({ api }, { api.panes() }, { true })
        assertTrue(gate.later(Wire.Later("l1", address, false)).ok)
        waitFor("awake") { !api.panes().first { it.address == address }.later }
        assertEquals(Lens.Status.WAITING, Lens.status(api.panes().first { it.address == address }, null, false))
        api.later(address, true)
        waitFor("parked again") { api.panes().first { it.address == address }.later }
        api.type(address, "echo back")
        api.key(address, "Enter")
        waitFor("woken by input") { !api.panes().first { it.address == address }.later }
        assertNull(api.panes().first { it.address == address }.attention)
        assertEquals(0, Inbox.waiting(api.panes()).count { it.address == address })
    }

    @Test
    fun s34_mailNeedsAWriteGrantSaneTextAndAMailboxOneMayRead() {
        val (api, tok) = setup()
        val address = "$HOST/work/0.0"
        assertTrue("the agent holds mail", api.hosts().single().mail)
        val reader = Api(relay.url, core.token(write = null), "muxway-android-e2e")
        val readOnly = assertThrows(ApiException::class.java) { reader.mail(address, "hello") }
        assertEquals(403, readOnly.status)
        assertEquals("read_only", readOnly.code)
        // A raw client sending nothing, a control character, too much, or no text gets the hub's refusal.
        for (bad in listOf("""{"text":""}""", """{"text":"a\u0007b"}""", """{"text":"${"x".repeat(4001)}"}""", "{}")) {
            assertEquals(bad.take(24), 400, post(tok, address, bad, op = "mail"))
        }
        // The client refuses the same before anything leaves the phone.
        for (bad in listOf("", "\u0007", "x".repeat(4001))) assertThrows(IllegalArgumentException::class.java) { api.mail(address, bad) }
        assertNull(api.panes().single().mail)
        assertTrue(api.mailbox(address).isEmpty())
        assertTrue(api.talk().isEmpty())
        // A reader whose grant names another pane: this pane's mailbox is not found, as the pane itself is not.
        val other = Api(relay.url, core.token(read = "$HOST/other/*", write = null), "muxway-android-e2e")
        assertTrue(other.panes().isEmpty())
        val missing = assertThrows(ApiException::class.java) { other.mailbox(address) }
        assertEquals(404, missing.status)
        assertEquals("not_found", missing.code)
    }

    @Test
    fun s34b_aReadOnlyPaneTakesNoMailEvenWithAWriteGrant() {
        val (api, _) = setup(expose = null)
        val refused = assertThrows(ApiException::class.java) { api.mail("$HOST/work/0.0", "hello") }
        assertEquals(403, refused.status)
        assertEquals("read_only", refused.code)
        assertNull(api.panes().single().mail)
    }

    @Test
    fun f35_theMailSheetsRowsMergeTheMailboxAndTheFeedAndTheProcessReadsItsMail() {
        val (api, tok) = setup()
        val a = "$HOST/work/0.0"
        core.tmux("split-window", "-t", "work:0", "-h")
        core.cli("expose", "--tmux-name", core.tmux, "-t", "work:0.1", "--write")
        waitFor("two panes listed") { api.panes().size == 2 }
        val b = "$HOST/work/0.1"
        val idA = core.paneId()
        val idB = core.tmux("display-message", "-p", "-t", "work:0.1", "#{pane_id}").trim()
        // The owner mails A from the phone: no origin, so the letter is from you, and nothing is typed.
        val id = api.mail(a, "run the tests on the branch")
        assertTrue(id > 0)
        waitFor("the count listed") { api.panes().first { it.address == a }.mail?.unread == 1 }
        val box = api.mailbox(a)
        assertEquals(1, box.size)
        assertEquals(id, box[0].id)
        assertNull(box[0].from)
        assertEquals("phone", box[0].actor)
        assertTrue(box[0].unread)
        assertEquals("run the tests on the branch", box[0].text)
        assertFalse(core.pane().contains("run the tests"))
        val feed = api.talk()
        assertEquals(1, feed.size)
        assertEquals("mail", feed[0].kind)
        assertNull(feed[0].from)
        assertEquals(a, feed[0].to.address)
        assertEquals(id, feed[0].id)
        val paneA = api.panes().first { it.address == a }
        val paneB = api.panes().first { it.address == b }
        var rows = MailRows.rows(paneA, box, feed, emptyMap())
        assertEquals(1, rows.size)
        assertFalse(rows[0].out)
        assertNull(rows[0].who)
        assertEquals(true, rows[0].unread)
        assertFalse(rows[0].yours)
        // B mails A from its shell, as an agent would: the origin is B.
        val out = core.client(tok, idB, "mail", a, "tests green, two warnings", "--agent-dir", core.agentDir.path)
        assertTrue(out, out.startsWith("mail "))
        waitFor("two letters") { api.mailbox(a).size == 2 }
        val boxA = api.mailbox(a)
        assertEquals(b, boxA[1].from?.address)
        assertEquals(idB, boxA[1].from?.paneId)
        waitFor("two entries") { api.talk().size == 2 }
        val feed2 = api.talk()
        assertEquals(b, feed2[1].from?.address)
        // A's rows: two in, the second from B. B's rows: the owner's mail to A, since A is of the
        // conversation, then B's own to A, whole from A's box and unread there.
        rows = MailRows.rows(paneA, boxA, feed2, emptyMap())
        assertEquals(listOf(false, false), rows.map { it.out })
        assertEquals(b, rows[1].who?.address)
        assertEquals(listOf(a), MailRows.sentTo(paneB, api.mailbox(b), feed2, api.panes()))
        val rowsB = MailRows.rows(paneB, api.mailbox(b), feed2, mapOf(a to boxA))
        assertEquals(2, rowsB.size)
        assertTrue(rowsB[0].yours)
        assertEquals(a, rowsB[0].who?.address)
        assertTrue(rowsB[1].out)
        assertFalse(rowsB[1].yours)
        assertEquals(a, rowsB[1].who?.address)
        assertEquals("tests green, two warnings", rowsB[1].text)
        assertFalse(rowsB[1].cut)
        assertEquals(true, rowsB[1].unread)
        assertEquals(listOf(a), MailRows.partners(rowsB, b))
        // Without A's box the excerpt stands and nobody knows whether it was read.
        assertNull(MailRows.rows(paneB, api.mailbox(b), feed2, emptyMap())[1].unread)
        // A token that reads A alone sees B's letter without its origin, and no feed entry for it.
        val narrow = Api(relay.url, core.token(read = a, write = null), "muxway-android-e2e")
        val seen = narrow.mailbox(a)
        assertEquals(2, seen.size)
        assertNull(seen[1].from)
        assertEquals(listOf(id), narrow.talk().map { it.id })
        // The process in A reads its mail: the count clears, the letters say read, the rows too.
        val read = core.readMail(idA)
        assertTrue(read, read.contains("run the tests on the branch") && read.contains("tests green"))
        waitFor("the count cleared") { api.panes().first { it.address == a }.mail == null }
        assertTrue(api.mailbox(a).none { it.unread })
        assertEquals(listOf(false, false), MailRows.rows(paneA, api.mailbox(a), api.talk(), emptyMap()).map { it.unread })
        // The messages count: everything is new until looked at, nothing after.
        assertEquals(2, Feed.unseen(feed2, emptyMap()))
        assertEquals(0, Feed.unseen(feed2, Feed.seen(feed2, emptyMap())))
    }

    @Test
    fun s36_sharingNeedsAWriteGrantAndSaneInput() {
        val (api, tok) = setup()
        val address = "$HOST/work/0.0"
        val reader = Api(relay.url, core.token(write = null), "muxway-android-e2e")
        val refused = assertThrows(ApiException::class.java) { reader.share(address, 3600, "aaaa-bbbb-2222") }
        assertEquals(403, refused.status)
        assertEquals("read_only", refused.code)
        // A raw client with a lifetime outside the range or a password the hub does not take.
        for (bad in listOf("""{"ttl":59}""", """{"ttl":604801}""", """{"password":"seven77"}""", """{"password":"with\tab-in-it"}""")) {
            assertEquals(bad, 400, post(tok, address, bad, op = "shares"))
        }
        // The client refuses the same before anything leaves the phone, and a path only in the id's alphabet.
        assertThrows(IllegalArgumentException::class.java) { api.share(address, 59, "aaaa-bbbb-2222") }
        assertThrows(IllegalArgumentException::class.java) { api.share(address, 604801, "aaaa-bbbb-2222") }
        assertThrows(IllegalArgumentException::class.java) { api.share(address, 3600, "seven77") }
        assertThrows(IllegalArgumentException::class.java) { api.share(address, 3600, "with\tab-in-it") }
        assertThrows(IllegalArgumentException::class.java) { api.revokeShare("../admin") }
        assertTrue(api.shares().isEmpty())
        assertEquals(0, api.panes().single().shared)
        // A share that is not this token's is not found, as a wrong id is.
        val gone = assertThrows(ApiException::class.java) { api.revokeShare("nothing-here") }
        assertEquals(404, gone.status)
    }

    @Test
    fun s36b_aReadOnlyPaneIsNotSharedEvenWithAWriteGrant() {
        val (api, _) = setup(expose = null)
        val refused = assertThrows(ApiException::class.java) { api.share("$HOST/work/0.0", 3600, "aaaa-bbbb-2222") }
        assertEquals(403, refused.status)
        assertEquals("read_only", refused.code)
        assertTrue(api.shares().isEmpty())
    }

    @Test
    fun f37_aShareIsMadeOnceCountedForTheSharerAloneOpenedWithItsPasswordAndRevoked() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        val password = Shares.suggest()
        Env.secrets.add(password)
        assertTrue(password, Regex("^[a-z2-7]{4}-[a-z2-7]{4}-[a-z2-7]{4}$").matches(password))
        val before = System.currentTimeMillis() / 1000
        val s = api.share(address, 3600, password)
        assertTrue(s.url, s.url.startsWith("${core.url}/s/"))
        assertEquals(password, s.password)
        assertTrue(s.expires in before + 3500..before + 3700)
        assertEquals(HOST, s.host)
        assertEquals(core.paneId(), s.paneId)
        assertEquals(address, s.address)
        // The sharer's listing counts it; another writer's does not, nor does it see the share.
        waitFor("shared in the listing") { api.panes().single().shared == 1 }
        val other = Api(relay.url, core.token(), "muxway-android-e2e")
        assertEquals(0, other.panes().single().shared)
        assertTrue(other.shares().isEmpty())
        val listed = api.shares()
        assertEquals(listOf(s.id), listed.map { it.id })
        assertEquals("", listed[0].password)
        assertEquals(0, listed[0].watching)
        assertEquals(listOf(s.id), Shares.of(listed, api.panes().single()).map { it.id })
        // The link is live; the password opens it for an observer, a wrong one does not; a watcher is counted.
        assertEquals(200, observer("GET", "/v1/share/${s.id}").first)
        assertEquals(401, observer("POST", "/v1/share/${s.id}/open", """{"password":"wrong-password-1"}""").first)
        val (opened, cookie) = observer("POST", "/v1/share/${s.id}/open", """{"password":"$password"}""")
        assertEquals(204, opened)
        assertNotNull(cookie)
        assertEquals(200, observer("GET", "/v1/share/${s.id}/pane?lines=50", cookie = cookie).first)
        waitFor("one watching") { api.shares().single().watching == 1 }
        // Revoked: gone for the sharer, the listing, the observer and a second revoke.
        api.revokeShare(s.id)
        assertTrue(api.shares().isEmpty())
        waitFor("the count gone") { api.panes().single().shared == 0 }
        assertEquals(404, observer("GET", "/v1/share/${s.id}").first)
        assertTrue(observer("GET", "/v1/share/${s.id}/pane?lines=50", cookie = cookie).first in setOf(401, 404))
        val gone = assertThrows(ApiException::class.java) { api.revokeShare(s.id) }
        assertEquals(404, gone.status)
    }

    /** A multipart upload as a hostile or odd client would send it, with its own file name: the status and the body. */
    private fun postFile(tok: String, address: String, filename: String, type: String, data: ByteArray): Pair<Int, String> {
        val c = java.net.URL("${core.url}/v1/panes/$address/files").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $tok")
        val boundary = "e2eboundary0123456789"
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\nContent-Type: $type\r\n\r\n".toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        c.outputStream.use { it.write(head); it.write(data); it.write(tail) }
        val status = c.responseCode
        val body = (if (status < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
        return status to body
    }

    @Test
    fun s38_aFilesOwnNameGivesAnExtensionAndNothingElseAndAReaderSendsNoFile() {
        val (api, tok) = setup(inbox = true)
        val address = "$HOST/work/0.0"
        // A name with a path in it: the extension is not a word, so the host names the file .bin, under its inbox.
        val (status, body) = postFile(tok, address, "../../etc/x.sh/", Files.OCTET, "not a script\n".toByteArray())
        assertEquals(body, 200, status)
        val path = Json.sent(body).path
        assertTrue(path, path.endsWith(".bin"))
        assertEquals(path, core.inbox.absolutePath, File(path).parentFile!!.absolutePath)
        assertTrue(inboxFiles().any { it.name == File(path).name })
        Thread.sleep(2_100)
        // An extension in capitals lands lowercased, as one word.
        val (s2, b2) = postFile(tok, address, "x.ABC", Files.OCTET, byteArrayOf(1, 2, 3))
        assertEquals(b2, 200, s2)
        assertTrue(b2, Json.sent(b2).path.endsWith(".abc"))
        // A reader is refused whatever the type; the client refuses an extension that is no word before any request.
        val reader = Api(relay.url, core.token(write = null), "muxway-android-e2e")
        assertEquals("read_only", assertThrows(ApiException::class.java) { reader.sendFile(address, byteArrayOf(1), Files.OCTET, "pcap") }.code)
        val before = relay.seen.size
        assertThrows(IllegalArgumentException::class.java) { api.sendFile(address, byteArrayOf(1), Files.OCTET, "a/b") }
        assertThrows(IllegalArgumentException::class.java) { api.sendFile(address, byteArrayOf(1), "text/html") }
        assertEquals(before, relay.seen.size)
        assertEquals(2, inboxFiles().size)
    }

    @Test
    fun f39_aFileOfAnyTypeLandsUnderTheNamesExtensionAndComesBackAsItWent() {
        val (api, _) = setup(inbox = true)
        val address = "$HOST/work/0.0"
        val capture = byteArrayOf(0xd4.toByte(), 0xc3.toByte(), 0xb2.toByte(), 0xa1.toByte(), 2, 0, 4, 0) + ByteArray(40) { (it * 7).toByte() }
        val pcap = api.sendFile(address, capture, Files.type("application/vnd.tcpdump.pcap", "capture.pcap"), Files.ext("capture.pcap"))
        assertTrue(pcap.path, pcap.path.endsWith(".pcap"))
        assertEquals(capture.size.toLong(), pcap.bytes)
        Thread.sleep(2_100)
        val source = "print('hi')\n".toByteArray()
        val py = api.sendFile(address, source, Files.type("text/x-python", "tool.py"), Files.ext("tool.py"))
        assertTrue(py.path, py.path.endsWith(".py"))
        // Both paths are links in a pane's text, by the inbox pattern.
        assertEquals(listOf(File(pcap.path).name, File(py.path).name), Links.findFiles("see ${pcap.path} and ${py.path}.").map { it.name })
        // Read back: the bytes as they went; a kind the host does not show comes as a download or plain text, never sniffed.
        val back = api.readFile(address, File(pcap.path).name)
        assertTrue(capture.contentEquals(back.bytes))
        assertEquals(Files.OCTET, back.type.substringBefore(';'))
        val backPy = api.readFile(address, File(py.path).name)
        assertTrue(source.contentEquals(backPy.bytes))
        assertTrue(backPy.type, backPy.type.substringBefore(';') in listOf(Files.OCTET, "text/plain"))
        assertEquals(2, inboxFiles().size)
    }

    @Test
    fun f40_theListingMarksTheOnlyPaneOfItsSessionAsLast() {
        val (api, _) = setup(allowClose = true)
        assertTrue("the session's only pane is its last", api.panes().single().last)
        val made = api.split(HOST, "work", 0, "v")
        waitFor("two panes") { api.panes().size == 2 }
        assertTrue("neither of two is the last", api.panes().none { it.last })
        Thread.sleep(2_100)
        api.closePane(made.address)
        waitFor("one pane again, the last") { api.panes().singleOrNull()?.last == true }
    }

    @Test
    fun z99_noSecretInAnyLogOfTheRun() {
        val logs = File(Env.runDir, "logs")
        val found = ArrayList<String>()
        logs.walk().filter { it.isFile }.forEach { f ->
            val text = f.readText()
            for (s in Env.secrets.toList()) if (s.length > 12 && text.contains(s)) found.add("${f.relativeTo(Env.runDir)} (${s.take(6)}…)")
        }
        File(Env.runDir, "secrets-check.txt").writeText("${Env.secrets.size} secrets searched in ${logs.walk().count { it.isFile }} logs: ${if (found.isEmpty()) "none found" else found.joinToString()}\n")
        assertTrue("secrets in logs: $found", found.isEmpty())
    }
}
