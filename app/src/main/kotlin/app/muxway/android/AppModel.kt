package app.muxway.android

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.muxway.android.protocol.Api
import app.muxway.android.protocol.ApiException
import app.muxway.android.protocol.Host
import app.muxway.android.protocol.HubUrl
import app.muxway.android.protocol.Input
import app.muxway.android.protocol.Pane
import app.muxway.android.store.Settings
import app.muxway.android.store.SettingsStore
import app.muxway.android.store.Vault
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
    var sendError by mutableStateOf<String?>(null)

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
        open(Screen.Panes)
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
        api = null
        views.clear()
        panes = emptyList()
        hosts = emptyList()
        cursor = null
        open(Screen.Panes)
        return null
    }

    fun forgetToken() {
        vault.clear()
        hasToken = false
        api = null
        views.clear()
        screen = Screen.Connect
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

    /** One read of a pane; a 304 leaves the content alone. */
    suspend fun pollOnce(address: String) {
        val v = view(address)
        try {
            val r = withContext(Dispatchers.IO) { api().read(address, settings.lines, v.etag) }
            if (r != null) {
                v.pane = r.pane
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

    fun type(address: String, text: String, enter: Boolean) = send(address) {
        val clean = Input.printable(text)
        if (clean.isNotEmpty()) it.type(address, clean)
        if (enter) it.key(address, "Enter")
    }

    /** False when the pane is still busy with the previous send. */
    fun key(address: String, name: String) = send(address) { it.key(address, name) }

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

    private fun tokenRefused() {
        api = null
        if (screen !is Screen.Connect) screen = Screen.Connect
    }

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
