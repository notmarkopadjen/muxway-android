package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ClipTest {
    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    private fun ascii(b: ByteArray, at: Int, n: Int) = String(b, at, n, Charsets.US_ASCII)
    private fun frame(ms: Int, value: Int): ByteArray {
        val out = ByteArray(Clip.SAMPLE_RATE * 2 * ms / 1000)
        for (i in 0 until out.size / 2) { out[i * 2] = value.toByte(); out[i * 2 + 1] = (value shr 8).toByte() }
        return out
    }

    @Test
    fun wavHeaderIsWhatTheHubChecks() {
        val pcm = byteArrayOf(0, 0, -24, 3, 24, -4, -1, 127)
        val b = Clip.wav(pcm)
        val v = le(b)
        assertEquals(44 + 8, b.size)
        assertEquals("RIFF", ascii(b, 0, 4))
        assertEquals(36 + 8, v.getInt(4))
        assertEquals("WAVE", ascii(b, 8, 4))
        assertEquals("fmt ", ascii(b, 12, 4))
        assertEquals(16, v.getInt(16))
        assertEquals(1, v.getShort(20).toInt())
        assertEquals(1, v.getShort(22).toInt())
        assertEquals(16000, v.getInt(24))
        assertEquals(32000, v.getInt(28))
        assertEquals(2, v.getShort(32).toInt())
        assertEquals(16, v.getShort(34).toInt())
        assertEquals("data", ascii(b, 36, 4))
        assertEquals(8, v.getInt(40))
        assertEquals(1000, v.getShort(46).toInt())
        assertEquals(-1000, v.getShort(48).toInt())
        assertEquals(32767, v.getShort(50).toInt())
    }

    @Test
    fun stopsAtSixtySecondsAndKnowsWhenItIsTooShort() {
        val c = Clip()
        assertFalse(c.sendable)
        c.push(frame(400, 0))
        assertFalse(c.sendable)
        c.push(frame(100, 0))
        assertTrue(c.sendable)
        assertEquals(0.5, c.seconds, 1e-9)
        val second = frame(1000, 100)
        repeat(70) { c.push(second) }
        assertTrue(c.full)
        assertEquals(Clip.MAX_BYTES, c.bytes)
        assertEquals(60.0, c.seconds, 1e-9)
        assertEquals(0f, c.push(second))
        assertEquals(44 + Clip.MAX_BYTES, c.wav().size)
    }

    @Test
    fun loudnessAndMeter() {
        assertEquals(0f, Clip.rms(frame(100, 0)))
        assertEquals(1f, Clip.rms(frame(100, 16000)))
        assertEquals(0.25f, Clip.rms(frame(100, 2000)), 1e-6f)
        assertEquals(0f, Clip.rms(ByteArray(0)))
        assertEquals("▁▁▁▁▁▁▁▁", Clip.meter(emptyList()))
        assertEquals("▁▁▁▁▁▁▅█", Clip.meter(listOf(0f, 0.5f, 1f)))
        assertEquals("▁▁▅", Clip.meter(listOf(0.5f), 3))
        val c = Clip()
        repeat(10) { c.push(frame(100, 2000)) }
        assertEquals(8, c.levels.size)
        assertEquals("▃▃▃▃▃▃▃▃", Clip.meter(c.levels))
    }
}
