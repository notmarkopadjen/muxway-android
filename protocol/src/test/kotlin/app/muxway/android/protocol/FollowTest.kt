package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowTest {
    private fun pane(hub: String, address: String, id: String) = Pane(
        host = address.substringBefore('/'), address = address, session = "work", windowIndex = 0, window = "shell",
        paneIndex = address.substringAfterLast('.').toInt(), paneId = id, width = 80, height = 24, title = "", command = "bash",
        mode = "write", alias = "", activity = 0, attention = null, hub = hub,
    )

    private val online = { host: String -> host == "alice-laptop" }

    @Test
    fun aReadIsTheTilesWhenTheIdsMatchOrNoneIsLearntYet() {
        assertTrue(Follow.own("", "%3"))
        assertTrue(Follow.own("%3", "%3"))
        assertFalse(Follow.own("%3", "%4"))
        assertFalse(Follow.own("%3", ""))
    }

    @Test
    fun anIdOutsideTheGrammarIsNone() {
        assertEquals("%3", Follow.id("%3"))
        assertEquals("%123456", Follow.id("%123456"))
        for (bad in listOf("", "3", "%", "%3\u0007", "%-3", "%3 ", "x".repeat(40), "%" + "9".repeat(40))) assertEquals(bad, "", Follow.id(bad))
    }

    @Test
    fun aPaneListedElsewhereMovesItsTileAndOneGoneCloses() {
        // %3 at 0.0 exited: tmux renumbered %4 from 0.1 to 0.0.
        val open = listOf("h1:alice-laptop/work/0.0", "h1:alice-laptop/work/0.1")
        val ids = mapOf(open[0] to "%3", open[1] to "%4")
        val listing = listOf(pane("h1", "alice-laptop/work/0.0", "%4"))
        val out = Follow.reconcile("h1", open, { ids[it] ?: "" }, emptySet(), listing, online)
        assertEquals(listOf(open[0]), out.gone)
        assertEquals(mapOf(open[1] to open[0]), out.moved)
        // Nothing changed: nothing to do.
        val same = Follow.reconcile("h1", open, { ids[it] ?: "" }, emptySet(), listOf(pane("h1", "alice-laptop/work/0.0", "%3"), pane("h1", "alice-laptop/work/0.1", "%4")), online)
        assertTrue(same.empty)
    }

    @Test
    fun aTileIsKeptWhileItsHostIsAwayAndGoneWhenTheHostIsThereWithoutIt() {
        val k = "h1:bob-server/ops/0.0"
        val none = Follow.reconcile("h1", listOf(k), { "%7" }, emptySet(), emptyList(), online)
        assertTrue(none.empty)
        // The host is online with no pane of it listed: the pane was closed or hidden.
        val up = Follow.reconcile("h1", listOf(k), { "%7" }, emptySet(), emptyList()) { true }
        assertEquals(listOf(k), up.gone)
        // Another pane of the host is listed: the host is there.
        val other = Follow.reconcile("h1", listOf(k), { "%7" }, emptySet(), listOf(pane("h1", "bob-server/ops/0.1", "%8")), online)
        assertEquals(listOf(k), other.gone)
        // The read found nothing at the address, whatever the hosts say.
        val missing = Follow.reconcile("h1", listOf(k), { "%7" }, setOf(k), emptyList(), online)
        assertEquals(listOf(k), missing.gone)
    }

    @Test
    fun aTileWithoutAnIdIsLeftToItsReadsUnlessTheyFoundNothing() {
        val k = "h1:alice-laptop/work/0.0"
        assertTrue(Follow.reconcile("h1", listOf(k), { "" }, emptySet(), emptyList(), online).empty)
        assertEquals(listOf(k), Follow.reconcile("h1", listOf(k), { "" }, setOf(k), emptyList(), online).gone)
    }

    @Test
    fun anotherHubsTilesAndPanesAreNotLookedAt() {
        val mine = "h1:alice-laptop/work/0.0"
        val theirs = "h2:alice-laptop/work/0.0"
        // The same host name on two hubs: the other hub's pane with the same id is not this tile's.
        val out = Follow.reconcile("h1", listOf(mine, theirs), { "%3" }, emptySet(), listOf(pane("h2", "alice-laptop/work/0.5", "%3")), { false })
        assertTrue(out.empty)
        val swap = Follow.reconcile("h2", listOf(mine, theirs), { "%3" }, emptySet(), listOf(pane("h2", "alice-laptop/work/0.5", "%3")), { false })
        assertEquals(mapOf(theirs to "h2:alice-laptop/work/0.5"), swap.moved)
        assertTrue(swap.gone.isEmpty())
    }
}
