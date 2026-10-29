package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddMenuTest {
    private fun pane(hub: String, host: String, session: String, n: Int, mode: String = "write", title: String = "") = Pane(
        host = host, address = "$host/$session/0.$n", session = session, windowIndex = 0, window = "w",
        paneIndex = n, paneId = "%$n", width = 80, height = 24, title = title, command = "bash", mode = mode,
        alias = "", activity = 0L, attention = null, hub = hub,
    )

    private val a0 = pane("home", "alice-laptop", "work", 0, title = "build")
    private val a1 = pane("home", "alice-laptop", "work", 1)
    private val a2 = pane("home", "alice-laptop", "logs", 0)
    private val b0 = pane("work", "bob-server", "ops", 0, mode = "read")
    private val panes = listOf(a0, a1, a2, b0)
    private val hosts = listOf(Host("alice-laptop", true, sessions = true, hub = "home"), Host("bob-server", true, hub = "work"))

    private fun items(rows: List<AddMenu.Row>) = rows.filterIsInstance<AddMenu.Row.Item>().map { it.pane.key to it.minimised }

    @Test
    fun newPaneAndSessionLeadThenTheRestByHubHostAndSession() {
        val rows = AddMenu.rows(panes, hosts, open = listOf(a0.key), minimised = emptyList(), focused = a0.key) { "hub-$it" }
        assertEquals(AddMenu.Row.NewPane(a0), rows[0])
        assertEquals(AddMenu.Row.NewSession("home", "alice-laptop"), rows[1])
        assertEquals(AddMenu.Row.Rule, rows[2])
        assertEquals(AddMenu.Row.Heading("hub-home · alice-laptop · work"), rows[3])
        assertEquals(AddMenu.Row.Item(a1, false), rows[4])
        assertEquals(AddMenu.Row.Heading("hub-home · alice-laptop · logs"), rows[5])
        assertEquals(AddMenu.Row.Item(a2, false), rows[6])
        assertEquals(AddMenu.Row.Heading("hub-work · bob-server · ops"), rows[7])
        assertEquals(AddMenu.Row.Item(b0, false), rows[8])
        assertEquals(9, rows.size)
    }

    @Test
    fun oneHubNeedsNoHubInTheHeadings() {
        val one = panes.filter { it.hub == "home" }
        val rows = AddMenu.rows(one, hosts, listOf(a0.key), emptyList(), a0.key)
        assertEquals(AddMenu.Row.Heading("alice-laptop · work"), rows[3])
    }

    @Test
    fun minimisedComeFirstAndOpenOnesAreLeftOut() {
        val rows = AddMenu.rows(panes, hosts, open = listOf(a0.key, a1.key), minimised = listOf(b0.key), focused = a1.key)
        assertEquals(listOf(b0.key to true, a2.key to false), items(rows))
        assertEquals(2, rows.count { it == AddMenu.Row.Rule })
    }

    @Test
    fun whatTheTokenOrHostRefusesIsDimmedWithTheReason() {
        val rows = AddMenu.rows(panes, hosts, listOf(b0.key), emptyList(), b0.key)
        assertEquals("read only", (rows[0] as AddMenu.Row.NewPane).reason)
        assertEquals("no sessions here", (rows[1] as AddMenu.Row.NewSession).reason)
        assertNull((AddMenu.rows(panes, hosts, listOf(a0.key), emptyList(), a0.key)[0] as AddMenu.Row.NewPane).reason)
    }

    @Test
    fun fourOpenSaysAPickReplacesTheFocused() {
        val four = listOf(a0.key, a1.key, a2.key, b0.key)
        val more = pane("home", "alice-laptop", "work", 7)
        val rows = AddMenu.rows(panes + more, hosts, four, emptyList(), a0.key)
        assertTrue(rows.contains(AddMenu.Row.Heading("four open: a pick replaces bash · build")))
        assertEquals(listOf(more.key to false), items(rows))
        // A minimised pane is open too: three tiles and one below are four.
        val three = AddMenu.rows(panes + more, hosts, four.take(3), listOf(b0.key), a0.key)
        assertTrue(three.contains(AddMenu.Row.Heading("four open: a pick replaces bash · build")))
        assertTrue(AddMenu.rows(panes + more, hosts, four.take(3), emptyList(), a0.key).none { it is AddMenu.Row.Heading && it.text.startsWith("four") })
    }

    @Test
    fun nothingToAddIsEmptyButForTheTwoHeads() {
        val rows = AddMenu.rows(listOf(a0), hosts, listOf(a0.key), emptyList(), a0.key)
        assertEquals(2, rows.size)
        assertTrue(items(rows).isEmpty())
    }
}
