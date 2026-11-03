package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamesTest {
    private val index = Names.index(
        listOf(
            Entry("docs", "dir"), Entry("plans", "dir"), Entry("a b", "dir"), Entry("notes.md", "file"),
            Entry("it's.txt", "file"), Entry("Makefile", "file"), Entry("sock", "other"), Entry(".", "dir"), Entry("..", "dir"),
        ),
    )

    private fun names(line: String) = Names.find(line, index).map { Triple(line.substring(it.start, it.end), it.name, it.kind) }
    private fun n(name: String, kind: String) = Triple(name, name, kind)

    @Test
    fun findsTheNamesOfAListingInColumnsAndOnePerRow() {
        assertEquals(listOf(n("docs", "dir"), n("notes.md", "file"), n("plans", "dir")), names("docs  notes.md  plans  sock"))
        assertEquals(listOf(n("plans", "dir")), names("plans"))
    }

    @Test
    fun readsLsLongLsFAndTheQuotingOfLs() {
        assertEquals(listOf(n("docs", "dir")), names("drwxr-xr-x 2 alice alice 4096 Nov  2 10:00 docs"))
        assertEquals(listOf(n("docs", "dir"), n("notes.md", "file"), n("Makefile", "file")), names("docs/  notes.md  Makefile*"))
        assertEquals(listOf(n("a b", "dir"), n("it's.txt", "file"), n("docs", "dir")), names("'a b'  \"it's.txt\"  docs"))
    }

    @Test
    fun readsATreeAGitStatusAndProse() {
        assertEquals(listOf(n("docs", "dir")), names("├── docs"))
        assertEquals(listOf(n("notes.md", "file")), names("│   └── notes.md"))
        assertEquals(listOf(n("notes.md", "file")), names("\tmodified:   notes.md"))
        assertEquals(listOf(n("notes.md", "file"), n("docs", "dir")), names("see notes.md, then docs."))
        assertEquals(listOf(n("docs", "dir"), n("plans", "dir"), n("notes.md", "file")), names("(docs) [plans] <notes.md>"))
    }

    @Test
    fun leavesANameGluedToOtherCharactersAPartOfAPathAndTheDots() {
        assertEquals(emptyList<Triple<String, String, String>>(), names("mydocs docsy docs/plans notes.md.bak x.docs"))
        assertEquals(emptyList<Triple<String, String, String>>(), names(". .. ./docs ../plans"))
        assertEquals(emptyList<Triple<String, String, String>>(), names("http://example.com/docs"))
    }

    @Test
    fun neverLinksWhatTheHostDidNotListNorAnEntryOfAnotherKind() {
        assertEquals(emptyList<Triple<String, String, String>>(), names("sock other things"))
        assertEquals(emptyList<Names.Mark>(), Names.find("docs", Names.index(emptyList())))
        assertEquals(emptyList<Names.Mark>(), Names.find("docs", Names.Index.EMPTY))
    }

    @Test
    fun letsNoTwoNamesOverlap() {
        val idx = Names.index(listOf(Entry("a b", "dir"), Entry("b", "file"), Entry("a", "file")))
        assertEquals(listOf(Names.Mark(0, 3, "a b", "dir")), Names.find("a b", idx))
        assertEquals(listOf(Names.Mark(0, 1, "b", "file"), Names.Mark(2, 3, "a", "file")), Names.find("b a", idx))
    }

    @Test
    fun theNameAtAColumn() {
        assertEquals("notes.md", Names.at("docs  notes.md", 7, index)?.name)
        assertNull(Names.at("docs  notes.md", 4, index))
    }

    @Test
    fun quotesEveryNameWhole() {
        assertEquals("'docs'", Names.shellQuote("docs"))
        assertEquals("'a b'", Names.shellQuote("a b"))
        assertEquals("'it'\\''s'", Names.shellQuote("it's"))
        assertEquals("''\\''; rm -rf ~ #'", Names.shellQuote("'; rm -rf ~ #"))
        assertEquals("'-n'", Names.shellQuote("-n"))
        assertEquals("'$(x)`y`'", Names.shellQuote("$(x)`y`"))
    }

    @Test
    fun typesCdForADirectoryAndTheSettingForAFile() {
        assertEquals("cd -- '-x'", Names.lineFor("dir", "-x", OpenMode.PREVIEW, ""))
        assertEquals("cd -- 'a b'", Names.lineFor("dir", "a b", OpenMode.CAT, ""))
        assertNull(Names.lineFor("file", "notes.md", OpenMode.PREVIEW, ""))
        assertEquals("cat -- 'notes.md'", Names.lineFor("file", "notes.md", OpenMode.CAT, ""))
        assertEquals("vim 'it'\\''s'", Names.lineFor("file", "it's", OpenMode.COMMAND, "vim {}"))
        assertEquals("less 'notes.md'", Names.lineFor("file", "notes.md", OpenMode.COMMAND, "less"))
        assertNull(Names.lineFor("file", "notes.md", OpenMode.COMMAND, "  "))
        assertEquals("diff 'x' 'x'", Names.lineFor("file", "x", OpenMode.COMMAND, "diff {} {}"))
    }

    @Test
    fun titlesSayWhatATapDoes() {
        assertEquals("cd docs", Names.titleFor("dir", "docs", OpenMode.CAT, ""))
        assertEquals("preview notes.md", Names.titleFor("file", "notes.md", OpenMode.PREVIEW, ""))
        assertEquals("cat notes.md", Names.titleFor("file", "notes.md", OpenMode.CAT, ""))
        assertEquals("vim notes.md", Names.titleFor("file", "notes.md", OpenMode.COMMAND, "vim {}"))
        assertEquals("preview notes.md", Names.titleFor("file", "notes.md", OpenMode.COMMAND, ""))
    }
}
