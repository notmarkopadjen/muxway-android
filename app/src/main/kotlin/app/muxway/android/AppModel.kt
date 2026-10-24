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
import app.muxway.android.protocol.AdminClient
import app.muxway.android.protocol.Api
import app.muxway.android.protocol.ApiException
import app.muxway.android.protocol.Commands
import app.muxway.android.protocol.Host
import app.muxway.android.protocol.HubUrl
import app.muxway.android.protocol.Input
import app.muxway.android.protocol.Lens
import app.muxway.android.protocol.LogLine
import app.muxway.android.protocol.Pane
import app.muxway.android.protocol.Setup
import app.muxway.android.store.Hub
import app.muxway.android.store.Hubs
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
    var hasToken by mutableStateOf(vault.hasToken(settings.current))
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

    /** Whether the token carries the admin grant, asked with the transcriber; Settings then shows the admin section. */
    var admin by mutableStateOf(false)
        private set
    var adminLog by mutableStateOf(listOf<LogLine>())
        private set
    var adminClients by mutableStateOf(listOf<AdminClient>())
        private set
    var adminError by mutableStateOf<String?>(null)
        private set
    var adminLoading by mutableStateOf(false)
        private set

    /** The last lines sent to a shell, newest first; the command row offers them again. */
    val recent = mutableStateListOf<String>().also { it.addAll(store.recent()) }

    /** Why the Connect screen is showing, when the app put the user there. */
    var notice by mutableStateOf<String?>(null)

    /** A scanned code for a hub that already has a token, until the person says which to keep. */
    var pendingCode by mutableStateOf<Pair<Setup.Code, Hub>?>(null)
        private set

    /** A pane a link asked for, opened once the listing confirms it exists. */
    private var pendingOpen: String? = null

    /** The host a new session is being named for, while its dialog shows. */
    var naming by mutableStateOf<String?>(null)
    var creating by mutableStateOf(false)
        private set
    var createError by mutableStateOf<String?>(null)

    init {
        AttentionService.apply(app)
    }

    private fun ready() = settings.hubUrl.isNotEmpty() && hasToken

    private fun api(): Api {
        api?.let { return it }
        val token = vault.token(settings.current) ?: throw ApiException(401, "unauthorized")
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

    /** Saves the preferences; the hubs are changed through [saveHub], [useHub] and [removeHub]. */
    fun saveSettings(s: Settings) {
        settings = s.copy(hubs = settings.hubs, current = settings.current)
        store.save(settings)
        AttentionService.apply(getApplication())
    }

    fun hasTokenFor(id: String) = vault.hasToken(id)

    /**
     * Adds a hub, or with [id] changes one, and switches to it. A token given replaces the one
     * stored; a name left empty comes from the host. Returns a message when something is wrong.
     */
    fun saveHub(id: String?, name: String, url: String, newToken: String): String? {
        val (u, why) = HubUrl.check(url)
        if (u == null) return why
        val others = settings.hubs.filter { it.id != id }
        val n = name.trim().ifEmpty { Hubs.defaultName(u, others) }
        if (!Hubs.NAME.matches(n)) return "Name: a-z, 0-9 and -, up to 32"
        if (others.any { it.name == n }) return "That name is taken"
        if (others.any { it.url == u }) return "That hub is in the list"
        if (id == null && settings.hubs.size >= Hubs.MAX) return "Up to ${Hubs.MAX} hubs"
        val tok = newToken.trim()
        if (tok.isNotEmpty() && (Input.printable(tok) != tok || tok.length > 200)) return "That is not a token"
        val hubId = id ?: Hubs.newId(settings.hubs)
        if (tok.isEmpty() && !vault.hasToken(hubId)) return "Paste a token"
        if (tok.isNotEmpty()) vault.store(hubId, tok)
        val hub = Hub(hubId, n, u)
        settings = settings.copy(hubs = if (id == null) settings.hubs + hub else settings.hubs.map { if (it.id == id) hub else it })
        useHub(hubId)
        return null
    }

    /** Makes [id] the hub the app talks to: the client, the list and the watch follow. */
    fun useHub(id: String) {
        val hub = settings.hubs.firstOrNull { it.id == id } ?: return
        settings = settings.copy(current = id)
        store.save(settings)
        reset()
        hasToken = vault.hasToken(id)
        AttentionService.apply(getApplication())
        if (hasToken) {
            notice = null
            open(Screen.Panes)
        } else {
            notice = "Paste or scan a token for ${hub.name}."
            screen = Screen.Connect
        }
    }

    /** Drops a hub and its token; the next in the list takes over when it was the current one. */
    fun removeHub(id: String) {
        vault.clear(id)
        val wasCurrent = settings.current == id
        val hubs = settings.hubs.filter { it.id != id }
        settings = settings.copy(hubs = hubs, current = if (wasCurrent) hubs.firstOrNull()?.id ?: "" else settings.current)
        store.save(settings)
        if (wasCurrent) reset()
        hasToken = settings.current.isNotEmpty() && vault.hasToken(settings.current)
        notice = null
        AttentionService.apply(getApplication())
        if (!ready()) screen = Screen.Connect
    }

    private fun reset() {
        api = null
        probed = false
        transcriber = false
        admin = false
        adminLog = emptyList()
        adminClients = emptyList()
        views.clear()
        lastKind.clear()
        panes = emptyList()
        hosts = emptyList()
        listError = null
        selected.clear()
        cursor = null
    }

    /**
     * A scanned connect code adds a hub, or gives a known one its token; hub and token go
     * through the same checks as typed ones. A hub that has a token already is not changed
     * until [replaceWithCode] says so.
     */
    fun connectWithCode(text: String): String? {
        val code = Setup.parse(text) ?: return "That is not a muxway code"
        val (u, why) = HubUrl.check(code.hub)
        if (u == null) return why
        val have = settings.hubs.firstOrNull { it.url == u }
        if (have != null && vault.hasToken(have.id)) {
            pendingCode = code to have
            return null
        }
        return saveHub(have?.id, have?.name ?: "", u, code.token)
    }

    fun replaceWithCode(): String? {
        val (code, hub) = pendingCode ?: return null
        pendingCode = null
        return saveHub(hub.id, hub.name, hub.url, code.token)
    }

    fun keepToken() {
        val (_, hub) = pendingCode ?: return
        pendingCode = null
        useHub(hub.id)
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
                    admin = withContext(Dispatchers.IO) { api().isAdmin() }
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

    /** The hub's last 200 log lines and who is connected, for the admin section. */
    fun refreshAdmin() {
        if (!admin || adminLoading) return
        viewModelScope.launch {
            adminLoading = true
            try {
                val (log, clients) = withContext(Dispatchers.IO) { api().adminLog() to api().adminClients() }
                adminLog = log
                adminClients = clients
                adminError = null
            } catch (e: ApiException) {
                adminError = e.message
                if (e.code == "forbidden") admin = false
                if (e.code == "unauthorized") tokenRefused()
            } finally {
                adminLoading = false
            }
        }
    }

    fun endSession(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api().endSession(id) }
            } catch (e: ApiException) {
                adminError = e.message
            }
            refreshAdmin()
        }
    }

    /** One more pane in the window of [pane], beside a wide pane or below a tall one; it opens. */
    fun splitWindow(pane: Pane) = create(inDialog = false) {
        it.split(pane.host, pane.session, pane.windowIndex, if (pane.width > 2 * pane.height) "h" else "v")
    }

    /** A session named by the person on [host]; its pane opens and the dialog closes. */
    fun startSession(host: String, name: String) {
        val n = name.trim()
        if (!Api.SESSION_NAME.matches(n)) {
            createError = "a-z, 0-9, _ and -, up to 32"
            return
        }
        create(inDialog = true) { it.newSession(host, n) }
    }

    private fun create(inDialog: Boolean, action: (Api) -> Pane) {
        if (creating) return
        viewModelScope.launch {
            creating = true
            createError = null
            try {
                val pane = withContext(Dispatchers.IO) { action(api()) }
                naming = null
                open(Screen.View(listOf(pane.address)))
            } catch (e: ApiException) {
                if (inDialog) createError = e.message else listError = e.message
                if (e.code == "unauthorized") tokenRefused()
            } finally {
                creating = false
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
        admin = false
        adminLog = emptyList()
        adminClients = emptyList()
        vault.clear(settings.current)
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
