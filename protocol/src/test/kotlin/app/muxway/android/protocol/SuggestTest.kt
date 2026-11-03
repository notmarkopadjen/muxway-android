package app.muxway.android.protocol

import app.muxway.android.protocol.Suggest.Body
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuggestTest {
    private val pool = listOf("git status", "git log --oneline -20", "echo one two", "make test")
    private val names = Names.index(listOf(Entry("docs", "dir"), Entry("notes.md", "file"), Entry("a b", "dir")))

    @Test
    fun theShadowFollowsTextBackspaceAndSpaceAndStartsAfreshOnEnterAndTheClearingKeys() {
        var s: String? = ""
        s = Suggest.nextShadow(s, Body.Text("git st"))
        assertEquals("git st", s)
        s = Suggest.nextShadow(s, Body.Key("BSpace"))
        assertEquals("git s", s)
        s = Suggest.nextShadow(s, Body.Key("BSpace", 10))
        assertEquals("", s)
        s = Suggest.nextShadow(s, Body.Text("ls"))
        s = Suggest.nextShadow(s, Body.Key("Space"))
        assertEquals("ls ", s)
        s = Suggest.nextShadow(s, Body.Key("C-l"))
        assertEquals("ls ", s)
        for (k in listOf("Enter", "C-c", "C-u")) assertEquals("", Suggest.nextShadow("abc", Body.Key(k)))
    }

    @Test
    fun theShadowIsUnknownAfterAKeyItCannotFollowAndKnownAgainAfterEnter() {
        for (k in listOf("Up", "Down", "Tab", "C-r", "Left", "Right", "Home", "End", "Delete", "C-a")) assertNull(k, Suggest.nextShadow("git", Body.Key(k)))
        assertNull(Suggest.nextShadow(null, Body.Text("x")))
        assertNull(Suggest.nextShadow(null, Body.Key("BSpace")))
        assertEquals("", Suggest.nextShadow(null, Body.Key("Enter")))
        assertNull(Suggest.nextShadow("x", Body.Text("a\nb")))
    }

    @Test
    fun theScreensCommandsComeNewestFirstOnceEachWithoutTheCursorRowAChoiceOrASecret() {
        val rows = listOf(
            "alice@box:~/work$ git status", "On branch main", "$ make test", "ok", "❯ ls -la", "$ make test", "❯ 1. Yes", "$ ",
        )
        assertEquals(listOf("make test", "ls -la", "git status"), Suggest.screenCommands(rows, 7))
        assertEquals(listOf("b"), Suggest.screenCommands(listOf("a > b")))
        assertEquals(emptyList<String>(), Suggest.screenCommands(listOf("x".repeat(100) + "$ hidden")))
        assertEquals(emptyList<String>(), Suggest.screenCommands(listOf("$ mysql -u root -p hunter2", "$ curl -u a:b https://x/", "$ export TOKEN=abc")))
    }

    @Test
    fun goesOnFromTheShadowWhereTheRowConfirmsIt() {
        assertEquals("atus", Suggest.suggest("$ git st", 9, "git st", pool))
        assertEquals("atus", Suggest.suggest("alice@box:~$ git st", 20, "git st", pool))
        assertNull(Suggest.suggest("$ git status", 13, "git status", pool))
        assertNull(Suggest.suggest("$ ", 3, "", pool))
        // The screen has not caught up, or shows something else.
        assertNull(Suggest.suggest("$ git s", 8, "git st", pool))
        assertNull(Suggest.suggest("$ make", 7, "git st", pool))
    }

    @Test
    fun withoutAShadowTakesTheLongestWordBoundarySuffixALineStartsWith() {
        assertEquals("atus", Suggest.suggest("alice@box:~/work$ git st", 25, null, pool))
        assertEquals("ne two", Suggest.suggest("$ echo o", 9, null, pool))
        assertNull(Suggest.suggest("$ g", 4, null, pool))
        assertNull(Suggest.suggest("$ xgit st", 10, null, pool))
        assertNull(Suggest.suggest("$ agit", 7, null, pool))
    }

    @Test
    fun showsNothingWithTextRightOfTheCursorAndNothingOffTheRow() {
        assertNull(Suggest.suggest("$ git st", 3, "git st", pool))
        assertNull(Suggest.suggest("$ git status", 5, null, pool))
        assertEquals("atus", Suggest.suggest("$ git st   ", 9, "git st", pool))
        assertEquals("two", Suggest.suggest("$ echo one", 12, "echo one ", pool))
        assertEquals("two", Suggest.suggest("$ echo one", 12, null, pool))
        assertNull(Suggest.suggest("$ git st", 0, "git st", pool))
    }

    @Test
    fun completesANameOfTheDirectoryAfterAWordADirectoryWithItsSlash() {
        assertEquals("cs/", Suggest.suggest("$ cd do", 8, "cd do", pool, names))
        assertEquals("tes.md", Suggest.suggest("$ cat no", 9, "cat no", pool, names))
        assertNull(Suggest.suggest("$ cat a", 8, "cat a", pool, names))
        assertEquals("ocs/", Suggest.suggest("$ cd d", 7, "cd d", pool, names))
        assertNull(Suggest.suggest("$ echo d", 9, "echo d", pool, names))
        assertEquals("cs/", Suggest.suggest("$ echo do", 10, "echo do", pool, names))
        assertNull(Suggest.suggest("$ do", 5, "do", pool, names))
        assertNull(Suggest.suggest("$ cd docs", 10, "cd docs", pool, names))
        assertEquals("atus", Suggest.suggest("$ git st", 9, "git st", pool, names))
    }

    @Test
    fun theLinesGhostIsTheCompletionOfTheTypedText() {
        assertEquals("atus", Suggest.completion("git st", pool))
        assertEquals("cs/", Suggest.completion("cd do", pool, names))
        assertNull(Suggest.completion("", pool))
        assertNull(Suggest.completion("git status", pool))
    }

    @Test
    fun theFirstWordIsTheGhostUpToAndIncludingTheNextBlank() {
        assertEquals("ne ", Suggest.firstWord("ne two"))
        assertEquals(" two ", Suggest.firstWord(" two three"))
        assertEquals("atus", Suggest.firstWord("atus"))
        assertEquals("", Suggest.firstWord(""))
    }
}
