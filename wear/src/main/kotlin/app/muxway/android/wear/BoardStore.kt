package app.muxway.android.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.muxway.android.protocol.Board

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
