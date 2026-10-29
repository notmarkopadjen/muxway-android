package app.muxway.android

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.muxway.android.protocol.AddMenu
import app.muxway.android.protocol.AdminClient
import app.muxway.android.protocol.Api
import app.muxway.android.protocol.ApiException
import app.muxway.android.files.Attachment
import app.muxway.android.files.Attachments
import app.muxway.android.protocol.Commands
import app.muxway.android.protocol.Files
import app.muxway.android.protocol.Host
import app.muxway.android.protocol.HubUrl
import app.muxway.android.protocol.Input
import app.muxway.android.protocol.Keys
import app.muxway.android.protocol.Lens
import app.muxway.android.protocol.LogLine
import app.muxway.android.protocol.Pane
import app.muxway.android.protocol.Setup
import app.muxway.android.protocol.Wheel
import app.muxway.android.protocol.WheelQueue
import app.muxway.android.store.Hub
import app.muxway.android.store.Hubs
import app.muxway.android.store.Settings
import app.muxway.android.store.SettingsStore
import app.muxway.android.store.Vault
import app.muxway.android.watch.AttentionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

sealed class Screen {
    data object Connect : Screen()
    data object Panes : Screen()
    data object Scan : Screen()
    data object Share : Screen()
    /** The open panes by key (see [Keys]), tiles first in their order. */
    data class View(val addresses: List<String>) : Screen()
}

/** The host a session is being named for, in its dialog; [into] is the view that asked, if one did. */
data class Naming(val hub: String, val host: String, val into: List<String>? = null)

/** One open pane, by its key: what was last read and how it went. */
class PaneState(val key: String) {
    /** The address its hub knows it by. */
    val address = Keys.address(key)
    var pane by mutableStateOf<Pane?>(null)
    var content by mutableStateOf("")
    /** Blank rows at the bottom of the screen the host left out of [content]. */
    var trimmed by mutableIntStateOf(0)
    var etag: String? = null
    var error by mutableStateOf<String?>(null)
    var updated by mutableStateOf("")
    var busy by mutableStateOf(false)

    /** When the content last changed, in seconds; the lens counts a recent change as working. */
    var changedAt by mutableLongStateOf(0L)

    /** Whether anything has been read yet, and when this client last typed into or sized the pane. */
    var read = false
    var ownAt = 0L

    /** Fit: the last size sent to the host, when, and whether a release is owed. */
    var fitKey = ""
    var fitAt = 0L
    var fitRetried = false
    var fitted by mutableStateOf(false)
    var fitJob: Job? = null

    /** What another state of the same pane carries over when its key changes. */
    fun take(o: PaneState) {
        pane = o.pane; content = o.content; trimmed = o.trimmed; etag = o.etag; error = o.error; updated = o.updated
        changedAt = o.changedAt; read = o.read; ownAt = o.ownAt
        fitKey = o.fitKey; fitAt = o.fitAt; fitRetried = o.fitRetried; fitted = o.fitted
    }

    /** Files on their way to the host's inbox: the one going, the ones waiting, the paths that landed. */
    var sending by mutableStateOf<String?>(null)
    val queued = ArrayDeque<Uri>()
    var fileJob: Job? = null
    var landed by mutableStateOf(listOf<String>())

    /** Wheel notches behind the request in flight, the pump draining them, the read it started. */
    val wheel = WheelQueue()
    var wheelJob: Job? = null
    var wheelRead: Job? = null
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val vault = Vault(app)
    private val userAgent = "muxway-android/" + BuildConfig.VERSION_NAME

    var settings by mutableStateOf(store.load())
        private set

    /** Whether any hub has a token: the app has somewhere to list from. */
    var hasToken by mutableStateOf(false)
        private set

    /**
     * One hub the app talks to: its client, its listing and what it answered once. Every hub
     * with a token is in use at the same time; a pane's key says which one it belongs to.
     */
    inner class Conn(val hub: Hub) {
        private var api: Api? = null
        var hosts by mutableStateOf(listOf<Host>())
        var panes by mutableStateOf(listOf<Pane>())
        var listError by mutableStateOf<String?>(null)
        var listing by mutableStateOf(false)
        var listJob: Job? = null
        var cursor: Long? = null
        var probed = false

        /** Whether the hub has a transcriber, asked once per connection with the first listing. */
        var transcriber by mutableStateOf(false)

        /** Whether the token carries the admin grant, asked with the transcriber; Settings then shows the admin section. */
        var admin by mutableStateOf(false)
        var adminLog by mutableStateOf(listOf<LogLine>())
        var adminClients by mutableStateOf(listOf<AdminClient>())
        var adminError by mutableStateOf<String?>(null)
        var adminLoading by mutableStateOf(false)

        /** Whether the hub takes a wheel count; a hub from before the field says no once, then it is sent one notch at a time. */
        var wheelCounts = true

        val id get() = hub.id
        val name get() = hub.name

        fun api(): Api {
            api?.let { return it }
            val token = vault.token(hub.id)
                ?: throw if (vault.hasToken(hub.id)) ApiException(0, "keystore") else ApiException(401, "unauthorized")
            return Api(hub.url, token, userAgent).also { api = it }
        }

        fun forget() {
            api = null
            probed = false
            wheelCounts = true
            transcriber = false
            admin = false
            adminLog = emptyList()
            adminClients = emptyList()
            panes = emptyList()
            hosts = emptyList()
            listError = null
            cursor = null
        }
    }

    private val conns = LinkedHashMap<String, Conn>()

    /** The hubs with a token, in the list's order; a connection is made the first time it is asked for. */
    val connections: List<Conn>
        get() = settings.hubs.filter { vault.hasToken(it.id) }.map { h ->
            conns[h.id]?.takeIf { it.hub == h } ?: Conn(h).also { conns[h.id] = it }
        }

    var screen by mutableStateOf<Screen>(Screen.Connect)
        private set

    val hosts: List<Host> get() = connections.flatMap { it.hosts }
    val panes: List<Pane> get() = connections.flatMap { it.panes }

    /** Every hub's listing trouble on one line, each named when there are several. */
    val listError: String?
        get() {
            val c = connections
            return c.mapNotNull { x -> x.listError?.let { if (c.size > 1) "${x.name}: $it" else it } }.joinToString(" · ").ifEmpty { null }
        }
    val listing: Boolean get() = connections.any { it.listing }
    val selected = mutableStateListOf<String>()

    val views = mutableStateMapOf<String, PaneState>()

    /** The kind of the last event a hub sent for each pane, by key; `working` is what the lens wants. */
    val lastKind = mutableStateMapOf<String, String>()
    var sendError by mutableStateOf<String?>(null)

    /** The hubs whose token carries the admin grant; Settings shows a section for each. */
    val adminHubs: List<Conn> get() = connections.filter { it.admin }

    /** The last lines sent to a shell, newest first; the command row offers them again. */
    val recent = mutableStateListOf<String>().also { it.addAll(store.recent()) }

    /** Why the Connect screen is showing, when the app put the user there. */
    var notice by mutableStateOf<String?>(null)

    /** A scanned code for a hub that already has a token, until the person says which to keep. */
    var pendingCode by mutableStateOf<Pair<Setup.Code, Hub>?>(null)
        private set

    /** A pane a link asked for, opened once the listing confirms it exists. */
    private var pendingOpen: String? = null

    /** Whether a pane takes files, asked once per pane and kept for a minute, as the browser does. */
    private val inbox = mutableStateMapOf<String, Boolean>()
    private val inboxAt = HashMap<String, Long>()

    /** The files another app shared, until a pane is picked for them or the share is dropped. */
    var sharing by mutableStateOf(listOf<Uri>())
        private set

    /** The host a new session is being named for, while its dialog shows. */
    var naming by mutableStateOf<Naming?>(null)
    var creating by mutableStateOf(false)
        private set
    var createError by mutableStateOf<String?>(null)

    init {
        hasToken = settings.hubs.any { vault.hasToken(it.id) }
        screen = if (ready()) Screen.Panes else Screen.Connect
        AttentionService.apply(app)
    }

    private fun ready() = hasToken

    /**
     * The connection a key belongs to; a bare address is the first hub's. A hub without a
     * token still answers, with a client that refuses every call, so nothing here throws.
     */
    private fun conn(key: String): Conn {
        val c = connections
        c.firstOrNull { it.id == Keys.hub(key) }?.let { return it }
        if (Keys.hub(key).isEmpty()) c.firstOrNull()?.let { return it }
        val hub = settings.hubs.firstOrNull { it.id == Keys.hub(key) } ?: settings.hubs.firstOrNull() ?: Hub("", "", "")
        return conns[hub.id]?.takeIf { it.hub == hub } ?: Conn(hub).also { if (hub.id.isNotEmpty()) conns[hub.id] = it }
    }

    private fun api(key: String): Api = conn(key).api()

    /** The hub's name for a key, for the menu and the list; "" with one hub. */
    fun hubName(id: String): String = settings.hubs.firstOrNull { it.id == id }?.name ?: ""

    fun open(s: Screen) {
        if (s is Screen.Panes) { selected.clear(); refreshList() }
        if (s !is Screen.Share) sharing = emptyList()
        screen = s
    }

    /** Back: pane view, settings and the share chooser return to the list; false when there is nowhere to go. */
    fun back(): Boolean {
        if (screen is Screen.Panes || (screen is Screen.Connect && !ready())) return false
        open(if (screen is Screen.Scan) Screen.Connect else Screen.Panes)
        return true
    }

    /**
     * Files another app shared: the chooser opens for a pane to take them, up to ten. Without
     * a hub and a token the Connect screen says why.
     */
    fun share(uris: List<Uri>) {
        if (!ready()) {
            notice = "Connect to a hub first, then share again."
            screen = Screen.Connect
            return
        }
        sharing = uris.take(MAX_SHARE)
        screen = Screen.Share
        refreshList()
    }

    /** The shared files go to this pane, which opens; their paths land on its line. */
    fun shareInto(address: String) {
        val uris = sharing
        sharing = emptyList()
        if (uris.isNotEmpty()) sendFiles(address, uris)
        open(Screen.View(listOf(address)))
    }

    /** Saves the preferences; the hubs are changed through [saveHub] and [removeHub]. */
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
        val (u, why) = HubUrl.check(url, BuildConfig.DEBUG)
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
        settings = settings.copy(hubs = if (id == null) settings.hubs + hub else settings.hubs.map { if (it.id == id) hub else it }, current = settings.current.ifEmpty { hubId })
        store.save(settings)
        // A changed hub starts over: its client, listing and views are built again.
        conns.remove(hubId)
        dropHub(hubId)
        hasToken = settings.hubs.any { vault.hasToken(it.id) }
        AttentionService.apply(getApplication())
        notice = null
        if (ready()) open(Screen.Panes)
        return null
    }

    /** Drops a hub and its token; the others go on. */
    fun removeHub(id: String) {
        vault.clear(id)
        val hubs = settings.hubs.filter { it.id != id }
        settings = settings.copy(hubs = hubs, current = if (settings.current == id) hubs.firstOrNull()?.id ?: "" else settings.current)
        store.save(settings)
        conns.remove(id)
        dropHub(id)
        hasToken = hubs.any { vault.hasToken(it.id) }
        notice = null
        AttentionService.apply(getApplication())
        if (!ready()) screen = Screen.Connect
    }

    /** Forgets everything held for one hub: its views, events, inbox answers and selection. */
    private fun dropHub(id: String) {
        for (k in views.keys.toList()) if (Keys.hub(k) == id) views.remove(k)
        for (k in lastKind.keys.toList()) if (Keys.hub(k) == id) lastKind.remove(k)
        for (k in inbox.keys.toList()) if (Keys.hub(k) == id) { inbox.remove(k); inboxAt.remove(k) }
        selected.removeAll { Keys.hub(it) == id }
        (screen as? Screen.View)?.let { v ->
            val rest = v.addresses.filter { Keys.hub(it) != id }
            if (rest.size != v.addresses.size) screen = if (rest.isEmpty()) Screen.Panes else Screen.View(rest)
        }
    }

    /**
     * A scanned connect code adds a hub, or gives a known one its token; hub and token go
     * through the same checks as typed ones. A hub that has a token already is not changed
     * until [replaceWithCode] says so.
     */
    fun connectWithCode(text: String): String? {
        val code = Setup.parse(text) ?: return "That is not a muxway code"
        val (u, why) = HubUrl.check(code.hub, BuildConfig.DEBUG)
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
        pendingCode = null
        if (ready()) open(Screen.Panes)
    }

    /**
     * `muxway://open?pane=<key>`, from a notification or the watch; a bare address is the
     * first hub's. Only a well-formed key of a listed pane opens anything; the link can set
     * nothing else.
     */
    fun openLink(uri: Uri?) {
        if (uri == null || uri.scheme != "muxway" || uri.host != "open" || !ready()) return
        val given = uri.getQueryParameter("pane") ?: return
        if (!Keys.valid(given)) return
        val first = connections.firstOrNull() ?: return
        val key = if (Keys.hub(given).isEmpty()) Keys.make(first.id, given) else given
        pendingOpen = key
        if (panes.any { it.key == key }) openPending() else refreshList()
    }

    private fun openPending() {
        val key = pendingOpen ?: return
        if (panes.none { it.key == key }) return
        pendingOpen = null
        open(Screen.View(listOf(key)))
    }

    /** Lists every hub; each answers on its own. */
    fun refreshList() {
        for (c in connections) refreshList(c)
    }

    private fun refreshList(c: Conn) {
        if (c.listJob?.isActive == true) return
        c.listJob = viewModelScope.launch {
            c.listing = true
            try {
                val (h, p) = withContext(Dispatchers.IO) { c.api().hosts() to c.api().panes() }
                c.hosts = h.map { it.copy(hub = c.id) }
                c.panes = p.map { it.copy(hub = c.id) }
                c.listError = null
                if (!c.probed) {
                    c.transcriber = withContext(Dispatchers.IO) { c.api().hasTranscriber() }
                    c.admin = withContext(Dispatchers.IO) { c.api().isAdmin() }
                    c.probed = true
                }
                openPending()
            } catch (e: ApiException) {
                c.listError = e.message
                if (e.code == "unauthorized") tokenRefused(c)
            } finally {
                c.listing = false
            }
        }
    }

    /** The hub's last 200 log lines and who is connected, for its admin section. */
    fun refreshAdmin(c: Conn) {
        if (!c.admin || c.adminLoading) return
        viewModelScope.launch {
            c.adminLoading = true
            try {
                val (log, clients) = withContext(Dispatchers.IO) { c.api().adminLog() to c.api().adminClients() }
                c.adminLog = log
                c.adminClients = clients
                c.adminError = null
            } catch (e: ApiException) {
                c.adminError = e.message
                if (e.code == "forbidden") c.admin = false
                if (e.code == "unauthorized") tokenRefused(c)
            } finally {
                c.adminLoading = false
            }
        }
    }

    fun endSession(c: Conn, id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { c.api().endSession(id) }
            } catch (e: ApiException) {
                c.adminError = e.message
            }
            refreshAdmin(c)
        }
    }

    /**
     * One more pane in the window of [pane], beside a wide pane or below a tall one. It opens
     * alone, or as one more tile of the view [into] when the view asked.
     */
    fun splitWindow(pane: Pane, into: List<String>? = null) = create(pane.hub, inDialog = false, into = into) {
        it.split(pane.host, pane.session, pane.windowIndex, if (pane.width > 2 * pane.height) "h" else "v")
    }

    /** A session named by the person on [host]; its pane opens and the dialog closes. */
    fun startSession(hub: String, host: String, name: String, into: List<String>? = null) {
        val n = name.trim()
        if (!Api.SESSION_NAME.matches(n)) {
            createError = "a-z, 0-9, _ and -, up to 32"
            return
        }
        create(hub, inDialog = true, into = into) { it.newSession(host, n) }
    }

    private fun create(hub: String, inDialog: Boolean, into: List<String>?, action: (Api) -> Pane) {
        if (creating) return
        val c = connections.firstOrNull { it.id == hub } ?: return
        viewModelScope.launch {
            creating = true
            createError = null
            try {
                val pane = withContext(Dispatchers.IO) { action(c.api()) }.copy(hub = c.id)
                naming = null
                open(Screen.View(addTile(into.orEmpty(), pane.key)))
            } catch (e: ApiException) {
                if (inDialog) createError = e.message else c.listError = e.message
                if (e.code == "unauthorized") tokenRefused(c)
            } finally {
                creating = false
            }
        }
    }

    /** [key] as one more pane of the view [open]; at four tiles it takes the first tile's place. */
    private fun addTile(open: List<String>, key: String): List<String> =
        if (key in open) open else if (open.size < AddMenu.MAX_TILES) open + key else listOf(key) + open.drop(1)

    /** The keys of every hub with a token, as a word, so the follow starts over when the set changes. */
    val connectionIds: String get() = connections.joinToString(" ") { it.id }

    /** Holds a long poll on every hub's events; any event re-lists that hub. */
    suspend fun followEvents() = coroutineScope {
        for (c in connections) launch { follow(c) }
    }

    private suspend fun follow(c: Conn) {
        while (true) {
            try {
                val ev = withContext(Dispatchers.IO) { c.api().events(c.cursor, 30) }
                c.cursor = ev.next
                for (e in ev.events) if (e.address.isNotEmpty() && e.kind.isNotEmpty()) lastKind[Keys.make(c.id, e.address)] = e.kind
                if (ev.events.isNotEmpty()) refreshList(c)
            } catch (e: ApiException) {
                if (e.code == "unauthorized") { tokenRefused(c); return }
                delay(5_000)
            }
        }
    }

    fun toggleSelected(key: String) {
        if (!selected.remove(key) && selected.size < AddMenu.MAX_TILES) selected.add(key)
    }

    fun view(key: String) = views.getOrPut(key) { PaneState(key) }

    /** What the lens says about a pane right now: waiting for you, working, or quiet. */
    fun status(pane: Pane, now: Long): Lens.Status {
        val changed = views[pane.key]?.let { now - it.changedAt in 0..Lens.RECENT_SECONDS } == true
        return Lens.status(pane, lastKind[pane.key], changed)
    }

    /** Whether the pane's hub has a transcriber: the microphone shows. */
    fun transcriber(key: String): Boolean = connections.firstOrNull { it.id == Keys.hub(key) }?.transcriber == true

    /** One read of a pane; a 304 leaves the content alone. */
    suspend fun pollOnce(key: String) {
        val v = view(key)
        try {
            val r = withContext(Dispatchers.IO) { api(key).read(v.address, settings.lines, v.etag) }
            if (r != null) {
                val at = System.currentTimeMillis() / 1000
                v.pane = r.pane.copy(hub = Keys.hub(key))
                if (r.content != v.content && Lens.changeCounts(!v.read, v.ownAt, at)) v.changedAt = at
                v.content = r.content
                v.trimmed = r.trimmed
                v.etag = r.etag
                v.read = true
            }
            v.error = null
            v.updated = LocalTime.now().format(CLOCK)
        } catch (e: ApiException) {
            v.error = e.message
            if (e.code == "unauthorized") tokenRefused(conn(key))
            // The pane exited on the host: its tile has nothing left to show.
            if (e.code == "not_found") drop(key)
        }
    }

    fun type(key: String, text: String, enter: Boolean): Boolean {
        val clean = Input.typed(text)
        val sent = send(key) { api, address ->
            if (clean.isNotEmpty()) api.type(address, clean)
            if (enter) api.key(address, "Enter")
        }
        if (sent && enter && '\n' !in clean && view(key).pane?.let { Commands.isShell(it.command) } == true) remember(clean)
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
     * A pane alone in the view sizes its [window] instead, with the pane [zoom]ed over its
     * siblings, so none of them is squeezed.
     */
    /**
     * With a [layout] the whole window is laid out as the tiles are, [mates] being the tiles'
     * keys, re-read after; when the host moved panes the tiles follow them by id.
     */
    fun fit(key: String, cols: Int, rows: Int, again: Boolean = false, window: Boolean = false, zoom: Boolean = false, layout: String? = null, mates: List<String> = emptyList()) {
        val v = view(key)
        val sizeKey = "${cols}x$rows${if (window) " window" else ""}${if (zoom) " zoom" else ""}${layout?.let { " $it" } ?: ""}"
        // Once per size, unless asked again, or the host drifted from it (another terminal sized
        // the window): then once more, after twenty seconds, and no further on its own, so two
        // clients never fight over a window; the header shows the drift and a tap sends again.
        // With a layout the pane's size is its tile's, not the window's: no drift to read.
        val drifted = layout == null && v.pane?.let { it.width != cols || it.height != rows } == true
        val stale = System.currentTimeMillis() - v.fitAt > 20_000
        if (again) v.fitRetried = false
        if (v.fitKey == sizeKey && !again && !(drifted && stale && !v.fitRetried)) return
        if (v.fitKey == sizeKey && !again) v.fitRetried = true
        v.fitJob?.cancel()
        v.fitJob = viewModelScope.launch {
            delay(300)
            var tries = 0
            while (true) {
                try {
                    v.ownAt = System.currentTimeMillis() / 1000
                    val r = withContext(Dispatchers.IO) { api(key).resize(v.address, cols, rows, window, zoom, layout) }
                    v.fitKey = sizeKey
                    v.fitAt = System.currentTimeMillis()
                    v.fitted = true
                    v.pane = r.pane.copy(hub = Keys.hub(key))
                    v.etag = null
                    for (m in mates) views[m]?.let { if (it !== v) { it.ownAt = v.ownAt; it.etag = null } }
                    val moved = if (r.panes.isNotEmpty()) follow(key, r.panes) else emptyMap()
                    val lead = moved[key] ?: key
                    pollOnce(lead)
                    for (m in mates) (moved[m] ?: m).let { if (it != lead) launch { pollOnce(it) } }
                    return@launch
                } catch (e: ApiException) {
                    if (e.code == "rate_limited" && tries++ < 3) { delay(1100); continue }
                    v.error = e.message
                    if (e.code == "unauthorized") tokenRefused(conn(key))
                    return@launch
                } catch (e: IllegalArgumentException) {
                    return@launch
                }
            }
        }
    }

    /**
     * A tile gone while the view stays: the pane keeps the size it was given, so a pane shown
     * again in its place is not sized twice, and nothing is sent when it comes back at that size.
     */
    fun keep(key: String) {
        views[key]?.fitJob?.cancel()
    }

    /** Gives back every pane this view sized; the view calls it when it is left. */
    fun releaseFits() {
        for (key in views.keys.toList()) release(key)
    }

    /** Gives the pane back to tmux, if this app ever sized it. */
    /**
     * The host moved the window's panes into the layout's order, so their addresses changed: the
     * listing takes the answer, and the open tiles follow their panes by id, each tile's state
     * carried to its new key. The old keys to the new ones.
     */
    private fun follow(key: String, panes: List<Pane>): Map<String, String> {
        val c = conn(key)
        val fresh = panes.map { it.copy(hub = c.id) }
        val byId = fresh.associateBy { it.paneId }
        val before = c.panes.associate { it.key to it.paneId }
        c.panes = c.panes.map { p -> if (p.hub == c.id && p.host == fresh[0].host) byId[p.paneId] ?: p else p }
        val view = screen as? Screen.View ?: return emptyMap()
        val moved = LinkedHashMap<String, String>()
        for (a in view.addresses) {
            val id = before[a] ?: views[a]?.pane?.paneId ?: continue
            val next = byId[id]?.key ?: continue
            if (next != a) moved[a] = next
        }
        if (moved.isEmpty()) return moved
        val states = moved.keys.associateWith { views.remove(it) }
        for ((old, next) in moved) states[old]?.let { view(next).take(it) }
        screen = Screen.View(view.addresses.map { moved[it] ?: it })
        return moved
    }

    fun release(key: String) {
        val v = views[key] ?: return
        v.fitJob?.cancel()
        v.fitKey = ""
        v.fitAt = 0L
        v.fitRetried = false
        if (!v.fitted) return
        v.fitted = false
        v.ownAt = System.currentTimeMillis() / 1000
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { api(key).release(v.address) } }
            v.etag = null
        }
    }

    /** A tap on the pane: a left click at the cell, when the pane's program has the mouse. */
    fun click(key: String, x: Int, y: Int) = mouse(key, "left", x, y)

    /**
     * Wheel notches at the cell, the smooth way to scroll a program that has the mouse. They
     * queue behind the request in flight and go as one counted request each, so a long drag
     * is a few requests and the program keeps up with the hand.
     */
    fun wheel(key: String, up: Boolean, x: Int, y: Int, count: Int = 1) {
        val v = view(key)
        if (v.pane?.mouse == false) {
            sendError = ApiException.messageFor(400, "no_mouse_pane")
            return
        }
        v.wheel.add(up, x, y, count)
        if (v.wheelJob?.isActive == true) return
        v.wheelJob = viewModelScope.launch { pumpWheel(v) }
    }

    private suspend fun pumpWheel(v: PaneState) {
        // A key may be on its way; the notches wait for it.
        while (v.busy) delay(25)
        v.busy = true
        v.ownAt = System.currentTimeMillis() / 1000
        sendError = null
        val c = conn(v.key)
        try {
            do {
                while (true) {
                    val n = v.wheel.next() ?: break
                    c.wheelCounts = withContext(Dispatchers.IO) { Wheel.deliver(c.api(), v.address, n, c.wheelCounts) }
                    v.etag = null
                    // The screen follows without holding the next request up.
                    if (v.wheelRead?.isActive != true) v.wheelRead = viewModelScope.launch { pollOnce(v.key) }
                }
                v.wheelRead?.join()
                pollOnce(v.key)
            } while (v.wheel.size > 0)
        } catch (e: ApiException) {
            v.wheel.clear()
            sendError = mouseError(e)
            if (e.code == "unauthorized") tokenRefused(c)
        } finally {
            v.busy = false
        }
    }

    private fun mouse(key: String, button: String, x: Int, y: Int) = send(key) { api, address ->
        if (view(key).pane?.mouse == false) throw ApiException(400, "no_mouse_pane")
        try {
            api.mouse(address, button, x, y)
        } catch (e: ApiException) {
            throw ApiException(e.status, mouseCode(e))
        }
    }

    // An older hub refuses the field; a pane without mouse mode refuses the input.
    private fun mouseCode(e: ApiException) = when (e.code) {
        "bad_json" -> "no_mouse_hub"
        "bad_input" -> "no_mouse_pane"
        else -> e.code
    }

    private fun mouseError(e: ApiException) = ApiException.messageFor(e.status, mouseCode(e))

    /** False when the pane is still busy with the previous send. */
    fun key(key: String, name: String) = send(key) { api, address -> api.key(address, name) }

    /**
     * A spoken clip to the pane's hub's transcriber; [heard] gets the words, which the bar
     * shows and never sends by itself. A refusal lands in [sendError] like a refused send.
     * False while the pane is still busy with a send.
     */
    fun transcribe(key: String, wav: ByteArray, heard: (String) -> Unit): Boolean {
        val v = view(key)
        if (v.busy) return false
        viewModelScope.launch {
            v.busy = true
            sendError = null
            try {
                val text = withContext(Dispatchers.IO) { api(key).transcribe(wav) }
                if (text.isNotEmpty()) heard(text)
            } catch (e: ApiException) {
                sendError = when (e.code) {
                    "rate_limited" -> "One clip every five seconds"
                    "bad_input" -> "Too short"
                    else -> e.message
                }
                if (e.code == "unauthorized") tokenRefused(conn(key))
            } catch (e: IllegalArgumentException) {
                sendError = "Too short"
            } finally {
                v.busy = false
            }
        }
        return true
    }

    /** Whether the pane takes files: true after a 204, false after a refusal, null while unasked. */
    fun takesFiles(key: String): Boolean? = inbox[key]

    /** Asks the hub whether the pane takes files, unless it answered within the minute. */
    fun probeInbox(key: String) {
        val now = System.currentTimeMillis()
        if (now - (inboxAt[key] ?: 0L) < 60_000) return
        inboxAt[key] = now
        viewModelScope.launch {
            try {
                inbox[key] = withContext(Dispatchers.IO) { api(key).takesFiles(Keys.address(key)) }
            } catch (e: ApiException) {
                // No connection: ask again next time.
                inboxAt.remove(key)
            }
        }
    }

    /**
     * Files for the pane's program, one after another: read into memory, checked, sent to the
     * host's inbox, and each path lands in [PaneState.landed] for the line to take. A refusal
     * goes to [sendError] with the file's name. Nothing is typed into the pane.
     */
    fun sendFiles(key: String, uris: List<Uri>) {
        val v = view(key)
        v.queued.addAll(uris)
        if (v.fileJob?.isActive == true) return
        v.fileJob = viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            while (true) {
                val uri = v.queued.removeFirstOrNull() ?: break
                val f = when (val a = withContext(Dispatchers.IO) { Attachments.read(resolver, uri) }) {
                    is Attachment.Refused -> { sendError = "${a.name}: ${a.why}"; continue }
                    is Attachment.Ready -> a
                }
                v.sending = Files.progress(f.name, f.bytes.size.toLong())
                sendError = null
                var tries = 0
                while (true) {
                    try {
                        val sent = withContext(Dispatchers.IO) { api(key).sendFile(v.address, f.bytes, f.type) }
                        v.landed = v.landed + sent.path
                        break
                    } catch (e: ApiException) {
                        // One file every two seconds per principal: the next of a batch waits its turn.
                        if (e.code == "rate_limited" && tries++ < 3) { delay(2_100); continue }
                        sendError = "${f.name}: ${fileRefusal(e)}"
                        if (e.code == "unauthorized") { v.queued.clear(); tokenRefused(conn(key)) }
                        break
                    }
                }
                v.sending = null
            }
        }
    }

    private fun fileRefusal(e: ApiException): String = when (e.code) {
        "too_large" -> "too large, 8 MiB at most"
        "not_found" -> "no inbox on this host"
        "rate_limited" -> "one file every two seconds"
        else -> e.message?.replaceFirstChar { it.lowercase() } ?: e.code
    }

    private fun hostOf(key: String): Host? {
        val name = Keys.address(key).substringBefore('/')
        return connections.firstOrNull { it.id == Keys.hub(key) }?.hosts?.firstOrNull { it.name == name }
    }

    /** Whether the pane's host lets clients close panes; the pane must be writable as well. */
    fun hostCloses(key: String): Boolean = hostOf(key)?.close == true

    /** Whether the pane's host serves files a pane prints the paths of, to writers. */
    fun hostPreviews(key: String): Boolean = hostOf(key)?.preview == true

    /** A word for the person in the pane view that is not an error: what was copied, what was saved. */
    var told by mutableStateOf<String?>(null)

    /** A file of the host, by inbox name or by a printed path, for the sheet; null when refused. */
    fun fetchFile(key: String, name: String?, path: String?, done: (Api.Fetched?) -> Unit) {
        viewModelScope.launch {
            try {
                val address = Keys.address(key)
                val f = withContext(Dispatchers.IO) {
                    if (name != null) api(key).readFile(address, name) else api(key).previewFile(address, path ?: "")
                }
                done(f)
            } catch (e: ApiException) {
                if (e.code == "unauthorized") tokenRefused(conn(key))
                done(null)
            }
        }
    }

    /** The block's text onto the clipboard; the notice says how many rows. */
    fun copyBlock(text: String, rows: Int) {
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("muxway", text))
        told = "copied, $rows ${if (rows == 1) "row" else "rows"}"
    }

    /** Closes the pane on its host; its tile leaves, and with none left the list shows. */
    fun closePane(key: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api(key).closePane(Keys.address(key)) }
                drop(key)
                refreshList(conn(key))
            } catch (e: ApiException) {
                sendError = e.message
            }
        }
    }

    /** Takes a pane out of the view it is open in; the last tile leaves the view for the list. */
    /** Closes the tile, not the pane: its fit is given back and the view goes on without it, or back to the list. */
    fun closeTile(key: String) {
        release(key)
        drop(key)
    }

    private fun drop(key: String) {
        val view = screen as? Screen.View ?: return
        if (key !in view.addresses) return
        views.remove(key)
        val rest = view.addresses - key
        open(if (rest.isEmpty()) Screen.Panes else Screen.View(rest))
    }

    fun swapTiles(addresses: List<String>, a: String, b: String) {
        val i = addresses.indexOf(a)
        val j = addresses.indexOf(b)
        if (i < 0 || j < 0 || i == j) return
        screen = Screen.View(addresses.toMutableList().also { it[i] = b; it[j] = a })
    }

    /** Replaces one open pane by another, keeping the others. */
    fun swap(addresses: List<String>, at: Int, key: String) {
        screen = Screen.View(addresses.mapIndexed { i, a -> if (i == at) key else a })
    }

    /** One more tile: [key] joins the view, or, with four tiles on screen ([full]), takes the focused tile's place. */
    fun addPane(addresses: List<String>, focusedAt: Int, key: String, full: Boolean) {
        if (key in addresses) return
        screen = Screen.View(if (!full) addresses + key else addresses.mapIndexed { i, a -> if (i == focusedAt) key else a })
    }

    private fun send(key: String, action: (Api, String) -> Unit): Boolean {
        val v = view(key)
        if (v.busy) return false
        // What the pane prints next is this client's doing, not the program working on its own.
        v.ownAt = System.currentTimeMillis() / 1000
        viewModelScope.launch {
            v.busy = true
            sendError = null
            try {
                withContext(Dispatchers.IO) { action(api(key), v.address) }
                v.etag = null
                pollOnce(key)
            } catch (e: ApiException) {
                sendError = e.message
                if (e.code == "unauthorized") tokenRefused(conn(key))
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
     * retries it, which would only run the hub's lockout up for this network. The other
     * hubs go on; with none left the Connect screen says why.
     */
    private fun tokenRefused(c: Conn) {
        vault.clear(c.id)
        c.forget()
        conns.remove(c.id)
        dropHub(c.id)
        hasToken = settings.hubs.any { vault.hasToken(it.id) }
        notice = "${c.name} refused the stored token, so it was forgotten. Paste or scan a new one."
        if (!ready() && screen !is Screen.Connect) screen = Screen.Connect
        AttentionService.apply(getApplication())
    }

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        const val MAX_SHARE = 10
    }
}
