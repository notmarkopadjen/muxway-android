package app.muxway.android.watch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import app.muxway.android.BuildConfig
import app.muxway.android.protocol.Api
import app.muxway.android.protocol.ApiException
import app.muxway.android.protocol.Board
import app.muxway.android.protocol.Keys
import app.muxway.android.protocol.Lens
import app.muxway.android.protocol.Pane
import app.muxway.android.protocol.WatchGate
import app.muxway.android.store.Settings
import app.muxway.android.store.SettingsStore
import app.muxway.android.store.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Follows the panes while the app is closed: one long poll per hub on its events, a listing
 * after each answer, the board of every hub's panes to the watch and a notification per pane
 * that waits. Runs while the watch setting is on and a token is stored for any hub.
 */
class AttentionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notifier: Notifier
    private lateinit var bridge: WatchBridge
    private var job: Job? = null
    private var board: Board = Board.EMPTY
    @Volatile private var panes: List<Pane> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        bridge = WatchBridge(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = SettingsStore(this)
        val settings = store.load()
        if (intent?.action == ACTION_STOP) {
            store.save(settings.copy(watch = false))
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ANSWER) {
            answer(intent.getStringExtra(EXTRA_PANE).orEmpty(), intent.getStringExtra(EXTRA_CHOICE).orEmpty())
            return START_STICKY
        }
        val vault = Vault(this)
        apis.clear()
        for (h in settings.hubs) vault.token(h.id)?.let { apis[h.id] = Api(h.url, it, "muxway-android/" + BuildConfig.VERSION_NAME) }
        if (apis.isEmpty() || !settings.watch) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, Notifier.SUMMARY_ID, notifier.summary(null, "connecting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        job?.cancel()
        gate = WatchGate({ hub -> apis[hub] ?: apis.values.firstOrNull() }, { panes }, { store.load().watchAnswer })
        byHub.clear()
        job = scope.launch {
            for ((id, api) in apis) launch { follow(id, api, settings) }
        }
        return START_STICKY
    }

    /**
     * A choice tapped on a question's notification. The pane must be in the last listing,
     * writable, and the choice one it offers; then it goes to its hub as the app would send it.
     */
    private fun answer(key: String, choice: String) {
        val pane = panes.firstOrNull { it.key == key } ?: return
        val api = apis[pane.hub] ?: return
        if (!pane.writable || choice !in pane.attention?.choices.orEmpty()) return
        scope.launch {
            val error = try {
                api.answer(pane.address, choice)
                null
            } catch (e: ApiException) {
                e.message
            }
            notifier.answered(key, error)
        }
    }

    private val apis = LinkedHashMap<String, Api>()

    /** Each hub's panes, by hub id; the board is their union. */
    private val byHub = HashMap<String, List<Pane>>()
    private val lastKind = ConcurrentHashMap<String, String>()

    @Synchronized
    private fun merge(hub: String, listed: List<Pane>?, settings: Settings) {
        if (listed == null) byHub.remove(hub) else byHub[hub] = listed
        panes = byHub.values.flatten()
        board = Board.rank(panes, System.currentTimeMillis() / 1000, settings.notifyText) { Lens.status(it, lastKind[it.key], false) }
        notifier.show(board, panes.filter { it.writable }.map { it.key }.toSet())
        bridge.push(board)
    }

    /** One long poll on one hub's events, a listing after each answer. */
    private suspend fun follow(hub: String, api: Api, settings: Settings) {
        var cursor: Long? = null
        while (true) {
            try {
                merge(hub, api.panes().map { it.copy(hub = hub) }, settings)
                val ev = api.events(cursor, 30)
                cursor = ev.next
                for (e in ev.events) if (e.address.isNotEmpty() && e.kind.isNotEmpty()) lastKind[Keys.make(hub, e.address)] = e.kind
            } catch (e: ApiException) {
                if (e.code == "unauthorized") {
                    // Final, as in the app: a refused token is forgotten so nothing retries it.
                    Vault(this).clear(hub)
                    merge(hub, null, settings)
                    notifier.say("a hub refused its token; open muxway")
                    if (byHub.isEmpty()) stopSelf()
                    return
                }
                if (byHub.isEmpty()) notifier.say("no connection")
                delay(5_000)
            }
        }
    }

    override fun onDestroy() {
        gate = null
        job?.cancel()
        bridge.push(board.copy(live = false), force = true)
        notifier.clear()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.muxway.android.watch.STOP"
        const val ACTION_ANSWER = "app.muxway.android.watch.ANSWER"
        const val EXTRA_PANE = "pane"
        const val EXTRA_CHOICE = "choice"

        /** The gate the watch's requests go through, while the service runs. */
        @Volatile var gate: WatchGate? = null

        /** Starts or stops the service to match the setting and whether a token is stored. */
        fun apply(context: Context) {
            val intent = Intent(context, AttentionService::class.java)
            val s = SettingsStore(context).load()
            if (s.watch && s.hubs.any { Vault(context).hasToken(it.id) }) context.startForegroundService(intent)
            else context.stopService(intent)
        }
    }
}
