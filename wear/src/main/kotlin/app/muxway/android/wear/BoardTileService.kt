package app.muxway.android.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The board as a tile: a line per pane, each a tap, the summary at the foot. */
class BoardTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = future {
        // A quick look at what the phone left and whether it is there, then the stored answer.
        withTimeoutOrNull(1500) { BoardStore.sync(this@BoardTileService); Phone.reachable(this@BoardTileService) }
        val board = BoardStore.load(this)
        val now = System.currentTimeMillis() / 1000
        val phone = BoardStore.phone(this)
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES)
            .setFreshnessIntervalMillis(FRESH_MILLIS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(board, now, phone)))
            .build()
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> = future {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build()
    }

    private fun layout(board: app.muxway.android.protocol.Board, now: Long, phone: Boolean): LayoutElement {
        val column = Column.Builder().setWidth(expand()).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        column.addContent(text("┌─ muxway ─┐", Palette.DIM, 12f))
        column.addContent(Spacer.Builder().setHeight(dp(6f)).build())
        val lines = ROWS
        for (i in 0 until lines) {
            val cell = Face.row(board, i, lines, now, phone)
            val line = if (cell.title.isEmpty()) cell.text else "${cell.text}  ${cell.title}"
            val t = Text.Builder().setText(line).setMaxLines(1).setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                .setFontStyle(FontStyle.Builder().setSize(sp(15f)).setColor(argb(Palette.argbForValue(cell.value))).build())
            if (cell.address.isNotEmpty()) {
                val open = ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(PaneActivity::class.java.name)
                        .addKeyToExtraMapping(PaneActivity.ADDRESS, ActionBuilders.stringExtra(cell.address)).build(),
                ).build()
                t.setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(ModifiersBuilders.Clickable.Builder().setId("row$i").setOnClick(open).build()).build())
            }
            column.addContent(t.build())
        }
        column.addContent(Spacer.Builder().setHeight(dp(6f)).build())
        val s = Face.summary(board, now, phone)
        column.addContent(text("─ ${s.text}${if (s.title.isEmpty()) "" else " · " + s.title} ─", Palette.argbForValue(s.value), 12f))
        return column.build()
    }

    private fun text(s: String, colour: Int, size: Float): LayoutElement =
        Text.Builder().setText(s).setMaxLines(1).setFontStyle(FontStyle.Builder().setSize(sp(size)).setColor(argb(colour)).build()).build()

    private fun <T> future(make: suspend () -> T): ListenableFuture<T> = CallbackToFutureAdapter.getFuture { completer ->
        scope.launch { runCatching { make() }.onSuccess { completer.set(it) }.onFailure { completer.setException(it) } }
        "tile"
    }

    companion object {
        const val RESOURCES = "1"
        const val ROWS = 6

        /** Drawn again this often while shown, so a board that stopped coming turns stale on the tile too. */
        const val FRESH_MILLIS = 5 * 60 * 1000L
    }
}
