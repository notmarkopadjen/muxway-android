package app.muxway.android.watch

import app.muxway.android.protocol.Input
import app.muxway.android.protocol.WatchGate
import app.muxway.android.protocol.Wire
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * The watch's requests about a pane arrive here and go through the service's gate; the
 * answer goes back to the node that asked. With the service stopped every answer is "paused".
 */
class WatchRequests : WearableListenerService() {
    override fun onMessageReceived(m: MessageEvent) {
        val r = Wire.request(m.path, m.data) ?: return
        val gate = AttentionService.gate
        val (path, bytes) = when {
            gate == null && r is Wire.Read -> Wire.TAIL to Wire.tail(Wire.Tail(r.id, r.address, "", null, false, false, WatchGate.PAUSED))
            gate == null -> Wire.DONE to Wire.done(Wire.Done(r.id, false, WatchGate.PAUSED))
            r is Wire.Read -> Wire.TAIL to Wire.tail(gate.read(r))
            r is Wire.Key -> Wire.DONE to Wire.done(gate.key(r))
            r is Wire.Text -> Wire.DONE to Wire.done(gate.text(r))
            else -> return
        }
        Wearable.getMessageClient(this).sendMessage(m.sourceNodeId, path, bytes)
    }

    /** A clip streams in on a channel named /clip/<id>; the text heard goes back as a message. */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(Wire.CLIP + "/")) return
        val id = Input.printable(channel.path.removePrefix(Wire.CLIP + "/")).take(64)
        if (id.isEmpty()) return
        val client = Wearable.getChannelClient(this)
        // Off the listener's thread, so tails keep flowing while the hub listens.
        thread(name = "muxway-clip") {
            val wav = try {
                Tasks.await(client.getInputStream(channel)).use { readCapped(it) }
            } catch (e: Exception) {
                null
            }
            runCatching { Tasks.await(client.close(channel)) }
            val gate = AttentionService.gate
            val heard = when {
                gate == null -> Wire.Heard(id, "", WatchGate.PAUSED)
                wav == null -> Wire.Heard(id, "", WatchGate.REFUSED)
                else -> gate.transcribe(id, wav)
            }
            Wearable.getMessageClient(this).sendMessage(channel.nodeId, Wire.HEARD, Wire.heard(heard))
        }
    }

    /** The whole stream, or null when it runs past the clip cap. */
    private fun readCapped(s: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            if (out.size() + n > Wire.MAX_CLIP_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
