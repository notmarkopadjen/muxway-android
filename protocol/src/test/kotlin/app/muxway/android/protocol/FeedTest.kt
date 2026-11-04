package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedTest {
    private fun pane(address: String, id: String, hub: String, alias: String = "", title: String = "") =
        Pane("alice-laptop", address, "work", 0, "shell", 0, id, 80, 24, title, "bash", "write", alias, 0, null, hub = hub)

    private val a1 = TalkEntry(1, 100, "t", null, End("alice-laptop", "alice-laptop/work/0.0", "%1"), "mail", 2, "hi", id = 1, hub = "a")
    private val a2 = TalkEntry(2, 300, "t", End("alice-laptop", "alice-laptop/work/0.1", "%2"), End("alice-laptop", "alice-laptop/work/0.0", "%1"), "key", 5, "Enter", hub = "a")
    private val b1 = TalkEntry(1, 200, "t", End("bob-server", "bob-server/ops/0.0", "%1"), End("bob-server", "bob-server/ops/0.1", "%2"), "text", 4, "make", hub = "b")

    @Test
    fun everyHubsEntriesMergeByTime() {
        assertEquals(listOf(a1, b1, a2), Feed.merge(listOf(listOf(a1, a2), listOf(b1))))
    }

    @Test
    fun theUnseenCountIsPerHubAndSeenMovesTheMarks() {
        val all = listOf(a1, a2, b1)
        assertEquals(3, Feed.unseen(all, emptyMap()))
        assertEquals(1, Feed.unseen(all, mapOf("a" to 1L, "b" to 1L)))
        val seen = Feed.seen(all, mapOf("a" to 5L))
        assertEquals(mapOf("a" to 5L, "b" to 1L), seen)
        assertEquals(0, Feed.unseen(all, seen))
    }

    @Test
    fun aRowNamesPanesAsTheirTabsDoByAddressThenByIdThenByTheBareAddress() {
        val panes = listOf(pane("alice-laptop/work/0.0", "%1", "a", alias = "review"), pane("alice-laptop/work/1.0", "%2", "a"), pane("alice-laptop/work/0.0", "%9", "b"))
        assertEquals("review", Feed.nameOf(panes, "a", "alice-laptop/work/0.0", "%1"))
        // The pane moved since the entry was made: its id finds it on the same host.
        assertEquals("bash", Feed.nameOf(panes, "a", "alice-laptop/work/0.1", "%2"))
        assertEquals("work/0.1", Feed.nameOf(panes, "a", "alice-laptop/work/0.1", "%7"))
        assertEquals("work/0.1", Feed.nameOf(panes, "a", "alice-laptop/work/0.1"))
        // Another hub's pane with the same address is another pane.
        assertEquals("bash", Feed.nameOf(panes, "b", "alice-laptop/work/0.0"))
    }

    @Test
    fun aRowLaysBothPanesBesideOrTheOneAClientWroteToAndSaysTheKindOfAKey() {
        assertEquals(listOf("alice-laptop/work/0.0"), Feed.panesOf(a1))
        assertEquals(listOf("alice-laptop/work/0.1", "alice-laptop/work/0.0"), Feed.panesOf(a2))
        assertEquals("hi", Feed.excerpt(a1))
        assertEquals("key: Enter", Feed.excerpt(a2))
        assertEquals("make", Feed.excerpt(b1))
    }
}
