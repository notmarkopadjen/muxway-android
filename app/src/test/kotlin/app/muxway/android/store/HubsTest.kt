package app.muxway.android.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubsTest {
    private val home = Hub("a1", "home", "https://hub.example.com")
    private val work = Hub("b2", "work", "https://work.example.com:8443")

    @Test
    fun roundTrip() {
        assertEquals(listOf(home, work), Hubs.decode(Hubs.encode(listOf(home, work))))
        assertEquals(emptyList<Hub>(), Hubs.decode(null))
        assertEquals(emptyList<Hub>(), Hubs.decode(""))
        assertEquals(emptyList<Hub>(), Hubs.decode("{not"))
    }

    @Test
    fun decodeKeepsOnlyWellShapedEntriesOnePerIdAndUrl() {
        val json = """[
            {"id":"a1","name":"home","url":"https://hub.example.com/"},
            {"id":"a1","name":"again","url":"https://again.example.com"},
            {"id":"c3","name":"twin","url":"https://hub.example.com"},
            {"id":"d4","name":"Bad Name","url":"https://x.example.com"},
            {"id":"e5","name":"plain","url":"http://plain.example.com"},
            {"id":"f6","name":"evil","url":"javascript:alert(1)"},
            {"id":"g7","name":"user","url":"https://alice@user.example.com"},
            {"id":"not an id","name":"ok","url":"https://ok.example.com"},
            {"id":"h8","name":"local","url":"http://127.0.0.1:7600"},
            "junk", 7
        ]"""
        assertEquals(
            listOf(Hub("a1", "home", "https://hub.example.com"), Hub("h8", "local", "http://127.0.0.1:7600")),
            Hubs.decode(json),
        )
    }

    @Test
    fun namesComeFromTheHostAndStayUnique() {
        assertEquals("hub-example-com", Hubs.defaultName("https://hub.example.com", emptyList()))
        assertEquals("hub-example-com-2", Hubs.defaultName("https://hub.example.com:8443", listOf(Hub("x", "hub-example-com", "https://a.example.com"))))
        assertEquals("127-0-0-1", Hubs.defaultName("http://127.0.0.1:7600", emptyList()))
        assertTrue(Hubs.NAME.matches(Hubs.defaultName("https://" + "a".repeat(80) + ".example.com", emptyList())))
    }

    @Test
    fun idsAreFreshAndWellShaped() {
        val id = Hubs.newId(listOf(home, work))
        assertEquals(8, id.length)
        assertNotEquals(home.id, id)
        assertTrue(Hubs.decode(Hubs.encode(listOf(Hub(id, "lab", "https://lab.example.com")))).isNotEmpty())
    }

    @Test
    fun theOneHubOfAnOlderInstallKeepsTheLegacyId() {
        assertTrue(Hubs.NAME.matches(Hubs.LEGACY_ID))
        assertEquals(listOf(Hub(Hubs.LEGACY_ID, "hub", "https://hub.example.com")), Hubs.decode(Hubs.encode(listOf(Hub(Hubs.LEGACY_ID, "hub", "https://hub.example.com")))))
    }
}
