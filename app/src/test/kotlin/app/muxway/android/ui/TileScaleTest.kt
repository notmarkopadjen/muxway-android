package app.muxway.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileScaleTest {
    // The text setting is 12; the pane's columns would fill the tile at 9.
    private fun of(fitting: Boolean, held: Boolean, reflow: Boolean = false, zoom: Float = 1f) = TileScale.of(fitting, held, reflow, 12f, 9f, zoom)

    @Test
    fun fitOnAndTheHostTookItDrawsAtTheTextSettingAndCutsAtTheTile() {
        val d = of(fitting = true, held = false)
        assertEquals(12f, d.drawSp, 0f)
        assertEquals(12f, d.cellSp, 0f)
        assertFalse(d.paneCols)
    }

    @Test
    fun heldIsDrawnScaledToThePanesColumnsAndStillMeasuredAtTheTextSetting() {
        val d = of(fitting = true, held = true)
        assertEquals(9f, d.drawSp, 0f)
        assertEquals(12f, d.cellSp, 0f)
        assertTrue(d.paneCols)
    }

    @Test
    fun heldNeverScalesUpAPaneNarrowerThanTheTile() {
        val d = TileScale.of(fitting = true, held = true, reflow = false, textSp = 12f, fitSp = 30f, zoom = 1f)
        assertEquals(12f, d.drawSp, 0f)
        assertTrue(d.paneCols)
    }

    @Test
    fun fitOffIsDrawnLikeAHeldTile() {
        val d = of(fitting = false, held = false)
        assertEquals(9f, d.drawSp, 0f)
        assertEquals(12f, d.cellSp, 0f)
        assertTrue(d.paneCols)
    }

    @Test
    fun reflowCutsAtTheTileAtTheTextSettingForFitOffAndForHeld() {
        for (held in listOf(false, true)) {
            val d = of(fitting = !held, held = held, reflow = true)
            assertEquals(12f, d.drawSp, 0f)
            assertEquals(12f, d.cellSp, 0f)
            assertFalse(d.paneCols)
        }
        // Fit on and taken: reflow changes nothing.
        assertEquals(of(fitting = true, held = false), of(fitting = true, held = false, reflow = true))
    }

    @Test
    fun pinchZoomScalesTheDrawingAndTheCellsAlike() {
        val d = of(fitting = true, held = true, zoom = 2f)
        assertEquals(18f, d.drawSp, 0f)
        assertEquals(24f, d.cellSp, 0f)
        val taken = of(fitting = true, held = false, zoom = 0.5f)
        assertEquals(6f, taken.drawSp, 0f)
        assertEquals(6f, taken.cellSp, 0f)
    }

    @Test
    fun theSizeNeverGoesUnderTwo() {
        val d = TileScale.of(fitting = true, held = true, reflow = false, textSp = 12f, fitSp = 0.5f, zoom = 0.5f)
        assertEquals(2f, d.drawSp, 0f)
        assertEquals(6f, d.cellSp, 0f)
    }
}
