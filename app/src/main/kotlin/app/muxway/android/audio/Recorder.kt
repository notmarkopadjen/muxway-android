package app.muxway.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.muxway.android.protocol.Clip
import kotlin.concurrent.thread

/**
 * The microphone into a [Clip]: 16 kHz mono PCM read on its own thread until stopped, cancelled
 * or sixty seconds are in. [onFrame] reports each frame on that thread; [onFull] the cap.
 */
class Recorder(private val onFrame: (Clip) -> Unit, private val onFull: () -> Unit) {
    private val clip = Clip()
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var reader: Thread? = null

    val recording: Boolean get() = running

    /** False when the device has no usable microphone. Needs RECORD_AUDIO, granted by the caller. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(Clip.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Clip.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Clip.SAMPLE_RATE))
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        record = r
        running = true
        r.startRecording()
        reader = thread(name = "muxway-mic") {
            // A tenth of a second per frame, as the meter wants it.
            val buf = ByteArray(Clip.SAMPLE_RATE / 10 * 2)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) break
                clip.push(buf, n)
                onFrame(clip)
                if (clip.full) {
                    running = false
                    onFull()
                }
            }
        }
        return true
    }

    /** Closes the microphone and hands over the clip. */
    fun stop(): Clip {
        close()
        return clip
    }

    /** Closes the microphone; the clip is dropped with this object. */
    fun cancel() = close()

    private fun close() {
        running = false
        record?.let {
            try { it.stop() } catch (e: IllegalStateException) { /* never started */ }
            it.release()
        }
        record = null
        reader?.join(500)
        reader = null
    }
}
