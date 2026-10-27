package app.muxway.android.protocol

import app.muxway.android.protocol.Markdown.Block
import app.muxway.android.protocol.Markdown.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test
    fun blocksOfAPlan() {
        val src = """
            # Plan

            ## Now

            - [x] Journal: append, replay
            - [ ] Compaction
              with a second row

            > a quote

            ```sh
            dotnet test
            ```

            | # | Test |
            |---|------|
            | 1 | Append |

            ---
            The end, in **bold** and `code`.
        """.trimIndent()
        val b = Markdown.parse(src)
        assertEquals(Block.Heading(1, "Plan"), b[0])
        assertEquals(Block.Heading(2, "Now"), b[1])
        assertEquals(Block.Items(false, listOf(listOf(Block.Para("[x] Journal: append, replay")), listOf(Block.Para("[ ] Compaction\nwith a second row")))), b[2])
        assertEquals(Block.Quote(listOf(Block.Para("a quote"))), b[3])
        assertEquals(Block.Code("sh", "dotnet test"), b[4])
        assertEquals(Block.Table(listOf("#", "Test"), listOf(listOf("1", "Append"))), b[5])
        assertEquals(Block.Rule, b[6])
        assertEquals(Block.Para("The end, in **bold** and `code`."), b[7])
        assertEquals(8, b.size)
    }

    @Test
    fun indentedCodeAndOrderedItems() {
        val b = Markdown.parse("1. one\n2. two\n\n# Code\n\n    make test\n    ok\n")
        assertEquals(Block.Items(true, listOf(listOf(Block.Para("one")), listOf(Block.Para("two")))), b[0])
        assertEquals(Block.Heading(1, "Code"), b[1])
        assertEquals(Block.Code("", "make test\nok"), b[2])
    }

    @Test
    fun inlineRuns() {
        assertEquals(
            listOf(Span("in "), Span("bold", bold = true), Span(" and "), Span("x", code = true), Span(" and "), Span("it", italic = true), Span(" "), Span("gone", strike = true)),
            Markdown.inline("in **bold** and `x` and *it* ~~gone~~"),
        )
        assertEquals(listOf(Span("see "), Span("the hub", href = "https://hub.example.com"), Span(".")), Markdown.inline("see [the hub](https://hub.example.com)."))
        assertEquals(listOf(Span("see "), Span("the plan"), Span(".")), Markdown.inline("see [the plan](docs/PLAN.md)."))
        assertEquals(listOf(Span("a `b` c", bold = true)), Markdown.inline("**a `b` c**").let { listOf(Span(it.joinToString("") { s -> if (s.code) "`${s.text}`" else s.text }, bold = true)) })
    }

    @Test
    fun aTagIsText() {
        assertEquals(listOf(Block.Para("<script>alert(1)</script> stays text")), Markdown.parse("<script>alert(1)</script> stays text"))
    }
}
