package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensTest {
    private val now = 1_767_225_600L

    private fun pane(command: String, title: String, kind: String? = null) = Pane(
        host = "alice-laptop", address = "alice-laptop/work/0.0", session = "work", windowIndex = 0, window = "claude",
        paneIndex = 0, paneId = "%1", width = 96, height = 28, title = title, command = command, mode = "write",
        alias = "", activity = now, attention = kind?.let { Attention(it, now, "") },
    )

    @Test
    fun claudeIsRecognisedByCommandOrTitle() {
        assertTrue(Lens.isClaude(pane("claude", "")))
        assertTrue(Lens.isClaude(pane("node", "✳ muxway")))
        assertFalse(Lens.isClaude(pane("bash", "alice@laptop: ~")))
    }

    @Test
    fun aWorkingEventOrAChangingScreenMeansWorkingForClaudeOnly() {
        assertEquals(Lens.Status.WORKING, Lens.status(pane("claude", "✳ x"), "working", false))
        assertEquals(Lens.Status.WORKING, Lens.status(pane("claude", "✳ x"), null, true))
        assertEquals(Lens.Status.QUIET, Lens.status(pane("claude", "✳ x"), "done", false))
        assertEquals(Lens.Status.QUIET, Lens.status(pane("claude", "✳ x"), null, false))
        assertEquals(Lens.Status.QUIET, Lens.status(pane("bash", ""), "working", true))
        assertEquals("⟳", Lens.glyph(Lens.Status.WORKING, null))
        assertEquals("working", Lens.word(Lens.Status.WORKING, null))
        assertNull(Lens.glyph(Lens.Status.QUIET, null))
        assertNull(Lens.word(Lens.Status.QUIET, null))
    }

    @Test
    fun aNotificationIsWaitingWhateverElseHappened() {
        assertEquals(Lens.Status.WAITING, Lens.status(pane("claude", "✳ x", "permission"), "working", true))
        assertEquals(Lens.Status.WAITING, Lens.status(pane("bash", "", "done"), null, false))
        assertEquals("!", Lens.glyph(Lens.Status.WAITING, "permission"))
        assertEquals("?", Lens.glyph(Lens.Status.WAITING, "question"))
        assertEquals("z", Lens.glyph(Lens.Status.WAITING, "idle"))
        assertEquals("✓", Lens.glyph(Lens.Status.WAITING, "done"))
        assertEquals("!", Lens.glyph(Lens.Status.WAITING, "surprise"))
        assertEquals("permission", Lens.word(Lens.Status.WAITING, "permission"))
        assertEquals("waiting", Lens.word(Lens.Status.WAITING, null))
    }

    @Test
    fun aChangeThisClientCausedIsNotTheProgramWorking() {
        val now = 1_767_225_600L
        assertEquals(false, Lens.changeCounts(firstRead = true, ownAt = 0L, at = now))
        assertEquals(false, Lens.changeCounts(firstRead = false, ownAt = now, at = now))
        assertEquals(false, Lens.changeCounts(firstRead = false, ownAt = now - 2, at = now))
        assertEquals(true, Lens.changeCounts(firstRead = false, ownAt = now - 3, at = now))
        assertEquals(true, Lens.changeCounts(firstRead = false, ownAt = 0L, at = now))
    }

    @Test
    fun theNameDropsClaudesMark() {
        assertEquals("muxway android", Lens.name(pane("claude", "✳ muxway android")))
        assertEquals("alice@laptop: ~", Lens.name(pane("bash", "alice@laptop: ~")))
        assertEquals("work/0.0", Lens.name(pane("bash", "✳")))
        assertEquals("work/0.0", Lens.name(pane("bash", "")))
    }
}
