package app.muxway.android.wear

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.muxway.android.protocol.Board
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** The last board the phone sent and whether a phone is reachable, in app-private preferences. */
object BoardStore {
    private const val FILE = "board"

    fun load(context: Context): Board = Board.parse(prefs(context).getString("json", null))

    fun phone(context: Context): Boolean = prefs(context).getBoolean("phone", false)

    fun save(context: Context, json: String?) {
        prefs(context).edit().putString("json", json).apply()
    }

    fun savePhone(context: Context, reachable: Boolean) {
        prefs(context).edit().putBoolean("phone", reachable).apply()
    }

    /**
     * The board the Data Layer already holds, for a listener that was not there when it came:
     * a fresh install, or an app the system had stopped. A read of the local cache, no radio;
     * a newer board than the stored one is kept and every surface told.
     */
    suspend fun sync(context: Context) {
        val items = runCatching { Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*" + BoardListenerService.PATH)).await() }.getOrNull() ?: return
        var newest: String? = null
        var newestAt = load(context).at
        try {
            for (item in items) {
                val map = DataMapItem.fromDataItem(item).dataMap
                val at = map.getLong("at")
                if (at > newestAt) { newestAt = at; newest = map.getString(BoardListenerService.KEY) }
            }
        } finally {
            items.release()
        }
        if (newest == null) return
        save(context, newest)
        refresh(context)
    }

    /** Every complication and the tile draw again. */
    fun refresh(context: Context) {
        for (c in COMPLICATIONS) ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, c)).requestUpdateAll()
        TileService.getUpdater(context).requestUpdate(BoardTileService::class.java)
    }

    fun listen(context: Context, onChange: () -> Unit): () -> Unit {
        val p = prefs(context)
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        p.registerOnSharedPreferenceChangeListener(l)
        return { p.unregisterOnSharedPreferenceChangeListener(l) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val COMPLICATIONS = listOf(
        Row1Complication::class.java, Row2Complication::class.java, Row3Complication::class.java, Row4Complication::class.java,
        SummaryComplication::class.java,
    )
}
