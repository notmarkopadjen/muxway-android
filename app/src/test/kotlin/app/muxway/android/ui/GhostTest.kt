package app.muxway.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GhostTest {
    @Test
    fun rightAtTheEndTakesTheGhostWhole() {
        assertEquals("ne two", Ghost.take("Right", atEnd = true, ghost = "ne two"))
    }

    @Test
    fun ctrlRightAtTheEndTakesTheFirstWordWithItsBlank() {
        assertEquals("ne ", Ghost.take("C-Right", atEnd = true, ghost = "ne two"))
        assertEquals("two", Ghost.take("C-Right", atEnd = true, ghost = "two"))
    }

    @Test
    fun nothingIsTakenWithoutAGhost() {
        assertNull(Ghost.take("Right", atEnd = true, ghost = ""))
        assertNull(Ghost.take("C-Right", atEnd = true, ghost = ""))
    }

    @Test
    fun nothingIsTakenWithTheCaretElsewhere() {
        assertNull(Ghost.take("Right", atEnd = false, ghost = "ne two"))
        assertNull(Ghost.take("C-Right", atEnd = false, ghost = "ne two"))
    }

    @Test
    fun everyOtherKeyTakesNothing() {
        for (name in listOf("Left", "Up", "Down", "Enter", "Tab", "S-Right", "M-Right", "C-S-Right", "End", "BSpace", null)) {
            assertNull(name, Ghost.take(name, atEnd = true, ghost = "ne two"))
        }
    }
}
