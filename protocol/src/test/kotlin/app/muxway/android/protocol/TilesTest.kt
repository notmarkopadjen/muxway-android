package app.muxway.android.protocol

import app.muxway.android.protocol.WindowLayout.Node.Leaf
import app.muxway.android.protocol.WindowLayout.Node.Split
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TilesTest {
    private val row = Tiles.relabel(Split(true, listOf(Leaf(0), Leaf(1), Leaf(2))))

    @Test
    fun theMiddleIsASwapAndTheEdgesSplit() {
        assertEquals(Tiles.Zone.SWAP, Tiles.zoneAt(0.5f, 0.5f))
        assertEquals(Tiles.Zone.BOTTOM, Tiles.zoneAt(0.5f, 0.9f))
        assertEquals(Tiles.Zone.TOP, Tiles.zoneAt(0.5f, 0.05f))
        assertEquals(Tiles.Zone.LEFT, Tiles.zoneAt(0.1f, 0.5f))
        assertEquals(Tiles.Zone.RIGHT, Tiles.zoneAt(0.95f, 0.6f))
    }

    @Test
    fun aDropSplitsTheTargetAndClosesTheOldPlace() {
        assertEquals(Split(true, listOf(Split(false, listOf(Leaf(0), Leaf(2)), "r.0"), Leaf(1)), "r"), Tiles.dropOn(row, 2, 0, Tiles.Zone.BOTTOM))
        assertEquals(Split(true, listOf(Split(false, listOf(Leaf(2), Leaf(0)), "r.0"), Leaf(1)), "r"), Tiles.dropOn(row, 2, 0, Tiles.Zone.TOP))
        // Across, and a split left with one child gives way.
        val t = Tiles.dropOn(row, 2, 0, Tiles.Zone.BOTTOM)
        assertEquals(Split(true, listOf(Leaf(0), Leaf(1), Leaf(2)), "r"), Tiles.dropOn(t, 2, 1, Tiles.Zone.RIGHT))
        assertSame(row, Tiles.dropOn(row, 1, 1, Tiles.Zone.LEFT))
    }

    @Test
    fun aClosedTileIsPrunedAndTheRestRenumbered() {
        val t = Tiles.dropOn(row, 2, 0, Tiles.Zone.BOTTOM)
        assertEquals(Split(true, listOf(Leaf(1), Leaf(0)), "r"), Tiles.prune(t, 0))
        assertEquals(Leaf(0), Tiles.prune(Split(true, listOf(Leaf(0), Leaf(1)), "r"), 1))
    }

    @Test
    fun theHostsLayoutBecomesTheTreeWithItsShares() {
        // .1 over .2 on the left, .0 down the right: tiles open as .0, .1, .2.
        val layout = "abcd,197x41,0,0{98x41,0,0[98x20,0,0,25,98x20,0,21,26],98x41,99,0,14}"
        val h = Tiles.fromHost(layout, listOf("%14", "%25", "%26"))!!
        assertEquals(Split(true, listOf(Split(false, listOf(Leaf(1), Leaf(2)), "r.0"), Leaf(0)), "r"), h.node)
        assertEquals(listOf(0.5f, 0.5f), h.weights["r"])
        assertNull(Tiles.fromHost(layout, listOf("%14", "%25")))
        assertNull(Tiles.fromHost("junk", listOf("%14")))
        assertNull(Tiles.fromHost(layout, listOf("%14", "%25", "%26", "%27")))
        // A split within a cell of even is even; a wider one keeps its fractions.
        val u = Tiles.fromHost("4e55,100x30,0,0{70x30,0,0,3,29x30,71,0,4}", listOf("%3", "%4"))!!
        assertEquals(listOf(70f / 99, 29f / 99), u.weights["r"])
        // Round trip through the proportional layout.
        val ids = listOf("%14", "%25", "%26")
        val back = Tiles.fromHost(Tiles.layoutFor(h.node, h.weights, ids), ids)!!
        assertTrue(Tiles.sameTree(back.node, h.node))
        assertTrue(Tiles.nearWeights(h.node, back.weights, h.weights, 0.01f))
        // A moved tile is another tree; other shares are not near.
        val swapped = Tiles.fromHost(layout, listOf("%25", "%14", "%26"))!!
        assertFalse(Tiles.sameTree(h.node, swapped.node))
        assertFalse(Tiles.nearWeights(h.node, h.weights, mapOf("r" to listOf(0.6f, 0.4f)), 0.03f))
    }

    @Test
    fun tmuxsLayoutStringsParseAndJunkDoesNot() {
        val p = WindowLayout.parse("dddb,200x50,0,0{100x50,0,0[100x25,0,0,1,100x24,0,26,3],99x50,101,0,2}")!!
        assertEquals(listOf("%1", "%3", "%2"), WindowLayout.panes(p).map { it.id })
        assertEquals(200, p.w)
        for (bad in listOf("", "a87d", "a87d,100x30", "a87d,100x30,0,0", "a87d,100x30,0,0{50x30,0,0,3", "a87d,100x30,0,0,1,junk", "100x30,0,0,1", "a87d,100x30,0,0,1;x")) {
            assertNull(bad, WindowLayout.parse(bad))
        }
    }

    @Test
    fun aTileLaidAcrossTakesTheWholeSplit() {
        val t = Split(true, listOf(Split(false, listOf(Leaf(1), Leaf(2)), "r.0"), Leaf(0)), "r")
        assertEquals(Split(false, listOf(Split(true, listOf(Leaf(1), Leaf(0)), "r.0"), Leaf(2)), "r"), Tiles.across(t, 2, "r", false, false))
        assertEquals(Split(false, listOf(Leaf(0), Leaf(1), Leaf(2)), "r"), Tiles.across(t, 0, "r", false, true))
        assertEquals(Split(true, listOf(Leaf(2), Leaf(1), Leaf(0)), "r"), Tiles.across(t, 1, "r.0", true, false))
        assertSame(t, Tiles.across(t, 0, "nope", false, true))
    }

    @Test
    fun sharesDragWithAFloorAndRectsPlaceTheTiles() {
        assertEquals(listOf(0.6f, 0.4f), Tiles.drag(listOf(0.5f, 0.5f), 0, 0.1f))
        assertEquals(listOf(0.85f, 0.15f), Tiles.drag(listOf(0.5f, 0.5f), 0, 0.9f))
        assertEquals(listOf(0.5f, 0.5f), Tiles.drag(listOf(0.5f, 0.5f), 1, 0.1f))
        val t = Split(true, listOf(Split(false, listOf(Leaf(1), Leaf(2)), "r.0"), Leaf(0)), "r")
        val r = Tiles.rects(t, mapOf("r" to listOf(0.6f, 0.4f)))
        assertEquals(Tiles.Rect(0.6f, 0f, 0.4f, 1f), r[0])
        assertEquals(Tiles.Rect(0f, 0.5f, 0.6f, 0.5f), r[2])
        assertEquals("r", Tiles.rootKey(t))
        assertNull(Tiles.rootKey(Leaf(0)))
    }
}
