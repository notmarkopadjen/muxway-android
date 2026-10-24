package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {
    private fun pane(command: String, title: String) = Pane(
        host = "alice-laptop", address = "alice-laptop/work/0.0", session = "work", windowIndex = 0, window = "shell",
        paneIndex = 0, paneId = "%0", width = 80, height = 24, title = title, command = command, mode = "write",
        alias = "", activity = 0L, attention = null,
    )

    @Test
    fun claudeGetsSlashCommandsSentWholeOrFilledWithASpace() {
        val (kind, row) = Commands.forPane(pane("node", "✳ building"), emptyList())!!
        assertEquals(Commands.Kind.CLAUDE, kind)
        for (c in row) {
            assertTrue(c.text, c.text.startsWith("/"))
            if (c.send) assertEquals(c.text, c.text.trim()) else assertTrue(c.text, c.text.endsWith(" ") && !c.text.endsWith("  "))
        }
        assertFalse(row.first { it.label == "/rename" }.send)
        assertEquals(Commands.Kind.CLAUDE, Commands.forPane(pane("claude", "claude"), emptyList())!!.first)
    }

    @Test
    fun aShellGetsItsCommandsExitFirstAndOtherProgramsNothing() {
        assertTrue(Commands.isShell("bash"))
        assertTrue(Commands.isShell("zsh"))
        assertFalse(Commands.isShell("vim"))
        val (kind, row) = Commands.forPane(pane("bash", "bash"), emptyList())!!
        assertEquals(Commands.Kind.SHELL, kind)
        assertEquals("exit", row.first().text)
        assertTrue(row.all { it.send })
        assertNull(Commands.forPane(pane("vim", "notes.md"), emptyList()))
    }

    @Test
    fun recentLinesFollowTheFixedOnesNewestFirstWithoutRepeats() {
        var recent = emptyList<String>()
        for (l in listOf("make test", "  ls  ", "git status", "make test", "", "x".repeat(201))) recent = Commands.remembered(recent, l)
        assertEquals(listOf("make test", "git status", "ls"), recent)
        val row = Commands.forPane(pane("bash", "bash"), recent)!!.second
        assertEquals(listOf("make test"), row.drop(Commands.SHELL.size).map { it.text })
        val long = "y".repeat(40)
        val last = Commands.forPane(pane("sh", ""), listOf(long))!!.second.last()
        assertEquals(long, last.text)
        assertEquals(24, last.label.length)
        assertTrue(last.label.endsWith("…"))
    }

    @Test
    fun theListIsBounded() {
        var recent = emptyList<String>()
        for (i in 0 until Commands.RECENT_MAX + 3) recent = Commands.remembered(recent, "cmd $i")
        assertEquals(Commands.RECENT_MAX, recent.size)
        assertEquals("cmd ${Commands.RECENT_MAX + 2}", recent.first())
    }
}
