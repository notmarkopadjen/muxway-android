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
    fun everyHarnessIsNamedByItsCommandOrItsTitleUnderAnInterpreter() {
        assertEquals(Lens.Agent.CODEX, Lens.agent(pane("codex", "codex")))
        assertEquals(Lens.Agent.ANTIGRAVITY, Lens.agent(pane("agy", "notes")))
        assertEquals(Lens.Agent.GEMINI, Lens.agent(pane("node", "Gemini CLI")))
        assertEquals(Lens.Agent.ANTIGRAVITY, Lens.agent(pane("node", "antigravity · fix")))
        assertEquals(Lens.Agent.COPILOT, Lens.agent(pane("bun", "GitHub Copilot")))
        assertNull(Lens.agent(pane("bash", "gemini")))
        assertNull(Lens.agent(pane("node", "server")))
        for ((command, agent) in listOf("opencode" to Lens.Agent.OPENCODE, "copilot" to Lens.Agent.COPILOT, "aider" to Lens.Agent.AIDER, "goose" to Lens.Agent.GOOSE, "amp" to Lens.Agent.AMP, "cursor-agent" to Lens.Agent.CURSOR)) {
            assertEquals(command, agent, Lens.agent(pane(command, "")))
        }
        assertEquals(Lens.Description(Lens.Agent.CODEX, "Codex", ""), Lens.describe(pane("codex", "codex")))
        assertEquals(Lens.Description(Lens.Agent.CODEX, "Codex", ""), Lens.describe(pane("codex", "Codex")))
        assertEquals(Lens.Description(Lens.Agent.OPENCODE, "OpenCode", "muxway"), Lens.describe(pane("opencode", "muxway")))
        assertEquals(Lens.Description(Lens.Agent.ANTIGRAVITY, "Antigravity", "notes"), Lens.describe(pane("agy", "notes")))
        assertEquals("Codex", Lens.tab(pane("codex", "codex")))
        assertEquals("notes", Lens.tab(pane("agy", "notes")))
        assertEquals("Antigravity · notes", Lens.name(pane("agy", "notes")))
    }

    @Test
    fun aWorkingEventOrAChangingScreenMeansWorkingForAnAgentOnly() {
        assertEquals(Lens.Status.WORKING, Lens.status(pane("claude", "✳ x"), "working", false))
        assertEquals(Lens.Status.WORKING, Lens.status(pane("claude", "✳ x"), null, true))
        assertEquals(Lens.Status.WORKING, Lens.status(pane("codex", "codex"), "working", false))
        assertEquals(Lens.Status.WORKING, Lens.status(pane("agy", "notes"), null, true))
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
    fun anIdleNoticeWhileTheScreenMovesIsWorking() {
        assertEquals(Lens.Status.WORKING, Lens.status(pane("claude", "✳ x", "idle"), null, true))
        assertEquals(Lens.Status.WAITING, Lens.status(pane("claude", "✳ x", "idle"), "working", false))
        assertEquals(Lens.Status.WAITING, Lens.status(pane("claude", "✳ x", "question"), null, true))
        assertEquals(Lens.Status.WAITING, Lens.status(pane("bash", "", "idle"), null, true))
        assertEquals(Lens.Status.WORKING, Lens.status(pane("codex", "codex", "idle"), null, true))
    }

    @Test
    fun claudeIsNamedWithItsCodenameAndAnythingElseByItsCommand() {
        assertEquals(Lens.Description(Lens.Agent.CLAUDE, "Claude", "muxway android"), Lens.describe(pane("claude", "✳ muxway android")))
        assertEquals(Lens.Description(Lens.Agent.CLAUDE, "Claude", "fixing the build"), Lens.describe(pane("node", "✻ fixing the build")))
        assertEquals(Lens.Description(Lens.Agent.CLAUDE, "Claude", ""), Lens.describe(pane("claude", "claude")))
        assertEquals(Lens.Description(null, "vim", "notes.md"), Lens.describe(pane("vim", "notes.md")))
        assertEquals(Lens.Description(null, "bash", ""), Lens.describe(pane("bash", "bash")))
        assertEquals(Lens.Description(null, "work/0.0", ""), Lens.describe(pane("", "")))
        assertEquals("Claude · muxway android", Lens.name(pane("claude", "✳ muxway android")))
        assertEquals("bash · alice@laptop: ~", Lens.name(pane("bash", "alice@laptop: ~")))
        assertEquals("Claude", Lens.name(pane("bash", "✳")))
        assertEquals("bash", Lens.name(pane("bash", "")))
        assertEquals("muxway android", Lens.tab(pane("claude", "✳ muxway android")))
        assertEquals("Claude", Lens.tab(pane("claude", "")))
        assertEquals("vim · notes.md", Lens.tab(pane("vim", "notes.md")))
        assertEquals("alice-laptop/work/0.0", pane("vim", "notes.md").place)
    }
}
