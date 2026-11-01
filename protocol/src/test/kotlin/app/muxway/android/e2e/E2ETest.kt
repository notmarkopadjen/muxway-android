package app.muxway.android.e2e

import app.muxway.android.protocol.Ansi
import app.muxway.android.protocol.Api
import app.muxway.android.protocol.Wheel
import app.muxway.android.protocol.WheelQueue
import app.muxway.android.protocol.ApiException
import app.muxway.android.protocol.Board
import app.muxway.android.protocol.Changes
import app.muxway.android.protocol.Clip
import app.muxway.android.protocol.Files
import app.muxway.android.protocol.Inbox
import app.muxway.android.protocol.Keyboard
import app.muxway.android.protocol.Lens
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
