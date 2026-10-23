package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensTest {
    private val now = 1_767_225_600L

    private fun pane(command: String, title: String, activity: Long, kind: String? = null) = Pane(
        host = "alice-laptop", address = "alice-laptop/work/0.0", session = "work", windowIndex = 0, window = "claude",
        paneIndex = 0, paneId = "%1", width = 96, height = 28, title = title, command = command, mode = "write",
        alias = "", activity = activity, attention = kind?.let { Attention(it, now, "") },
    )

    @Test
    fun claudeIsRecognisedByCommandOrTitle() {
        assertTrue(Lens.isClaude(pane("claude", "", now)))
        assertTrue(Lens.isClaude(pane("node", "✳ muxway", now)))
        assertFalse(Lens.isClaude(pane("bash", "alice@laptop: ~", now)))
    }

    @Test
    fun recentActivityInAClaudePaneIsWorking() {
        assertEquals(Lens.Status.WORKING, Lens.status(pane("claude", "✳ x", now - 3), now))
        assertEquals("⟳", Lens.glyph(pane("claude", "✳ x", now - 29), now))
        assertEquals(Lens.Status.QUIET, Lens.status(pane("claude", "✳ x", now - 31), now))
        assertEquals(Lens.Status.QUIET, Lens.status(pane("claude", "✳ x", 0), now))
        assertEquals(Lens.Status.QUIET, Lens.status(pane("bash", "", now), now))
        assertNull(Lens.glyph(pane("bash", "", now), now))
    }

    @Test
    fun aNotificationIsWaitingWhateverTheActivity() {
        assertEquals("!", Lens.glyph(pane("claude", "✳ x", now, "permission"), now))
        assertEquals("?", Lens.glyph(pane("claude", "✳ x", now, "question"), now))
        assertEquals("z", Lens.glyph(pane("claude", "✳ x", now - 999, "idle"), now))
        assertEquals("✓", Lens.glyph(pane("bash", "", now - 999, "done"), now))
        assertEquals("!", Lens.glyph(pane("bash", "", now, "surprise"), now))
    }

    @Test
    fun theNameDropsClaudesMark() {
        assertEquals("muxway android", Lens.name(pane("claude", "✳ muxway android", now)))
        assertEquals("alice@laptop: ~", Lens.name(pane("bash", "alice@laptop: ~", now)))
        assertEquals("work/0.0", Lens.name(pane("bash", "✳", now)))
        assertEquals("work/0.0", Lens.name(pane("bash", "", now)))
    }
}
