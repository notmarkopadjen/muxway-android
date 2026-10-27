package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysTest {
    @Test
    fun aKeyIsTheHubAndTheAddress() {
        assertEquals("h1:alice-laptop/work/0.0", Keys.make("h1", "alice-laptop/work/0.0"))
        assertEquals("h1", Keys.hub("h1:alice-laptop/work/0.0"))
        assertEquals("alice-laptop/work/0.0", Keys.address("h1:alice-laptop/work/0.0"))
    }

    @Test
    fun aBareAddressIsTheFirstHubs() {
        assertEquals("alice-laptop/work/0.0", Keys.make("", "alice-laptop/work/0.0"))
        assertEquals("", Keys.hub("alice-laptop/work/0.0"))
        assertEquals("alice-laptop/work/0.0", Keys.address("alice-laptop/work/0.0"))
        assertTrue(Keys.valid("alice-laptop/work/0.0"))
    }

    @Test
    fun onlyWellShapedKeysAreValid() {
        assertTrue(Keys.valid("h1:alice-laptop/work/0.0"))
        assertTrue(Keys.valid("abcdefgh:alice-laptop/@build"))
        assertFalse(Keys.valid("H1:alice-laptop/work/0.0"))
        assertFalse(Keys.valid("toolongtoolongtoo:alice-laptop/work/0.0"))
        assertFalse(Keys.valid("h1:../etc/passwd"))
        assertFalse(Keys.valid("h1:"))
        assertFalse(Keys.valid(""))
        assertFalse(Keys.valid("h1:a:alice-laptop/work/0.0"))
    }
}
