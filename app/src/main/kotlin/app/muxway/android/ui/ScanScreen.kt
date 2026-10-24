package app.muxway.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.muxway.android.AppModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Points the camera at a connect code; a good one adds the hub with its token and opens the
 * panes. For a hub that has a token already, the person says whether the new one replaces it.
 */
@Composable
fun ScanScreen(model: AppModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    var status by remember { mutableStateOf("point the camera at the code from connect-qr.py") }
    var last by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(4.dp)) {
        Frame("muxway", crumb = "scan") { Btn("back", { model.back() }) }
        Box(Modifier.fillMaxWidth().weight(1f).padding(4.dp).border(1.dp, p.ink), contentAlignment = Alignment.Center) {
            when {
                granted -> CameraPreview { text ->
                    if (text == last) return@CameraPreview
                    last = text
                    model.connectWithCode(text)?.let { status = "! $it" }
                }
                asked -> Text("camera refused · paste the token instead", color = p.dim)
                else -> Text("camera permission…", color = p.dim)
            }
        }
        model.pendingCode?.let { (_, hub) ->
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${hub.name} has a token: ", color = p.ink)
                Btn("replace it", { model.replaceWithCode()?.let { status = "! $it" } })
                Btn("keep it", { model.keepToken() })
            }
        }
        Text(" $status", color = if (status.startsWith("!")) p.alert else p.dim, modifier = Modifier.padding(4.dp))
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor, QrAnalyzer { text -> view.post { onCode(text) } })
                provider.unbindAll()
                try {
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: Exception) {
                    // No usable camera; the status line already tells the user to paste instead.
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}

/** Decodes QR codes from the luminance plane of each frame; frames are dropped once looked at. */
private class QrAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val buf = plane.buffer
            buf.rewind()
            val data = ByteArray(w * h)
            if (plane.rowStride == w && plane.pixelStride == 1) {
                buf.get(data, 0, minOf(data.size, buf.remaining()))
            } else {
                val row = ByteArray(plane.rowStride)
                for (y in 0 until h) {
                    val at = y * plane.rowStride
                    if (at >= buf.limit()) break
                    buf.position(at)
                    val n = minOf(plane.rowStride, buf.remaining())
                    buf.get(row, 0, n)
                    for (x in 0 until w) {
                        val i = x * plane.pixelStride
                        if (i < n) data[y * w + x] = row[i]
                    }
                }
            }
            val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
            val text = decode(source) ?: decode(source.invert())
            if (text != null) onCode(text)
        } finally {
            image.close()
        }
    }

    private fun decode(source: LuminanceSource): String? = try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: NotFoundException) {
        null
    } finally {
        reader.reset()
    }
}
