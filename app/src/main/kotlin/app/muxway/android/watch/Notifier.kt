package app.muxway.android.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.muxway.android.MainActivity
import app.muxway.android.R
import app.muxway.android.protocol.Board
import app.muxway.android.protocol.Row

/** One notification per pane that waits, and the ongoing summary the service lives in. */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val posted = HashMap<String, Pair<String, Long>>()

    init {
        manager.createNotificationChannel(
            NotificationChannel(ATTENTION, "a pane waits for you", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A question, a permission, idle or done in a pane you follow"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(WATCH, "following panes", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the app follows your panes in the background"
                setShowBadge(false)
            },
        )
    }

    fun summary(board: Board?, state: String): Notification {
        val text = when {
            board == null -> state
            board.waiting == 0 -> "${board.rows.size} panes, none waiting"
            else -> "${board.waiting} waiting · ${board.rows.size} panes"
        }
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val stop = Intent(context, AttentionService::class.java).setAction(AttentionService.ACTION_STOP)
        return Notification.Builder(context, WATCH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("muxway")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(null, "stop following", PendingIntent.getService(context, 1, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            .build()
    }

    /** Posts for new or changed waits, clears what no longer waits, refreshes the summary. */
    fun show(board: Board) {
        val waiting = board.rows.filter { it.state == Board.WAITING }.associateBy { it.address }
        for (gone in posted.keys - waiting.keys) {
            manager.cancel(gone, ATTENTION_ID)
            posted.remove(gone)
        }
        for ((address, row) in waiting) {
            val key = row.kind to row.at
            if (posted[address] == key) continue
            posted[address] = key
            manager.notify(address, ATTENTION_ID, attention(row))
        }
        manager.notify(SUMMARY_ID, summary(board, ""))
    }

    /** The summary with a state line in place of counts. */
    fun say(state: String) = manager.notify(SUMMARY_ID, summary(null, state))

    fun clear() {
        for (address in posted.keys) manager.cancel(address, ATTENTION_ID)
        posted.clear()
    }

    private fun attention(row: Row): Notification {
        val link = Intent(Intent.ACTION_VIEW, openUri(row.address)).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, row.address.hashCode(), link, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context, ATTENTION)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${row.glyph} ${row.word}")
            .setContentText(if (row.text.isEmpty()) row.name else "${row.name} · ${row.text}")
            .setWhen(row.at * 1000)
            .setShowWhen(row.at > 0)
            .setAutoCancel(true)
            .setGroup(ATTENTION)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(tap)
            .build()
    }

    companion object {
        const val ATTENTION = "attention"
        const val WATCH = "watch"
        const val SUMMARY_ID = 1
        const val ATTENTION_ID = 2

        /** The deep link the phone app answers: it opens that pane and nothing else. */
        fun openUri(address: String): Uri = Uri.Builder().scheme("muxway").authority("open").appendQueryParameter("pane", address).build()
    }
}
