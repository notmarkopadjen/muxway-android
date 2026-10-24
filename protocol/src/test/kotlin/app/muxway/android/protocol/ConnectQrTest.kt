package app.muxway.android.protocol

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The script's code, read back with the same decoder the app uses. */
class ConnectQrTest {
    private val script = listOf(File("../scripts/connect-qr.py"), File("scripts/connect-qr.py")).firstOrNull { it.isFile }
    private val token = "mxt_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"

    private fun run(vararg args: String, stdin: String = ""): String {
        val p = ProcessBuilder(listOf("python3", "-I", script!!.path) + args).redirectErrorStream(true).start()
        p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(out, 0, p.waitFor())
        return out
    }

    private fun decode(modules: List<String>): String {
        val n = modules.size
        val scale = 4
        val quiet = 4 * scale
        val size = n * scale + 2 * quiet
        val pixels = IntArray(size * size) { -1 }
        for (y in 0 until n) for (x in 0 until n) if (modules[y][x] == '1') {
            for (dy in 0 until scale) for (dx in 0 until scale) pixels[(quiet + y * scale + dy) * size + quiet + x * scale + dx] = 0xFF000000.toInt()
        }
        val reader = MultiFormatReader()
        reader.setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        return reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))).text
    }

    @Test
    fun theCodeDecodesToTheHubAndTheToken() {
        assumeTrue(script != null)
        val modules = run("https://hub.example.com", token, "--modules").trim().lines()
        assertEquals(modules.size, modules[0].length)
        val code = Setup.parse(decode(modules))!!
        assertEquals("https://hub.example.com", code.hub)
        assertEquals(token, code.token)
    }

    @Test
    fun longerHubUrlsAndTokensFromStdinStillDecode() {
        assumeTrue(script != null)
        val hub = "https://tmux.hub.example.com:8443/some/longer/path"
        val modules = run(hub, "--modules", stdin = "token phone\n$token\nexpires 2027\n").trim().lines()
        val code = Setup.parse(decode(modules))!!
        assertEquals(hub, code.hub)
        assertEquals(token, code.token)
    }

    @Test
    fun theDrawnCodeNeverPrintsTheToken() {
        assumeTrue(script != null)
        val out = run("https://hub.example.com", token)
        assertFalse(out.contains("mxt_"))
        assertTrue(out.contains("▀") || out.contains("▄") || out.contains("█"))
    }
}
