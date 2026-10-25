package app.muxway.android.wear

import app.muxway.android.protocol.Board
import app.muxway.android.protocol.Lens
import app.muxway.android.protocol.Row

/**
 * What every surface on the watch shows, computed in one place: a line per row and a summary.
 * [value] is for faces that colour by it: 0 dim (nothing, working, stale, overflow), 1 ink
 * (a quiet pane), 2 attention (a pane that waits).
 */
data class Cell(val text: String, val title: String, val value: Int, val address: String = "")

object Face {
    const val ROWS = 4

    /** A fixture for previews and editors: never a real pane. */
    val preview = Board(
        listOf(
            Row("alice-laptop/work/0.1", "deploy", Board.WAITING, "question", 1_767_225_540, ""),
            Row("alice-laptop/work/0.0", "build", Board.WAITING, "done", 1_767_225_590, ""),
            Row("alice-laptop/work/1.0", "tests", Board.WORKING, "", 0, ""),
            Row("alice-laptop/work/1.1", "shell", Board.QUIET, "", 0, ""),
            Row("alice-laptop/work/2.0", "logs", Board.QUIET, "", 0, ""),
        ),
        1_767_225_600, true,
    )

    /** Why the board cannot be trusted right now, or null. */
    fun problem(board: Board, now: Long, phone: Boolean): String? = when {
        !phone -> "no phone"
        !board.live -> "paused"
        board.stale(now) -> "stale"
        else -> null
    }

    /** Line [i] of a display with [n] lines. */
    fun row(board: Board, i: Int, n: Int, now: Long, phone: Boolean): Cell {
        val problem = problem(board, now, phone)
        val shown = board.shown(n)
        val hidden = board.hidden(n)
        return when {
            i < shown.size -> {
                val r = shown[i]
                val text = r.glyph?.let { "$it ${r.word}" } ?: r.name
                val title = if (r.glyph != null) r.name else ""
                val value = if (problem != null) 0 else when (r.state) { Board.WAITING -> 2; Board.QUIET -> 1; else -> 0 }
                Cell(text, title, value, r.address)
            }
            i == shown.size && hidden > 0 -> Cell("+$hidden", "more", 0)
            i == 0 && problem != null -> Cell(problem, "", 0)
            i == 0 && board.rows.isEmpty() -> Cell("no panes", "", 0)
            else -> Cell("·", "", 0)
        }
    }

    /** The bottom line: what the panes are doing, strongest state first, or what is wrong. */
    fun summary(board: Board, now: Long, phone: Boolean): Cell {
        val panes = panes(board.rows.size)
        problem(board, now, phone)?.let { return Cell(it, panes, 0) }
        val working = "${board.working} working"
        return when {
            board.rows.isEmpty() -> Cell("no panes", "", 0)
            board.waiting > 0 -> Cell("${board.waiting} waiting", if (board.working > 0) "$working · $panes" else panes, 2)
            board.working > 0 -> Cell(working, panes, 0)
            else -> Cell("all quiet", panes, 1)
        }
    }

    /** Seven characters at most, for a small slot: what waits, else what works, else nothing to say. */
    fun short(board: Board, now: Long, phone: Boolean): String {
        if (problem(board, now, phone) != null) return "--"
        board.rows.firstOrNull { it.state == Board.WAITING }?.let { return "${it.glyph}${board.waiting}" }
        if (board.working > 0) return "${Lens.glyph(Lens.Status.WORKING, null)}${board.working}"
        return "·"
    }

    /**
     * The summary as a gauge, for faces that draw one: the panes that wait or work, out of all
     * of them, so one working pane of two is half a ring and a quiet day is an empty one. The
     * colour hint in [Cell.value] is for the rows; a ring cannot say which state it is showing.
     */
    fun gauge(board: Board, now: Long, phone: Boolean): Pair<Float, Float> {
        if (problem(board, now, phone) != null || board.rows.isEmpty()) return 0f to 1f
        return (board.waiting + board.working).toFloat() to board.rows.size.toFloat()
    }

    private fun panes(n: Int) = if (n == 1) "1 pane" else "$n panes"
}
