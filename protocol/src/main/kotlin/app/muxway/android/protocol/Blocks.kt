package app.muxway.android.protocol

/**
 * The blocks of a screen, as Claude Code and its kind print them: a run of rows down one bar
 * (│ ┃ | >), a ● or ❯ row with the rows indented under it, a ⎿ result with its rows. A block is
 * what a long press copies: the text without the bar, the marker and the indent, the rows inside
 * kept as they are. The browser's `term/blocks.ts`, row for row.
 */
object Blocks {
    data class Block(val start: Int, val end: Int, val text: String)

    private val bar = Regex("""^(\s*)([│┃|>])( ?)(.*)$""")
    private val bullet = Regex("""^(\s*)([●❯⏺✻✶✽✢·⎿])( +)(.*)$""")

    fun find(lines: List<String>): List<Block> {
        val out = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val b = bar.matchEntire(lines[i])
            if (b != null && b.groupValues[4].isNotBlank()) {
                var j = i
                val rows = ArrayList<String>()
                while (j < lines.size) {
                    val m = bar.matchEntire(lines[j]) ?: break
                    if (m.groupValues[1] != b.groupValues[1] || m.groupValues[2] != b.groupValues[2]) break
                    rows.add(m.groupValues[4])
                    j++
                }
                out.add(Block(i, j - 1, trimEnd(rows)))
                i = j
                continue
            }
            val m = bullet.matchEntire(lines[i])
            if (m != null && m.groupValues[4].isNotBlank()) {
                val indent = m.groupValues[1].length + m.groupValues[2].length + m.groupValues[3].length
                val rows = arrayListOf(m.groupValues[4])
                var j = i + 1
                while (j < lines.size) {
                    val row = lines[j]
                    if (row.isBlank()) {
                        val next = lines.getOrNull(j + 1)
                        if (next != null && under(next, indent) && !bullet.matches(next) && !bar.matches(next)) {
                            rows.add("")
                            j++
                            continue
                        }
                        break
                    }
                    if (!under(row, indent) || bullet.matches(row) || bar.matches(row)) break
                    rows.add(row.substring(indent))
                    j++
                }
                out.add(Block(i, j - 1, trimEnd(rows)))
                i = j
                continue
            }
            i++
        }
        return out
    }

    /** The block [row] belongs to, if any. */
    fun at(all: List<Block>, row: Int): Block? = all.firstOrNull { row in it.start..it.end }

    private fun under(row: String, indent: Int): Boolean = row.length > indent && row.substring(0, indent).isBlank()

    private fun trimEnd(rows: MutableList<String>): String {
        while (rows.isNotEmpty() && rows.last().isBlank()) rows.removeAt(rows.lastIndex)
        return rows.joinToString("\n") { it.trimEnd() }
    }
}
