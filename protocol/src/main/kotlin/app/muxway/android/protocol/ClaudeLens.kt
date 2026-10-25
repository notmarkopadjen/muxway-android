package app.muxway.android.protocol

/**
 * Claude Code ends its screen with chrome: the summary of the last turn, notices on the
 * right, a rule that may carry the session name, the input box, a rule and a footer. On a
 * small screen only the conversation is wanted, so this names the rows to keep, by index,
 * as the feature-phone and glasses clients do; the caller keeps the colours.
 */
object ClaudeLens {
    private const val MARKS = "✻✽✶✢✦✧·*"

    /** Rows of the transcript, and the input box rows pinned under it when someone typed into it. */
    data class Shaped(val body: List<Int>, val pinned: List<Int>)

    /** One of Claude's numbered choices as the screen shows it. */
    data class Choice(val digit: Char, val label: String, val marked: Boolean)

    fun runs(command: String) = command == "claude"

    /**
     * Null when [rows] are not Claude Code's screen. Forced, the lens does its best with any
     * rows and answers null only when there is nothing to hide.
     */
    fun shape(rows: List<String>, forced: Boolean): Shaped? {
        var below = -1
        var above = -1
        var i = rows.size - 1
        while (i >= 0 && above < 0) {
            // The bottom rule is clean; the one above it may carry the session name.
            if (rule(rows[i], below >= 0)) {
                if (below < 0) below = i else above = i
            }
            i--
        }
        if (above < 0) return if (forced) prompt(rows) else null
        val first = chrome(rows, above)
        val pinned = if (empty(rows, above + 1, below)) emptyList() else (above + 1 until below).toList()
        return Shaped(transcript(rows, first), pinned)
    }

    /** Without rules, the last prompt row near the bottom is the box and what follows it is footer. */
    private fun prompt(rows: List<String>): Shaped? {
        var box = -1
        var i = rows.size - 1
        while (i >= 0 && i >= rows.size - 8 && box < 0) {
            if (rows[i].startsWith("❯") || rows[i].startsWith("> ") || rows[i] == ">") box = i
            i--
        }
        if (box < 0) return null
        val first = chrome(rows, box)
        return Shaped(transcript(rows, first), if (empty(rows, box, box + 1)) emptyList() else listOf(box))
    }

    /** Where the chrome starts above row [at]: blank rows and right-aligned notices, then the summary of the last turn. */
    private fun chrome(rows: List<String>, at: Int): Int {
        var first = at
        while (first > 0 && (rows[first - 1].isEmpty() || rows[first - 1].startsWith("        "))) first--
        if (first > 0 && summary(rows[first - 1])) first--
        return first
    }

    /** The rows before the chrome, without the blank rows Claude fills a tall terminal with. */
    private fun transcript(rows: List<String>, first: Int): List<Int> {
        var end = first
        while (end > 0 && rows[end - 1].isEmpty()) end--
        return (0 until end).toList()
    }

    /** True when the box rows hold nothing but the prompt mark, or Claude's own hint (Try "..."). */
    private fun empty(rows: List<String>, from: Int, to: Int): Boolean {
        for (i in from until to) {
            var r = rows[i].trim()
            if (r.startsWith("❯") || r.startsWith(">")) r = r.substring(1).trim()
            if (r.isNotEmpty() && !(i == from && r.startsWith("Try \""))) return false
        }
        return true
    }

    /** The summary of the last turn: a spinner mark, then a duration such as 58s or 5m 27s. */
    fun summary(s: String): Boolean {
        if (s.length < 3 || s[1] != ' ' || s[0] !in MARKS) return false
        var i = 2
        while (i < s.length) {
            if (s[i].isDigit()) {
                var j = i
                while (j < s.length && s[j].isDigit()) j++
                if (j < s.length && (s[j] == 's' || s[j] == 'm') && (j + 1 == s.length || !s[j + 1].isLowerCase())) return true
                i = j
            }
            i++
        }
        return false
    }

    /**
     * A row of horizontal lines: three or more line characters, or twenty dashes once the hub
     * spelt them in ASCII. Loose, text up to half as long as the line is allowed, which is
     * how Claude writes the session name into its top rule.
     */
    fun rule(s: String, loose: Boolean = false): Boolean {
        var lines = 0
        var dashes = 0
        var other = 0
        for (c in s) {
            when {
                c == '─' || c == '━' || c == '═' -> lines++
                c == '-' || c == '=' -> dashes++
                c != ' ' -> other++
            }
        }
        if (other > 0 && (!loose || other * 2 > lines + dashes)) return false
        return lines >= 3 || (lines == 0 && dashes >= 20)
    }

    /**
     * Claude's numbered choices on the screen: the last run of rows `1. …` up to `9. …`
     * among the final [within] rows, two or more, the one marked `❯` first. Labels are cut.
     */
    fun choices(rows: List<String>, within: Int = 20): List<Choice> {
        val start = maxOf(0, rows.size - within)
        var i = rows.size - 1
        while (i >= start && CHOICE.matchEntire(rows[i]) == null) i--
        val run = ArrayList<Choice>()
        while (i >= start) {
            val m = CHOICE.matchEntire(rows[i]) ?: break
            val digit = m.groupValues[2][0]
            if (run.isNotEmpty() && digit + 1 != run.first().digit) break
            run.add(0, Choice(digit, m.groupValues[3].trim().take(60), m.groupValues[1].isNotEmpty()))
            i--
        }
        if (run.size < 2 || run.first().digit != '1') return emptyList()
        return run.sortedByDescending { it.marked }
    }

    private val CHOICE = Regex("^\\s*(❯\\s*)?([1-9])\\.\\s+(\\S.*)$")
}
