package app.muxway.android.e2e

import app.muxway.android.protocol.Ansi
import app.muxway.android.protocol.Api
import app.muxway.android.protocol.ApiException
import app.muxway.android.protocol.Board
import app.muxway.android.protocol.Lens
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

    private fun setup(expose: String? = "--write", write: String? = "$HOST/*", revoke: Boolean = false, allowSessions: Boolean = false): Pair<Api, String> {
        val logs = Env.logs(name.methodName)
        core = Core(logs).start(expose, allowSessions)
        cleanups.add(core::close)
        val tok = core.token(write = write)
        if (revoke) core.revokeAll()
        relay = Relay(core.url, logs)
        cleanups.add(relay::close)
        return Api(relay.url, tok, "muxway-android-e2e") to tok
    }

    private fun waitPane(contains: String) = waitFor("pane shows $contains") { core.pane().contains(contains) }

    /** Null when this hub has mouse input, else the reason to skip: an older hub answers bad_json. */
    private fun mouseUnsupported(api: Api, address: String): Boolean = try {
        api.mouse(address, "left", 1, 1)
        false
    } catch (e: ApiException) {
        e.code == "bad_json"
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
        assertEquals("unknown key", assertThrows(IllegalArgumentException::class.java) { api.key("$HOST/work/0.0", "C-a") }.message)
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
        val r = api.read(address, 20, null)!!
        assertTrue(r.content, r.content.contains("$ "))
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
        val fitted = api.resize(address, 40, 12)
        assertEquals(40, fitted.width)
        assertEquals(12, fitted.height)
        waitFor("tmux shows 40 columns") { core.width() == 40 }
        api.type(address, "echo " + "x".repeat(50))
        api.key(address, "Enter")
        waitPane("xxxxx")
        // The hub joins wrapped lines, so the listing is what says the pane is 40 wide now.
        assertEquals(40, api.panes().single().width)
        assertTrue(api.read(address, 20, null)!!.content.contains("x".repeat(50)))
        api.release(address)
        waitFor("tmux is back to its own size") { core.width() == before.width }
    }

    @Test
    fun f16_aClickReachesAProgramThatAskedForTheMouse() {
        val (api, _) = setup()
        val address = "$HOST/work/0.0"
        assumeTrue("hub without mouse input", !mouseUnsupported(api, address))
        api.type(address, "printf '\\033[?1000h\\033[?1006h'; cat -v")
        api.key(address, "Enter")
        waitFor("cat running") { core.command() == "cat" }
        api.mouse(address, "left", 5, 3)
        waitPane("^[[<0;5;3M")
        waitPane("^[[<0;5;3m")
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
