package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupTest {
    private val token = "mxt_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"

    @Test
    fun aCodeGivesHubAndToken() {
        val c = Setup.parse("muxway://connect?hub=https://hub.example.com&token=$token")!!
        assertEquals("https://hub.example.com", c.hub)
        assertEquals(token, c.token)
    }

    @Test
    fun percentEncodingIsUndoneAndUnknownFieldsIgnored() {
        val c = Setup.parse("muxway://connect?x=1&hub=https%3A%2F%2Fhub.example.com%3A8443%2Fmux&token=$token&=bad")!!
        assertEquals("https://hub.example.com:8443/mux", c.hub)
        assertEquals(token, c.token)
    }

    @Test
    fun anythingElseIsNotACode() {
        assertNull(Setup.parse(""))
        assertNull(Setup.parse("https://hub.example.com"))
        assertNull(Setup.parse("MUXWAY://connect?hub=https://hub.example.com&token=$token"))
        assertNull(Setup.parse("muxway://connect?hub=https://hub.example.com"))
        assertNull(Setup.parse("muxway://connect?token=$token"))
        assertNull(Setup.parse("muxway://connect?hub=&token=$token"))
        assertNull(Setup.parse("muxway://connect?hub=https://hub.example.com&token=mxt_%0Aabc"))
        assertNull(Setup.parse("muxway://connect?hub=https://hub.example.com&token=mxt_\u0007abc"))
        assertNull(Setup.parse("muxway://connect?hub=https://hub.example.com&token=mxt_%zz"))
        assertNull(Setup.parse("muxway://connect?hub=https://hub.example.com&token=mxt_%ff"))
        assertNull(Setup.parse("muxway://connect?hub=https://hub.example.com&token=" + "a".repeat(2000)))
    }
}
