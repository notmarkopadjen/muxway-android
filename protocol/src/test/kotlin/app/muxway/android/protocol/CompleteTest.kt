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
        assertEquals(listOf("/compact", "/context", "/cost", "/config"), Complete.forPane(claude, "/co", 3, emptyList()))
        assertEquals(listOf("/rename", "/resume", "/review", "/release-notes"), Complete.forPane(claude, "/re", 3, emptyList()))
        assertEquals(listOf("claude", "claude -c", "claude --resume", "clear"), Complete.forPane(shell, "cl", 2, emptyList()))
        assertEquals(listOf("/approvals"), Complete.forPane(pane("codex", "codex"), "/ap", 3, emptyList()))
        assertEquals(listOf("/goal", "/grill-me"), Complete.forPane(pane("agy", "notes"), "/g", 2, emptyList()))
        assertEquals(listOf("git status", "git diff", "git log --oneline -20", "git pull", "git push", "git stash", "git stash pop", "git fetch"), Complete.forPane(shell, "git", 3, listOf("git push", "git fetch", "ls -l")))
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
        assertEquals(listOf("make", "make test", "make check"), Complete.forPane(shell, "make", 4, listOf("make check")))
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

    @Test
    fun anyWordTwoLettersInGetsTheScreensWordsAfterTheCommands() {
        val words = Complete.screenWords("ls\ndocs/plans/a.md  notes.txt  README\n$ cat docs/plans/a.md\n│ quoted │\nhi there, Alice.")
        assertEquals(listOf("Alice", "there", "quoted", "docs/plans/a.md"), words.take(4))
        assertTrue("ls" !in words && "│" !in words)
        assertEquals(listOf(Complete.Offer("docs/plans/a.md", true)), Complete.offers(shell, "cat do", 6, emptyList(), words))
        assertTrue(Complete.offers(shell, "d", 1, emptyList(), words).none { it.word })
        val first = Complete.offers(claude, "/co", 3, emptyList(), listOf("/compact-notes.md"))
        assertEquals(false, first[0].word)
        assertTrue(first.any { it.text == "/compact-notes.md" && it.word })
        assertTrue(Complete.offers(shell, "cat docs/plans/a.md", 19, emptyList(), words).isEmpty())
        assertEquals(Complete.At("do", 4, false), Complete.wordAt("cat do", 6))
        assertNull(Complete.wordAt("cat do", 5))
        assertEquals("cat docs/plans/a.md " to 20, Complete.take("cat do", 6, "docs/plans/a.md"))
    }
}
