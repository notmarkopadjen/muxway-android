package app.muxway.android.protocol

import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A spoken clip: 16 kHz mono 16-bit PCM gathered in memory up to sixty seconds, wrapped in
 * the WAV the hub takes, and dropped after. Nothing is stored.
 */
class Clip {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 60
        const val MIN_SECONDS = 0.5
        const val MAX_BYTES = SAMPLE_RATE * 2 * MAX_SECONDS
        /** Frames the meter shows: the last eight. */
        const val METER_WIDTH = 8
        private const val BLOCKS = "▁▂▃▄▅▆▇█"

        /** Loudness of a frame of 16-bit samples, 0 to 1. */
        fun rms(buf: ByteArray, n: Int = buf.size): Float {
            val samples = n / 2
            if (samples == 0) return 0f
            var sum = 0.0
            for (i in 0 until samples) {
                val s = ((buf[i * 2].toInt() and 0xff) or (buf[i * 2 + 1].toInt() shl 8)).toShort().toDouble()
                sum += s * s
            }
            return min(1.0, sqrt(sum / samples) / 8000).toFloat()
        }

        /** The last levels as block characters, oldest left, padded to the width. */
        fun meter(levels: List<Float>, width: Int = METER_WIDTH): String {
            val last = levels.takeLast(width)
            val out = StringBuilder(BLOCKS[0].toString().repeat(width - last.size))
            for (l in last) out.append(BLOCKS[min(BLOCKS.length - 1, (l * BLOCKS.length).toInt())])
            return out.toString()
        }

        /** A 44-byte RIFF/WAVE header around mono 16-bit PCM. */
        fun wav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val out = ByteArray(44 + pcm.size)
            fun ascii(at: Int, s: String) = s.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
            fun u32(at: Int, v: Int) { for (i in 0 until 4) out[at + i] = (v ushr (8 * i)).toByte() }
            fun u16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v ushr 8).toByte() }
            ascii(0, "RIFF"); u32(4, 36 + pcm.size); ascii(8, "WAVE")
            ascii(12, "fmt "); u32(16, 16); u16(20, 1); u16(22, 1)
            u32(24, sampleRate); u32(28, sampleRate * 2); u16(32, 2); u16(34, 16)
            ascii(36, "data"); u32(40, pcm.size)
            pcm.copyInto(out, 44)
            return out
        }
    }

    private val pcm = ByteArrayOutputStream()
    private val recent = ArrayList<Float>()

    /** Loudness of the last frames, oldest first. */
    val levels: List<Float> get() = recent

    val bytes: Int get() = pcm.size()
    val seconds: Double get() = bytes / (SAMPLE_RATE * 2.0)
    val full: Boolean get() = bytes >= MAX_BYTES
    val sendable: Boolean get() = seconds >= MIN_SECONDS

    /** One frame from the microphone; what does not fit in sixty seconds is dropped. Returns its loudness. */
    fun push(buf: ByteArray, n: Int = buf.size): Float {
        val take = min(n, MAX_BYTES - bytes)
        if (take <= 0) return 0f
        pcm.write(buf, 0, take)
        val level = rms(buf, take)
        recent.add(level)
        if (recent.size > METER_WIDTH) recent.subList(0, recent.size - METER_WIDTH).clear()
        return level
    }

    fun wav(): ByteArray = wav(pcm.toByteArray())
}
