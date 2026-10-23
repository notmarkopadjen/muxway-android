package app.muxway.android.ui

import app.muxway.android.protocol.Input
import org.junit.Assert.assertTrue
import org.junit.Test

class HardKeysTest {
    @Test
    fun everyHardwareKeyMapsToAProtocolKey() {
        assertTrue(HardKeys.MAP.isNotEmpty())
        for ((k, name) in HardKeys.MAP) assertTrue("$k -> $name", Input.isKey(name))
    }
}
