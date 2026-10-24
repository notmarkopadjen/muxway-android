package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksTest {
    private fun hrefs(line: String) = Links.find(line).map { it.href }

    @Test
    fun findsHttpAndHttpsRunsWithTheirPositions() {
        assertEquals(
            listOf(Links.Link(4, 29, "https://example.com/a?b=1"), Links.Link(34, 56, "http://example.org/x#y")),
            Links.find("see https://example.com/a?b=1 and http://example.org/x#y now"),
        )
        assertEquals(listOf("HTTPS://EXAMPLE.COM/Up"), hrefs("HTTPS://EXAMPLE.COM/Up"))
    }

    @Test
    fun admitsNoOtherScheme() {
        for (s in listOf(
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "ftp://example.com/f",
            "file:///etc/passwd",
            "mailto:alice@example.com",
            "https:// example.com",
            "https://",
            "https://.",
            "see http:/example.com and https:example.com",
        )) assertEquals(s, emptyList<String>(), hrefs(s))
    }

    @Test
    fun leavesTheSentenceItsPunctuationAndBrackets() {
        assertEquals(listOf("https://example.com/x"), hrefs("(https://example.com/x)"))
        assertEquals(listOf("https://example.com/y"), hrefs("https://example.com/y."))
        assertEquals(listOf("https://example.com/z", "https://example.com/w"), hrefs("at https://example.com/z, then https://example.com/w;"))
        assertEquals(listOf("https://example.com/v"), hrefs("<https://example.com/v>"))
        assertEquals(listOf("https://example.com/q", "https://example.com/r"), hrefs("\"https://example.com/q\",\"https://example.com/r\""))
        assertEquals(listOf("https://example.com/t"), hrefs("`https://example.com/t`"))
        assertEquals(listOf("https://example.com/s"), hrefs("'https://example.com/s'."))
        assertEquals(listOf("https://example.com/?a=1"), hrefs("https://example.com/?a=1))."))
    }

    @Test
    fun keepsABracketTheAddressOpened() {
        assertEquals(listOf("https://example.com/a_(b)"), hrefs("https://example.com/a_(b)"))
        assertEquals(listOf("https://example.com/a_(b)"), hrefs("(https://example.com/a_(b))"))
        assertEquals(listOf("http://[2001:db8::1]"), hrefs("http://[2001:db8::1]"))
        assertEquals(listOf("http://[2001:db8::1]/p"), hrefs("http://[2001:db8::1]/p."))
        assertEquals(listOf("https://example.com/{x}"), hrefs("https://example.com/{x}]"))
    }

    @Test
    fun aRunStopsAtBlankSpaceOnly() {
        assertEquals(listOf("https://example.com/long/path/that/is/cut"), hrefs("https://example.com/long/path/that/is/cut"))
        assertEquals(listOf("https://example.com/a"), hrefs("https://example.com/a\tb"))
        assertEquals(listOf("https://example.com/a"), hrefs("https://example.com/a b"))
    }

    @Test
    fun theLinkAtAColumn() {
        val line = "see https://example.com/m. now"
        assertNull(Links.at(line, 3))
        assertEquals("https://example.com/m", Links.at(line, 4)?.href)
        assertEquals("https://example.com/m", Links.at(line, 24)?.href)
        assertNull(Links.at(line, 25))
    }
}
