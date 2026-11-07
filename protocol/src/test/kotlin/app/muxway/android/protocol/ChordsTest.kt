package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ChordsTest {
    @Test
    fun ctrlWithABracketIsThePaneChordByCodeOrByCharacter() {
        assertEquals(1, Chords.pane(ctrl = true, alt = false, shift = false, meta = false, code = ']', char = ']'))
        assertEquals(-1, Chords.pane(ctrl = true, alt = false, shift = false, meta = false, code = '[', char = '['))
        // A layout whose bracket sits on another key: the character tells.
        assertEquals(1, Chords.pane(ctrl = true, alt = false, shift = false, meta = false, code = null, char = ']'))
        assertEquals(-1, Chords.pane(ctrl = true, alt = false, shift = false, meta = false, code = '9', char = '['))
        // The code alone tells when the character is none (Ctrl eats it on some keyboards).
        assertEquals(1, Chords.pane(ctrl = true, alt = false, shift = false, meta = false, code = ']', char = null))
    }

    @Test
    fun anyOtherModifierOrKeyIsNoChord() {
        assertEquals(0, Chords.pane(ctrl = false, alt = false, shift = false, meta = false, code = ']', char = ']'))
        assertEquals(0, Chords.pane(ctrl = true, alt = true, shift = false, meta = false, code = ']', char = ']'))
        assertEquals(0, Chords.pane(ctrl = true, alt = false, shift = true, meta = false, code = ']', char = '}'))
        assertEquals(0, Chords.pane(ctrl = true, alt = false, shift = false, meta = true, code = ']', char = ']'))
        assertEquals(0, Chords.pane(ctrl = true, alt = false, shift = false, meta = false, code = 'c', char = 'c'))
        assertEquals(0, Chords.pane(ctrl = true, alt = false, shift = false, meta = false, code = null, char = null))
    }
}
