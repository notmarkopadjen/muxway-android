package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BesideTest {
    private val a = "h:alice-laptop/work/0.0"
    private val b = "h:alice-laptop/work/0.1"
    private val c = "h:alice-laptop/work/0.2"
    private val d = "h:alice-laptop/work/1.0"
    private val e = "h:alice-laptop/work/1.1"

    @Test
    fun anOpenPartnerIsSwappedNextToTheTileOrLeftWhereItIs() {
        val open = listOf(a, b, c, d)
        assertSame(open, Beside.lay(open, a, b, emptyMap()))
        assertSame(open, Beside.lay(open, b, a, emptyMap()))
        assertEquals(listOf(a, d, c, b), Beside.lay(open, a, d, emptyMap()))
        assertEquals(listOf(a, c, b, d), Beside.lay(open, d, b, emptyMap()))
        assertSame(open, Beside.lay(open, a, a, emptyMap()))
        assertSame(open, Beside.lay(open, e, a, emptyMap()))
    }

    @Test
    fun aPartnerNotOpenComesInAfterTheTileWhileThereIsRoom() {
        assertEquals(listOf(a, e), Beside.lay(listOf(a), a, e, emptyMap()))
        assertEquals(listOf(a, e, b, c), Beside.lay(listOf(a, b, c), a, e, emptyMap()))
        assertEquals(listOf(a, b, c, e), Beside.lay(listOf(a, b, c), c, e, emptyMap()))
    }

    @Test
    fun withFourOpenThePartnerTakesThePlaceFocusedLongestAgoNeverTheTileItself() {
        val open = listOf(a, b, c, d)
        val at = mapOf(a to 10L, b to 40L, c to 20L, d to 30L)
        assertEquals(listOf(a, b, e, d), Beside.lay(open, a, e, at))
        assertEquals(listOf(e, b, c, d), Beside.lay(open, c, e, at))
        // Without times the neighbour's slot gives way.
        assertEquals(listOf(a, e, c, d), Beside.lay(open, a, e, emptyMap()))
        assertEquals(listOf(a, b, e, d), Beside.lay(open, d, e, emptyMap()))
        assertEquals(listOf(a, b, e), Beside.lay(listOf(a, b, c), a, e, mapOf(a to 1L, b to 3L, c to 2L), max = 3))
    }

    @Test
    fun thePartnersAreThePanesTheListingHasOnTheSameHubEachOnce() {
        fun pane(n: Int, hub: String = "h", talk: List<Talk> = emptyList()) = Pane(
            host = "alice-laptop", address = "alice-laptop/work/0.$n", session = "work", windowIndex = 0, window = "w",
            paneIndex = n, paneId = "%$n", width = 80, height = 24, title = "", command = "bash", mode = "write",
            alias = "", activity = 0, attention = null, hub = hub, talk = talk,
        )
        val talk = listOf(Talk("alice-laptop/work/0.1", "%1", 1, "both"), Talk("alice-laptop/work/0.2", "%2", 1, "to"), Talk("alice-laptop/work/0.1", "%1", 2, "from"), Talk("alice-laptop/work/0.9", "%9", 1, "to"))
        val panes = listOf(pane(0, talk = talk), pane(1), pane(2), pane(2, hub = "other"))
        assertEquals(listOf(pane(1), pane(2)), Beside.partners(panes[0], panes))
        assertEquals(emptyList<Pane>(), Beside.partners(pane(0, hub = "other", talk = talk).copy(hub = "none"), panes))
    }
}
