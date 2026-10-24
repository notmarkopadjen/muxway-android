package app.muxway.android

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.muxway.android.protocol.Address
import app.muxway.android.protocol.Api
import app.muxway.android.protocol.ApiException
import app.muxway.android.protocol.Commands
import app.muxway.android.protocol.Host
import app.muxway.android.protocol.HubUrl
import app.muxway.android.protocol.Input
import app.muxway.android.protocol.Lens
import app.muxway.android.protocol.Pane
import app.muxway.android.protocol.Setup
import app.muxway.android.store.Settings
import app.muxway.android.store.SettingsStore
import app.muxway.android.store.Vault
import app.muxway.android.watch.AttentionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

sealed class Screen {
    data object Connect : Screen()
    data object Panes : Screen()
    data object Scan : Screen()
    data class View(val addresses: List<String>) : Screen()
}

/** One open pane: what was last read and how it went. */
class PaneState(val address: String) {
    var pane by mutableStateOf<Pane?>(null)
    var content by mutableStateOf("")
    var etag: String? = null
    var error by mutableStateOf<String?>(null)
    var updated by mutableStateOf("")
    var busy by mutableStateOf(false)

    /** When the content last changed, in seconds; the lens counts a recent change as working. */
    var changedAt by mutableLongStateOf(0L)

    /** Fit: the last size sent to the host, when, and whether a release is owed. */
    var fitKey = ""
    var fitAt = 0L
    var fitRetried = false
    var fitted = false
    var fitJob: Job? = null
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val vault = Vault(app)
    private val userAgent = "muxway-android/" + BuildConfig.VERSION_NAME

    var settings by mutableStateOf(store.load())
        private set
    var hasToken by mutableStateOf(vault.hasToken())
        private set
    private var api: Api? = null

    var screen by mutableStateOf<Screen>(if (ready()) Screen.Panes else Screen.Connect)
        private set

    var hosts by mutableStateOf(listOf<Host>())
    var panes by mutableStateOf(listOf<Pane>())
    var listError by mutableStateOf<String?>(null)
    var listing by mutableStateOf(false)
    val selected = mutableStateListOf<String>()
    private var listJob: Job? = null
    private var cursor: Long? = null

    val views = mutableStateMapOf<String, PaneState>()

    /** The kind of the last event the hub sent for each pane; `working` is what the lens wants. */
    val lastKind = mutableStateMapOf<String, String>()
    var sendError by mutableStateOf<String?>(null)

    /** Whether the hub has a transcriber, asked once per connection with the first listing. */
    var transcriber by mutableStateOf(false)
        private set
    private var probed = false

    /** The last lines sent to a shell, newest first; the command row offers them again. */
    val recent = mutableStateListOf<String>().also { it.addAll(store.recent()) }

    /** Why the Connect screen is showing, when the app put the user there. */
    var notice by mutableStateOf<String?>(null)

    /** A pane a link asked for, opened once the listing confirms it exists. */
    private var pendingOpen: String? = null

    init {
        AttentionService.apply(app)
    }

    private fun ready() = settings.hubUrl.isNotEmpty() && hasToken

    private fun api(): Api {
        api?.let { return it }
        val token = vault.token() ?: throw ApiException(401, "unauthorized")
        return Api(settings.hubUrl, token, userAgent).also { api = it }
    }

    fun open(s: Screen) {
        if (s is Screen.Panes) { selected.clear(); refreshList() }
        screen = s
    }

    /** Back: pane view and settings return to the list; false when there is nowhere to go. */
    fun back(): Boolean {
        if (screen is Screen.Panes || (screen is Screen.Connect && !ready())) return false
        open(if (screen is Screen.Scan) Screen.Connect else Screen.Panes)
        return true
    }

    /** Saves settings and, when given, a new token. Returns a message when something is wrong. */
    fun save(s: Settings, newToken: String): String? {
        val (url, why) = HubUrl.check(s.hubUrl)
        if (url == null) return why
        val tok = newToken.trim()
        if (tok.isNotEmpty()) {
            if (Input.printable(tok) != tok || tok.length > 200) return "That is not a token"
            vault.store(tok)
            hasToken = true
        } else if (!hasToken) {
            return "Paste a token"
        }
        settings = s.copy(hubUrl = url)
        store.save(settings)
        AttentionService.apply(getApplication())
        notice = null
        api = null
        probed = false
        transcriber = false
        views.clear()
        lastKind.clear()
        panes = emptyList()
        hosts = emptyList()
        cursor = null
        open(Screen.Panes)
        return null
    }

    /** A scanned connect code: hub and token go through the same checks as typed ones. */
    fun connectWithCode(text: String): String? {
        val code = Setup.parse(text) ?: return "That is not a muxway code"
        return save(settings.copy(hubUrl = code.hub), code.token)
    }

    fun forgetToken() {
        vault.clear()
        hasToken = false
        api = null
        probed = false
        transcriber = false
        views.clear()
        screen = Screen.Connect
        AttentionService.apply(getApplication())
    }

    /**
     * `muxway://open?pane=<address>`, from a notification or the watch. Only a well-formed
     * address of a listed pane opens anything; the link can set nothing else.
     */
    fun openLink(uri: Uri?) {
        if (uri == null || uri.scheme != "muxway" || uri.host != "open" || !ready()) return
        val address = uri.getQueryParameter("pane") ?: return
        if (!Address.valid(address)) return
        pendingOpen = address
        if (panes.any { it.address == address }) openPending() else refreshList()
    }

    private fun openPending() {
        val address = pendingOpen ?: return
        pendingOpen = null
        if (panes.any { it.address == address }) open(Screen.View(listOf(address)))
    }

    fun refreshList() {
        if (listJob?.isActive == true) return
        listJob = viewModelScope.launch {
            listing = true
            try {
                val (h, p) = withContext(Dispatchers.IO) { api().hosts() to api().panes() }
                hosts = h
                panes = p
                listError = null
                if (!probed) {
                    transcriber = withContext(Dispatchers.IO) { api().hasTranscriber() }
                    probed = true
                }
                openPending()
            } catch (e: ApiException) {
                listError = e.message
                if (e.code == "unauthorized") tokenRefused()
            } finally {
                listing = false
            }
        }
    }

    /** Holds a long poll on the hub's events; any event re-lists the panes. */
    suspend fun followEvents() {
        while (true) {
            try {
                val ev = withContext(Dispatchers.IO) { api().events(cursor, 30) }
                cursor = ev.next
                for (e in ev.events) if (e.address.isNotEmpty() && e.kind.isNotEmpty()) lastKind[e.address] = e.kind
                if (ev.events.isNotEmpty()) refreshList()
            } catch (e: ApiException) {
                if (e.code == "unauthorized") { tokenRefused(); return }
                delay(5_000)
            }
        }
    }

    fun toggleSelected(address: String) {
        if (!selected.remove(address) && selected.size < 4) selected.add(address)
    }

    fun view(address: String) = views.getOrPut(address) { PaneState(address) }

    /** What the lens says about a pane right now: waiting for you, working, or quiet. */
    fun status(pane: Pane, now: Long): Lens.Status {
        val changed = views[pane.address]?.let { now - it.changedAt in 0..Lens.RECENT_SECONDS } == true
        return Lens.status(pane, lastKind[pane.address], changed)
    }

    /** One read of a pane; a 304 leaves the content alone. */
    suspend fun pollOnce(address: String) {
        val v = view(address)
        try {
            val r = withContext(Dispatchers.IO) { api().read(address, settings.lines, v.etag) }
            if (r != null) {
                v.pane = r.pane
                if (r.content != v.content) v.changedAt = System.currentTimeMillis() / 1000
                v.content = r.content
                v.etag = r.etag
            }
            v.error = null
            v.updated = LocalTime.now().format(CLOCK)
        } catch (e: ApiException) {
            v.error = e.message
            if (e.code == "unauthorized") tokenRefused()
        }
    }

    fun type(address: String, text: String, enter: Boolean): Boolean {
        val clean = Input.typed(text)
        val sent = send(address) {
            if (clean.isNotEmpty()) it.type(address, clean)
            if (enter) it.key(address, "Enter")
        }
        if (sent && enter && '\n' !in clean && view(address).pane?.let { Commands.isShell(it.command) } == true) remember(clean)
        return sent
    }

    private fun remember(line: String) {
        val next = Commands.remembered(recent, line)
        if (next == recent.toList()) return
        recent.clear()
        recent.addAll(next)
        store.saveRecent(next)
    }

    /**
     * Fit: asks the host to size the pane to [cols] × [rows], debounced, once per distinct size,
     * retried after the hub's one-per-second limit. The answer is the pane as listed afterwards.
     */
    fun fit(address: String, cols: Int, rows: Int, again: Boolean = false) {
        val v = view(address)
        val key = "${cols}x$rows"
        // Once per size, unless asked again, or the host drifted from it (another terminal sized
        // the window): then once more, after twenty seconds, and no further on its own, so two
        // clients never fight over a window; the header shows the drift and a tap sends again.
        val drifted = v.pane?.let { it.width != cols || it.height != rows } == true
        val stale = System.currentTimeMillis() - v.fitAt > 20_000
        if (again) v.fitRetried = false
        if (v.fitKey == key && !again && !(drifted && stale && !v.fitRetried)) return
        if (v.fitKey == key && !again) v.fitRetried = true
        v.fitJob?.cancel()
        v.fitJob = viewModelScope.launch {
            delay(300)
            var tries = 0
            while (true) {
                try {
                    val p = withContext(Dispatchers.IO) { api().resize(address, cols, rows) }
                    v.fitKey = key
                    v.fitAt = System.currentTimeMillis()
                    v.fitted = true
                    v.pane = p
                    v.etag = null
                    pollOnce(address)
                    return@launch
                } catch (e: ApiException) {
                    if (e.code == "rate_limited" && tries++ < 3) { delay(1100); continue }
                    v.error = e.message
                    if (e.code == "unauthorized") tokenRefused()
                    return@launch
                } catch (e: IllegalArgumentException) {
                    return@launch
                }
            }
        }
    }

    /** Gives the pane back to tmux, if this app ever sized it. */
    fun release(address: String) {
        val v = views[address] ?: return
        v.fitJob?.cancel()
        v.fitKey = ""
        v.fitAt = 0L
        v.fitRetried = false
        if (!v.fitted) return
        v.fitted = false
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { api().release(address) } }
            v.etag = null
        }
    }

    /** A tap on the pane: a left click at the cell, when the pane's program has the mouse. */
    fun click(address: String, x: Int, y: Int) = mouse(address, "left", x, y)

    /** One wheel notch at the cell; the smooth way to scroll a program that has the mouse. */
    fun wheel(address: String, up: Boolean, x: Int, y: Int) = mouse(address, if (up) "wheelup" else "wheeldown", x, y)

    private fun mouse(address: String, button: String, x: Int, y: Int) = send(address) {
        if (view(address).pane?.mouse == false) throw ApiException(400, "no_mouse_pane")
        try {
            it.mouse(address, button, x, y)
        } catch (e: ApiException) {
            // An older hub refuses the field; a pane without mouse mode refuses the input.
            throw when (e.code) {
                "bad_json" -> ApiException(e.status, "no_mouse_hub")
                "bad_input" -> ApiException(e.status, "no_mouse_pane")
                else -> e
            }
        }
    }

    /** False when the pane is still busy with the previous send. */
    fun key(address: String, name: String) = send(address) { it.key(address, name) }

    /**
     * A spoken clip to the hub's transcriber; [heard] gets the words, which the bar shows and
     * never sends by itself. A refusal lands in [sendError] like a refused send. False while
     * the pane is still busy with a send.
     */
    fun transcribe(address: String, wav: ByteArray, heard: (String) -> Unit): Boolean {
        val v = view(address)
        if (v.busy) return false
        viewModelScope.launch {
            v.busy = true
            sendError = null
            try {
                val text = withContext(Dispatchers.IO) { api().transcribe(wav) }
                if (text.isNotEmpty()) heard(text)
            } catch (e: ApiException) {
                sendError = when (e.code) {
                    "rate_limited" -> "One clip every five seconds"
                    "bad_input" -> "Too short"
                    else -> e.message
                }
                if (e.code == "unauthorized") tokenRefused()
            } catch (e: IllegalArgumentException) {
                sendError = "Too short"
            } finally {
                v.busy = false
            }
        }
        return true
    }

    /** Swaps two open panes' places. */
    fun swapTiles(addresses: List<String>, a: String, b: String) {
        val i = addresses.indexOf(a)
        val j = addresses.indexOf(b)
        if (i < 0 || j < 0 || i == j) return
        screen = Screen.View(addresses.toMutableList().also { it[i] = b; it[j] = a })
    }

    /** Replaces one open pane by another, keeping the others. */
    fun swap(addresses: List<String>, at: Int, address: String) {
        screen = Screen.View(addresses.mapIndexed { i, a -> if (i == at) address else a })
    }

    private fun send(address: String, action: (Api) -> Unit): Boolean {
        val v = view(address)
        if (v.busy) return false
        viewModelScope.launch {
            v.busy = true
            sendError = null
            try {
                withContext(Dispatchers.IO) { action(api()) }
                v.etag = null
                pollOnce(address)
            } catch (e: ApiException) {
                sendError = e.message
                if (e.code == "unauthorized") tokenRefused()
            } catch (e: IllegalArgumentException) {
                sendError = "Nothing to send"
            } finally {
                v.busy = false
            }
        }
        return true
    }

    /**
     * A 401 is final: the token is wrong, expired or revoked. It is deleted so nothing
     * retries it, which would only run the hub's lockout up for this network.
     */
    private fun tokenRefused() {
        api = null
        probed = false
        transcriber = false
        vault.clear()
        hasToken = false
        views.clear()
        panes = emptyList()
        notice = "The hub refused the stored token, so it was forgotten. Paste or scan a new one."
        if (screen !is Screen.Connect) screen = Screen.Connect
        AttentionService.apply(getApplication())
    }

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
