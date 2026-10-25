package app.muxway.android.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundTest {
    private val screen = 450f

    @Test
    fun aBandAcrossTheMiddleNeedsNothing() {
        assertEquals(0f, Round.inset(screen, 0.5f, 0.5f), 0.01f)
    }

    @Test
    fun aBandNearTheEdgeNeedsMostOfTheHalf() {
        // The band's far edge is 0.44 of the screen from the middle: 198 of the 225 the radius has.
        val far = Round.inset(screen, 0.86f, 0.94f)
        assertEquals(225f - 106.9f, far, 0.5f)
        assertTrue(far > Round.inset(screen, 0.78f, 0.86f))
    }

    @Test
    fun theWidestEdgeDecides() {
        // Top edge 0.2 from the middle, bottom edge 0.25: the bottom one is the narrow place.
        assertEquals(Round.inset(screen, 0.75f, 0.75f), Round.inset(screen, 0.3f, 0.75f), 0.01f)
    }

    @Test
    fun aBandOffTheScreenIsClampedToTheHalf() {
        assertEquals(225f, Round.inset(screen, -0.4f, 1.6f), 0.01f)
    }

    @Test
    fun aScreenWithoutASizeAsksForNothing() {
        assertEquals(0f, Round.inset(0f, 0.1f, 0.9f), 0.01f)
    }

    @Test
    fun bandsWalkDownTheScreenAsThePaneScreenLaysItOut() {
        val round = Bands(screen, true)
        round.skip(45f)
        val top = round.next(34f)
        round.next(16f)
        val tail = round.next(253f)
        round.next(16f)
        val keys = round.next(34f)
        val keysBelow = round.next(34f)
        assertTrue("the tail sits in the wide middle", tail < top)
        assertTrue("the keys are narrower than the tail", tail < keys)
        assertTrue("the second key row is the narrowest place", keys < keysBelow)
        assertTrue("and still leaves room for two keys", screen - 2 * keysBelow > 120f)
    }

    @Test
    fun aSquareWatchKeepsItsCorners() {
        val square = Bands(screen, false)
        square.skip(45f)
        assertEquals(0f, square.next(34f), 0.01f)
        assertEquals(0f, square.next(300f), 0.01f)
    }
}
