package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Claude Code lens as the glasses and the feature phone test it, on row indexes. */
class ClaudeLensTest {
    private val rule = "─".repeat(60)
    private val summary = "✻ Crunched for 58s · done 8:36 AM"
    private val notice = "                           ↓ Update installed · Restart to apply"
    private val footer = "  ⏵⏵ auto mode on (shift+tab to cycle) · ← 5 agents"
    private val claude = listOf(" Claude Code v1.0", " /home/alice/demo", "❯ say pong", "● pong", "", summary, "", notice, "$rule demo ─", "❯ hi", rule, footer)

    private fun rows(s: ClaudeLens.Shaped, all: List<String>) = s.body.map { all[it] } to s.pinned.map { all[it] }

    @Test
    fun theTranscriptStaysAndTheTypedBoxIsPinned() {
        val (body, pinned) = rows(ClaudeLens.shape(claude, false)!!, claude)
        assertEquals(listOf(" Claude Code v1.0", " /home/alice/demo", "❯ say pong", "● pong"), body)
        assertEquals(listOf("❯ hi"), pinned)
    }

    @Test
    fun anEmptyBoxAndClaudesHintAreHidden() {
        val empty = claude.toMutableList().also { it[9] = "❯ " }
        assertEquals(emptyList<Int>(), ClaudeLens.shape(empty, false)!!.pinned)
        val hint = claude.toMutableList().also { it[9] = "❯ Try \"fix the build\"" }
        assertEquals(emptyList<Int>(), ClaudeLens.shape(hint, false)!!.pinned)
    }

    @Test
    fun aShellIsLeftAloneAndOneRuleIsNotClaude() {
        assertNull(ClaudeLens.shape(listOf("$ ls", "a b c", "$ "), false))
        assertNull(ClaudeLens.shape(listOf("x", rule, "❯ hi", "y"), false))
        // Forced on a shell with no prompt row: nothing to hide.
        assertNull(ClaudeLens.shape(listOf("$ ls", "a b c", "done"), true))
        // Forced with a prompt row: the box and what follows it.
        val s = ClaudeLens.shape(listOf("● pong", "", "❯ hi", footer), true)!!
        assertEquals(listOf(0), s.body)
        assertEquals(listOf(2), s.pinned)
    }

    @Test
    fun aCraftedScreenHidesOnlyItsOwnBottom() {
        val many = List(1000) { rule } + listOf("❯ typed", rule, "footer")
        val s = ClaudeLens.shape(many, false)!!
        assertEquals(999, s.body.size)
        assertEquals(listOf(1000), s.pinned)
        val wide = listOf("keep me", "─".repeat(400), "❯", "─".repeat(400), "x".repeat(400))
        val w = ClaudeLens.shape(wide, false)!!
        assertEquals(listOf(0), w.body)
        assertTrue(w.pinned.isEmpty())
        // A transcript row that looks like a rule with text is not a rule.
        assertEquals(false, ClaudeLens.rule("--- a/file"))
        assertEquals(false, ClaudeLens.rule("──"))
        assertEquals(true, ClaudeLens.rule("  ═══  "))
        assertEquals(true, ClaudeLens.rule("─".repeat(10) + " demo", true))
        assertEquals(false, ClaudeLens.rule("─── my long session name here", true))
    }

    @Test
    fun theSummaryRowIsKnownByItsDuration() {
        assertTrue(ClaudeLens.summary(summary))
        assertTrue(ClaudeLens.summary("* Puttering... (37s · v 1.6k tokens)"))
        assertEquals(false, ClaudeLens.summary("● pong"))
        assertEquals(false, ClaudeLens.summary("● done at 8:41 PM"))
        assertEquals(false, ClaudeLens.summary("* 1.6k tokens"))
    }

    @Test
    fun claudesChoicesAreReadMarkedFirst() {
        val screen = listOf("● I will run make test", "", "Do you want to proceed?", "  1. Yes", "❯ 2. Yes, and don't ask again for make", "  3. No, and tell Claude what to do differently (esc)", "", rule, "❯ ", rule, footer)
        val c = ClaudeLens.choices(screen)
        assertEquals(listOf('2', '1', '3'), c.map { it.digit })
        assertEquals("Yes, and don't ask again for make", c[0].label)
        assertTrue(c[0].marked)
        assertTrue(ClaudeLens.choices(listOf("$ ls", "1. one", "$ ")).isEmpty())
        assertTrue(ClaudeLens.choices(listOf("2. two", "3. three")).isEmpty())
        assertEquals(2, ClaudeLens.choices(List(30) { "x" } + listOf("1. a", "2. b")).size)
        assertTrue(ClaudeLens.choices(listOf("1. a", "2. b") + List(30) { "x" }).isEmpty())
    }
}
