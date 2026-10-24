package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTest {
    private val now = 1_767_225_600L

    private fun pane(n: Int, title: String = "✳ pane $n", kind: String? = null, at: Long = now) = Pane(
        host = "alice-laptop", address = "alice-laptop/work/0.$n", session = "work", windowIndex = 0, window = "claude",
        paneIndex = n, paneId = "%$n", width = 96, height = 28, title = title, command = "claude", mode = "write",
        alias = "", activity = now, attention = kind?.let { Attention(it, at, "deploy to staging?") },
    )

    private fun rank(panes: List<Pane>, working: Set<String> = emptySet(), withText: Boolean = false) =
        Board.rank(panes, now, withText) { Lens.status(it, if (it.address in working) "working" else null, false) }

    @Test
    fun waitingComesFirstOldestOnTopThenWorkingThenQuiet() {
        val b = rank(
            listOf(pane(1), pane(2, kind = "done", at = now - 5), pane(3), pane(4, kind = "question", at = now - 60), pane(5)),
            working = setOf("alice-laptop/work/0.5"),
        )
        assertEquals(listOf("pane 4", "pane 2", "pane 5", "pane 1", "pane 3"), b.rows.map { it.name })
        assertEquals(listOf(Board.WAITING, Board.WAITING, Board.WORKING, Board.QUIET, Board.QUIET), b.rows.map { it.state })
        assertEquals("question", b.rows[0].kind)
        assertEquals("?", b.rows[0].glyph)
        assertEquals("⟳", b.rows[2].glyph)
        assertEquals(2, b.waiting)
        assertTrue(b.live)
    }

    @Test
    fun fourRowsShowAllOrThreeAndACount() {
        val four = rank((1..4).map { pane(it) })
        assertEquals(4, four.shown(4).size)
        assertEquals(0, four.hidden(4))
        val six = rank((1..6).map { pane(it) })
        assertEquals(3, six.shown(4).size)
        assertEquals(3, six.hidden(4))
        assertEquals(0, Board.EMPTY.shown(4).size)
    }

    @Test
    fun textTravelsOnlyWhenAskedAndOnlyForWaitingPanes() {
        val panes = listOf(pane(1, kind = "question"), pane(2))
        assertEquals("", rank(panes).rows[0].text)
        val with = rank(panes, withText = true)
        assertEquals("deploy to staging?", with.rows[0].text)
        assertEquals("", with.rows[1].text)
    }

    @Test
    fun jsonRoundTrip() {
        val b = rank(listOf(pane(1, kind = "permission"), pane(2)), working = setOf("alice-laptop/work/0.2"), withText = true)
        assertEquals(b, Board.parse(b.json()))
    }

    @Test
    fun staleWhenOldOrStoppedOrFromTheFuture() {
        val b = rank(listOf(pane(1)))
        assertFalse(b.stale(now + 10))
        assertTrue(b.stale(now + Board.STALE_SECONDS + 1))
        assertTrue(b.copy(live = false).stale(now + 10))
        assertTrue(b.stale(now - 3600))
        assertTrue(Board.EMPTY.stale(now))
    }

    @Test
    fun hostileJsonIsCappedCleanedOrEmpty() {
        assertEquals(Board.EMPTY, Board.parse(null))
        assertEquals(Board.EMPTY, Board.parse("not json"))
        assertEquals(Board.EMPTY, Board.parse("[1,2,3]"))
        assertEquals(Board.EMPTY, Board.parse("x".repeat(2 shl 20)))
        assertEquals(0, Board.parse("""{"rows": "nope", "at": "soon"}""").rows.size)
        val many = StringBuilder("""{"at": 1, "live": true, "rows": [""")
        for (i in 0 until 10_000) {
            if (i > 0) many.append(',')
            many.append("""{"address": "alice-laptop/work/0.$i", "name": "n", "state": 7}""")
        }
        many.append("]}")
        val b = Board.parse(many.toString())
        assertEquals(Board.MAX_ROWS, b.rows.size)
        assertEquals(Board.WAITING, b.rows[0].state)
        val nasty = Board.parse(
            """{"at": 1, "live": true, "rows": [
                {"address": "alice-laptop/work/0.0", "name": "a\u001b[31mb\u0007c\u0000d", "state": -3, "kind": "${"k".repeat(500)}", "text": 5},
                {"address": "../../etc/passwd", "name": "x"},
                {"address": "alice-laptop/@build", "name": "ok"},
                "junk", 42]}""",
        )
        assertEquals(2, nasty.rows.size)
        assertEquals("abcd", nasty.rows[0].name)
        assertEquals(Board.QUIET, nasty.rows[0].state)
        assertEquals(200, nasty.rows[0].kind.length)
        assertEquals("5", nasty.rows[0].text)
        assertEquals("alice-laptop/@build", nasty.rows[1].address)
    }

    @Test
    fun theBoardCarriesNoHubOrToken() {
        val json = rank(listOf(pane(1, kind = "question")), withText = true).json()
        for (key in listOf("token", "hub", "mxt_", "http")) assertFalse(key, json.contains(key))
    }

    @Test
    fun addressesFollowTheProtocolsGrammar() {
        for (ok in listOf("alice-laptop/work/0.0", "alice-laptop/work/logs.12", "alice-laptop/@build", "h/s/w.1", "a/b.c_d-e/f.g.9")) assertTrue(ok, Address.valid(ok))
        for (bad in listOf("", "alice-laptop", "alice-laptop/work", "alice-laptop/work/0", "Alice/work/0.0", "alice-laptop/work/0.0/x",
            "alice-laptop/@Build", "alice-laptop/@", "alice/work/0.0?token=x", "alice/wo rk/0.0", "a/b/c.\u0000", "a".repeat(40) + "/b/c.0", "alice//0.0")) assertFalse(bad, Address.valid(bad))
    }
}
