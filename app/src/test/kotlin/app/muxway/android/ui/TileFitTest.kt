package app.muxway.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TileFitTest {
    private val windows = mapOf("a/w/0.0" to "a/w/0", "a/w/0.1" to "a/w/0", "a/w/1.0" to "a/w/1", "b/w/0.0" to "b/w/0")
    private fun held(open: List<String>, focused: String) = TileFit.held(open, focused, windows::getValue)

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
