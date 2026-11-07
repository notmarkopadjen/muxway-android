package app.muxway.android.protocol

import app.muxway.android.protocol.WindowLayout.Node.Leaf
import app.muxway.android.protocol.WindowLayout.Node.Split
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskTest {
    private val a = "alice-laptop/work/0.0"
    private val b = "alice-laptop/work/0.1"
    private val c = "alice-laptop/work/0.2"
    private val two = Desk(panes = listOf(a, b), focused = b, arrangement = "custom", tree = Split(true, listOf(Leaf(0), Leaf(1)), "r"), weights = mapOf("r" to listOf(0.6f, 0.4f)), minimised = listOf(c), by = "web", rev = 12, updated = 1767225600)

    @Test
    fun equalDesksSayTheSameWhoeverWroteThemAndWhen() {
        assertTrue(Desks.same(two, two.copy(by = "phone", rev = 13, updated = 1)))
        assertFalse(Desks.same(two, two.copy(focused = a)))
        assertFalse(Desks.same(two, two.copy(weights = mapOf("r" to listOf(0.5f, 0.5f)))))
        assertFalse(Desks.same(two, two.copy(minimised = emptyList())))
        assertFalse(Desks.same(two, null))
        assertEquals(listOf(a, b), Desks.panesOf(two))
        assertEquals(emptyList<String>(), Desks.panesOf(Desk(view = "window", window = "alice-laptop/work/0")))
        assertTrue(Desks.holds(two))
        assertTrue(Desks.holds(Desk(view = "window", window = "alice-laptop/work/0")))
        assertFalse(Desks.holds(Desk()))
        assertFalse(Desks.holds(null))
    }

    @Test
    fun theRowsWordsAreTheWebs() {
        assertEquals("desk", Desks.words(null))
        assertEquals("desk · empty", Desks.words(Desk()))
        assertEquals("desk · 2 panes · custom · 1 minimised · by web", Desks.words(two))
        assertEquals("desk · 1 pane", Desks.words(Desk(panes = listOf(a), arrangement = "row")))
        assertEquals("desk · the window alice-laptop/work/0", Desks.words(Desk(view = "window", window = "alice-laptop/work/0")))
    }

    @Test
    fun theTreeRoundTripsThroughTheHubsJson() {
        val body = Json.deskBody(two)
        val back = Json.desk("""{"desk":${body.dropLast(1)},"updated":1767225600,"by":"web","rev":12}}""")
        assertEquals(two, back)
        // Nested, both ways: the main pane left, two stacked beside it.
        val three = Desk(panes = listOf(a, b, c), focused = a, arrangement = "custom", tree = Split(true, listOf(Leaf(0), Split(false, listOf(Leaf(1), Leaf(2)), "r.1")), "r"), weights = mapOf("r" to listOf(0.6f, 0.4f), "r.1" to listOf(0.5f, 0.5f)))
        assertEquals(three, Json.desk("""{"desk":${Json.deskBody(three)}}"""))
        assertFalse(body.contains("rev"))
        assertFalse(body.contains("updated"))
        assertFalse(body.contains("\"by\""))
        // A window desk carries the window and the focus alone.
        val w = Desk(view = "window", window = "alice-laptop/work/0", focused = c)
        assertEquals(w, Json.desk("""{"desk":${Json.deskBody(w)}}"""))
        assertFalse(Json.deskBody(w).contains("panes"))
    }

    @Test
    fun aTreeIsWellShapedForItsTilesOrItIsNone() {
        assertTrue(Desks.validTree(Split(true, listOf(Leaf(0), Leaf(1)), "r"), 2))
        assertTrue(Desks.validTree(Leaf(0), 1))
        assertFalse(Desks.validTree(Split(true, listOf(Leaf(0), Leaf(1)), "r"), 3))
        assertFalse(Desks.validTree(Split(true, listOf(Leaf(0), Leaf(0)), "r"), 2))
        assertFalse(Desks.validTree(Split(true, listOf(Leaf(0), Leaf(2)), "r"), 2))
        assertFalse(Desks.validTree(Split(true, listOf(Leaf(0), Leaf(1)), ""), 2))
        assertFalse(Desks.validTree(Split(true, listOf(Leaf(0), Leaf(1)), "k".repeat(17)), 2))
        assertFalse(Desks.validTree(Split(true, listOf(Leaf(0)), "r"), 1))
        assertTrue(Desks.validWeights(mapOf("r" to listOf(0.2f, 0.8f))))
        assertFalse(Desks.validWeights(mapOf("r" to listOf(2f, 0.5f))))
        assertFalse(Desks.validWeights(mapOf("r" to listOf(1f))))
        assertFalse(Desks.validWeights(mapOf("k".repeat(17) to listOf(0.5f, 0.5f))))
    }

    @Test
    fun aHostileDeskIsReadAsFarAsItIsSane() {
        // Five panes: none; a bad address and a repeated one are left out; the focus must be a pane.
        val five = Json.desk("""{"desk":{"view":"panes","panes":["$a","$b","$c","alice-laptop/work/0.3","alice-laptop/work/0.4"],"focused":"$a"}}""")
        assertEquals(emptyList<String>(), five.panes)
        assertEquals("", five.focused)
        val odd = Json.desk("""{"desk":{"view":"panes","panes":["$a","alice laptop/x/0.0","$a","$b"],"focused":"$c","arrangement":"spiral"}}""")
        assertEquals(listOf(a, b), odd.panes)
        assertEquals("", odd.focused)
        assertEquals("", odd.arrangement)
        // A tree with the wrong leaves is none, and custom with it; a weight of two drops the weights.
        val tree = Json.desk("""{"desk":{"view":"panes","panes":["$a","$b"],"arrangement":"custom","tree":{"kind":"h","key":"r","children":[0]},"weights":{"r":[2,0.5]}}}""")
        assertNull(tree.tree)
        assertEquals("", tree.arrangement)
        assertEquals(emptyMap<String, List<Float>>(), tree.weights)
        // A long key, a deep nest, an unknown kind: no tree.
        assertNull(Json.desk("""{"desk":{"view":"panes","panes":["$a","$b"],"tree":{"kind":"h","key":"${"k".repeat(40)}","children":[0,1]}}}""").tree)
        assertNull(Json.desk("""{"desk":{"view":"panes","panes":["$a","$b"],"tree":{"kind":"x","key":"r","children":[0,1]}}}""").tree)
        // by is cleaned and bounded; a view the phone does not know is panes; rev never negative.
        val by = Json.desk("""{"desk":{"view":"board","by":"we\u0007b${"x".repeat(100)}","rev":-3}}""")
        assertEquals("panes", by.view)
        assertEquals(64, by.by.length)
        assertFalse(by.by.contains('\u0007'))
        assertEquals(0, by.rev)
        // A window desk needs a window address.
        assertEquals("", Json.desk("""{"desk":{"view":"window","window":"../x"}}""").window)
        assertEquals("alice-laptop/work/0", Json.desk("""{"desk":{"view":"window","window":"alice-laptop/work/0"}}""").window)
    }
}
