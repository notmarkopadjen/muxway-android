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

/** The board as a tile: a line per pane, each a tap, the summary at the foot. */
class BoardTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = future {
        val board = BoardStore.load(this)
        val now = System.currentTimeMillis() / 1000
        val phone = BoardStore.phone(this)
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES)
            .setFreshnessIntervalMillis(0)
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

    private fun <T> future(make: () -> T): ListenableFuture<T> = CallbackToFutureAdapter.getFuture { completer ->
        runCatching(make).onSuccess { completer.set(it) }.onFailure { completer.setException(it) }
        "tile"
    }

    companion object {
        const val RESOURCES = "1"
        const val ROWS = 6
    }
}
