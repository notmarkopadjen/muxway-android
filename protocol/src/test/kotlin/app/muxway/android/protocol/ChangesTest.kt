package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangesTest {
    private val entries = listOf(
        Change("src/a.go", "modified"), Change("src/new.go", "added", staged = true), Change("src/moved.go", "renamed", staged = true, from = "src/old.go"),
        Change("src/gone.go", "deleted"), Change("notes.txt", "untracked"), Change("-rf", "untracked"), Change("docs/x/y.md", "conflict"),
    )

    @Test
    fun theTreeIsByDirectoryDirectoriesFirstAndSorted() {
        val t = Changes.tree(entries)
        assertEquals(listOf("docs", "src"), t.dirs.map { it.name })
        assertEquals(listOf("-rf", "notes.txt"), t.files.map { it.path })
        assertEquals(7, t.count)
        val src = t.dirs[1]
        assertEquals(4, src.count)
        assertEquals(listOf("src/a.go", "src/gone.go", "src/moved.go", "src/new.go"), src.files.map { it.path })
        assertEquals("x", t.dirs[0].dirs[0].name)
        assertEquals("docs/x", t.dirs[0].dirs[0].path)
        assertEquals("y.md", t.dirs[0].dirs[0].files[0].name)
    }

    @Test
    fun theCountsAndTheSummary() {
        val c = Changes.counts(entries)
        assertEquals(Changes.Counts(added = 3, changed = 2, deleted = 1, conflict = 1), c)
        assertEquals("+3 ~2 −1 !1", Changes.summary(c))
        assertEquals("✓", Changes.summary(Changes.counts(emptyList())))
        assertTrue(Changes.counts(emptyList()).clean)
        assertEquals("+", Changes.mark("untracked"))
        assertEquals("→", Changes.mark("renamed"))
        assertEquals("−", Changes.mark("deleted"))
    }

    @Test
    fun whatAChangeOpensAsAndHowADiffRowIsRead() {
        assertEquals(listOf("file"), Changes.views("untracked"))
        assertEquals(listOf("diff"), Changes.views("deleted"))
        assertEquals(listOf("diff", "file"), Changes.views("modified"))
        assertEquals('h', Changes.rowKind("+++ b/src/a.go"))
        assertEquals('+', Changes.rowKind("+func A() {}"))
        assertEquals('-', Changes.rowKind("-old"))
        assertEquals('@', Changes.rowKind("@@ -1 +1,3 @@"))
        assertEquals('h', Changes.rowKind("diff --git a/x b/x"))
        assertEquals(' ', Changes.rowKind(" package a"))
    }

    @Test
    fun aPathIsAskableAsListedAndNeverWithAControlCharacter() {
        assertTrue(Changes.askable("src/a.go"))
        assertTrue(Changes.askable("-rf"))
        assertTrue(Changes.askable("with space.txt"))
        assertFalse(Changes.askable(""))
        assertFalse(Changes.askable("a\nb"))
        assertFalse(Changes.askable("a\u0007"))
        assertFalse(Changes.askable("x".repeat(1025)))
    }

    @Test
    fun theHubsAnswerIsReadDefensively() {
        val c = Json.changes("""{"root":"/home/alice/work/muxway","branch":"main","ahead":1,"entries":[{"path":"src/a.go","kind":"modified"},{"path":"src/new.go","kind":"added","staged":true},{"path":"x","kind":"weird\u0007"},{"path":"","kind":"added"}]}""".replace("""{"path":"","kind":"added"}""", """{"path":"ok","kind":"deleted"}"""))
        assertEquals("muxway", c.repo)
        assertEquals("main", c.branch)
        assertEquals(1, c.ahead)
        assertEquals(listOf("modified", "added", "modified", "deleted"), c.entries.map { it.kind })
        assertTrue(c.entries[1].staged)
        val d = Json.diff("""{"diff":"+a\u001b[31m\n-b\tc","binary":false,"cut":true}""")
        assertEquals("+a[31m\n-b\tc", d.diff)
        assertTrue(d.cut)
    }
}
