package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Random

class SharesTest {
    private fun pane(address: String, id: String, host: String = "alice-laptop") =
        Pane(host, address, "work", 0, "shell", 0, id, 80, 24, "", "bash", "write", "", 0, null, hub = "a")

    private fun share(id: String, host: String, paneId: String) = Share(id, "https://hub.example.com/s/$id", "", 1767225600, host, paneId, "", 0)

    @Test
    fun aSuggestedPasswordIsThreeGroupsOfFourFromTheHubsAlphabetAndSeldomTheSame() {
        val shape = Regex("^[a-z2-7]{4}-[a-z2-7]{4}-[a-z2-7]{4}$")
        val made = (1..50).map { Shares.suggest() }
        assertTrue(made.all { shape.matches(it) })
        assertTrue(made.all { Shares.validPassword(it) })
        assertTrue(made.toSet().size > 45)
        val stuck = object : Random() { override fun nextInt(bound: Int) = bound - 1 }
        assertEquals("7777-7777-7777", Shares.suggest(stuck))
    }

    @Test
    fun aPasswordIsEightToSixtyFourPrintableCharacters() {
        assertFalse(Shares.validPassword("seven77"))
        assertTrue(Shares.validPassword("eight888"))
        assertTrue(Shares.validPassword("x".repeat(64)))
        assertFalse(Shares.validPassword("x".repeat(65)))
        assertFalse(Shares.validPassword("with\tab-in-it"))
        assertFalse(Shares.validPassword("with\u0007bell"))
        assertTrue(Shares.validPassword("with space ok"))
    }

    @Test
    fun aPanesSharesAreFoundByHostAndPaneIdAndTheLifetimesAreThree() {
        val shares = listOf(share("one", "alice-laptop", "%3"), share("two", "bob-server", "%3"), share("three", "alice-laptop", "%5"), share("four", "alice-laptop", "%3"))
        assertEquals(listOf("one", "four"), Shares.of(shares, pane("alice-laptop/work/0.0", "%3")).map { it.id })
        assertEquals(emptyList<Share>(), Shares.of(shares, pane("alice-laptop/work/0.1", "%9")))
        assertEquals(listOf(3600, 86400, 604800), Shares.LIFETIMES.map { it.second })
        assertTrue(Shares.ID.matches("3nK_abc-DEF"))
        assertFalse(Shares.ID.matches("../x"))
        assertFalse(Shares.ID.matches(""))
    }

    @Test
    fun untilNamesTheDayAndTheTime() {
        assertEquals("Jan 1, 00:00", Shares.until(1767225600, ZoneId.of("UTC")))
        assertEquals("Jan 1, 01:00", Shares.until(1767225600, ZoneId.of("Europe/Berlin")))
    }
}
