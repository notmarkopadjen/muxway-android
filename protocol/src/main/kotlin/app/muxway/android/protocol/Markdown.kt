package app.muxway.android.protocol

/**
 * Markdown as blocks and inline runs, the browser's subset: headings, paragraphs, lists,
 * quotes, fenced and indented code, tables, rules; inline code, emphasis, strong, strike and
 * links. Anything else is the text it is; no tag in a file reaches the screen.
 */
object Markdown {
    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Para(val text: String) : Block()
        data class Code(val lang: String, val text: String) : Block()
        data class Quote(val blocks: List<Block>) : Block()
        data class Items(val ordered: Boolean, val items: List<List<Block>>) : Block()
        data class Table(val head: List<String>, val rows: List<List<String>>) : Block()
        data object Rule : Block()
    }

    /** One run of inline text; [href] is a web address the run links to. */
    data class Span(val text: String, val code: Boolean = false, val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false, val href: String? = null)

    private val fence = Regex("""^(\s{0,3})(`{3,}|~{3,})\s*([\w+-]*)\s*$""")
    private val heading = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val rule = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val item = Regex("""^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$""")
    private val quote = Regex("""^\s{0,3}>\s?(.*)$""")
    private val tableSep = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
    private val indented = Regex("""^ {4}|^\t""")
    private val inline = Regex(
        """(`+)([^`]|[^`][\s\S]*?[^`])\1(?!`)|\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|\*(?=\S)(.+?)(?<=\S)\*|_(?=\S)([^_]+?)(?<=\S)_|\[([^\]]+)\]\(([^)\s]+)\)""",
    )

    fun parse(src: String): List<Block> = blocks(src.replace("\r\n", "\n").replace('\r', '\n').split('\n'))

    private fun blocks(ls: List<String>): List<Block> {
        val out = ArrayList<Block>()
        var i = 0
        while (i < ls.size) {
            val line = ls[i]
            if (line.isBlank()) { i++; continue }
            val f = fence.find(line)
            if (f != null) {
                val mark = f.groupValues[2]
                val body = ArrayList<String>()
                var j = i + 1
                while (j < ls.size && !ls[j].trim().startsWith(mark)) body.add(ls[j++])
                out.add(Block.Code(f.groupValues[3].lowercase(), body.joinToString("\n")))
                i = j + 1
                continue
            }
            val h = heading.find(line)
            if (h != null) {
                out.add(Block.Heading(h.groupValues[1].length, h.groupValues[2]))
                i++
                continue
            }
            if (rule.matches(line)) { out.add(Block.Rule); i++; continue }
            if (quote.matches(line)) {
                val inner = ArrayList<String>()
                while (i < ls.size && quote.matches(ls[i])) inner.add(quote.find(ls[i++])!!.groupValues[1])
                out.add(Block.Quote(blocks(inner)))
                continue
            }
            val it0 = item.find(line)
            if (it0 != null) {
                val indent = it0.groupValues[1].length
                val ordered = it0.groupValues[2][0].isDigit()
                val items = ArrayList<List<Block>>()
                while (i < ls.size) {
                    val m = item.find(ls[i]) ?: break
                    if (m.groupValues[1].length != indent || m.groupValues[2][0].isDigit() != ordered) break
                    val own = arrayListOf(m.groupValues[3])
                    i++
                    // The rows indented under the item belong to it, a nested list among them.
                    while (i < ls.size) {
                        val row = ls[i]
                        if (row.isBlank()) {
                            val next = ls.getOrNull(i + 1)
                            if (next != null && next.isNotBlank() && leading(next) > indent) { own.add(""); i++; continue }
                            break
                        }
                        if (leading(row) <= indent) break
                        own.add(row.substring(minOf(leading(row), indent + 2)))
                        i++
                    }
                    items.add(blocks(own))
                }
                out.add(Block.Items(ordered, items))
                continue
            }
            if ('|' in line && i + 1 < ls.size && tableSep.matches(ls[i + 1])) {
                val head = cells(line)
                val rows = ArrayList<List<String>>()
                var j = i + 2
                while (j < ls.size && '|' in ls[j] && ls[j].isNotBlank()) rows.add(cells(ls[j++]))
                out.add(Block.Table(head, rows))
                i = j
                continue
            }
            if (indented.containsMatchIn(line) && out.lastOrNull() !is Block.Para) {
                val body = ArrayList<String>()
                while (i < ls.size && (indented.containsMatchIn(ls[i]) || ls[i].isBlank())) body.add(ls[i++].replaceFirst(indented, ""))
                while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.lastIndex)
                out.add(Block.Code("", body.joinToString("\n")))
                continue
            }
            val para = ArrayList<String>()
            while (i < ls.size && ls[i].isNotBlank() && !fence.matches(ls[i]) && !heading.matches(ls[i]) && !rule.matches(ls[i]) && !quote.matches(ls[i]) && !item.matches(ls[i])) para.add(ls[i++].trim())
            out.add(Block.Para(para.joinToString("\n")))
        }
        return out
    }

    private fun leading(s: String) = s.length - s.trimStart().length

    private fun cells(row: String) = row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** Code spans first, so nothing inside them is marked up; then strong, emphasis, strike and links. */
    fun inline(text: String, bold: Boolean = false, italic: Boolean = false, strike: Boolean = false, href: String? = null): List<Span> {
        val out = ArrayList<Span>()
        var at = 0
        for (m in inline.findAll(text)) {
            val g = m.groups
            if (m.range.first > at) out.add(Span(text.substring(at, m.range.first), bold = bold, italic = italic, strike = strike, href = href))
            when {
                g[2] != null -> out.add(Span(g[2]!!.value.let { if (it.length > 2 && it.startsWith(" ") && it.endsWith(" ")) it.substring(1, it.length - 1) else it }, code = true, bold = bold, italic = italic, strike = strike, href = href))
                g[3] != null || g[4] != null -> out.addAll(inline((g[3] ?: g[4])!!.value, true, italic, strike, href))
                g[5] != null -> out.addAll(inline(g[5]!!.value, bold, italic, true, href))
                g[6] != null || g[7] != null -> out.addAll(inline((g[6] ?: g[7])!!.value, bold, true, strike, href))
                g[8] != null -> {
                    val target = g[9]!!.value
                    out.addAll(inline(g[8]!!.value, bold, italic, strike, if (target.startsWith("http://", true) || target.startsWith("https://", true)) target else href))
                }
            }
            at = m.range.last + 1
        }
        if (at < text.length) out.add(Span(text.substring(at), bold = bold, italic = italic, strike = strike, href = href))
        return out
    }
}
