package app.muxway.android.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import app.muxway.android.protocol.Change
import app.muxway.android.protocol.Changes
import app.muxway.android.protocol.Code
import app.muxway.android.protocol.Diff
import app.muxway.android.protocol.Links
import app.muxway.android.protocol.Markdown
import app.muxway.android.AppModel
import app.muxway.android.protocol.Api
import app.muxway.android.protocol.Files
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A file of the host to show: one of the inbox, by name, or a printed path the host previews;
 * with a [change], one of the repository's, which opens as its diff or as it is.
 */
data class Shown(val address: String, val name: String? = null, val path: String? = null, val change: Change? = null) {
    val label get() = name ?: path ?: ""
    val fileName get() = name ?: path?.substringAfterLast('/')?.ifEmpty { null } ?: "file"
}

/**
 * The file over the pane, the browser's window on a phone: an image as itself, a text as it is,
 * Markdown rendered (with a `plain` switch), a PDF framed page by page, anything else as its
 * download only, with the name, the size, `⤓` to save it under Downloads, `open` to hand it to
 * the system for the types it shows, and `×`. A path in a rendered file opens through [onOpen],
 * from the same pane.
 */
@Composable
fun FileSheet(model: AppModel, shown: Shown, onOpen: (Shown) -> Unit = {}, onClose: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var got by remember(shown) { mutableStateOf<Api.Fetched?>(null) }
    var failed by remember(shown) { mutableStateOf(false) }
    val markdown = shown.fileName.endsWith(".md", true) || shown.fileName.endsWith(".markdown", true)
    var rendered by remember(shown) { mutableStateOf(true) }
    // A change opens on its diff when it has one; the file is a tap away. Each is asked for once.
    val views = shown.change?.let { Changes.views(it.kind) } ?: listOf("file")
    var side by remember(shown) { mutableStateOf(views[0]) }
    var diff by remember(shown) { mutableStateOf<Diff?>(null) }
    var diffFailed by remember(shown) { mutableStateOf(false) }
    var asked by remember(shown) { mutableStateOf(setOf<String>()) }
    LaunchedEffect(shown, side) {
        if (side in asked) return@LaunchedEffect
        asked = asked + side
        if (side == "file") model.fetchFile(shown.address, shown.name, shown.path) { got = it; failed = it == null }
        else model.fetchDiff(shown.address, shown.change!!.path) { diff = it; diffFailed = it == null }
    }
    val f = if (side == "file") got else null
    val lang = remember(shown) { Code.languageOf(shown.fileName) }
    val text = f != null && f.type.startsWith("text/")
    val image = f != null && f.type.startsWith("image/")
    val bitmap = remember(f) { if (image && f != null) BitmapFactory.decodeByteArray(f.bytes, 0, f.bytes.size) else null }
    val pdf = f != null && f.type == "application/pdf"
    val pages by produceState<List<ImageBitmap>?>(null, f) {
        value = if (pdf && f != null) withContext(Dispatchers.IO) { renderPdf(context, shown.fileName, f) } else null
    }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(p.bg).border(1.dp, p.ink).padding(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("┌─ file ─ ", color = p.dim, maxLines = 1)
                Text(shown.label, color = p.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (f != null) Text(" ${Files.size(f.bytes.size.toLong())} ", color = p.dim, maxLines = 1)
                if (views.size > 1) for (v in views) Btn(v, { side = v }, selected = side == v, modifier = Modifier.testTag("side:$v"))
                if (f != null && text && markdown) Btn("plain", { rendered = !rendered }, selected = !rendered)
                if (f != null) Btn("⤓", { model.told = save(context, shown.fileName, f) })
                if (f != null && !text && !image && !pdf) Btn("open", { open(context, shown.fileName, f) })
                Btn("×", onClose)
            }
            Rule()
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 480.dp).padding(top = 4.dp)) {
                when {
                    side == "diff" -> when {
                        diffFailed -> Text("the host has no diff for this path", color = p.alert, modifier = Modifier.padding(8.dp))
                        diff == null -> Text("loading…", color = p.dim, modifier = Modifier.padding(8.dp))
                        else -> DiffView(diff!!, p)
                    }
                    failed -> Text(
                        if (shown.name != null) "gone from the host" else "the host does not preview this file",
                        color = p.alert, modifier = Modifier.padding(8.dp),
                    )
                    f == null -> Text("loading…", color = p.dim, modifier = Modifier.padding(8.dp))
                    bitmap != null -> Image(bitmap.asImageBitmap(), contentDescription = shown.label, modifier = Modifier.fillMaxWidth())
                    pdf -> when {
                        pages == null -> Text("drawing…", color = p.dim, modifier = Modifier.padding(8.dp))
                        pages!!.isEmpty() -> Text("could not draw this PDF; ⤓ saves it", color = p.dim, modifier = Modifier.padding(8.dp))
                        else -> LazyColumn(Modifier.fillMaxWidth().testTag("pdf")) {
                            items(pages!!.size) { i ->
                                Image(pages!![i], contentDescription = "page ${i + 1}", modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).border(1.dp, p.dim))
                            }
                            if (pages!!.size == PDF_PAGES) item { Text("the first $PDF_PAGES pages; ⤓ saves the whole file", color = p.dim, modifier = Modifier.padding(4.dp)) }
                        }
                    }
                    text && markdown && rendered -> {
                        val blocks = remember(f) { Markdown.parse(String(f.bytes, Charsets.UTF_8)) }
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("rendered")) {
                            Blocks(blocks, p) { path -> onOpen(Shown(shown.address, path = path)) }
                        }
                    }
                    text -> {
                        // Code in its colours, by the file's extension; anything else as it is.
                        val glow = LocalEffects.current && !p.light
                        val lines = remember(f, glow) { String(f.bytes, Charsets.UTF_8).let { s -> if (lang != null) Code.lines(Code.tokenize(lang, s)).map { coloured(it, p, glow) } else s.split('\n').map { AnnotatedString(it) } } }
                        val sideways = rememberScrollState()
                        LazyColumn(Modifier.fillMaxWidth().horizontalScroll(sideways)) {
                            items(lines.size) { i -> Text(lines[i], color = p.ink, maxLines = 1, softWrap = false) }
                        }
                    }
                    else -> Text("no preview for this type; ⤓ saves it, open hands it to the system", color = p.dim, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}

/** Markdown's blocks as the terminal would draw them: no picture, every mark a character. */
@Composable
private fun Blocks(blocks: List<Markdown.Block>, p: Palette, onPath: (String) -> Unit) {
    for (b in blocks) when (b) {
        is Markdown.Block.Heading -> {
            Text(inline(b.text, p, onPath), color = if (b.level <= 2) p.accent else p.ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
            if (b.level == 1) Rule()
        }
        is Markdown.Block.Para -> Text(inline(b.text.replace('\n', ' '), p, onPath), color = p.ink, modifier = Modifier.padding(vertical = 2.dp))
        is Markdown.Block.Code -> {
            // The fence's language picks the colours.
            val sideways = rememberScrollState()
            val lang = Code.languageOf(b.lang)
            val glow = LocalEffects.current && !p.light
            val lines = if (lang != null) Code.lines(Code.tokenize(lang, b.text)).map { coloured(it, p, glow) } else b.text.split('\n').map { AnnotatedString(it) }
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(p.panel).padding(4.dp).horizontalScroll(sideways)) {
                for (line in lines) Text(if (line.isEmpty()) AnnotatedString(" ") else line, color = p.ink, maxLines = 1, softWrap = false)
            }
        }
        is Markdown.Block.Quote -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text("│ ", color = p.dim)
            Column(Modifier.weight(1f)) { Blocks(b.blocks, p, onPath) }
        }
        is Markdown.Block.Items -> for ((i, item) in b.items.withIndex()) Row(Modifier.fillMaxWidth()) {
            Text(if (b.ordered) "${i + 1}. " else "• ", color = p.dim)
            Column(Modifier.weight(1f)) { Blocks(item, p, onPath) }
        }
        is Markdown.Block.Table -> {
            val widths = b.head.indices.map { c -> maxOf(b.head[c].length, b.rows.maxOfOrNull { it.getOrElse(c) { "" }.length } ?: 0) }
            val row: (List<String>) -> String = { cells -> widths.indices.joinToString(" │ ") { c -> cells.getOrElse(c) { "" }.padEnd(widths[c]) } }
            val sideways = rememberScrollState()
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).horizontalScroll(sideways)) {
                Text(row(b.head), color = p.ink, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                Text(widths.joinToString("─┼─") { "─".repeat(it) }, color = p.dim, maxLines = 1, softWrap = false)
                for (r in b.rows) Text(inline(row(r), p, onPath), color = p.ink, maxLines = 1, softWrap = false)
            }
        }
        is Markdown.Block.Rule -> Rule(Modifier.padding(vertical = 4.dp))
    }
}

/** A diff as rows of text: added rows in the ok colour, removed rows dim and struck, hunk and file headers dim. */
@Composable
private fun DiffView(d: Diff, p: Palette) {
    if (d.binary) { Text("a binary file: no text to show", color = p.dim, modifier = Modifier.padding(8.dp)); return }
    val lines = remember(d) { d.diff.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it } }
    val sideways = rememberScrollState()
    LazyColumn(Modifier.fillMaxWidth().horizontalScroll(sideways).testTag("diff")) {
        items(lines.size) { i ->
            val l = lines[i]
            when (Changes.rowKind(l)) {
                '+' -> Text(l, color = p.ok, maxLines = 1, softWrap = false)
                '-' -> Text(l, color = p.dim, maxLines = 1, softWrap = false, textDecoration = TextDecoration.LineThrough)
                '@', 'h' -> Text(l, color = p.dim, maxLines = 1, softWrap = false)
                else -> Text(l.ifEmpty { " " }, color = p.ink, maxLines = 1, softWrap = false)
            }
        }
        if (d.cut) item { Text("… cut at 1 MiB", color = p.dim, maxLines = 1) }
        if (lines.isEmpty()) item { Text("no difference", color = p.dim, maxLines = 1) }
    }
}

/**
 * One line of code in its colours, the browser's: comments dim and italic, strings, numbers,
 * keywords (bold too) and names each in the palette's hue, glowing in it where text glows,
 * punctuation dim.
 */
private fun coloured(runs: List<Code.Run>, p: Palette, glow: Boolean): AnnotatedString = buildAnnotatedString {
    fun hue(c: Color, bold: Boolean = false) = SpanStyle(
        color = c,
        fontWeight = if (bold) FontWeight.Bold else null,
        shadow = if (glow) Shadow(c.copy(alpha = 0.5f), blurRadius = 4f) else null,
    )
    for (r in runs) {
        val style = when (r.kind) {
            Code.Kind.COMMENT -> SpanStyle(color = p.dim, fontStyle = FontStyle.Italic)
            Code.Kind.STRING -> hue(p.syntax.string)
            Code.Kind.NUMBER -> hue(p.syntax.number)
            Code.Kind.KEYWORD -> hue(p.syntax.keyword, bold = true)
            Code.Kind.NAME -> hue(p.syntax.name)
            Code.Kind.PUNCT -> SpanStyle(color = p.dim)
            Code.Kind.TEXT -> null
        }
        if (style == null) append(r.text) else withStyle(style) { append(r.text) }
    }
}

private val linkStyle = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))

/** The inline runs with their styles; web addresses and the host's paths in them are links. */
private fun inline(text: String, p: Palette, onPath: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    for (s in Markdown.inline(text)) {
        val start = length
        val style = SpanStyle(
            color = if (s.code) p.accent else Color.Unspecified,
            background = if (s.code) p.panel else Color.Unspecified,
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            textDecoration = if (s.strike) TextDecoration.LineThrough else null,
        )
        withStyle(style) { append(s.text) }
        val href = s.href
        when {
            href != null -> addLink(LinkAnnotation.Url(href, linkStyle), start, length)
            !s.code -> {
                val links = Links.find(s.text)
                for (l in links) addLink(LinkAnnotation.Url(l.href, linkStyle), start + l.start, start + l.end)
                for (l in Links.findPaths(s.text)) if (links.none { it.start < l.end && l.start < it.end }) {
                    addLink(LinkAnnotation.Clickable(l.path, linkStyle) { onPath(l.path) }, start + l.start, start + l.end)
                }
            }
        }
    }
}

/** The bytes into Downloads under the file's own name; what the notice says. */
private fun save(context: android.content.Context, name: String, f: Api.Fetched): String = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, f.type)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("no downloads")
        resolver.openOutputStream(uri)?.use { it.write(f.bytes) } ?: throw IllegalStateException("no downloads")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } else {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        File(dir, name).writeBytes(f.bytes)
    }
    "saved to Downloads: $name"
} catch (e: Exception) {
    "could not save: ${e.message ?: "refused"}"
}

/** How many pages of a PDF are drawn, and how wide; the rest is the download's. */
private const val PDF_PAGES = 20
private const val PDF_WIDTH = 800

/** The PDF's pages as pictures, framed by the sheet; none when the platform cannot read it. */
private fun renderPdf(context: android.content.Context, name: String, f: Api.Fetched): List<ImageBitmap> = try {
    val dir = File(context.cacheDir, "shown").also { it.mkdirs() }
    val file = File(dir, name)
    file.writeBytes(f.bytes)
    val out = ArrayList<ImageBitmap>()
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { r ->
            for (i in 0 until minOf(r.pageCount, PDF_PAGES)) r.openPage(i).use { page ->
                val height = (PDF_WIDTH.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, PDF_WIDTH * 4)
                val b = Bitmap.createBitmap(PDF_WIDTH, height, Bitmap.Config.ARGB_8888)
                b.eraseColor(android.graphics.Color.WHITE)
                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                out.add(b.asImageBitmap())
            }
        }
    }
    file.delete()
    out
} catch (e: Exception) {
    emptyList()
}

/** The bytes into the app's cache and off to whatever the system opens the type with, read-only. */
private fun open(context: android.content.Context, name: String, f: Api.Fetched) {
    val dir = File(context.cacheDir, "shown").also { it.mkdirs() }
    val file = File(dir, name)
    file.writeBytes(f.bytes)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, name))
    } catch (e: Exception) {
        // Nothing opens the type: the download is still there.
    }
}
