package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelTest {
    @Test
    fun aModelIsKeptAsItIs() {
        assertEquals("Pixel 9 Pro Fold", Label.of("Pixel 9 Pro Fold"))
        assertEquals("SM-F956B", Label.of("SM-F956B"))
    }

    @Test
    fun nothingUsableGivesPhone() {
        assertEquals("phone", Label.of(null))
        assertEquals("phone", Label.of(""))
        assertEquals("phone", Label.of("   "))
        assertEquals("phone", Label.of("телефон"))
    }

    @Test
    fun theLabelIsAsciiPrintableTrimmedAndBounded() {
        assertEquals("Pixel 9", Label.of("  Pixel\t9\n "))
        assertEquals("Pixel9", Label.of("Pixel\u00079 \u00e9"))
        val long = Label.of("A".repeat(40))
        assertEquals(Label.MAX, long.length)
        assertTrue(long.all { it in ' '..'~' })
        // A cut that ends on a blank is trimmed again.
        assertEquals("A".repeat(31), Label.of("A".repeat(31) + " " + "B".repeat(10)))
    }

    @Test
    fun aResizeBodyCarriesTheLabelOnlyWhenGiven() {
        assertTrue(Json.size(80, 24, client = "Pixel 9").contains("\"client\":\"Pixel 9\""))
        assertTrue(!Json.size(80, 24).contains("client"))
        assertTrue(!Json.size(80, 24, client = "").contains("client"))
    }
}
