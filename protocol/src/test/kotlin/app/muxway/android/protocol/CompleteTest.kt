package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleteTest {
    private fun pane(command: String, title: String = "") = Pane(
        host = "alice-laptop", address = "alice-laptop/work/0.0", session = "work", windowIndex = 0, window = "shell",
        paneIndex = 0, paneId = "%0", width = 80, height = 24, title = title, command = command, mode = "write",
        alias = "", activity = 0L, attention = null,
    )

    private val claude = pane("claude", "✳ building")
    private val shell = pane("bash")

    @Test
    fun theFirstWordIsTakenOnlyWhileItIsTheOneBeingTyped() {
        assertEquals("/comp", Complete.firstWord("/comp", 5))
        assertEquals("/comp", Complete.firstWord("  /comp", 7))
        assertEquals("/comp", Complete.firstWord("/comp rest", 5))
        assertNull("the caret is inside the word", Complete.firstWord("/comp", 3))
        assertNull("a second word is not the first", Complete.firstWord("git sta", 7))
        assertNull("a word already ended", Complete.firstWord("/compact ", 9))
        assertNull(Complete.firstWord("", 0))
        assertNull(Complete.firstWord("/comp", 99))
    }

    @Test
    fun claudeOffersItsSlashCommandsAndAShellItsStarters() {
        // The command row's own commands come first, then the rest of Claude's.
        assertEquals(listOf("/compact", "/context", "/config", "/cost"), Complete.forPane(claude, "/co", 3, emptyList()))
        assertEquals(listOf("/rename", "/resume", "/release-notes", "/review"), Complete.forPane(claude, "/re", 3, emptyList()))
        assertEquals(listOf("claude", "claude -c", "clear"), Complete.forPane(shell, "cl", 2, emptyList()))
        assertEquals(listOf("git status", "git push"), Complete.forPane(shell, "git", 3, listOf("git push", "ls -l")))
        assertTrue(Complete.forPane(pane("vim"), "cl", 2, emptyList()).isEmpty())
        assertTrue(Complete.forPane(shell, "zzz", 3, emptyList()).isEmpty())
    }

    @Test
    fun nothingAPanePrintedIsEverOffered() {
        // The pane's own title and command cannot reach the pool, nor can a line it printed.
        val hostile = pane("rm -rf /", "✳ mxt_secret_token")
        val offered = Complete.forPane(hostile, "m", 1, emptyList()) + Complete.forPane(hostile, "r", 1, emptyList())
        assertTrue(offered.none { it.contains("mxt_") || it.contains("rm -rf") })
        // A shell's recent lines are this client's own sends, and they are offered.
        assertEquals(listOf("make test"), Complete.forPane(shell, "make", 4, listOf("make test")))
    }

    @Test
    fun atMostEightAreOffered() {
        assertEquals(Complete.MAX, Complete.forPane(claude, "/", 1, emptyList()).size)
    }

    @Test
    fun takingOneLeavesASpaceAndTheCaretAfterIt() {
        assertEquals("/compact " to 9, Complete.take("/comp", 5, "/compact"))
        assertEquals("  /compact " to 11, Complete.take("  /comp", 7, "/compact"))
        assertEquals("git status rest" to 11, Complete.take("git rest", 3, "git status"))
    }
}
