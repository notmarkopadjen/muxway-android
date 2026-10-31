package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class InboxTest {
    private val now = 1_767_225_600L

    private fun pane(n: Int, hub: String, kind: String? = null, at: Long = now) = Pane(
        host = "alice-laptop", address = "alice-laptop/work/0.$n", session = "work", windowIndex = 0, window = "w",
        paneIndex = n, paneId = "%$n", width = 80, height = 24, title = "", command = "bash", mode = "write",
        alias = "", activity = now, attention = kind?.let { Attention(it, at, "") }, hub = hub,
    )

    @Test
    fun theWaitingPanesOfEveryHubComeOldestFirst() {
        val w = Inbox.waiting(listOf(pane(1, "a"), pane(2, "a", "done", now - 5), pane(3, "b", "question", now - 60), pane(4, "b"), pane(5, "a", "idle", now - 5)))
        assertEquals(listOf("b:alice-laptop/work/0.3", "a:alice-laptop/work/0.2", "a:alice-laptop/work/0.5"), w.map { it.key })
        assertEquals(0, Inbox.waiting(emptyList()).size)
    }

    @Test
    fun howLongAgoInAWordOrTwo() {
        assertEquals("0s", Inbox.ago(now + 5, now))
        assertEquals("12s", Inbox.ago(now - 12, now))
        assertEquals("3m", Inbox.ago(now - 200, now))
        assertEquals("2h", Inbox.ago(now - 7300, now))
        assertEquals("1d", Inbox.ago(now - 90000, now))
    }
}
