package app.muxway.android

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
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
import app.muxway.android.protocol.Desk
import app.muxway.android.protocol.Desks
import app.muxway.android.protocol.Feed
import app.muxway.android.protocol.Files
import app.muxway.android.protocol.Follow
import app.muxway.android.protocol.Host
import app.muxway.android.protocol.Inbox
import app.muxway.android.protocol.HubUrl
import app.muxway.android.protocol.Input
import app.muxway.android.protocol.Keys
import app.muxway.android.protocol.Lens
import app.muxway.android.protocol.Letter
import app.muxway.android.protocol.LogLine
import app.muxway.android.protocol.ChangeSet
import app.muxway.android.protocol.Listing
import app.muxway.android.protocol.Diff
import app.muxway.android.protocol.Pane
import app.muxway.android.protocol.Setup
import app.muxway.android.protocol.Share
import app.muxway.android.protocol.TalkEntry
import app.muxway.android.protocol.Wheel
import app.muxway.android.protocol.WheelQueue
import app.muxway.android.protocol.WindowLayout
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

/** The pane whose mail sheet is open; [mailId] frames one mail, [line] opens with the line focused. */
data class MailTarget(val key: String, val mailId: Long = 0, val line: Boolean = false)

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

    /** Whether a frame of its own has been read yet (the warm-up ends with it), and when this client last typed into or sized the pane. */
    var read by mutableStateOf(false)
    var ownAt = 0L

    /**
     * The pane this state is for, by the host's id (see [Follow]): learnt from the listing when the
     * view opened, or from the first read; "" until then. A read that names another pane at the
     * address is a stranger's and is not taken.
     */
    var paneId = ""

    /** A read found no pane at the address: the next listing says whether the pane moved or is gone. */
    var missing = false

    /** Fit: the last size sent to the host, when, and whether a release is owed. */
    var fitKey = ""
    var fitAt = 0L
    var fitRetried = false
    var fitted by mutableStateOf(false)
    var fitJob: Job? = null

    /** What another state of the same pane carries over when its key changes. */
    fun take(o: PaneState) {
        pane = o.pane; content = o.content; trimmed = o.trimmed; etag = o.etag; error = o.error; updated = o.updated
        changedAt = o.changedAt; read = o.read; ownAt = o.ownAt; paneId = o.paneId
        fitKey = o.fitKey; fitAt = o.fitAt; fitRetried = o.fitRetried; fitted = o.fitted
    }

    /** Git's view of the pane's repository, when the host shows one; when it said there is none, until when not to ask again. */
    var changes by mutableStateOf<ChangeSet?>(null)
    var changesError by mutableStateOf<String?>(null)
    var noRepoUntil = 0L

    /** The pane's directory as the host listed it, for the names on its screen; the directory it was listed for, its tag, and when not to ask again. */
    var listing by mutableStateOf<Listing?>(null)
    var listingCwd: String? = null
    var listingEtag: String? = null
    var noDirUntil = 0L

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

        /** When a read last asked for the listing (a stranger, a 404), so a window of tiles asks once. */
        var askedAt = 0L

        /**
         * The hub's desk, the view its clients share: as last read (the list's row and the view
         * watch it), its tag, what the hub has as last read by the view or answered to a write (the
         * phone writes nothing the hub has and applies nothing it shows), the revision of the
         * phone's own last write, whether the hub has desks at all, and whether the token may
         * write it (not after `read_only`).
         */
        var desk by mutableStateOf<Desk?>(null)
        var deskEtag: String? = null
        var deskKnown: Desk? = null
        var deskRev = 0L
        var deskAsked = false
        var deskWritable = true
        var deskJob: Job? = null
        var deskPending: Desk? = null

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

        /** What panes said to panes on this hub, the last hundred entries, oldest first. */
        var talk by mutableStateOf(listOf<TalkEntry>())

        /** Whether the hub shares panes: null until asked, false on a hub older than shares; and the token's live shares. */
        var sharing by mutableStateOf<Boolean?>(null)
        var shares by mutableStateOf(listOf<Share>())

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
            talk = emptyList()
            sharing = null
            shares = emptyList()
            listError = null
            cursor = null
            desk = null
            deskEtag = null
            deskKnown = null
            deskRev = 0L
            deskAsked = false
            deskWritable = true
            deskPending = null
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

    /** Whether the inbox sheet is open, and what every hub's `/v1/inbox` answered while it was. */
    var inboxOpen by mutableStateOf(false)
    var inboxRows by mutableStateOf<List<Pane>?>(null)
        private set

    /** The panes that wait for you, on every hub, oldest first, from the listings held here. */
    val waiting: List<Pane> get() = Inbox.waiting(panes)

    /** Every hub's talk feed as one list, oldest first, as the listings brought it. */
    val talk: List<TalkEntry> get() = Feed.merge(connections.map { it.talk })

    /** The newest entry looked at, per hub; the header counts what came after it. */
    private var talkSeen by mutableStateOf(store.talkSeen())
    val unseen: Int get() = Feed.unseen(talk, talkSeen)

    /** Whether the messages sheet is open, and which pane's mail sheet is. */
    var messagesOpen by mutableStateOf(false)
    var mailOpen by mutableStateOf<MailTarget?>(null)

    /** The messages sheet opened: everything held now has been looked at. */
    fun markTalkSeen() {
        talkSeen = Feed.seen(talk, talkSeen)
        store.saveTalkSeen(talkSeen)
    }

    /** Asks the hub for its feed, or every hub; a hub that fails, or has none, keeps its last entries. */
    suspend fun fetchTalk(hub: String? = null) {
        for (c in connections) if (hub == null || c.id == hub) fetchTalk(c)
    }

    private suspend fun fetchTalk(c: Conn) {
        try {
            c.talk = withContext(Dispatchers.IO) { c.api().talk() }.map { it.copy(hub = c.id) }
        } catch (e: ApiException) {
            if (e.code == "unauthorized") tokenRefused(c)
        }
    }

    /** The mailboxes read for the mail sheet, by key; a pane whose mail the token may not read, or a host without mail, has the reason. */
    val mailboxes = mutableStateMapOf<String, List<Letter>>()
    val mailboxErrors = mutableStateMapOf<String, String>()

    suspend fun fetchMailbox(key: String) {
        try {
            mailboxes[key] = withContext(Dispatchers.IO) { api(key).mailbox(Keys.address(key)) }
            mailboxErrors.remove(key)
        } catch (e: ApiException) {
            if (e.code == "unauthorized") tokenRefused(conn(key))
            if (e.code == "not_found") {
                mailboxes[key] = emptyList()
                mailboxErrors[key] = "this pane holds no mail you may read"
            }
        }
    }

    /** The mail sheet's line: a send on its way, and the hub's refusal of the last one. */
    var mailing by mutableStateOf(false)
        private set
    var mailError by mutableStateOf<String?>(null)

    /** A message for the pane's process, never typed; the feed, the mailbox and the listing are asked again. */
    fun mail(key: String, text: String, done: () -> Unit) {
        if (mailing) return
        val c = conn(key)
        viewModelScope.launch {
            mailing = true
            mailError = null
            try {
                withContext(Dispatchers.IO) { api(key).mail(Keys.address(key), text) }
                done()
                fetchTalk(c)
                fetchMailbox(key)
                refreshList(c)
            } catch (e: ApiException) {
                mailError = e.message
                if (e.code == "unauthorized") tokenRefused(c)
            } catch (e: IllegalArgumentException) {
                mailError = "Nothing to send"
            } finally {
                mailing = false
            }
        }
    }

    /** Which pane's share sheet is open, by key. */
    var shareOpen by mutableStateOf<String?>(null)

    /** Whether the pane's hub shares panes; null until it answered, false on a hub older than shares. */
    fun hubShares(key: String): Boolean? = connections.firstOrNull { it.id == Keys.hub(key) }?.sharing

    /** The token's live shares on the hub, asked while the sheet is open and after each change. */
    suspend fun fetchShares(hub: String) {
        connections.firstOrNull { it.id == hub }?.let { fetchShares(it) }
    }

    private suspend fun fetchShares(c: Conn) {
        try {
            c.shares = withContext(Dispatchers.IO) { c.api().shares() }
            c.sharing = true
        } catch (e: ApiException) {
            if (e.code == "unauthorized") tokenRefused(c)
            if (e.code == "not_found") c.sharing = false
        }
    }

    /** The share sheet's state: a create on its way, and the hub's refusal of the last one. */
    var shareBusy by mutableStateOf(false)
        private set
    var shareError by mutableStateOf<String?>(null)

    /**
     * Shares the pane read-only for [ttl] seconds behind [password]. The answer, with its password
     * this once, goes to the sheet alone, which forgets it with done; the hub keeps only the hash.
     */
    fun share(key: String, ttl: Int, password: String, made: (Share) -> Unit) {
        if (shareBusy) return
        val c = conn(key)
        viewModelScope.launch {
            shareBusy = true
            shareError = null
            try {
                made(withContext(Dispatchers.IO) { api(key).share(Keys.address(key), ttl, password) })
                fetchShares(c)
                refreshList(c)
            } catch (e: ApiException) {
                shareError = when (e.code) {
                    "too_many" -> "twenty shares already: revoke one first"
                    "bad_input" -> "the password needs 8 to 64 characters"
                    "read_only" -> "only a writer of the pane may share it"
                    else -> e.message
                }
                if (e.code == "unauthorized") tokenRefused(c)
            } catch (e: IllegalArgumentException) {
                shareError = "the password needs 8 to 64 characters"
            } finally {
                shareBusy = false
            }
        }
    }

    /** Ends a share, and every observer's session with it; the list and the listing follow. */
    fun revokeShare(key: String, id: String) {
        val c = conn(key)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api(key).revokeShare(id) }
            } catch (e: ApiException) {
                shareError = e.message
                if (e.code == "unauthorized") tokenRefused(c)
            } catch (e: IllegalArgumentException) {
                return@launch
            }
            fetchShares(c)
            refreshList(c)
        }
    }

    /** Text onto the clipboard; a [sensitive] clip is not previewed by the system. */
    fun copyText(text: String, sensitive: Boolean) {
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("muxway", text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        clipboard?.setPrimaryClip(clip)
    }

    /** The panes of a feed entry side by side: both, or the one a client wrote to; only panes the listing has, each woken. */
    fun openBeside(e: TalkEntry) {
        val keys = Feed.panesOf(e).map { Keys.make(e.hub, it) }.filter { k -> panes.any { it.key == k } }
        if (keys.isEmpty()) return
        for (k in keys) wake(k)
        open(Screen.View(keys))
    }

    /** Asks every hub for its inbox and keeps the union, oldest first; a hub that fails keeps its last rows. */
    suspend fun fetchInbox() {
        val rows = ArrayList<Pane>()
        for (c in connections) {
            try {
                rows.addAll(withContext(Dispatchers.IO) { c.api().inbox() }.map { it.copy(hub = c.id) })
            } catch (e: ApiException) {
                if (e.code == "unauthorized") tokenRefused(c)
                rows.addAll(inboxRows.orEmpty().filter { it.hub == c.id })
            }
        }
        inboxRows = Inbox.waiting(rows)
    }

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
        inboxOpen = false
        messagesOpen = false
        mailOpen = null
        shareOpen = null
        if (s is Screen.Panes) { selected.clear(); refreshList() }
        if (s !is Screen.Share) sharing = emptyList()
        if (s is Screen.View) store.saveLastPlace("view") else if (s is Screen.Panes) store.saveLastPlace("list")
        if (s is Screen.View) setView(s.addresses) else screen = s
    }

    /**
     * An opening the view takes once, the desk laid out for the tiles about to open: the
     * arrangement, the tree and the shares, the minimised row and, when the desk is opened on
     * purpose, the focus (a desk followed moves no focus).
     */
    class Opening(val keys: List<String>, val arrangement: String, val tree: WindowLayout.Node?, val weights: Map<String, List<Float>>, val minimised: List<String>, val focused: String?)
    var opening: Opening? = null

    /** The desk the view opened from, in the view's own words: a view equal to it writes nothing back. */
    private var deskBaseline: Desk? = null
    fun noteDeskBaseline(d: Desk) { deskBaseline = d }

    /** Whether the app, started on the list after being closed in a view, still owes the desk its opening. */
    private var startOnDesk = store.lastPlace() == "view"
    private val startedAt = System.currentTimeMillis()

    fun deskOf(hub: String): Desk? = connections.firstOrNull { it.id == hub }?.desk

    /** Whether the hub has desks: a hub older than them answered `404` and shows no row. */
    fun hasDesk(hub: String): Boolean = connections.firstOrNull { it.id == hub }?.let { it.deskAsked && it.desk != null } == true

    /** Whether the view should follow [d]: not what it already applied or wrote, and not older than the phone's own last write. */
    fun deskFollowable(hub: String, d: Desk): Boolean {
        val c = connections.firstOrNull { it.id == hub } ?: return false
        return d.rev >= c.deskRev && !Desks.same(d, c.deskKnown)
    }

    /** The view applied [d]: the hub shows it, nothing to follow or write. */
    fun noteDeskKnown(hub: String, d: Desk) {
        connections.firstOrNull { it.id == hub }?.deskKnown = d
    }

    private suspend fun fetchDesk(c: Conn) {
        try {
            val got = withContext(Dispatchers.IO) { c.api().desk(c.deskEtag) }
            c.deskAsked = true
            if (got != null) {
                c.desk = got.first
                c.deskEtag = got.second.ifEmpty { null }
            }
        } catch (e: ApiException) {
            if (e.code == "unauthorized") tokenRefused(c)
            // A hub older than desks: no row, nothing written.
            if (e.code == "not_found") { c.desk = null; c.deskAsked = true; c.deskWritable = false }
        }
        startOnDesk()
    }

    // Started on the list after a view: the first hub's desk that holds panes opens, within the
    // first moments; with every desk read and none holding anything, the list it is.
    private fun startOnDesk() {
        if (!startOnDesk) return
        if (screen !is Screen.Panes || pendingOpen != null || System.currentTimeMillis() - startedAt > 20_000) { startOnDesk = false; return }
        val cs = connections
        val with = cs.firstOrNull { Desks.holds(it.desk) }
        if (with != null) { startOnDesk = false; openDesk(with.id); return }
        if (cs.all { it.deskAsked }) { startOnDesk = false; store.saveLastPlace("list") }
    }

    /**
     * Opens the hub's desk: its tiles in their arrangement with the minimised row and, with
     * [focus], the focused one; a window desk opens the window's panes from the listing, four at
     * most, as the zoom from a lone pane does. A desk without panes opens nothing.
     */
    fun openDesk(hub: String, focus: Boolean = true) {
        val c = connections.firstOrNull { it.id == hub } ?: return
        val d = c.desk ?: return
        c.deskKnown = d
        val keys: List<String>
        var minimised: List<String> = emptyList()
        var arrangement = ""
        var tree: WindowLayout.Node? = null
        var weights: Map<String, List<Float>> = emptyMap()
        val focused: String?
        if (d.view == "window" && d.window.isNotEmpty()) {
            val mates = c.panes.filter { "${it.host}/${it.session}/${it.windowIndex}" == d.window || "${it.host}/${it.session}/${it.window}" == d.window }.map { it.key }.take(AddMenu.MAX_TILES)
            if (mates.isEmpty()) { told = "the desk's window has no pane here"; return }
            keys = mates
            focused = Keys.make(c.id, d.focused).takeIf { d.focused.isNotEmpty() && it in mates }
        } else {
            val panes = Desks.panesOf(d).map { Keys.make(c.id, it) }
            if (panes.isEmpty()) return
            keys = (panes + d.minimised.map { Keys.make(c.id, it) }).distinct().take(AddMenu.MAX_TILES)
            minimised = keys.drop(panes.size)
            arrangement = d.arrangement
            tree = d.tree
            weights = d.weights
            focused = Keys.make(c.id, d.focused).takeIf { d.focused.isNotEmpty() && it in panes }
        }
        opening = Opening(keys, arrangement, tree, weights, minimised, if (focus) focused else null)
        open(Screen.View(keys))
    }

    /**
     * The view as a desk, for the focused tile's hub, sent half a second after the last change
     * unless the hub has it already or the view opened from it; the newest change supersedes
     * the pending one. Nothing is written for a token without a writable pane, after a
     * `read_only` refusal, or to a hub older than desks.
     */
    fun writeDesk(hub: String, d: Desk) {
        val c = connections.firstOrNull { it.id == hub } ?: return
        if (Desks.same(d, deskBaseline) || Desks.same(d, c.deskKnown)) {
            c.deskPending = null
            c.deskJob?.cancel()
            return
        }
        if (!c.deskWritable || c.panes.none { it.writable }) return
        c.deskPending = d
        c.deskJob?.cancel()
        c.deskJob = viewModelScope.launch {
            delay(Desks.DEBOUNCE_MS)
            val next = c.deskPending ?: return@launch
            c.deskPending = null
            if (Desks.same(next, deskBaseline) || Desks.same(next, c.deskKnown)) return@launch
            try {
                val stored = withContext(Dispatchers.IO) { c.api().putDesk(next) }
                c.deskKnown = stored
                c.desk = stored
                c.deskRev = stored.rev
                c.deskEtag = null
            } catch (e: ApiException) {
                if (e.code == "unauthorized") tokenRefused(c)
                if (e.code == "read_only" || e.code == "not_found") c.deskWritable = false
            } catch (e: IllegalArgumentException) {
                // A desk the client itself refuses never leaves the phone.
            }
        }
    }

    /**
     * The view's addresses, each tile claimed for the pane the listing has at its address: a
     * state kept for another pane is dropped first, so no tile starts with a borrowed screen;
     * a key the listing lacks learns its pane from its first read.
     */
    private fun setView(addresses: List<String>) {
        for (k in addresses) {
            val listed = panes.firstOrNull { it.key == k }?.paneId.orEmpty()
            if (listed.isEmpty()) continue
            views[k]?.let { if (it.paneId.isNotEmpty() && it.paneId != listed) views.remove(k) }
            view(k).paneId = listed
        }
        screen = Screen.View(addresses)
    }

    /**
     * The tiles the last listing moved, old key to new, and a count of the moves, for the view
     * to carry its own per-key state (the minimised row, fit off) along.
     */
    var moved by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var moves by mutableIntStateOf(0)
        private set

    /**
     * A listing of [c] arrived: every open tile of that hub is checked against it (see
     * [Follow.reconcile]): a tile whose pane moved follows it, its state carried to the new key;
     * a tile whose pane is gone closes with a word; the rest stay.
     */
    private fun reconcile(c: Conn) {
        val view = screen as? Screen.View ?: return
        val online = { host: String -> c.hosts.any { it.name == host && it.online } }
        val missing = view.addresses.filter { views[it]?.missing == true }.toSet()
        val out = Follow.reconcile(c.id, view.addresses, { views[it]?.paneId ?: "" }, missing, c.panes, online)
        for (k in out.gone) {
            told = "${views[k]?.pane?.label ?: Keys.address(k).substringAfter('/')} is gone"
            drop(k)
        }
        if (out.moved.isEmpty()) return
        val now = screen as? Screen.View ?: return
        val states = out.moved.keys.associateWith { views.remove(it) }
        for ((old, next) in out.moved) {
            states[old]?.let { view(next).take(it) }
            view(next).missing = false
            lastKind.remove(old)?.let { lastKind[next] = it }
        }
        moved = out.moved
        moves++
        screen = Screen.View(now.addresses.map { out.moved[it] ?: it })
    }

    /** The listing of [c], asked at once but once per two seconds: a read saw another pane, or none, at a tile's address. */
    private fun listSoon(c: Conn) {
        val now = System.currentTimeMillis()
        if (now - c.askedAt < 2_000) return
        c.askedAt = now
        refreshList(c)
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
        forgetRecent()
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
        for (k in mailboxes.keys.toList()) if (Keys.hub(k) == id) { mailboxes.remove(k); mailboxErrors.remove(k) }
        if (mailOpen?.let { Keys.hub(it.key) } == id) mailOpen = null
        if (shareOpen?.let { Keys.hub(it) } == id) shareOpen = null
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
        openPane(key)
    }

    /** Opens one pane from the list, the inbox, the add menu or a link, and wakes it when it was parked. */
    fun openPane(key: String) {
        wake(key)
        open(Screen.View(listOf(key)))
    }

    /** Opens the picked panes side by side, each woken when it was parked. */
    fun openSelected() {
        val keys = selected.toList()
        for (k in keys) wake(k)
        open(Screen.View(keys))
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
                // The feed comes with the listing, so the messages count is as live as the list.
                fetchTalk(c)
                // Whether the hub shares panes, until it answered either way.
                if (c.sharing == null) fetchShares(c)
                if (!c.probed) {
                    c.transcriber = withContext(Dispatchers.IO) { c.api().hasTranscriber() }
                    c.admin = withContext(Dispatchers.IO) { c.api().isAdmin() }
                    c.probed = true
                }
                reconcile(c)
                // A link's pane first: a link to a view goes where it points, the desk waits.
                openPending()
                fetchDesk(c)
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
                // The answer names the pane before any listing does: the tile knows it from the start.
                if (pane.paneId.isNotEmpty()) { views.remove(pane.key); view(pane.key).paneId = pane.paneId }
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
                // The desk changed on the hub: it is asked for at once, so a view follows within the second.
                if (ev.events.any { it.kind == "desk" }) fetchDesk(c)
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
                // Another pane answers at this address: nothing of it is taken, and the listing says
                // where this tile's pane went.
                if (!Follow.own(v.paneId, r.pane.paneId)) { listSoon(conn(key)); return }
                if (v.paneId.isEmpty()) v.paneId = r.pane.paneId
                val at = System.currentTimeMillis() / 1000
                v.pane = r.pane.copy(hub = Keys.hub(key))
                if (r.content != v.content && Lens.changeCounts(!v.read, v.ownAt, at)) v.changedAt = at
                v.content = r.content
                v.trimmed = r.trimmed
                v.etag = r.etag
                v.read = true
            }
            v.missing = false
            v.error = null
            v.updated = LocalTime.now().format(CLOCK)
        } catch (e: ApiException) {
            v.error = e.message
            if (e.code == "unauthorized") tokenRefused(conn(key))
            // No pane at the address: a tile that knows its pane asks the listing whether it moved
            // or is gone; one that never learnt it has nothing left to show.
            if (e.code == "not_found") {
                if (v.paneId.isEmpty()) drop(key) else { v.missing = true; listSoon(conn(key)) }
            }
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

    /** Empties the sent lines: the command row's and the ghost word's pool. */
    fun forgetRecent() {
        recent.clear()
        store.saveRecent(emptyList())
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
     * With fit off the tiles' arrangement still goes to the host, as the window's layout without
     * a size; [mates] are the tiles' keys, re-read after. Once more after the host's second.
     */
    fun arrange(key: String, layout: String, mates: List<String>, retry: Boolean = true) {
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { api(key).arrange(Keys.address(key), layout) }
                val at = System.currentTimeMillis() / 1000
                for (m in mates) views[m]?.let { it.ownAt = at; it.etag = null }
                val moved = if (r.panes.isNotEmpty()) follow(key, r.panes) else emptyMap()
                for (m in mates) launch { pollOnce(moved[m] ?: m) }
            } catch (e: ApiException) {
                if (e.code == "rate_limited" && retry) { delay(1100); arrange(key, layout, mates, retry = false) }
                else if (e.code == "unauthorized") tokenRefused(conn(key))
                else sendError = e.message
            } catch (e: IllegalArgumentException) {
                // A layout the client itself refuses never leaves the phone.
            }
        }
    }

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
        this.moved = moved
        moves++
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
     * One of the fixed answers of the question the pane asked: a write, nothing typed into the
     * pane. The hub lists the pane without its attention afterwards, so its hub is listed again.
     */
    fun answer(key: String, choice: String) = send(key) { api, address -> api.answer(address, choice) }.also { if (it) refreshList(conn(key)) }

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
                        val sent = withContext(Dispatchers.IO) { api(key).sendFile(v.address, f.bytes, f.type, f.ext) }
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

    /** Whether the pane's host lists a shell pane's directory for writers. */
    fun hostLists(key: String): Boolean = hostOf(key)?.dir == true

    /** Whether the pane's host names panes for writers. */
    fun hostRenames(key: String): Boolean = hostOf(key)?.rename == true

    /** Whether the pane's host parks panes for later. */
    fun hostParks(key: String): Boolean = hostOf(key)?.later == true

    /**
     * Parks the pane for later, or wakes it: a write the hub and the host check. The listing
     * held here is marked at once, so the strip moves the tab, and the hub's is asked again.
     */
    fun later(key: String, on: Boolean) {
        val c = conn(key)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api(key).later(Keys.address(key), on) }
                c.panes = c.panes.map { if (it.key == key) it.copy(later = on) else it }
                views[key]?.let { v -> v.pane = v.pane?.copy(later = on) }
                refreshList(c)
            } catch (e: ApiException) {
                sendError = e.message
                if (e.code == "unauthorized") tokenRefused(c)
            }
        }
    }

    /** Coming back to a parked pane wakes it: a writer's pane on a host that parks; nothing otherwise. */
    fun wake(key: String) {
        val pane = panes.firstOrNull { it.key == key } ?: return
        if (pane.later && pane.writable && hostParks(key)) later(key, false)
    }

    /** A writer's pane's directory under a root, from the listing or the last read; null otherwise. */
    fun cwdOf(key: String): String? = panes.firstOrNull { it.key == key }?.cwd ?: view(key).pane?.cwd

    /** The name sheet's state: a rename on its way, and the hub's refusal of the last one. */
    var renaming by mutableStateOf(false)
    var renameError by mutableStateOf<String?>(null)

    /** Names the pane, or with [alias] empty takes the name away; the tile and the listing show the answer. */
    fun rename(key: String, alias: String, done: () -> Unit) {
        if (renaming) return
        viewModelScope.launch {
            renaming = true
            renameError = null
            try {
                val pane = withContext(Dispatchers.IO) { api(key).rename(Keys.address(key), alias) }
                view(key).pane = pane.copy(hub = Keys.hub(key))
                refreshList(conn(key))
                done()
            } catch (e: ApiException) {
                renameError = e.message
                if (e.code == "unauthorized") tokenRefused(conn(key))
            } catch (e: IllegalArgumentException) {
                renameError = ApiException.messageFor(400, "bad_alias")
            } finally {
                renaming = false
            }
        }
    }

    /**
     * The entries of the pane's directory, asked with the tag so an unchanged listing is no body;
     * a directory the host will not list is not asked again for a minute, unless it changes.
     */
    suspend fun fetchListing(key: String) {
        val v = view(key)
        val cwd = cwdOf(key)
        if (cwd == null) { dropListing(key); return }
        if (cwd != v.listingCwd) { v.listingEtag = null; v.noDirUntil = 0L }
        if (System.currentTimeMillis() < v.noDirUntil) return
        try {
            val got = withContext(Dispatchers.IO) { api(key).dir(v.address, v.listingEtag) }
            if (got != null) { v.listing = got.first; v.listingEtag = got.second.ifEmpty { null } }
            v.listingCwd = cwd
        } catch (e: ApiException) {
            if (e.code == "unauthorized") tokenRefused(conn(key))
            if (e.code == "not_found" || e.code == "read_only") {
                v.listing = null
                v.listingEtag = null
                v.listingCwd = cwd
                v.noDirUntil = System.currentTimeMillis() + 60_000
            }
        }
    }

    /** Forgets the pane's listing: it is no shell, or its directory is gone. */
    fun dropListing(key: String) {
        val v = view(key)
        v.listing = null; v.listingCwd = null; v.listingEtag = null
    }

    /** A word for the person in the pane view that is not an error: what was copied, what was saved. */
    var told by mutableStateOf<String?>(null)

    /**
     * The repository's changes for a writable pane on a host with preview roots; a pane in no
     * repository the host shows is asked again after a minute, unless [force].
     */
    suspend fun fetchChanges(key: String, force: Boolean = false) {
        val v = view(key)
        if (!force && System.currentTimeMillis() < v.noRepoUntil) return
        try {
            v.changes = withContext(Dispatchers.IO) { api(key).git(v.address) }
            v.changesError = null
            v.noRepoUntil = 0L
        } catch (e: ApiException) {
            if (e.code == "unauthorized") tokenRefused(conn(key))
            if (e.code == "not_found") {
                v.changes = null
                v.changesError = null
                v.noRepoUntil = System.currentTimeMillis() + 60_000
            } else if (e.code != "rate_limited") v.changesError = e.message
        }
    }

    /** One change's diff, for the sheet; null when refused. */
    fun fetchDiff(key: String, path: String, done: (Diff?) -> Unit) {
        viewModelScope.launch {
            try {
                done(withContext(Dispatchers.IO) { api(key).gitDiff(Keys.address(key), path) })
            } catch (e: ApiException) {
                if (e.code == "unauthorized") tokenRefused(conn(key))
                done(null)
            } catch (e: IllegalArgumentException) {
                done(null)
            }
        }
    }

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
        setView(addresses.toMutableList().also { it[i] = b; it[j] = a })
    }

    /** Replaces one open pane by another, keeping the others. */
    fun swap(addresses: List<String>, at: Int, key: String) {
        setView(addresses.mapIndexed { i, a -> if (i == at) key else a })
    }

    /** One more tile: [key] joins the view, or, with four tiles on screen ([full]), takes the focused tile's place. */
    fun addPane(addresses: List<String>, focusedAt: Int, key: String, full: Boolean) {
        if (key in addresses) return
        setView(if (!full) addresses + key else addresses.mapIndexed { i, a -> if (i == focusedAt) key else a })
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
