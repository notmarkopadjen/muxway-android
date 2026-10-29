package app.muxway.android.protocol

import app.muxway.android.protocol.WindowLayout.Node.Leaf
import app.muxway.android.protocol.WindowLayout.Node.Split
import app.muxway.android.protocol.WindowLayout.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutTest {
    private val ids = listOf("%3", "%4", "%5")
    private val mainLeft = Split(true, listOf(Leaf(0), Split(false, listOf(Leaf(1), Leaf(2)))))
    private val mainTop = Split(false, listOf(Leaf(0), Split(true, listOf(Leaf(1), Leaf(2)))))

    @Test
    fun theChecksumIsTmuxsOwn() {
        // Both strings are tmux's, checksums included.
        assertEquals("a87d", WindowLayout.checksum("100x30,0,0,0"))
        assertEquals("dddb", WindowLayout.checksum("200x50,0,0{100x50,0,0[100x25,0,0,1,100x24,0,26,3],99x50,101,0,2}"))
    }

    @Test
    fun eachPaneGetsTheCellsItsTileHoldsASplitTheSumAlongItAndTheLeastAcross() {
        val r = WindowLayout.exact(mainLeft, listOf(Size(60, 40), Size(60, 19), Size(60, 19)), ids)
        assertEquals(121, r.cols)
        assertEquals(39, r.rows)
        assertTrue(r.layout, Regex("^[0-9a-f]{4},121x39,0,0\\{60x39,0,0,3,60x39,0,0\\[60x19,0,0,4,60x19,0,0,5\\]\\}$").matches(r.layout))
        assertEquals(listOf(Size(60, 39), Size(60, 19), Size(60, 19)), r.sizes)
        val column = WindowLayout.exact(Split(false, listOf(Leaf(0), Leaf(1))), listOf(Size(80, 24), Size(70, 20)), ids.take(2))
        assertTrue(column.layout, Regex("^[0-9a-f]{4},70x45,0,0\\[70x24,0,0,3,70x20,0,0,4\\]$").matches(column.layout))
        assertEquals(WindowLayout.checksum(column.layout.substringAfter(',')), column.layout.substringBefore(','))
    }

    @Test
    fun aStackIsScaledDownToTheTileAcrossFromItNeverUp() {
        val r = WindowLayout.exact(mainLeft, listOf(Size(60, 30), Size(60, 20), Size(60, 20)), ids)
        assertEquals(30, r.rows)
        assertTrue(r.layout, r.layout.endsWith("[60x15,0,0,4,60x14,0,0,5]}"))
        assertEquals(listOf(Size(60, 30), Size(60, 15), Size(60, 14)), r.sizes)
    }

    @Test
    fun aLoneTileAndAGridAreCells() {
        val one = WindowLayout.exact(Leaf(0), listOf(Size(40, 12)), listOf("%7"))
        assertTrue(one.layout, Regex("^[0-9a-f]{4},40x12,0,0,7$").matches(one.layout))
        val grid = Split(false, listOf(Split(true, listOf(Leaf(0), Leaf(1))), Split(true, listOf(Leaf(2), Leaf(3)))))
        val r = WindowLayout.exact(grid, List(4) { Size(40, 10) }, listOf("%1", "%2", "%3", "%4"))
        assertEquals(81, r.cols)
        assertEquals(21, r.rows)
        assertTrue(r.layout, r.layout.endsWith("81x21,0,0[81x10,0,0{40x10,0,0,1,40x10,0,0,2},81x10,0,0{40x10,0,0,3,40x10,0,0,4}]"))
    }

    @Test
    fun theTilesAlongTheBottomAreNamed() {
        assertEquals(listOf(0, 2), WindowLayout.bottomLeaves(mainLeft))
        assertEquals(listOf(1, 2), WindowLayout.bottomLeaves(mainTop))
        assertEquals(listOf(0, 1), WindowLayout.bottomLeaves(Split(true, listOf(Leaf(0), Leaf(1)))))
        assertEquals(listOf(1), WindowLayout.bottomLeaves(Split(false, listOf(Leaf(0), Leaf(1)))))
        assertEquals(listOf(0), WindowLayout.bottomLeaves(Leaf(0)))
    }

    @Test
    fun onlyTmuxsPaneIdsGoIntoALayout() {
        assertThrows(IllegalArgumentException::class.java) { WindowLayout.exact(Leaf(0), listOf(Size(40, 12)), listOf("7")) }
        assertThrows(IllegalArgumentException::class.java) { WindowLayout.exact(Leaf(0), listOf(Size(40, 12)), listOf("%7,junk")) }
    }
}
