package app.muxway.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.muxway.android.AppModel
import app.muxway.android.MailTarget
import app.muxway.android.protocol.Feed
import app.muxway.android.protocol.Inbox
import app.muxway.android.protocol.Keys
import app.muxway.android.protocol.MailRows
import kotlinx.coroutines.delay

/**
 * The mail sheet, the browser's mail window: one pane's mail in full, what came in (`←`, the
 * sender or *you*, `unread` until the process read it, `replied`) and what it sent (`→`, whole
 * where the token reads the recipient's mailbox, else the feed's excerpt marked cut), merged by
 * time, oldest at the top, the mail asked for framed. The line at the bottom mails the pane or,
 * after *mail back*, whoever wrote to it: a message for its process, never typed. Reading here
 * takes nothing from the process. Mail is text: nothing in it is markup or a link.
 */
@Composable
fun MailSheet(model: AppModel, target: MailTarget, onClose: () -> Unit) {
    val p = LocalPalette.current
    val key = target.key
    val hub = Keys.hub(key)
    val address = Keys.address(key)
    val now = ticker()
    val pane = model.panes.firstOrNull { it.key == key } ?: model.view(key).pane
    val box = model.mailboxes[key].orEmpty()
    val feed = model.talk.filter { it.hub == hub }
    val listed = model.panes
    // The partners whose mailboxes give the whole texts of what went to them.
    val sentTo = remember(pane, box, feed, listed) { if (pane != null) MailRows.sentTo(pane, box, feed, listed) else emptyList() }
    LaunchedEffect(key, sentTo) {
        while (true) {
            model.fetchMailbox(key)
            for (a in sentTo) model.fetchMailbox(Keys.make(hub, a))
            model.fetchTalk(hub)
            delay(5_000)
        }
    }
    val boxes = sentTo.associateWith { model.mailboxes[Keys.make(hub, it)].orEmpty() }
    val rows = remember(pane, box, feed, boxes) { if (pane != null) MailRows.rows(pane, box, feed, boxes) else emptyList() }
    val targets = listOf(address) + MailRows.partners(rows, address)
    var to by remember(key) { mutableStateOf(address) }
    var text by remember(key) { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun writable(a: String) = listed.any { it.hub == hub && it.address == a && it.writable }
    fun name(a: String, id: String = "") = Feed.nameOf(listed, hub, a, id)
    val send = {
        val t = text.trim()
        if (t.isNotEmpty() && !model.mailing && writable(to)) model.mail(Keys.make(hub, to), t) { text = "" }
    }
    // The mail asked for, else the newest, comes into view once.
    val list = rememberLazyListState()
    var brought by remember(key) { mutableStateOf(false) }
    LaunchedEffect(rows.size) {
        if (brought || rows.isEmpty()) return@LaunchedEffect
        brought = true
        val at = if (target.mailId > 0) rows.indexOfFirst { !it.out && it.id == target.mailId } else -1
        list.scrollToItem(if (at >= 0) at else rows.lastIndex)
    }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(p.bg).border(1.dp, p.ink).padding(4.dp).testTag("mail-sheet")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("┌─ mail ─ ", color = p.dim, maxLines = 1)
                Text(pane?.name ?: address, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(" ", maxLines = 1)
                Rule(Modifier.weight(1f))
                Btn("×", onClose)
            }
            Rule()
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 400.dp).padding(top = 4.dp)) {
                if (rows.isEmpty()) Text(model.mailboxErrors[key] ?: "no mail yet", color = p.dim, modifier = Modifier.padding(8.dp))
                else LazyColumn(Modifier.fillMaxWidth(), state = list) {
                    items(rows.size, key = { rows[it].key }) { i ->
                        val r = rows[i]
                        val framed = target.mailId > 0 && !r.out && r.id == target.mailId
                        Column(
                            Modifier.fillMaxWidth().then(if (framed) Modifier.border(1.dp, p.ink) else Modifier).padding(horizontal = 4.dp, vertical = 6.dp)
                                .testTag("mail-row:${if (r.out) "out" else "in"}:${r.id}"),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (r.out) "→ " else "← ", color = p.talk, maxLines = 1)
                                Text(r.who?.let { name(it.address, it.paneId) } ?: "you", color = p.talk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                val meta = " · ${Inbox.ago(r.at, now)} · ${if (r.yours) "you (${r.actor})" else r.actor}" + (if (r.replyTo > 0) " · answers ${r.replyTo}" else "")
                                Text(meta, color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (r.cut) Text(" cut", color = p.dim, maxLines = 1)
                                when (r.unread) {
                                    true -> Text(" unread", color = p.attention, maxLines = 1, modifier = Modifier.testTag("mail-unread"))
                                    false -> Text(" read", color = p.dim, maxLines = 1)
                                    null -> {}
                                }
                                if (r.replied) Text(" · replied", color = p.dim, maxLines = 1)
                                val sender = r.who?.address
                                if (!r.out && sender != null) {
                                    val can = writable(sender)
                                    Text(
                                        "mail back", color = if (can) p.ink else p.faint, maxLines = 1,
                                        modifier = Modifier.padding(start = 6.dp).border(1.dp, if (can) p.dim else p.faint)
                                            .clickable(enabled = can) { to = sender; focus.requestFocus() }.padding(horizontal = 4.dp).testTag("mail-back"),
                                    )
                                }
                            }
                            Text(r.text, color = p.ink, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
                        }
                        Rule()
                    }
                }
            }
            // The line: the target, picked among the pane and its partners, then the text.
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✉ mail to ", color = p.dim, maxLines = 1)
                Box {
                    Btn(name(to) + (if (writable(to)) "" else " · read-only") + " ▾", { picking = !picking }, selected = picking, modifier = Modifier.testTag("mail-to"))
                    if (picking) Popup(alignment = Alignment.BottomStart, onDismissRequest = { picking = false }, properties = PopupProperties(focusable = true)) {
                        Column(Modifier.width(240.dp).border(1.dp, p.ink).background(p.panel).padding(4.dp)) {
                            for (a in targets) {
                                val can = writable(a)
                                Row(
                                    Modifier.fillMaxWidth().clickable(enabled = can) { to = a; picking = false }.padding(horizontal = 4.dp, vertical = 6.dp).testTag("mail-target:$a"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(name(a), color = if (can) p.ink else p.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (!can) Text("  read-only", color = p.dim, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            Field("  ", text, { text = it; model.mailError = null }, hint = "a message for its process; nothing is typed", focusRequester = focus, onEnter = send, maxLines = 4,
                keyboard = KeyboardOptions(imeAction = ImeAction.Send), actions = KeyboardActions(onSend = { send() }))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val under = model.mailError?.let { "! $it" } ?: if (writable(to)) "  nothing is typed; the count stands until the process reads" else "  you may not write to that pane"
                Text(under, color = if (model.mailError != null) p.alert else p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Btn(if (model.mailing) "…" else "send", send, enabled = !model.mailing && writable(to) && text.isNotBlank(), modifier = Modifier.testTag("mail-send"))
            }
        }
    }
    LaunchedEffect(key) { if (target.line) focus.requestFocus() }
}

/**
 * The messages sheet, the browser's messages window: what panes said to panes, mail and typed,
 * on every hub, oldest at the top, two lines a row. A tap on a mail row opens the mail sheet of
 * the pane it went to with that mail framed; a tap on a typed row, and `⇆` on any, lays the two
 * panes side by side. Opening it clears the count in the header.
 */
@Composable
fun MessagesSheet(model: AppModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val now = ticker()
    LaunchedEffect(Unit) {
        model.markTalkSeen()
        while (true) {
            model.fetchTalk()
            delay(5_000)
        }
    }
    val rows = model.talk
    val list = rememberLazyListState()
    var brought by remember { mutableStateOf(false) }
    LaunchedEffect(rows.size) {
        if (!brought && rows.isNotEmpty()) { brought = true; list.scrollToItem(rows.lastIndex) }
    }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(p.bg).border(1.dp, p.ink).padding(4.dp).testTag("messages")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("┌─ messages ─ ", color = p.dim, maxLines = 1)
                Text("${rows.size} ", color = p.ink, maxLines = 1)
                Rule(Modifier.weight(1f))
                Btn("×", onClose)
            }
            Rule()
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 480.dp).padding(top = 4.dp)) {
                if (rows.isEmpty()) Text("no pane talked to another lately", color = p.dim, modifier = Modifier.padding(8.dp))
                else LazyColumn(Modifier.fillMaxWidth(), state = list) {
                    items(rows.size, key = { "${rows[it].hub}:${rows[it].seq}" }) { i ->
                        val e = rows[i]
                        val from = e.from?.let { Feed.nameOf(model.panes, e.hub, it.address, it.paneId) } ?: "you"
                        val to = Feed.nameOf(model.panes, e.hub, e.to.address, e.to.paneId)
                        val beside = { onClose(); model.openBeside(e) }
                        // A mail is read where it went; a typed write has nothing to read, so it lays the two beside.
                        val open = { if (e.kind == "mail" && e.id > 0) { onClose(); model.mailOpen = MailTarget(Keys.make(e.hub, e.to.address), e.id) } else beside() }
                        Column(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 4.dp, vertical = 6.dp).testTag("talk-row:${e.hub}:${e.seq}")) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (e.kind == "mail") "✉ " else "⇄ ", color = p.talk, maxLines = 1)
                                Text(from, color = p.talk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Text(" → ", color = p.dim, maxLines = 1)
                                Text(to, color = p.talk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Text(" ${Inbox.ago(e.at, now)} · ${e.actor}", color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(" ⇆ ", color = p.ink, maxLines = 1, modifier = Modifier.border(1.dp, p.dim).clickable(onClick = beside).testTag("talk-beside"))
                            }
                            Text("  " + Feed.excerpt(e), color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Rule()
                    }
                }
            }
        }
    }
}

/** `✉`, or `✉ n` with the entries not looked at yet, for a header beside `⚑ n`; nothing while no pane talked. */
@Composable
fun MessagesCount(model: AppModel) {
    if (model.talk.isEmpty()) return
    val n = model.unseen
    Btn(if (n > 0) "✉ $n" else "✉", { model.messagesOpen = true }, selected = model.messagesOpen, modifier = Modifier.testTag("messages-count"))
}
