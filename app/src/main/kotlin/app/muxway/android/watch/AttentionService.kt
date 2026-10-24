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
import app.muxway.android.protocol.Lens
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

/**
 * Follows the panes while the app is closed: one long poll on the hub's events, a listing
 * after each answer, the board to the watch and a notification per pane that waits. Runs
 * while the watch setting is on and a token is stored.
 */
class AttentionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notifier: Notifier
    private lateinit var bridge: WatchBridge
    private var job: Job? = null
    private var board: Board = Board.EMPTY

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
        val token = Vault(this).token()
        if (token == null || settings.hubUrl.isEmpty() || !settings.watch) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, Notifier.SUMMARY_ID, notifier.summary(null, "connecting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        job?.cancel()
        job = scope.launch { follow(Api(settings.hubUrl, token, "muxway-android/" + BuildConfig.VERSION_NAME), settings) }
        return START_STICKY
    }

    private suspend fun follow(api: Api, settings: Settings) {
        val lastKind = HashMap<String, String>()
        var cursor: Long? = null
        while (true) {
            try {
                val panes = api.panes()
                board = Board.rank(panes, System.currentTimeMillis() / 1000, settings.notifyText) { Lens.status(it, lastKind[it.address], false) }
                notifier.show(board)
                bridge.push(board)
                val ev = api.events(cursor, 30)
                cursor = ev.next
                for (e in ev.events) if (e.address.isNotEmpty() && e.kind.isNotEmpty()) lastKind[e.address] = e.kind
            } catch (e: ApiException) {
                if (e.code == "unauthorized") {
                    // Final, as in the app: a refused token is forgotten so nothing retries it.
                    Vault(this).clear()
                    notifier.say("the hub refused the token; open muxway")
                    stopSelf()
                    return
                }
                notifier.show(board.copy(live = false))
                delay(5_000)
            }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        bridge.push(board.copy(live = false), force = true)
        notifier.clear()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.muxway.android.watch.STOP"

        /** Starts or stops the service to match the setting and whether a token is stored. */
        fun apply(context: Context) {
            val intent = Intent(context, AttentionService::class.java)
            if (SettingsStore(context).load().watch && Vault(context).hasToken()) context.startForegroundService(intent)
            else context.stopService(intent)
        }
    }
}
