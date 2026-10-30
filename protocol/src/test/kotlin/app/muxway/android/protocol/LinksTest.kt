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

    @Test
    fun findsInboxFilesByTheirNameAndPathsOfShownTypes() {
        val name = "20261025-102235-56fwge.png"
        assertEquals(listOf(name), Links.findFiles("what is wrong in /home/alice/.config/muxway/agent/inbox/$name ?").map { it.name })
        assertEquals(listOf(name), Links.findFiles("see /tmp/$name.").map { it.name })
        assertEquals(listOf("20261025-102235-d4e5f6.zip"), Links.findFiles("/i/20261025-102235-d4e5f6.zip").map { it.name })
        for (s in listOf(name, "/tmp/$name.exe", "/tmp/${name}x", "/tmp/x$name", "/tmp/20261025-102235-56fwge.PNG", "/tmp/20261025-102235-56fwge.svg")) {
            assertEquals(s, emptyList<Links.FileLink>(), Links.findFiles(s))
        }
        assertEquals(
            listOf("docs/plans/2026-10-26-fit-keeps-still.md", "~/notes/todo.txt", "/var/log/app.log"),
            Links.findPaths("1. docs/plans/2026-10-26-fit-keeps-still.md: fit ignores, see ~/notes/todo.txt and /var/log/app.log.").map { it.path },
        )
        assertEquals(listOf("./out/report.csv", "../shot.PNG"), Links.findPaths("wrote ./out/report.csv (3 rows) and ../shot.PNG").map { it.path })
        for (s in listOf("README.md", "notes.txt in the dir", "docs/plans/x.md.bak", "bin/run.exe", "keys/agent.key", "certs/host.crt", "config/x.env", "src/Makefile.am", "a/b.mdx", "x/y.md-")) {
            assertEquals(s, emptyList<Links.PathLink>(), Links.findPaths(s))
        }
        // The types of a project: sources, markup, config, archives, names without an extension.
        assertEquals(
            listOf("src/tx.py", "./Views/Home/Index.cshtml", "api/v1.proto", "ops/main.tf", "scripts/run.ps1", "out/a.diff", "src/Makefile", "./Dockerfile", "/opt/x/LICENSE", "~/dl/report.xlsx", "a/b.PY"),
            Links.findPaths("src/tx.py ./Views/Home/Index.cshtml api/v1.proto ops/main.tf scripts/run.ps1 out/a.diff src/Makefile ./Dockerfile /opt/x/LICENSE ~/dl/report.xlsx a/b.PY").map { it.path },
        )
        assertEquals(true, Links.isImage(name))
        assertEquals(false, Links.isImage("20261025-102235-56fwge.pdf"))
    }

    @Test
    fun theColumnTellsAFileAPathAndAnAddressApart() {
        val line = "see https://example.com/20261025-102235-56fwge.png and /tmp/20261025-102235-56fwge.png and docs/a.md"
        assertNull(Links.fileAt(line, 30))
        assertEquals("20261025-102235-56fwge.png", Links.fileAt(line, 60)?.name)
        assertNull(Links.pathAt(line, 60))
        assertEquals("docs/a.md", Links.pathAt(line, line.length - 2)?.path)
        assertNull(Links.pathAt(line, 0))
    }
}
