package app.muxway.android.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material.Text
import app.muxway.android.protocol.Address
import app.muxway.android.protocol.Board
import app.muxway.android.protocol.Clip
import app.muxway.android.protocol.WatchGate
import app.muxway.android.protocol.Wire
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One pane on the watch: its tail, a row of answers and the way to the phone. Everything is
 * asked of the phone; the tail is kept here in memory and nowhere else.
 */
class PaneActivity : ComponentActivity() {
    private var address = ""
    private var lines by mutableStateOf<List<String>>(emptyList())
    private var etag = ""
    private var writable by mutableStateOf(false)
    private var transcriber by mutableStateOf(false)
    private var problem by mutableStateOf<String?>("connecting")
    private var flash by mutableStateOf<String?>(null)
    private var heard by mutableStateOf<String?>(null)
    private var recording by mutableStateOf(false)
    private var sending by mutableStateOf(false)
    private var meter by mutableStateOf(Clip.meter(emptyList()))
    private var seconds by mutableIntStateOf(0)

    private var node: String? = null
    private var recorder: Recorder? = null
    private var readId = ""
    private var clipId = ""
    private val sent = HashSet<String>()
    private var seq = 0
    private val salt = java.util.UUID.randomUUID().toString().take(6)
    private var flashJob: Job? = null

    private val listener = MessageClient.OnMessageReceivedListener { m -> runOnUiThread { arrived(m.path, m.data) } }
    private val askMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else say("microphone refused")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra(ADDRESS)?.takeIf(Address::valid) ?: run { finish(); return }
        val row = BoardStore.load(this).rows.firstOrNull { it.address == address }
        setContent { Screen(row) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Wearable.getMessageClient(this@PaneActivity).addListener(listener)
                try {
                    while (true) {
                        read()
                        delay(READ_EVERY_MS)
                    }
                } finally {
                    Wearable.getMessageClient(this@PaneActivity).removeListener(listener)
                }
            }
        }
    }

    override fun onStop() {
        recorder?.cancel()
        recorder = null
        recording = false
        sending = false
        super.onStop()
    }

    private fun id(prefix: String) = "$prefix${++seq}-$salt"

    private suspend fun phone(): String? {
        node = node ?: Phone.nodeId(this)
        if (node == null) problem = "no phone"
        return node
    }

    private suspend fun read() {
        val n = phone() ?: return
        readId = id("r")
        if (!Phone.send(this, n, Wire.READ, Wire.read(readId, address, etag))) {
            node = null
            problem = "no phone"
        }
    }

    private fun arrived(path: String, bytes: ByteArray) {
        when (path) {
            Wire.TAIL -> {
                val t = Wire.tail(bytes) ?: return
                if (t.id != readId || t.address != address) return
                if (t.error.isNotEmpty()) {
                    problem = say(t.error)
                    return
                }
                problem = null
                etag = t.etag
                writable = t.writable
                transcriber = t.transcriber
                t.lines?.let { lines = it }
            }
            Wire.DONE -> {
                val d = Wire.done(bytes) ?: return
                if (!sent.remove(d.id)) return
                flashNow(if (d.ok) "sent" else say(d.error))
                if (d.ok) lifecycleScope.launch { delay(300); read() }
            }
            Wire.HEARD -> {
                val h = Wire.heard(bytes) ?: return
                if (h.id != clipId) return
                sending = false
                if (h.error.isNotEmpty()) flashNow(say(h.error)) else heard = h.text
            }
        }
    }

    private fun send(path: String, id: String, bytes: ByteArray) {
        sent.add(id)
        lifecycleScope.launch {
            val n = phone() ?: return@launch
            if (!Phone.send(this@PaneActivity, n, path, bytes)) {
                sent.remove(id)
                flashNow("no phone")
            }
        }
    }

    private fun key(name: String) = id("k").let { send(Wire.KEY, it, Wire.key(it, address, name)) }

    private fun letter(text: String) = id("t").let { send(Wire.TEXT, it, Wire.text(it, address, text, true)) }

    private fun typeHeard(enter: Boolean) {
        val h = clipId
        heard = null
        id("t").let { send(Wire.TEXT, it, Wire.heardText(it, address, h, enter)) }
    }

    private fun speak() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecording()
    }

    private fun startRecording() {
        val r = Recorder(
            onFrame = { c -> runOnUiThread { meter = Clip.meter(c.levels); seconds = c.seconds.toInt() } },
            onFull = { runOnUiThread { stopRecording() } },
        )
        if (!r.start()) {
            say("no microphone")
            return
        }
        recorder = r
        recording = true
        meter = Clip.meter(emptyList())
        seconds = 0
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        recording = false
        val clip = r.stop()
        if (!clip.sendable) {
            flashNow("too short")
            return
        }
        val id = id("c")
        clipId = id
        sending = true
        lifecycleScope.launch {
            val n = phone()
            if (n == null || !Phone.sendClip(this@PaneActivity, n, id, clip.wav())) {
                sending = false
                flashNow("no phone")
                return@launch
            }
            // The hub gives its transcriber ninety seconds; a little more and the screen gives up.
            delay(HEARD_TIMEOUT_MS)
            if (sending && clipId == id) {
                sending = false
                flashNow(say(WatchGate.NO_TRANSCRIBER))
            }
        }
    }

    private fun dropRecording() {
        recorder?.cancel()
        recorder = null
        recording = false
    }

    private fun say(error: String): String = when (error) {
        WatchGate.REFUSED -> "refused"
        WatchGate.READ_ONLY -> "read-only"
        WatchGate.TOO_SHORT -> "too short"
        WatchGate.RATE_LIMITED -> "one clip every five seconds"
        WatchGate.NO_TRANSCRIBER -> "transcriber unavailable"
        WatchGate.NO_CONNECTION -> "no connection"
        WatchGate.PAUSED -> "paused"
        else -> error
    }

    private fun flashNow(text: String) {
        flash = text
        flashJob?.cancel()
        flashJob = lifecycleScope.launch { delay(2_000); flash = null }
    }

    @Composable
    private fun Screen(row: app.muxway.android.protocol.Row?) {
        val scroll = rememberScrollState()
        LaunchedEffect(lines) { scroll.scrollTo(scroll.maxValue) }
        val value = when (row?.state) { Board.WAITING -> 2; Board.QUIET -> 1; else -> 0 }
        val name = row?.let { r -> r.glyph?.let { "$it ${r.name}" } ?: r.name } ?: address.substringAfter('/')
        Column(Modifier.fillMaxSize().background(Palette.bg).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("┌ $name", color = Palette.forValue(value), fontFamily = Palette.mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                if (lines.isEmpty()) {
                    Text(problem ?: "empty", color = Palette.dim, fontFamily = Palette.mono, fontSize = 11.sp, modifier = Modifier.padding(4.dp))
                } else {
                    Text(lines.joinToString("\n"), color = if (problem == null) Palette.ink else Palette.dim, fontFamily = Palette.mono, fontSize = 9.sp, lineHeight = 11.sp, softWrap = true)
                }
            }
            val note = flash ?: problem?.takeIf { lines.isNotEmpty() }
            Text(if (note == null) "└" else "└ $note", color = if (flash != null) Palette.attention else Palette.dim, fontFamily = Palette.mono, fontSize = 11.sp, maxLines = 1)
            when {
                recording -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$meter ${seconds}s", color = Palette.attention, fontFamily = Palette.mono, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Key("■", Palette.attention) { stopRecording() }
                    Key("drop", Palette.dim) { dropRecording() }
                }
                sending -> Text("  listening…", color = Palette.dim, fontFamily = Palette.mono, fontSize = 11.sp)
                heard != null -> Column {
                    Text(heard!!, color = Palette.ink, fontFamily = Palette.mono, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row {
                        Key("send", Palette.attention) { typeHeard(true) }
                        Key("type", Palette.ink) { typeHeard(false) }
                        Key("drop", Palette.dim) { heard = null }
                    }
                }
                else -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    if (writable) {
                        Key("Enter", Palette.ink) { key("Enter") }
                        Key("Esc", Palette.ink) { key("Escape") }
                        Key("y", Palette.ink) { letter("y") }
                        Key("n", Palette.ink) { letter("n") }
                        Key("^C", Palette.alert) { key("C-c") }
                        if (transcriber) Key("◉", Palette.attention) { speak() }
                    } else if (problem == null) {
                        Text("read-only ", color = Palette.dim, fontFamily = Palette.mono, fontSize = 11.sp)
                    }
                    Key("phone", Palette.dim) {
                        startActivity(Intent(this@PaneActivity, OpenPaneActivity::class.java).putExtra(OpenPaneActivity.ADDRESS, address))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    @Composable
    private fun Key(label: String, colour: Color, onClick: () -> Unit) {
        Box(
            Modifier.padding(end = 4.dp, top = 3.dp).border(1.dp, Palette.faint).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = colour, fontFamily = Palette.mono, fontSize = 12.sp, maxLines = 1)
        }
    }

    companion object {
        const val ADDRESS = "address"
        const val READ_EVERY_MS = 2_000L
        const val HEARD_TIMEOUT_MS = 100_000L
    }
}
