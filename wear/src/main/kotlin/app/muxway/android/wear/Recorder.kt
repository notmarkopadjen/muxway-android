package app.muxway.android.wear

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.muxway.android.protocol.Clip
import app.muxway.android.protocol.Wire
import kotlin.concurrent.thread

/**
 * The watch's microphone into a [Clip], as the phone's recorder does it: 16 kHz mono PCM on
 * its own thread until stopped, cancelled or thirty seconds are in.
 */
class Recorder(private val onFrame: (Clip) -> Unit, private val onFull: () -> Unit) {
    private val clip = Clip()
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var reader: Thread? = null

    /** False when the watch has no usable microphone. Needs RECORD_AUDIO, granted by the caller. */
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
            val buf = ByteArray(Clip.SAMPLE_RATE / 10 * 2)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) break
                clip.push(buf, n)
                onFrame(clip)
                if (clip.seconds >= Wire.MAX_CLIP_SECONDS) {
                    running = false
                    onFull()
                }
            }
        }
        return true
    }

    fun stop(): Clip {
        close()
        return clip
    }

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
