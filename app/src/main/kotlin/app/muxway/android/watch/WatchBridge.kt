package app.muxway.android.watch

import android.content.Context
import app.muxway.android.protocol.Board
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * Hands the board to the watch through the Data Layer. An item only syncs when it changes,
 * so the same rows go again only as a heartbeat every few minutes; the watch treats a
 * board older than ten minutes as stale.
 */
class WatchBridge(private val context: Context) {
    private var lastRows: List<Any>? = null
    private var lastAt = 0L

    fun push(board: Board, force: Boolean = false) {
        if (!force && board.rows == lastRows && board.at - lastAt < HEARTBEAT_SECONDS) return
        lastRows = board.rows
        lastAt = board.at
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putString(KEY, board.json())
            dataMap.putLong("at", board.at)
        }.asPutDataRequest().setUrgent()
        // No Play services, no watch: nothing to do and nothing to say.
        runCatching { Wearable.getDataClient(context).putDataItem(request) }
    }

    companion object {
        const val PATH = "/board"
        const val KEY = "board"
        const val HEARTBEAT_SECONDS = 300L
    }
}
