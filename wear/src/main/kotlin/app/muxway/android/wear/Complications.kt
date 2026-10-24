package app.muxway.android.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.muxway.android.protocol.Board
import kotlinx.coroutines.withTimeoutOrNull

/** One row of the board as a complication: any face with a slot can show it. */
abstract class RowComplication(private val index: Int) : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val board = BoardStore.load(this)
        val cell = Face.row(board, index, Face.ROWS, now(), phoneReachable())
        return build(request.complicationType, cell, cell.address.ifEmpty { null })
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, Face.row(Face.preview, index, Face.ROWS, Face.preview.at, true), null)

    private fun build(type: ComplicationType, cell: Cell, address: String?): ComplicationData? {
        val tap = if (address != null) openPane(address) else null
        val description = PlainComplicationText.Builder(listOf(cell.text, cell.title).filter { it.isNotEmpty() }.joinToString(" ")).build()
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(cell.value.toFloat(), 0f, 2f, description)
                .setText(PlainComplicationText.Builder(cell.text).build())
                .setTitle(PlainComplicationText.Builder(cell.title).build())
                .setTapAction(tap).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder(cell.text.take(7)).build(), description)
                .setTitle(PlainComplicationText.Builder(cell.title.take(7)).build())
                .setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(PlainComplicationText.Builder(if (cell.title.isEmpty()) cell.text else "${cell.text} · ${cell.title}").build(), description)
                .setTapAction(tap).build()
            else -> null
        }
    }

    private fun openPane(address: String): PendingIntent = PendingIntent.getActivity(
        this, index, Intent(this, OpenPaneActivity::class.java).putExtra(OpenPaneActivity.ADDRESS, address),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class Row1Complication : RowComplication(0)
class Row2Complication : RowComplication(1)
class Row3Complication : RowComplication(2)
class Row4Complication : RowComplication(3)

/** How many panes wait, or what is wrong; taps open the list. */
class SummaryComplication : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val board = BoardStore.load(this)
        val now = now()
        val phone = phoneReachable()
        return build(request.complicationType, Face.summary(board, now, phone), Face.short(board, now, phone))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, Face.summary(Face.preview, Face.preview.at, true), Face.short(Face.preview, Face.preview.at, true))

    private fun build(type: ComplicationType, cell: Cell, short: String): ComplicationData? {
        val tap = PendingIntent.getActivity(this, 9, Intent(this, BoardActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val description = PlainComplicationText.Builder("${cell.text} ${cell.title}".trim()).build()
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(cell.value.toFloat(), 0f, 2f, description)
                .setText(PlainComplicationText.Builder(cell.text).build())
                .setTitle(PlainComplicationText.Builder(cell.title).build())
                .setTapAction(tap).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder(short).build(), description)
                .setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(PlainComplicationText.Builder(if (cell.title.isEmpty()) cell.text else "${cell.text} · ${cell.title}").build(), description)
                .setTapAction(tap).build()
            else -> null
        }
    }
}

private fun now() = System.currentTimeMillis() / 1000

/** The stored answer, refreshed when the capability changes; a quick look at the network when asked. */
private suspend fun SuspendingComplicationDataSourceService.phoneReachable(): Boolean =
    withTimeoutOrNull(1500) { Phone.reachable(this@phoneReachable) } ?: BoardStore.phone(this)
