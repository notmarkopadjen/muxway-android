package app.muxway.android.wear

import app.muxway.android.protocol.Board
import app.muxway.android.protocol.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceTest {
    private val now = 1_767_225_600L
    private fun row(n: Int, state: Int, kind: String = "", at: Long = now) = Row("alice-laptop/work/0.$n", "pane $n", state, kind, at, "")
    private fun board(vararg rows: Row) = Board(rows.toList(), now, true)

    @Test
    fun rowsCarryGlyphWordNameAndAColourValue() {
        val b = board(row(1, Board.WAITING, "question"), row(2, Board.WORKING), row(3, Board.QUIET))
        assertEquals(Cell("? question", "pane 1", 2, "alice-laptop/work/0.1"), Face.row(b, 0, 4, now, true))
        assertEquals(Cell("⟳ working", "pane 2", 0, "alice-laptop/work/0.2"), Face.row(b, 1, 4, now, true))
        assertEquals(Cell("pane 3", "", 1, "alice-laptop/work/0.3"), Face.row(b, 2, 4, now, true))
        assertEquals(Cell("·", "", 0), Face.row(b, 3, 4, now, true))
    }

    @Test
    fun theLastLineCountsWhatDoesNotFit() {
        val b = board(*(1..6).map { row(it, Board.QUIET) }.toTypedArray())
        assertEquals("pane 3", Face.row(b, 2, 4, now, true).text)
        assertEquals(Cell("+3", "more", 0), Face.row(b, 3, 4, now, true))
        assertEquals("pane 6", Face.row(b, 5, 6, now, true).text)
    }

    @Test
    fun noPhoneStoppedOrStaleDimsEverythingAndSaysWhy() {
        val b = board(row(1, Board.WAITING, "done"))
        assertEquals("no phone", Face.problem(b, now, false))
        assertEquals(0, Face.row(b, 0, 4, now, false).value)
        assertEquals(Cell("no phone", "out of reach", 0), Face.summary(b, now, false))
        assertEquals("--", Face.short(b, now, false))
        assertEquals("paused", Face.problem(b.copy(live = false), now, true))
        assertEquals("stale", Face.problem(b, now + 3600, true))
        assertNull(Face.problem(b, now + 5, true))
        assertEquals(Cell("no phone", "the phone is out of reach", 0), Face.row(Board.EMPTY, 0, 4, now, false))
        assertEquals(Cell("paused", "the phone service is off", 0), Face.row(Board(emptyList(), now, false), 0, 4, now, true))
        assertEquals(Cell("no panes", "the hub lists none", 0), Face.row(Board(emptyList(), now, true), 0, 4, now, true))
    }

    @Test
    fun aWatchThatNeverGotABoardSaysSoNotPaused() {
        // The empty board is what a fresh install holds: not the phone's doing, so not "paused".
        assertEquals("no board", Face.problem(Board.EMPTY, now, true))
        assertEquals(Cell("no board", "none sent yet", 0), Face.summary(Board.EMPTY, now, true))
        assertEquals("no phone", Face.problem(Board.EMPTY, now, false))
    }

    @Test
    fun aProblemIsSpelledOutOverTheFreeRows() {
        val empty = Board(emptyList(), now, false)
        assertEquals(Cell("paused", "the phone service is off", 0), Face.row(empty, 0, 4, now, true))
        assertEquals(Cell("in the phone's muxway app", "", 0), Face.row(empty, 1, 4, now, true))
        assertEquals(Cell("turn on", "watch and notifications", 0), Face.row(empty, 2, 4, now, true))
        assertEquals(Cell("·", "", 0), Face.row(empty, 3, 4, now, true))
        // With panes on the board the rows keep them, dim, and the advice takes what is left.
        val old = board(row(1, Board.WAITING, "done"), row(2, Board.QUIET))
        val later = now + 23 * 60
        assertEquals(Cell("✓ done", "pane 1", 0, "alice-laptop/work/0.1"), Face.row(old, 0, 4, later, true))
        assertEquals(Cell("stale", "last board 23 min ago", 0), Face.row(old, 2, 4, later, true))
        assertEquals(Cell("the phone stopped sending", "", 0), Face.row(old, 3, 4, later, true))
        assertEquals(Cell("stale", "23 min ago", 0), Face.summary(old, later, true))
        // A full face has no free row: the summary alone carries the why.
        val full = board(*(1..6).map { row(it, Board.QUIET) }.toTypedArray())
        assertEquals(Cell("+3", "more", 0), Face.row(full, 3, 4, later, true))
        assertEquals(Cell("stale", "23 min ago", 0), Face.summary(full, later, true))
        assertEquals("no phone", Face.row(full, 6, 8, now, false).text)
    }

    @Test
    fun everyAdviceLineFitsARowOfTheFace() {
        for (p in listOf("no phone", "no board", "paused", "stale", "no panes")) {
            for (c in Face.advice(p, board(), now + 7 * 86400)) {
                assertEquals(c.text, true, c.text.length <= 26)
                assertEquals(c.title, true, c.title.length <= 25)
            }
        }
        assertEquals("3 h", Face.age(3 * 3600 + 59))
        assertEquals("2 d", Face.age(2 * 86400 + 5))
        assertEquals("0 min", Face.age(-30))
    }

    @Test
    fun theSummarySaysWhatThePanesAreDoing() {
        assertEquals(Cell("2 waiting", "3 panes", 2), Face.summary(board(row(1, Board.WAITING, "done"), row(2, Board.WAITING, "idle"), row(3, Board.QUIET)), now, true))
        assertEquals(Cell("all quiet", "1 pane", 1), Face.summary(board(row(1, Board.QUIET)), now, true))
        assertEquals(Cell("no panes", "", 0), Face.summary(Board(emptyList(), now, true), now, true))
        assertEquals("✓2", Face.short(board(row(1, Board.WAITING, "done"), row(2, Board.WAITING, "idle")), now, true))
    }

    @Test
    fun panesThatWorkAreNotQuiet() {
        val both = board(row(1, Board.WORKING), row(2, Board.WORKING))
        assertEquals(Cell("2 working", "2 panes", 0), Face.summary(both, now, true))
        assertEquals("⟳2", Face.short(both, now, true))
        // One of each: the waiting pane leads, the working one is named beside the count.
        val mixed = board(row(1, Board.WAITING, "question"), row(2, Board.WORKING), row(3, Board.QUIET))
        assertEquals(Cell("1 waiting", "1 working · 3 panes", 2), Face.summary(mixed, now, true))
        assertEquals("?1", Face.short(mixed, now, true))
        assertEquals("·", Face.short(board(row(1, Board.QUIET)), now, true))
    }

    @Test
    fun theGaugeIsAProportionOfThePanesNotAState() {
        // One working pane of two is half a ring, whatever colour the face draws it in.
        assertEquals(1f to 2f, Face.gauge(board(row(1, Board.WORKING), row(2, Board.QUIET)), now, true))
        assertEquals(3f to 5f, Face.gauge(board(row(1, Board.WAITING, "done"), row(2, Board.WAITING, "idle"), row(3, Board.WORKING), row(4, Board.QUIET), row(5, Board.QUIET)), now, true))
        // A quiet day is an empty ring, and so is one the watch cannot trust or has no panes for.
        assertEquals(0f to 2f, Face.gauge(board(row(1, Board.QUIET), row(2, Board.QUIET)), now, true))
        assertEquals(0f to 1f, Face.gauge(board(row(1, Board.WORKING)), now, false))
        assertEquals(0f to 1f, Face.gauge(Board(emptyList(), now, true), now, true))
    }

    @Test
    fun thePreviewIsSyntheticAndFillsFourRows() {
        for (i in 0 until 4) assertEquals(true, Face.row(Face.preview, i, 4, Face.preview.at, true).text.isNotEmpty())
        assertEquals("+2", Face.row(Face.preview, 3, 4, Face.preview.at, true).text)
        assertEquals(true, Face.preview.rows.all { it.address.startsWith("alice-laptop/") })
    }
}
