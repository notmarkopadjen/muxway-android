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
        // The browser's row: nine named in the guideline and sixteen more, each with a tone.
        assertEquals(24, row.size)
        assertEquals(listOf("/clear", "/exit", "/compact", "/rename", "/model", "/resume", "/usage", "/context", "/help"), row.take(9).map { it.text.trim() })
        assertEquals(Commands.Tone.DANGER, row.first { it.text == "/clear" }.tone)
        assertEquals(Commands.Tone.START, row.first { it.text == "/resume" }.tone)
        assertEquals(Commands.Tone.INFO, row.first { it.text == "/help" }.tone)
        assertEquals(Commands.Tone.ACT, row.first { it.text == "/compact" }.tone)
    }

    @Test
    fun codexAndAntigravityGetTheirOwnSlashCommands() {
        val (kind, row) = Commands.forPane(pane("codex", "codex"), emptyList())!!
        assertEquals(Commands.Kind.CODEX, kind)
        assertEquals(listOf("/new", "/quit", "/compact", "/model"), row.take(4).map { it.text.trim() })
        assertFalse(row.first { it.label == "/mention" }.send)
        val (k2, agy) = Commands.forPane(pane("agy", "notes"), emptyList())!!
        assertEquals(Commands.Kind.ANTIGRAVITY, k2)
        assertEquals(22, agy.size)
        assertEquals(Commands.Tone.DANGER, agy.first { it.text == "/rewind" }.tone)
        assertFalse(agy.first { it.label == "/plan" }.send)
        assertNull(Commands.forPane(pane("opencode", "x"), emptyList()))
        assertEquals(Lens.Agent.CODEX, Commands.SHELL.first { it.text == "codex" }.agent)
        assertEquals(Lens.Agent.ANTIGRAVITY, Commands.SHELL.first { it.text == "agy" }.agent)
        assertEquals(Lens.Agent.CLAUDE, Commands.SHELL.first { it.text == "claude -c" }.agent)
        assertNull(Commands.SHELL.first { it.text == "exit" }.agent)
    }

    @Test
    fun theShellsStartersAreTheBrowsersWithTheirTones() {
        assertEquals(29, Commands.SHELL.size)
        assertEquals(Commands.Tone.DANGER, Commands.SHELL.first { it.text == "exit" }.tone)
        assertEquals(Commands.Tone.START, Commands.SHELL.first { it.text == "claude -c" }.tone)
        assertEquals(Commands.Tone.INFO, Commands.SHELL.first { it.text == "git status" }.tone)
        assertEquals(Commands.Tone.ACT, Commands.SHELL.first { it.text == "make test" }.tone)
        for (c in listOf("git pull", "make", "npm test", "docker ps", "tmux ls")) assertTrue(c, Commands.SHELL.any { it.text == c })
        assertEquals(Input.Group.CTRL, Input.group("C-c"))
        assertEquals(Input.Group.NAV, Input.group("PageUp"))
        assertEquals(Input.Group.FN, Input.group("F12"))
        assertEquals(Input.Group.EDIT, Input.group("BTab"))
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
        // Lines that are starters already are not listed twice; the others follow the starters.
        assertEquals(emptyList<String>(), Commands.forPane(pane("bash", "bash"), recent)!!.second.drop(Commands.SHELL.size).map { it.text })
        val row = Commands.forPane(pane("bash", "bash"), Commands.remembered(recent, "make check"))!!.second
        assertEquals(listOf("make check"), row.drop(Commands.SHELL.size).map { it.text })
        val long = "y".repeat(40)
        val last = Commands.forPane(pane("sh", ""), listOf(long))!!.second.last()
        assertEquals(long, last.text)
        assertEquals(24, last.label.length)
        assertTrue(last.label.endsWith("…"))
    }

    @Test
    fun aLineThatMayCarryASecretIsNotRemembered() {
        val secret = listOf(
            "curl -H 'Authorization: Bearer abc' https://hub.example.com",
            "muxway hub token create --name phone",
            "mysql -u alice --password=hunter2",
            "cat /etc/passwd",
            "echo mxt_" + "A".repeat(43),
            "vault read secret/app",
            "export API_KEY=abc", "OPENAI_API_KEY=abc ./run", "export HOME=/tmp", "FOO=bar make",
        )
        for (l in secret) assertTrue(l, Commands.looksSecret(l))
        for (l in listOf("make test", "git status", "ls -la", "echo a=b", "lowercase=1 run", "cd ~/work")) assertFalse(l, Commands.looksSecret(l))
        var recent = emptyList<String>()
        for (l in listOf("make test") + secret) recent = Commands.remembered(recent, l)
        assertEquals(listOf("make test"), recent)
    }

    @Test
    fun theListIsBounded() {
        var recent = emptyList<String>()
        for (i in 0 until Commands.RECENT_MAX + 3) recent = Commands.remembered(recent, "cmd $i")
        assertEquals(Commands.RECENT_MAX, recent.size)
        assertEquals("cmd ${Commands.RECENT_MAX + 2}", recent.first())
    }
}
