package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailRowsTest {
    private fun pane(address: String, id: String, hub: String = "a", mode: String = "write") =
        Pane("alice-laptop", address, "work", 0, "shell", 0, id, 80, 24, "", "bash", mode, "", 0, null, hub = hub)

    private val review = pane("alice-laptop/work/0.0", "%1")
    private val build = pane("alice-laptop/work/0.1", "%2")
    private val secret = pane("alice-laptop/work/0.2", "%3")
    private val stranger = pane("alice-laptop/work/0.3", "%4")
    private fun end(p: Pane) = End(p.host, p.address, p.paneId)

    private val box = listOf(
        Letter(7, 100, "reviewer", end(build), "run the tests on the branch and tell me what fails", readAt = 160, replied = true),
        Letter(9, 300, "alice", null, "ok, go ahead"),
    )
    private val feed = listOf(
        TalkEntry(1, 100, "reviewer", end(build), end(review), "mail", 51, "run the tests on the branch and tell me what fails", id = 7, hub = "a"),
        TalkEntry(2, 120, "reply", end(review), end(build), "mail", 240, "x".repeat(200), id = 8, replyTo = 7, hub = "a"),
        TalkEntry(3, 200, "console", end(review), end(secret), "mail", 9, "merge it.", id = 10, hub = "a"),
        TalkEntry(4, 250, "alice", null, end(build), "mail", 12, "and the docs", id = 11, hub = "a"),
        TalkEntry(5, 260, "alice", null, end(stranger), "mail", 5, "later", id = 12, hub = "a"),
        TalkEntry(6, 270, "reviewer", end(review), end(build), "text", 4, "make", hub = "a"),
        TalkEntry(1, 280, "reviewer", end(review), end(build), "mail", 5, "other", id = 1, hub = "b"),
        TalkEntry(7, 300, "alice", null, end(review), "mail", 12, "ok, go ahead", id = 9, hub = "a"),
    )

    @Test
    fun theMailboxAndTheFeedMergeByTimeWithWholeTextsWhereTheTokenReadsThePartner() {
        val buildBox = listOf(Letter(8, 120, "reply", end(review), "tests green, two warnings in the log, " + "y".repeat(200), replyTo = 7))
        val rows = MailRows.rows(review, box, feed, mapOf(build.address to buildBox))
        assertEquals(listOf("in7", "outa:2", "outa:3", "outa:4", "in9"), rows.map { it.key })
        // The mail that came in: its sender, read and answered.
        assertEquals(false, rows[0].out)
        assertEquals(build.address, rows[0].who?.address)
        assertEquals(false, rows[0].unread)
        assertTrue(rows[0].replied)
        // The reply the pane sent, whole from the partner's mailbox, unread there, answering the first.
        assertTrue(rows[1].out)
        assertTrue(rows[1].text.startsWith("tests green"))
        assertFalse(rows[1].cut)
        assertEquals(true, rows[1].unread)
        assertEquals(7L, rows[1].replyTo)
        // A recipient the token does not read: the feed's excerpt, cut only when there was more.
        assertEquals("merge it.", rows[2].text)
        assertFalse(rows[2].cut)
        assertNull(rows[2].unread)
        // The owner's own mail to a partner of this conversation, not to a pane outside it.
        assertTrue(rows[3].yours)
        assertEquals(build.address, rows[3].who?.address)
        assertTrue(rows.none { it.text == "later" })
        // The owner's mail to the pane itself: from you.
        assertNull(rows[4].who)
        assertEquals(true, rows[4].unread)
        assertFalse(rows[4].yours)
    }

    @Test
    fun anExcerptIsMarkedCutWhenTheMailWasLongerAndAnotherHubsFeedIsNotThisPanes() {
        val rows = MailRows.rows(review, emptyList(), feed, emptyMap())
        val reply = rows.first { it.key == "outa:2" }
        assertTrue(reply.cut)
        assertEquals(200, reply.text.length)
        assertNull(reply.unread)
        assertTrue(rows.none { it.key.startsWith("outb") })
        assertTrue(rows.none { it.text == "make" })
        // Nothing from the owner without a conversation to belong to.
        assertEquals(listOf("outa:2", "outa:3", "outa:4"), rows.map { it.key })
    }

    @Test
    fun thePartnersComeNewestFirstWithoutThePaneAndSentToNamesOnlyListedOnes() {
        val rows = MailRows.rows(review, box, feed, emptyMap())
        assertEquals(listOf(build.address, secret.address), MailRows.partners(rows, review.address))
        assertEquals(listOf(build.address), MailRows.sentTo(review, box, feed, listOf(review, build)))
        assertEquals(listOf(build.address, secret.address), MailRows.sentTo(review, box, feed, listOf(review, build, secret)))
        assertEquals(emptyList<String>(), MailRows.sentTo(review, box, feed, listOf(review, build.copy(hub = "b"))))
    }
}
