package app.muxway.android.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import app.muxway.android.AppModel
import app.muxway.android.protocol.Api
import app.muxway.android.protocol.Keys
import app.muxway.android.protocol.Share
import app.muxway.android.protocol.Shares
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The share sheet, the browser's share window: a writable pane's live shares with *revoke*, and
 * the form that makes one, a password and a lifetime. A new share's link, password and code are
 * shown once; the password leaves with *done* and the hub keeps only its hash. The code and the
 * image hold the link alone, so the link and the password travel apart.
 */
@Composable
fun ShareSheet(model: AppModel, key: String, onClose: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val hub = Keys.hub(key)
    val pane = model.panes.firstOrNull { it.key == key } ?: model.view(key).pane
    val all = model.connections.firstOrNull { it.id == hub }?.shares.orEmpty()
    val mine = remember(all, pane) { if (pane != null) Shares.of(all, pane) else emptyList() }
    var password by remember(key) { mutableStateOf(Shares.suggest()) }
    var ttl by remember(key) { mutableStateOf(86400) }
    // The share just made, with its password: the sheet's alone, gone with done or the sheet.
    var made by remember(key) { mutableStateOf<Share?>(null) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(note) { if (note.isNotEmpty()) { delay(2_000); note = "" } }
    LaunchedEffect(hub) {
        while (true) {
            model.fetchShares(hub)
            delay(10_000)
        }
    }
    val code = remember(made?.url) { made?.url?.let { qr(it) } }
    val create = {
        val pw = password.trim()
        if (!model.shareBusy && Shares.validPassword(pw)) model.share(key, ttl, pw) { made = it; password = Shares.suggest() }
    }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(p.bg).border(1.dp, p.ink).padding(4.dp).testTag("share-sheet")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("┌─ share ─ ", color = p.dim, maxLines = 1)
                Text("read only ", color = p.ink, maxLines = 1)
                Rule(Modifier.weight(1f))
                Btn("×", onClose)
            }
            Rule()
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
                if (pane != null) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        PaneName(pane, p.ink, p.dim, modifier = Modifier.weight(1f, fill = false))
                        Text("  ${pane.address}", color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
                if (mine.isEmpty()) Text("  not shared", color = p.dim, maxLines = 1, modifier = Modifier.padding(vertical = 4.dp))
                for (s in mine) {
                    Row(Modifier.fillMaxWidth().testTag("share-row:${s.id}"), verticalAlignment = Alignment.CenterVertically) {
                        Text("  until ${Shares.until(s.expires)}", color = p.ink, maxLines = 1)
                        Text(" · " + (if (s.watching > 0) "${s.watching} watching" else "nobody watching"), color = p.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Btn("revoke", { model.revokeShare(key, s.id); if (made?.id == s.id) made = null }, modifier = Modifier.testTag("share-revoke"))
                    }
                }
                Rule()
                val m = made
                when {
                    pane?.writable != true -> Text("  only a writer of the pane may share it", color = p.dim, maxLines = 1, modifier = Modifier.padding(vertical = 4.dp))
                    m == null -> {
                        Field("password", password, { password = it; model.shareError = null }, hint = "8 to 64 characters", onEnter = create)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("for ", color = p.dim, maxLines = 1)
                            for ((label, seconds) in Shares.LIFETIMES) Btn(label, { ttl = seconds }, selected = ttl == seconds, modifier = Modifier.testTag("share-ttl:$seconds"))
                            Spacer(Modifier.weight(1f))
                            Btn(if (model.shareBusy) "…" else "create", create, enabled = !model.shareBusy && Shares.validPassword(password.trim()), modifier = Modifier.testTag("share-create"))
                        }
                    }
                    else -> {
                        Text("  shared until ${Shares.until(m.expires)}. the password is shown only now, never again:", color = p.ink, modifier = Modifier.padding(vertical = 4.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("  link      ", color = p.dim, maxLines = 1)
                            Text(m.url, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("share-url"))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(16.dp))
                            Btn("copy link", { model.copyText(m.url, sensitive = false); note = "link copied" }, modifier = Modifier.testTag("share-copy-link"))
                            Btn("share link", { shareText(context, m.url) })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("  password  ", color = p.dim, maxLines = 1)
                            Text(m.password, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("share-password"))
                            Btn("copy password", { model.copyText(m.password, sensitive = true); note = "password copied" }, modifier = Modifier.testTag("share-copy-password"))
                        }
                        Text("  send the link and the password apart; the image holds the link only.", color = p.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (code != null) Image(code, contentDescription = "the share's link, as a code", modifier = Modifier.padding(4.dp).size(140.dp), filterQuality = FilterQuality.None)
                            Column {
                                Btn("share image", { code?.let { shareImage(context, it) } }, enabled = code != null)
                                Btn("save image", { code?.let { note = saveDownload(context, "muxway-share.png", Api.Fetched("image/png", png(it))) } }, enabled = code != null)
                                Btn("done", { made = null }, modifier = Modifier.testTag("share-done"))
                            }
                        }
                    }
                }
                val under = model.shareError?.let { "! $it" } ?: note.ifEmpty { "  the observer sees this pane's screen and the web links in it; nothing else" }
                Text(under, color = if (model.shareError != null) p.alert else p.dim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** The link as a QR code, black on white with the quiet zone, eight pixels a module; null when it does not fit. */
private fun qr(text: String): ImageBitmap? = try {
    val hints = mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val cell = 8
    val pixels = IntArray(m.width * cell * m.height * cell) { android.graphics.Color.WHITE }
    for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) {
        for (dy in 0 until cell) for (dx in 0 until cell) pixels[(y * cell + dy) * m.width * cell + x * cell + dx] = android.graphics.Color.BLACK
    }
    Bitmap.createBitmap(pixels, m.width * cell, m.height * cell, Bitmap.Config.ARGB_8888).asImageBitmap()
} catch (e: Exception) {
    null
}

private fun png(image: ImageBitmap): ByteArray = ByteArrayOutputStream().also { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

/** The text to another app through the system's share sheet. */
private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: Exception) {
        // Nothing takes text: the copy button is still there.
    }
}

/** The code as a PNG to another app, read-only from the app's cache through its file provider. */
private fun shareImage(context: Context, image: ImageBitmap) {
    val dir = File(context.cacheDir, "shown").also { it.mkdirs() }
    val file = File(dir, "muxway-share.png")
    file.writeBytes(png(image))
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = android.content.ClipData.newUri(context.contentResolver, "muxway-share.png", uri)
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: Exception) {
        // Nothing takes an image: save image is still there.
    }
}
