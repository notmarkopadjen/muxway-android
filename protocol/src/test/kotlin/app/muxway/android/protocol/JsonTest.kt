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
        // The alias is the name; Claude's codename follows as the status, and the tab is the alias.
        assertEquals("logs · build server", p.name)
        assertEquals("logs", p.tab)
        val unnamed = Json.panes("""{"panes":[{"session":"work","title":"\u2733 build server"}]}""").single()
        assertEquals("Claude · build server", unnamed.name)
        assertEquals("build server", unnamed.tab)
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
    fun aQuestionsChoicesAreReadOnlyWhenTheyFitWhatTheHubAllows() {
        fun att(choices: String) = Json.panes("""{"panes":[{"session":"work","attention":{"kind":"question","at":1,"text":"deploy?","choices":$choices}}]}""").single().attention!!.choices
        assertEquals(listOf("yes", "no"), att("""["yes","no"]"""))
        assertEquals(emptyList<String>(), att("""["yes"]"""))
        assertEquals(emptyList<String>(), att("""["1","2","3","4","5","6","7","8","9"]"""))
        assertEquals(emptyList<String>(), att("""["yes","${"x".repeat(41)}"]"""))
        assertEquals(emptyList<String>(), att("""["yes","a|b"]"""))
        assertEquals(emptyList<String>(), att("""["yes","no\u0007"]"""))
        assertEquals(emptyList<String>(), att("""["yes","yes"]"""))
        assertEquals(emptyList<String>(), Json.panes("""{"panes":[{"session":"work","attention":{"kind":"done","at":1,"text":""}}]}""").single().attention!!.choices)
        val e = Json.events("""{"events":[{"seq":1,"at":1,"address":"alice-laptop/work/1.0","kind":"question","text":"deploy?","choices":["yes","no","later"]}],"next":1}""")
        assertEquals(listOf("yes", "no", "later"), e.events.single().choices)
        val inbox = Json.inbox("""{"inbox":[{"session":"work","window_index":0,"pane_index":0,"mode":"write","attention":{"kind":"question","at":5,"text":"deploy?","choices":["yes","no"]}}],"count":1}""")
        assertEquals("deploy?", inbox.single().attention?.text)
        assertEquals(true, Json.hosts("""{"hosts":[{"name":"alice-laptop","online":true,"ask":true}]}""").single().ask)
        assertEquals(false, Json.hosts("""{"hosts":[{"name":"alice-laptop","online":true}]}""").single().ask)
        assertEquals("""{"choice":"yes"}""", Json.choice("yes"))
    }

    @Test
    fun theRenameFlagIsReadWhenGivenAndAnAliasGoesOutAsOne() {
        assertEquals(true, Json.hosts("""{"hosts":[{"name":"alice-laptop","online":true,"rename":true}]}""").single().rename)
        assertEquals(false, Json.hosts("""{"hosts":[{"name":"alice-laptop","online":true}]}""").single().rename)
        assertEquals("""{"alias":"review"}""", Json.alias("review"))
        assertEquals("""{"alias":""}""", Json.alias(""))
        assertEquals("review", Json.resized("""{"pane":{"session":"work","alias":"review"}}""").pane.alias)
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

    @Test
    fun aListingKeepsOnlyNamesAShellCanTakeWholeAndIsCapped() {
        val many = (1..2100).joinToString(",") { """{"name":"f$it","kind":"file"}""" }
        val l = Json.listing("""{"path":"/home/alice/work","entries":[{"name":"docs","kind":"dir"},{"name":"bad\u0007","kind":"file"},{"name":"","kind":"file"},{"name":"sock","kind":"weird"},$many],"truncated":true}""")
        assertEquals("/home/alice/work", l.path)
        assertEquals(Names.MAX_ENTRIES, l.entries.size)
        assertEquals(Entry("docs", "dir"), l.entries[0])
        assertEquals(Entry("sock", "other"), l.entries[1])
        assertEquals(Entry("f1", "file"), l.entries[2])
        assertTrue(l.truncated)
    }

    @Test
    fun cwdAndTheDirFlagAreReadWhenGivenAndAHostileCwdIsNot() {
        assertEquals("/home/alice/work", Json.panes("""{"panes":[{"session":"work","cwd":"/home/alice/work"}]}""").single().cwd)
        assertNull(Json.panes("""{"panes":[{"session":"work"}]}""").single().cwd)
        assertNull(Json.panes("""{"panes":[{"session":"work","cwd":"/x\u001b[2J"}]}""").single().cwd)
        assertTrue(Json.hosts("""{"hosts":[{"name":"alice-laptop","online":true,"dir":true}]}""").single().dir)
        assertFalse(Json.hosts("""{"hosts":[{"name":"alice-laptop","online":true}]}""").single().dir)
    }


    @Test
    fun theLaterMarkItsTimeAndTheHostsFlagAreReadWhenGivenAndTheBodyIsOneBoolean() {
        val p = Json.panes("""{"panes":[{"session":"work","later":true,"later_at":1767225600,"attention":{"kind":"done","at":1767225500,"text":"x"}}]}""").single()
        assertTrue(p.later)
        assertEquals(1767225600L, p.laterAt)
        assertEquals("done", p.attention?.kind)
        val awake = Json.panes("""{"panes":[{"session":"work","later_at":-5}]}""").single()
        assertFalse(awake.later)
        assertEquals(0L, awake.laterAt)
        val bare = Json.inbox("""{"inbox":[{"session":"work","later":true,"later_at":7}],"count":1}""").single()
        assertTrue(bare.later)
        assertNull(bare.attention)
        assertEquals(true, Json.hosts("""{"hosts":[{"name":"alice-laptop","online":true,"later":true}]}""").single().later)
        assertEquals(false, Json.hosts("""{"hosts":[{"name":"alice-laptop","online":true}]}""").single().later)
        assertEquals("""{"later":true}""", Json.later(true))
        assertEquals("""{"later":false}""", Json.later(false))
    }

    @Test
    fun aMailboxKeepsLinesSpacesTabsDropsAnOriginTheProtocolDoesNotSpellAndReadsTheMarks() {
        val box = Json.mailbox("""{"mail":[
            {"id":7,"at":100,"actor":{"id":"0ddba4b5","name":"re\u0007viewer"},"from":{"host":"alice-laptop","address":"alice-laptop/work/1.0","pane_id":"%3","alias":"review"},"text":"run\tthe tests\non the branch\u001b[1m","read_at":160,"replied":true},
            {"id":8,"at":200,"actor":{"name":"alice"},"from":null,"text":"ok","reply_to":7},
            {"id":9,"at":300,"actor":{"name":"x"},"from":{"host":"alice-laptop","address":"../../etc","pane_id":"%4"},"text":"bad origin"}
        ]}""")
        assertEquals(3, box.size)
        assertEquals("reviewer", box[0].actor)
        assertEquals("alice-laptop/work/1.0", box[0].from?.address)
        assertEquals("%3", box[0].from?.paneId)
        assertEquals("run     the tests\non the branch", box[0].text)
        assertFalse(box[0].unread)
        assertTrue(box[0].replied)
        assertNull(box[1].from)
        assertTrue(box[1].unread)
        assertEquals(7L, box[1].replyTo)
        assertNull(box[2].from)
        assertEquals(0L, box[2].replyTo)
        assertTrue(Json.mailbox("""{"mail":null}""").isEmpty())
        assertEquals(7L, Json.mailed("""{"mail":{"id":7,"at":1767225600}}"""))
        assertThrows(ApiException::class.java) { Json.mailed("""{"ok":true}""") }
    }

    @Test
    fun theFeedLeavesOutAnEntryWithoutADestinationAndCutsTextsAndNames() {
        val talk = Json.talk("""{"talk":[
            {"seq":42,"at":1,"actor":{"name":"reviewer"},"from":{"host":"alice-laptop","address":"alice-laptop/work/1.0","pane_id":"%3"},"to":{"host":"bob-desktop","address":"bob-desktop/work/0.0","pane_id":"%5"},"kind":"text","bytes":13,"text":"run the tests"},
            {"seq":43,"at":2,"actor":{"name":"alice"},"from":null,"to":{"host":"alice-laptop","address":"alice-laptop/@review","pane_id":"%3"},"kind":"mail","bytes":500,"text":"${"m".repeat(300)}","id":7,"reply_to":3},
            {"seq":44,"at":3,"actor":{"name":"x"},"from":null,"to":{"address":"nowhere"},"kind":"mail","text":"lost"},
            {"seq":45,"at":4,"actor":{"name":"${"n".repeat(80)}"},"to":{"host":"alice-laptop","address":"alice-laptop/work/1.0","pane_id":"%3"},"kind":"an\u0007swer\u0000${"k".repeat(20)}","text":"yes"}
        ],"next":45}""")
        assertEquals(listOf(42L, 43L, 45L), talk.map { it.seq })
        assertEquals("alice-laptop/work/1.0", talk[0].from?.address)
        assertEquals("bob-desktop/work/0.0", talk[0].to.address)
        assertEquals(13, talk[0].bytes)
        assertNull(talk[1].from)
        assertEquals(200, talk[1].text.length)
        assertEquals(7L, talk[1].id)
        assertEquals(3L, talk[1].replyTo)
        assertEquals(64, talk[2].actor.length)
        assertEquals(16, talk[2].kind.length)
        assertTrue(talk[2].kind.startsWith("answer"))
        assertNull(talk[2].from)
        assertTrue(Json.talk("""{"talk":null,"next":0}""").isEmpty())
        assertEquals(true, Json.hosts("""{"hosts":[{"name":"alice-laptop","online":true,"mail":true}]}""").single().mail)
        assertEquals(false, Json.hosts("""{"hosts":[{"name":"alice-laptop","online":true}]}""").single().mail)
    }

    @Test
    fun aShareIsReadWithItsPasswordOnceAndOnlyWithAnIdAndALinkTheProtocolSpells() {
        val s = Json.share("""{"share":{"id":"3nK_abc","url":"https://hub.example.com/s/3nK_abc","password":"aaaa-bbbb-2222","expires":1767225600,"host":"alice-laptop","pane_id":"%3","address":"alice-laptop/work/0.0","watching":2}}""")
        assertEquals("3nK_abc", s.id)
        assertEquals("https://hub.example.com/s/3nK_abc", s.url)
        assertEquals("aaaa-bbbb-2222", s.password)
        assertEquals(1767225600L, s.expires)
        assertEquals("%3", s.paneId)
        assertEquals(2, s.watching)
        val list = Json.shares("""{"shares":[
            {"id":"ok-1","url":"javascript:alert(1)","expires":1},
            {"id":"../x","url":"https://hub.example.com/s/x","expires":2},
            {"id":"two","url":"http://hub.example.com/s/two","expires":-5,"watching":-1}
        ]}""")
        assertEquals(listOf("ok-1", "two"), list.map { it.id })
        assertEquals("", list[0].url)
        assertEquals("", list[0].password)
        assertEquals(0L, list[1].expires)
        assertEquals(0, list[1].watching)
        assertTrue(Json.shares("""{"shares":null}""").isEmpty())
        assertThrows(ApiException::class.java) { Json.share("""{"share":{"id":"bad id","url":"https://hub.example.com/s/x"}}""") }
        assertEquals(1, Json.panes("""{"panes":[{"session":"work","shared":1}]}""").single().shared)
        assertEquals(0, Json.panes("""{"panes":[{"session":"work"}]}""").single().shared)
        assertEquals(0, Json.panes("""{"panes":[{"session":"work","shared":-3}]}""").single().shared)
        // The only pane of its session, which a close ends; absent or odd is not.
        assertTrue(Json.panes("""{"panes":[{"session":"work","last":true}]}""").single().last)
        assertFalse(Json.panes("""{"panes":[{"session":"work"}]}""").single().last)
        assertFalse(Json.panes("""{"panes":[{"session":"work","last":"yes"}]}""").single().last)
        val body = org.json.JSONObject(Json.shareBody(3600, "aaaa-bbbb-2222"))
        assertEquals(3600, body.getInt("ttl"))
        assertEquals("aaaa-bbbb-2222", body.getString("password"))
        assertEquals(2, body.length())
    }
}
