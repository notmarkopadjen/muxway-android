package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupsTest {
    private val now = 1_767_225_600L

    private fun pane(n: Int, hub: String = "a", host: String = "alice-laptop", talk: List<Talk> = emptyList(), mail: Mail? = null) = Pane(
        host = host, address = "$host/work/0.$n", session = "work", windowIndex = 0, window = "w",
        paneIndex = n, paneId = "%$n", width = 80, height = 24, title = "", command = "bash", mode = "write",
        alias = "", activity = now, attention = null, hub = hub, talk = talk, mail = mail,
    )

    private fun to(host: String, n: Int, way: String = "to") = Talk("$host/work/0.$n", "%$n", now, way)

    @Test
    fun partnersOfPartnersFormOneGroupNumberedByTheFirstMember() {
        val panes = listOf(pane(1, talk = listOf(to("alice-laptop", 2))), pane(2, talk = listOf(to("alice-laptop", 1, "from"), to("alice-laptop", 3))), pane(3), pane(4))
        val g = Groups.of(panes)
        assertEquals(1, g["a:alice-laptop/work/0.1"])
        assertEquals(1, g["a:alice-laptop/work/0.2"])
        assertEquals(1, g["a:alice-laptop/work/0.3"])
        assertNull(g["a:alice-laptop/work/0.4"])
    }

    @Test
    fun aPaneWhosePartnersAreOutOfTheListingFormsNoGroup() {
        val g = Groups.of(listOf(pane(1, talk = listOf(to("bob-desktop", 7))), pane(2)))
        assertTrue(g.isEmpty())
    }

    @Test
    fun twoHubsNeverJoin() {
        val panes = listOf(
            pane(1, hub = "a", talk = listOf(to("alice-laptop", 2))), pane(2, hub = "a"),
            pane(1, hub = "b", talk = listOf(to("alice-laptop", 2))), pane(2, hub = "b"),
        )
        val g = Groups.of(panes)
        assertEquals(1, g["a:alice-laptop/work/0.1"])
        assertEquals(1, g["a:alice-laptop/work/0.2"])
        assertEquals(2, g["b:alice-laptop/work/0.1"])
        assertEquals(2, g["b:alice-laptop/work/0.2"])
    }

    @Test
    fun aGroupAcrossHostsIsOneGroup() {
        val g = Groups.of(listOf(pane(1, talk = listOf(to("bob-desktop", 1))), pane(2), pane(1, host = "bob-desktop", talk = listOf(to("alice-laptop", 1, "from")))))
        assertEquals(1, g["a:alice-laptop/work/0.1"])
        assertEquals(1, g["a:bob-desktop/work/0.1"])
        assertNull(g["a:alice-laptop/work/0.2"])
    }

    @Test
    fun pullTogetherKeepsTheFirstMembersPlaceAndRunsCutTheRest() {
        val panes = listOf(pane(1, talk = listOf(to("alice-laptop", 3))), pane(2), pane(3), pane(4, talk = listOf(to("alice-laptop", 5))), pane(5))
        val g = Groups.of(panes)
        val pulled = Groups.pullTogether(panes, g) { it.key }
        assertEquals(listOf(1, 3, 2, 4, 5), pulled.map { it.paneIndex })
        val runs = Groups.runs(pulled, g) { it.key }
        assertEquals(listOf(1, null, 2), runs.map { it.group })
        assertEquals(listOf(listOf(1, 3), listOf(2), listOf(4, 5)), runs.map { r -> r.rows.map { it.paneIndex } })
    }

    @Test
    fun theListingsTalkAndMailAreReadWithinBounds() {
        val many = (0 until 70).joinToString(",") { """{"address":"alice-laptop/work/1.$it","pane_id":"%$it","at":1,"way":"to"}""" }
        val p = Json.panes("""{"panes":[{"session":"work","talk":[
            {"address":"bob-desktop/work/0.0","pane_id":"%5","at":1767225600,"way":"both"},
            {"address":"not an address","pane_id":"%6","at":1,"way":"to"},
            {"address":"bob-desktop/work/0.1","pane_id":"%7","at":1,"way":"sideways"},
            $many],"mail":{"unread":2,"at":1767225600}}]}""").single()
        assertEquals(Groups.MAX_PARTNERS, p.talk.size)
        assertEquals(Talk("bob-desktop/work/0.0", "%5", 1767225600, "both"), p.talk[0])
        assertEquals("alice-laptop/work/1.0", p.talk[1].address)
        assertEquals(Mail(2, 1767225600), p.mail)
        val none = Json.panes("""{"panes":[{"session":"work","mail":{"unread":0}}]}""").single()
        assertTrue(none.talk.isEmpty())
        assertNull(none.mail)
        assertNull(Json.panes("""{"panes":[{"session":"work","mail":{"unread":-3}}]}""").single().mail)
        assertNull(Json.panes("""{"panes":[{"session":"work"}]}""").single().mail)
    }
}
