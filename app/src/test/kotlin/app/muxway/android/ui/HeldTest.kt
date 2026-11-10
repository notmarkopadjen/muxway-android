package app.muxway.android.ui

import app.muxway.android.protocol.SizedBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldTest {
    private val me = "Pixel 9"
    private val mine = SizedBy("phone", me, 1L, mine = true)

    @Test
    fun theHostTookThisPhonesSizeNobodyHoldsAnything() {
        assertEquals(Held.NONE, Held.of(heldSize = false, sized = mine, label = me))
        assertEquals(Held.NONE, Held.of(heldSize = false, sized = null, label = me))
    }

    @Test
    fun aDriftNobodyIsNamedForIsTheHosts() {
        assertEquals(Held.HOST, Held.of(heldSize = true, sized = null, label = me))
        assertEquals("held", Held.words(Held.HOST))
    }

    @Test
    fun thisVeryClientHoldingItIsTheHostsToo() {
        assertEquals(Held.HOST, Held.of(heldSize = true, sized = mine, label = me))
    }

    @Test
    fun anotherTokenOrThisTokenFromAnotherDeviceIsAnother() {
        val web = SizedBy("web", "Chrome", 1L, mine = false)
        assertEquals(Held.OTHER("web", "Chrome"), Held.of(heldSize = true, sized = web, label = me))
        assertEquals("held by web · Chrome", Held.words(Held.OTHER("web", "Chrome")))
        val fold = SizedBy("phone", "SM-F956B", 1L, mine = true)
        assertEquals(Held.OTHER("phone", "SM-F956B"), Held.of(heldSize = true, sized = fold, label = me))
        assertEquals("held by phone", Held.words(Held.OTHER("phone", "")))
    }

    @Test
    fun aTouchRefitsOnlyAgainstAnotherTwoSecondsApart() {
        val other = Held.OTHER("web", "Chrome")
        assertTrue(Refit.wanted(other, 0L, Refit.GAP_MS))
        assertFalse(Refit.wanted(other, 1_000L, 1_000L + Refit.GAP_MS - 1))
        assertFalse(Refit.wanted(Held.HOST, 0L, 100_000L))
        assertFalse(Refit.wanted(Held.NONE, 0L, 100_000L))
    }
}
