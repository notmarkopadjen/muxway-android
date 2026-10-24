package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HubUrlTest {
    private fun ok(raw: String) = HubUrl.check(raw).also { assertNull(raw, it.second) }.first
    private fun bad(raw: String) = HubUrl.check(raw).also { assertNull(raw, it.first) }.second

    @Test
    fun httpsIsNormalised() {
        assertEquals("https://hub.example.com", ok("https://hub.example.com/"))
        assertEquals("https://hub.example.com:8443/mux", ok(" hub.example.com:8443/mux/ "))
        assertEquals("https://hub.example.com", ok("HTTPS://hub.example.com"))
    }

    @Test
    fun plainHttpOnlyToLoopback() {
        assertEquals("http://127.0.0.1:7600", ok("http://127.0.0.1:7600"))
        assertEquals("http://localhost:7600", ok("http://localhost:7600"))
        assertEquals("http://10.0.2.2:7600", ok("http://10.0.2.2:7600"))
        assertNotNull(bad("http://hub.example.com"))
        assertNotNull(bad("http://192.0.2.10:7600"))
    }

    @Test
    fun oddUrlsAreRefused() {
        assertNotNull(bad(""))
        assertNotNull(bad("ftp://hub.example.com"))
        assertNotNull(bad("https://alice@hub.example.com"))
        assertNotNull(bad("https://hub.example.com/?x=1"))
        assertNotNull(bad("https://hub.example.com/#frag"))
        assertNotNull(bad("https://"))
    }
}
