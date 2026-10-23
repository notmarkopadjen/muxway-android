package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTest {
    private val red = Ansi.Style(fg = 1)

    @Test
    fun keepLeavesTextNewlinesTabsAndSgrOnly() {
        val hostile = "a\u001b]0;title\u0007b\u001b]8;;http://x\u001b\\c\u001b[2J\u001b[H\u001b[31md\u0007\u0000\r\u007f\u0085e\tf\n\u001b[?25h\u001b(Bg\u001b["
        assertEquals("abc\u001b[31mde   f\ng", Ansi.keep(hostile))
        assertEquals("abcde   f\ng", Ansi.plain(Ansi.keep(hostile)))
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
