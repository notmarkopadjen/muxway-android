package app.muxway.android.ui

import app.muxway.android.protocol.Pane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileFitTest {
    private val windows = mapOf("a/w/0.0" to "a/w/0", "a/w/0.1" to "a/w/0", "a/w/0.2" to "a/w/0", "a/w/1.0" to "a/w/1", "b/w/0.0" to "b/w/0")
    private fun held(open: List<String>, focused: String) = TileFit.held(open, focused, windows::getValue)

    private fun pane(address: String, id: String, mode: String = "write") = Pane(
        host = address.substringBefore('/'), address = address, session = "w", windowIndex = address.substringAfter("w/").substringBefore('.').toInt(),
        window = "shell", paneIndex = address.substringAfterLast('.').toInt(), paneId = id, width = 80, height = 24, title = "", command = "bash",
        mode = mode, alias = "", activity = 0L, attention = null,
    )
    private val listed = listOf(pane("a/w/0.0", "%1"), pane("a/w/0.1", "%2"), pane("a/w/0.2", "%3", mode = "read"), pane("a/w/1.0", "%4"), pane("b/w/0.0", "%5"))
    private fun one(open: List<String>, panes: List<Pane> = listed) = TileFit.oneWindow(open, panes, windows::getValue)

    @Test
    fun everyPaneOfOneWindowOpenAsTilesGivesTheirIdsInTileOrder() {
        val two = listOf(pane("a/w/0.0", "%1"), pane("a/w/0.1", "%2"), pane("b/w/0.0", "%5"))
        assertEquals(listOf("%1", "%2"), one(listOf("a/w/0.0", "a/w/0.1"), two))
        assertEquals(listOf("%2", "%1"), one(listOf("a/w/0.1", "a/w/0.0"), two))
    }

    @Test
    fun aPaneMissingReadOnlyOrOfAnotherWindowKeepsTheHeldPath() {
        assertNull(one(listOf("a/w/0.0", "a/w/0.1")))
        assertNull(one(listOf("a/w/0.0", "a/w/0.1", "a/w/0.2")))
        assertNull(one(listOf("a/w/0.0", "b/w/0.0")))
        assertNull(one(listOf("a/w/0.0")))
        assertNull(one(listOf("a/w/0.0", "a/w/0.1"), listOf(pane("a/w/0.0", "1"), pane("a/w/0.1", "%2"))))
    }

    @Test
    fun tilesOfOtherWindowsAreNeverHeld() {
        assertEquals(emptySet<String>(), held(listOf("a/w/0.0", "a/w/1.0", "b/w/0.0"), "a/w/0.0"))
        assertEquals(emptySet<String>(), held(listOf("a/w/0.0"), "a/w/0.0"))
    }

    @Test
    fun theFocusedTileOfAWindowFitsAndTheOthersAreHeld() {
        assertEquals(setOf("a/w/0.0"), held(listOf("a/w/0.0", "a/w/0.1"), "a/w/0.1"))
        assertEquals(setOf("a/w/0.1"), held(listOf("a/w/0.0", "a/w/0.1"), "a/w/0.0"))
    }

    @Test
    fun withTheFocusElsewhereTheFirstOfTheWindowFits() {
        assertEquals(setOf("a/w/0.1"), held(listOf("a/w/1.0", "a/w/0.0", "a/w/0.1"), "a/w/1.0"))
        assertEquals(setOf("a/w/0.0"), held(listOf("a/w/0.1", "a/w/0.0", "b/w/0.0"), "b/w/0.0"))
    }
}
