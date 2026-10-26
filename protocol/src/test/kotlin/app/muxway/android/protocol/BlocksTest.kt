package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlocksTest {
    private val frame = listOf(
        "❯ Take a note in memory that ADO tickets are links",
        "",
        "● Noted and indexed. The rule is saved as a feedback memory",
        "  and listed at the top of the index.",
        "",
        "  Applied right away:",
        "",
        "  - AB#2174 daily statistics freeze",
        "  - AB#2175 binds page hides outages",
        "",
        "  │ The memory records the link formats",
        "  │ for work items and pull requests.",
        "  │",
        "  │ Apply it in replies.",
        "",
        "  ⎿  Ran 2 shell commands",
        "     wrote 1 memory",
        "",
        "✻ Cooked for 21s · done 5:04 PM",
        "",
        "> quoted",
        "> rows",
        "plain text after",
    )

    @Test
    fun findsBulletsWithTheirRowsBarsResultsAndQuotes() {
        val all = Blocks.find(frame)
        assertEquals(listOf(0 to 0, 2 to 8, 10 to 13, 15 to 16, 18 to 18, 20 to 21), all.map { it.start to it.end })
        assertEquals("Take a note in memory that ADO tickets are links", all[0].text)
        assertEquals(
            listOf(
                "Noted and indexed. The rule is saved as a feedback memory",
                "and listed at the top of the index.",
                "",
                "Applied right away:",
                "",
                "- AB#2174 daily statistics freeze",
                "- AB#2175 binds page hides outages",
            ).joinToString("\n"),
            all[1].text,
        )
        assertEquals("The memory records the link formats\nfor work items and pull requests.\n\nApply it in replies.", all[2].text)
        assertEquals("Ran 2 shell commands\nwrote 1 memory", all[3].text)
        assertEquals("quoted\nrows", all[5].text)
    }

    @Test
    fun leavesPlainRowsEmptyMarkersAndABareBarAlone() {
        assertEquals(emptyList<Blocks.Block>(), Blocks.find(listOf("plain", "", "●", "│", "  just indented")))
    }

    @Test
    fun tellsWhichBlockARowIsIn() {
        val all = Blocks.find(frame)
        assertEquals(2, Blocks.at(all, 5)?.start)
        assertEquals(10, Blocks.at(all, 12)?.start)
        assertNull(Blocks.at(all, 22))
    }
}
