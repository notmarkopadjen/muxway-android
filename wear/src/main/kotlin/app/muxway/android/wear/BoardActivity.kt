package app.muxway.android.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import app.muxway.android.protocol.Board
import kotlinx.coroutines.delay

/** Every pane, ranked as on the face, with the summary; a tap opens the pane on the watch. */
class BoardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BoardScreen { address -> startActivity(Intent(this, PaneActivity::class.java).putExtra(PaneActivity.ADDRESS, address)) } }
    }
}

@Composable
private fun BoardScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var board by remember { mutableStateOf(BoardStore.load(context)) }
    var phone by remember { mutableStateOf(BoardStore.phone(context)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    DisposableEffect(Unit) {
        val stop = BoardStore.listen(context) { board = BoardStore.load(context); phone = BoardStore.phone(context) }
        onDispose(stop)
    }
    LaunchedEffect(Unit) {
        phone = Phone.reachable(context)
        while (true) { delay(10_000); now = System.currentTimeMillis() / 1000 }
    }
    val problem = Face.problem(board, now, phone)
    val summary = Face.summary(board, now, phone)
    // The list centres the row it is on, so a pane at either end of it comes to the middle of a
    // round watch's glass, where it is whole and can be tapped; the bezel turns it too.
    val list = rememberScalingLazyListState(initialCenterItemIndex = 1)
    val crown = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { crown.requestFocus() } }
    val config = LocalConfiguration.current
    val edge = (if (config.isScreenRound) config.screenWidthDp * 0.08f else 4f).dp
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
        ScalingLazyColumn(
            Modifier.fillMaxSize().background(Palette.bg)
                .rotaryScrollable(RotaryScrollableDefaults.behavior(list), crown),
            state = list,
            autoCentering = AutoCenteringParams(itemIndex = 1),
            contentPadding = PaddingValues(horizontal = edge, vertical = 8.dp),
        ) {
            item {
                Text("┌─ muxway ─┐", color = Palette.dim, fontFamily = Palette.mono, fontSize = 12.sp)
            }
            if (board.rows.isEmpty()) {
                item { Text(problem ?: "no panes", color = Palette.dim, fontFamily = Palette.mono, fontSize = 14.sp, modifier = Modifier.padding(8.dp)) }
            }
            for (r in board.rows) {
                item(key = r.address) {
                    val value = if (problem != null) 0 else when (r.state) { Board.WAITING -> 2; Board.QUIET -> 1; else -> 0 }
                    Column(Modifier.fillMaxWidth().clickable { onOpen(r.address) }.padding(horizontal = 12.dp, vertical = 5.dp)) {
                        Row {
                            Text(r.glyph ?: "·", color = Palette.forValue(value), fontFamily = Palette.mono, fontSize = 15.sp)
                            Text(" ${r.name}", color = Palette.forValue(value), fontFamily = Palette.mono, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        (r.word ?: "").let { if (it.isNotEmpty()) Text("  $it", color = Palette.dim, fontFamily = Palette.mono, fontSize = 12.sp, maxLines = 1) }
                        if (r.text.isNotEmpty()) Text("  ${r.text}", color = Palette.dim, fontFamily = Palette.mono, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                Text("─ ${summary.text}${if (summary.title.isEmpty()) "" else " · " + summary.title} ─", color = Palette.forValue(summary.value), fontFamily = Palette.mono, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
