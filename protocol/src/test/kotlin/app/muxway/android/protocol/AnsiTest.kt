package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTest {
    private val red = Ansi.Style(fg = 1)

    @Test
    fun keepLeavesTextNewlinesTabsAndSgrOnly() {
        val hostile = "a\u001b]0;title\u0007b\u001b]8;;http://x\u001b\\c\u001b[2J\u001b[H\u001b[31md\u0007\u0000\r\u007f\u0085e\tf\n\u001b[?25h\u001b(Bg\u001b["
        assertEquals("ab\u001b]8;;http://x\u001b\\c\u001b[31mde   f\ng", Ansi.keep(hostile))
        assertEquals("abc\u001b[31mde   f\ng", Ansi.keep(hostile, links = false))
        assertEquals("abcde   f\ng", Ansi.plain(Ansi.keep(hostile)))
    }

    @Test
    fun aProgramsLinkIsKeptOnlyAsHttpOrHttpsAndClosedByAnEmptyOne() {
        fun osc(uri: String, label: String, end: String = "\u001b\\") = "\u001b]8;;$uri$end$label\u001b]8;;$end"
        val rows = Ansi.rows("see ${osc("https://example.com/x", "docs")} now", 0)
        assertEquals(listOf("see ", "docs", " now"), rows[0].map { it.text })
        assertEquals("https://example.com/x", rows[0][1].style.href)
        assertNull(rows[0][0].style.href)
        assertNull(rows[0][2].style.href)
        assertEquals("https://example.com/x", Ansi.hrefAt(rows[0], 4))
        assertEquals("https://example.com/x", Ansi.hrefAt(rows[0], 7))
        assertNull(Ansi.hrefAt(rows[0], 3))
        assertNull(Ansi.hrefAt(rows[0], 8))
        assertNull(Ansi.hrefAt(rows[0], 40))
        // BEL ends it too, a parameter is ignored, and colour inside the label keeps the link.
        val bel = Ansi.rows("\u001b]8;id=7;http://example.com/y\u0007la\u001b[31mbel\u001b[0m!\u001b]8;;\u0007.", 0)[0]
        assertEquals(listOf("la", "bel", "!", "."), bel.map { it.text })
        assertEquals(listOf("http://example.com/y", "http://example.com/y", "http://example.com/y", null), bel.map { it.style.href })
        assertEquals(1, bel[1].style.fg)
        // The link carries over a wrapped row and a newline until it is closed.
        val wrapped = Ansi.rows("${osc("https://example.com/a", "one\ntwo")} three", 0)
        assertEquals("https://example.com/a", wrapped[1][0].style.href)
        assertNull(wrapped[1][1].style.href)
        // What the phone may not open leaves the label as text; an address it may not follow closes the one before.
        for (bad in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "ftp://example.com/x", "https://", "http://x y", "http://x\u0001y", "http://x\u007f", "https://example.com/" + "a".repeat(Ansi.MAX_HREF), "mailto:alice@example.com", "HTTPS")) {
            val r = Ansi.rows("${osc("https://example.com/ok", "ok")}${osc(bad, "label")}", 0)[0]
            assertEquals(bad, listOf("ok", "label"), r.map { it.text })
            assertNull(bad, r[1].style.href)
            assertEquals(bad, "oklabel", Ansi.plain(Ansi.keep("${osc("https://example.com/ok", "ok")}${osc(bad, "label")}")))
        }
        assertEquals("https://example.com/" + "a".repeat(Ansi.MAX_HREF - 20), Ansi.target("https://example.com/" + "a".repeat(Ansi.MAX_HREF - 20)))
        assertEquals("HTTP://EXAMPLE.COM/A", Ansi.target("HTTP://EXAMPLE.COM/A"))
        // A link left open runs to the end of the content; a lone opener with no address is a close.
        assertEquals("https://example.com/z", Ansi.rows("\u001b]8;;https://example.com/z\u001b\\no end", 0)[0][0].style.href)
        assertNull(Ansi.rows("\u001b]8;;\u001b\\x", 0)[0][0].style.href)
    }

    @Test
    fun sgrWithLettersOrOtherFinalBytesIsDropped() {
        assertEquals("ab", Ansi.keep("a\u001b[31;?mb"))
        assertEquals("ab", Ansi.keep("a\u001b[31Kb"))
        assertEquals("amb", Ansi.keep("a\u001b[31;xmb"))
        assertEquals("a\u001b[mb", Ansi.keep("a\u001b[mb"))
    }

    @Test
    fun basicColoursBoldAndReset() {
        val rows = Ansi.rows("plain \u001b[1;31mbold red\u001b[0m back\u001b[92m bright\u001b[39m none", 0)
        assertEquals(1, rows.size)
        val spans = rows[0]
        assertEquals(listOf("plain ", "bold red", " back", " bright", " none"), spans.map { it.text })
        assertEquals(Ansi.Style.PLAIN, spans[0].style)
        assertEquals(Ansi.Style(fg = 1, bold = true), spans[1].style)
        assertEquals(Ansi.Style.PLAIN, spans[2].style)
        assertEquals(Ansi.Style(fg = 10), spans[3].style)
        assertEquals(Ansi.Style.PLAIN, spans[4].style)
    }

    @Test
    fun extendedColoursInBothForms() {
        val s = Ansi.rows("\u001b[38;5;208mx\u001b[48;2;1;2;3my\u001b[38:2::10:20:30mz\u001b[48:5:7mw", 0)[0]
        assertEquals(208, s[0].style.fg)
        assertEquals(Ansi.RGB or 0x010203, s[1].style.bg)
        assertEquals(Ansi.RGB or (10 shl 16) or (20 shl 8) or 30, s[2].style.fg)
        assertEquals(7, s[3].style.bg)
    }

    @Test
    fun attributesSwitchOnAndOff() {
        val s = Ansi.rows("\u001b[2;3;4;7ma\u001b[22;23mb\u001b[24;27mc", 0)[0]
        assertEquals(Ansi.Style(dim = true, italic = true, underline = true, inverse = true), s[0].style)
        assertEquals(Ansi.Style(underline = true, inverse = true), s[1].style)
        assertEquals(Ansi.Style.PLAIN, s[2].style)
    }

    @Test
    fun oddParametersAreHarmless() {
        val s = Ansi.rows("\u001b[999999;38;2;300;;5;38;9mx\u001b[38;2my\u001b[38;2;-1;0;0m", 0)[0]
        assertEquals("xy", s.single().text)
        assertEquals(Ansi.RGB or (255 shl 16) or 5, s.single().style.fg)
    }

    @Test
    fun rowsAreCutAtTheWidthAndKeepTheirStyle() {
        val rows = Ansi.rows("ab\u001b[31mcdef\ng", 3)
        assertEquals(3, rows.size)
        assertEquals(listOf("ab", "c"), rows[0].map { it.text })
        assertEquals(red, rows[0][1].style)
        assertEquals(listOf("def"), rows[1].map { it.text })
        assertEquals(red, rows[1][0].style)
        assertEquals("g", rows[2][0].text)
    }

    @Test
    fun emptyAndTrailingNewlineRows() {
        assertEquals(1, Ansi.rows("", 0).size)
        assertTrue(Ansi.rows("", 0)[0].isEmpty())
        assertEquals(listOf(listOf("a")), Ansi.rows("a\n", 0).map { r -> r.map { it.text } })
        assertEquals(listOf(listOf("a"), listOf()), Ansi.rows("a\n\n", 0).map { r -> r.map { it.text } })
        assertEquals(3, Ansi.rows("a\n\nb", 0).size)
    }
}
