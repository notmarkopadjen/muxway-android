package app.muxway.android.wear

import android.Manifest
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.input.RemoteInputIntentHelper
import app.muxway.android.protocol.Keys
import app.muxway.android.protocol.Ansi
import app.muxway.android.protocol.Board
import app.muxway.android.protocol.ClaudeLens
import app.muxway.android.protocol.Clip
import app.muxway.android.protocol.Input
import app.muxway.android.protocol.WatchGate
import app.muxway.android.protocol.Wire
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One pane on the watch: its tail in colour (Claude Code's chrome hidden by the lens), with
 * *reply* and the microphone above it and nothing below, so the tail has the screen. Every
 * answer, and the way to the phone, is in the reply menu. Everything is asked of the phone; the
 * tail is kept here in memory and nowhere else.
 */
class PaneActivity : ComponentActivity() {
    /** One row of the reply menu. */
    private class Answer(val label: String, val text: String = "", val key: String = "", val enter: Boolean = false)

    private var address = ""
    private var lines by mutableStateOf<List<String>>(emptyList())
    private var command by mutableStateOf("")
    private var etag = ""
    private var writable by mutableStateOf(false)
    private var transcriber by mutableStateOf(false)
    private var problem by mutableStateOf<String?>("connecting")
    private var flash by mutableStateOf<String?>(null)
    private var draft by mutableStateOf<String?>(null)
    private var draftHeard = ""
    private var menu by mutableStateOf(false)
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
        if (ok) startRecording() else flashNow("microphone refused")
    }
    private val askText = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val typed = r.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(TYPED) }?.toString() ?: return@registerForActivityResult
        val clean = Input.typed(typed).take(Input.MAX_TEXT_BYTES)
        if (clean.isNotEmpty()) { draftHeard = ""; draft = clean }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra(ADDRESS)?.takeIf(Keys::valid) ?: run { finish(); return }
        val row = BoardStore.load(this).rows.firstOrNull { it.address == address }
        setContent { if (menu) Menu() else Screen(row) }
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
                command = t.command
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
                if (h.error.isNotEmpty()) flashNow(say(h.error)) else { draftHeard = h.id; draft = h.text }
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

    private fun text(text: String, enter: Boolean) = id("t").let { send(Wire.TEXT, it, Wire.text(it, address, text, enter)) }

    private fun answer(a: Answer) {
        menu = false
        if (a.key.isNotEmpty()) key(a.key) else text(a.text, a.enter)
    }

    /** The draft is a transcript the phone holds under its clip id, or a line typed here. */
    private fun sendDraft(enter: Boolean) {
        val d = draft ?: return
        val heard = draftHeard
        draft = null
        draftHeard = ""
        if (heard.isNotEmpty()) id("t").let { send(Wire.TEXT, it, Wire.heardText(it, address, heard, enter)) }
        else text(d, enter)
    }

    /** The phone opens this pane behind its lock screen, so unlocking lands on it. */
    private fun openOnThePhone() {
        menu = false
        startActivity(Intent(this, OpenPaneActivity::class.java).putExtra(OpenPaneActivity.ADDRESS, address))
    }

    private fun typeOnTheWatch() {
        menu = false
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(TYPED).setLabel("reply").build()))
        try {
            askText.launch(intent)
        } catch (e: Exception) {
            flashNow("no keyboard")
        }
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
            flashNow("no microphone")
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
        WatchGate.NOT_ALLOWED -> "not allowed: phone setting"
        WatchGate.NO_CLIP -> "clip lost on the way"
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

    /** The rows to draw: Claude Code's screen without its chrome, the typed box pinned; any other program whole. */
    private fun shaped(): Pair<List<String>, List<String>> {
        val plain = lines.map { Ansi.plain(it) }
        val s = if (ClaudeLens.runs(command)) ClaudeLens.shape(plain, true) else null
        if (s == null) return lines to emptyList()
        return s.body.map { lines[it] } to s.pinned.map { lines[it] }
    }

    /** The reply menu: Claude's choices first, then the common answers; the keyboard is the last row. */
    private fun answers(): List<Answer> {
        val out = ArrayList<Answer>()
        val choices = if (ClaudeLens.runs(command)) ClaudeLens.choices(lines.map { Ansi.plain(it) }) else emptyList()
        for (c in choices) out.add(Answer("${c.digit} · ${c.label}", text = c.digit.toString()))
        out.add(Answer("Enter", key = "Enter"))
        out.add(Answer("y, Enter", text = "y", enter = true))
        out.add(Answer("n, Enter", text = "n", enter = true))
        if (choices.isEmpty()) {
            out.add(Answer("1 · first choice", text = "1"))
            out.add(Answer("2 · second choice", text = "2"))
            out.add(Answer("3 · third choice", text = "3"))
        }
        out.add(Answer("Esc", key = "Escape"))
        out.add(Answer("Ctrl-C", key = "C-c"))
        out.add(Answer("Tab", key = "Tab"))
        out.add(Answer("Shift-Tab", key = "BTab"))
        out.add(Answer("Up", key = "Up"))
        out.add(Answer("Down", key = "Down"))
        return out
    }

    private fun styled(line: String, ink: Color): AnnotatedString = buildAnnotatedString {
        for (span in Ansi.rows(line, 0).firstOrNull().orEmpty()) withStyle(Colours.style(span.style, ink)) { append(span.text) }
    }

    @Composable
    private fun Screen(row: app.muxway.android.protocol.Row?) {
        val scroll = rememberScrollState()
        val wheel = remember { FocusRequester() }
        LaunchedEffect(lines) { scroll.scrollTo(scroll.maxValue) }
        LaunchedEffect(Unit) { runCatching { wheel.requestFocus() } }
        val value = when (row?.state) { Board.WAITING -> 2; Board.QUIET -> 1; else -> 0 }
        val name = row?.let { r -> r.glyph?.let { "$it ${r.name}" } ?: r.name } ?: address.substringAfter('/')
        val (body, pinned) = remember(lines, command) { shaped() }
        val ink = if (problem == null) Palette.ink else Palette.dim
        val config = LocalConfiguration.current
        val round = config.isScreenRound
        Box(Modifier.fillMaxSize().background(Palette.bg)) {
            // The glass decides how wide each band of the screen may be. Nothing sits under the
            // tail but the note: every answer is in the reply menu, so the tail has the room.
            val tall = config.screenHeightDp.toFloat()
            val head = if (round) tall * 0.12f else 22f
            val foot = if (round) tall * 0.11f else 6f
            val tailTall = (tall - head - TOP - TITLE - NOTE - foot).coerceAtLeast(40f)
            val bands = Bands(tall, round)
            bands.skip(head)
            // The top row must never be squeezed: a button with no room left measures its label
            // at nothing and draws an empty box, as the microphone did on a 240 dp watch.
            val topIn = bands.next(TOP).coerceAtMost(((tall - ROW_LEAST) / 2f).coerceAtLeast(0f))
            val titleIn = bands.next(TITLE)
            val tailIn = bands.next(tailTall)
            val noteIn = bands.next(NOTE)
            fun side(inset: Float, flat: Float): Dp = (if (round) inset + 3f else flat).dp
            Column(Modifier.fillMaxSize().padding(top = head.dp, bottom = foot.dp)) {
                // The top: the microphone and reply, or what one of them is doing.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = side(topIn, 20f)),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        recording -> {
                            Key("■", Palette.attention) { stopRecording() }
                            Text("$meter ${seconds}s", color = Palette.attention, fontFamily = Palette.mono, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            Key("drop", Palette.dim) { dropRecording() }
                        }
                        sending -> Text("◉ listening…", color = Palette.dim, fontFamily = Palette.mono, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                        draft != null -> {
                            Key("send", Palette.attention) { sendDraft(true) }
                            Key("type", Palette.ink) { sendDraft(false) }
                            Key("drop", Palette.dim) { draft = null; draftHeard = "" }
                        }
                        writable -> {
                            Key("reply", Palette.ink, wide = true) { menu = true }
                            if (transcriber) Key("◉", Palette.attention, wide = true) { speak() }
                        }
                        problem == null -> {
                            Text("read-only ", color = Palette.dim, fontFamily = Palette.mono, fontSize = 11.sp)
                            Key("phone", Palette.dim) { openOnThePhone() }
                        }
                    }
                }
                if (draft != null) Text(draft!!, color = Palette.ink, fontFamily = Palette.mono, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = side(topIn, 26f)))
                Text("┌ $name", color = Palette.forValue(value), fontFamily = Palette.mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = side(titleIn, 14f), end = side(titleIn, 14f), top = 4.dp))
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = side(tailIn, 12f))
                        .verticalScroll(scroll).rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), wheel),
                ) {
                    if (lines.isEmpty()) {
                        Text(problem ?: "empty", color = Palette.dim, fontFamily = Palette.mono, fontSize = 11.sp, modifier = Modifier.padding(4.dp))
                    } else {
                        for (l in body) Text(styled(l, ink), fontFamily = Palette.mono, fontSize = 9.sp, lineHeight = 11.sp, softWrap = true)
                        if (pinned.isNotEmpty()) {
                            Text("┈".repeat(40), color = Palette.faint, fontFamily = Palette.mono, fontSize = 9.sp, maxLines = 1)
                            for (l in pinned) Text(styled(l, Palette.dim), fontFamily = Palette.mono, fontSize = 9.sp, lineHeight = 11.sp, softWrap = true)
                        }
                    }
                }
                val note = flash ?: problem?.takeIf { lines.isNotEmpty() }
                Text(if (note == null) "└" else "└ $note", color = if (flash != null) Palette.attention else Palette.dim, fontFamily = Palette.mono, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(horizontal = side(noteIn, 14f)))
            }
        }
    }

    @Composable
    private fun Menu() {
        BackHandler { menu = false }
        val answers = remember(lines, command) { answers() }
        val config = LocalConfiguration.current
        val round = config.isScreenRound
        // Every answer must be reachable: the list centres the row it is on, so the first and the
        // last come to the middle of the glass, and the bezel turns it as well as the finger.
        val list = rememberScalingLazyListState(initialCenterItemIndex = 1)
        val crown = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { crown.requestFocus() } }
        val edge = (if (round) config.screenWidthDp * 0.08f else 8f).dp
        Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
            ScalingLazyColumn(
                Modifier.fillMaxSize().background(Palette.bg)
                    .rotaryScrollable(RotaryScrollableDefaults.behavior(list), crown),
                state = list,
                autoCentering = AutoCenteringParams(itemIndex = 1),
                contentPadding = PaddingValues(horizontal = edge, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item { Text("┌─ reply ─┐", color = Palette.dim, fontFamily = Palette.mono, fontSize = 12.sp) }
                for (a in answers) item { MenuRow(a.label, if (a.label.contains('·')) Palette.attention else Palette.ink) { answer(a) } }
                item { MenuRow("type…", Palette.ink) { typeOnTheWatch() } }
                item { MenuRow("phone", Palette.dim) { openOnThePhone() } }
                item { Text("└ back", color = Palette.dim, fontFamily = Palette.mono, fontSize = 12.sp, modifier = Modifier.clickable { menu = false }.padding(8.dp)) }
            }
        }
    }

    /** One row of the menu: a box wide enough and tall enough to be hit on a watch. */
    @Composable
    private fun MenuRow(label: String, colour: Color, onClick: () -> Unit) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).border(1.dp, Palette.faint)
                .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = colour, fontFamily = Palette.mono, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun Key(label: String, colour: Color, wide: Boolean = false, onClick: () -> Unit) {
        Box(
            Modifier.padding(horizontal = if (wide) 5.dp else 2.dp, vertical = 2.dp).border(1.dp, Palette.faint)
                .clickable(onClick = onClick).padding(horizontal = if (wide) 14.dp else 7.dp, vertical = if (wide) 1.dp else 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = colour, fontFamily = Palette.mono, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1)
        }
    }

    companion object {
        const val ADDRESS = "address"
        const val TYPED = "text"
        const val READ_EVERY_MS = 2_000L
        const val HEARD_TIMEOUT_MS = 100_000L

        /** The heights the screen's bands are laid out with, in dp, and the room the top row keeps. */
        const val TOP = 26f
        const val ROW_LEAST = 150f
        const val TITLE = 16f
        const val NOTE = 16f
    }
}
