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
        board.at == 0L -> "no board"
        !board.live -> "paused"
        board.stale(now) -> "stale"
        else -> null
    }

    /** The problem's cause in a few words, for the line beside its name. */
    fun reason(problem: String, board: Board, now: Long): String = when (problem) {
        "no phone" -> "out of reach"
        "no board" -> "none sent yet"
        "paused" -> "service off"
        "stale" -> "${age(now - board.at)} ago"
        else -> ""
    }

    /**
     * The problem spelled out, a line per free row: what is wrong, then what to do about it.
     * Each line fits a row of the face (some twenty-five characters in either half).
     */
    fun advice(problem: String, board: Board, now: Long): List<Cell> = when (problem) {
        "no phone" -> listOf(
            Cell("no phone", "the phone is out of reach", 0),
            Cell("check its connection", "", 0),
            Cell("and that muxway is on it", "", 0),
        )
        "no board" -> listOf(
            Cell("no board", "the phone sent none yet", 0),
            Cell("open muxway on the phone", "", 0),
            Cell("and turn on", "watch and notifications", 0),
        )
        "paused" -> listOf(
            Cell("paused", "the phone service is off", 0),
            Cell("in the phone's muxway app", "", 0),
            Cell("turn on", "watch and notifications", 0),
        )
        "stale" -> listOf(
            Cell("stale", "last board ${age(now - board.at)} ago", 0),
            Cell("the phone stopped sending", "", 0),
            Cell("open muxway on the phone", "", 0),
        )
        "no panes" -> listOf(
            Cell("no panes", "the hub lists none", 0),
            Cell("expose one on a host", "", 0),
        )
        else -> emptyList()
    }

    /** Line [i] of a display with [n] lines. */
    fun row(board: Board, i: Int, n: Int, now: Long, phone: Boolean): Cell {
        val problem = problem(board, now, phone)
        val shown = board.shown(n)
        val hidden = board.hidden(n)
        val used = shown.size + if (hidden > 0) 1 else 0
        val note = problem ?: "no panes".takeIf { board.rows.isEmpty() }
        return when {
            i < shown.size -> {
                val r = shown[i]
                val text = r.glyph?.let { "$it ${r.word}" } ?: r.name
                val title = if (r.glyph != null) r.name else ""
                val value = if (problem != null) 0 else when (r.state) { Board.WAITING -> 2; Board.QUIET -> 1; else -> 0 }
                Cell(text, title, value, r.address)
            }
            i == shown.size && hidden > 0 -> Cell("+$hidden", "more", 0)
            note != null -> advice(note, board, now).getOrNull(i - used) ?: DOT
            else -> DOT
        }
    }

    /** The bottom line: what the panes are doing, strongest state first, or what is wrong and why. */
    fun summary(board: Board, now: Long, phone: Boolean): Cell {
        val panes = panes(board.rows.size)
        problem(board, now, phone)?.let { return Cell(it, reason(it, board, now), 0) }
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

    /** A duration in the coarsest unit that is not zero. */
    fun age(seconds: Long): String = when {
        seconds < 3600 -> "${maxOf(seconds, 0) / 60} min"
        seconds < 86400 -> "${seconds / 3600} h"
        else -> "${seconds / 86400} d"
    }

    private val DOT = Cell("·", "", 0)

    private fun panes(n: Int) = if (n == 1) "1 pane" else "$n panes"
}
