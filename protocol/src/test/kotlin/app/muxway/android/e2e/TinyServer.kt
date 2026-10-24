package app.muxway.android.e2e

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

class Request(val method: String, val uri: String, val headers: Map<String, String>, val body: ByteArray) {
    val path get() = uri.substringBefore('?')
    val query get() = uri.substringAfter('?', "")
    fun header(name: String) = headers[name.lowercase()]
}

class Response(val status: Int, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap())

/** A one-request-per-connection HTTP server on loopback, for tests that need a server that misbehaves. */
class TinyServer(private val handler: (Request) -> Response) : Closeable {
    private val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    val port: Int = socket.localPort
    val url = "http://127.0.0.1:$port"

    init {
        thread(isDaemon = true, name = "tiny-http-$port") {
            while (true) {
                val s = try { socket.accept() } catch (e: SocketException) { return@thread }
                thread(isDaemon = true) { s.use { serve(it) } }
            }
        }
    }

    private fun serve(s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        val line = readLine(input) ?: return
        val parts = line.split(' ')
        if (parts.size < 2) return
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: return
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val r = try { handler(Request(parts[0], parts[1], headers, body)) } catch (e: Exception) { Response(500, e.toString().toByteArray()) }
        val out = s.getOutputStream()
        val head = StringBuilder("HTTP/1.1 ${r.status} ${reason(r.status)}\r\nConnection: close\r\n")
        for ((k, v) in r.headers) head.append(k).append(": ").append(v).append("\r\n")
        if (r.status != 304) head.append("Content-Length: ${r.body.size}\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (r.status != 304 && r.status != 204) out.write(r.body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buf.isEmpty()) null else buf.toString()
            if (c == '\n'.code) return buf.toString().trimEnd('\r')
            buf.append(c.toChar())
            if (buf.length > 16 * 1024) return null
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 204 -> "No Content"; 302 -> "Found"; 304 -> "Not Modified"; 400 -> "Bad Request"
        401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 503 -> "Service Unavailable"; else -> "Status"
    }

    override fun close() = socket.close()
}
