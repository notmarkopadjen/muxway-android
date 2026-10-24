package app.muxway.android.e2e

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit

const val HOST = "alice-laptop"

/** Where the suite runs: the core binary and the run directory, both from scripts/e2e.sh. */
object Env {
    val bin: String? = System.getenv("MUXWAY_BIN")?.takeIf { File(it).canExecute() }
    val runDir: File = File(System.getenv("MUXWAY_RUN_DIR") ?: Files.createTempDirectory("muxway-android-run").toString())
    val secrets = java.util.Collections.synchronizedList(ArrayList<String>())

    fun logs(test: String): File = File(runDir, "logs/$test").also { it.mkdirs() }
}

fun freePort(): Int = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }

fun waitFor(what: String, seconds: Int = 15, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + seconds * 1000L
    while (System.currentTimeMillis() < end) {
        if (ok()) return
        Thread.sleep(100)
    }
    throw AssertionError("timed out waiting for $what")
}

/** Hub, agent and a throwaway tmux server for one test. */
class Core(private val logs: File) {
    private val bin = Env.bin!!
    val dir: File = Files.createTempDirectory("mxw-").toFile().also { it.setReadable(false, false); it.setReadable(true, true) }
    val port = freePort()
    val url = "http://127.0.0.1:$port"
    val tmux = "mxw-android-$port-${java.util.UUID.randomUUID().toString().take(6)}"
    private val procs = LinkedHashMap<String, Process>()
    val hubDir = File(dir, "hub")
    val agentDir = File(dir, "agent")

    fun cli(vararg args: String): String {
        val p = ProcessBuilder(bin, *args).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw IllegalStateException("muxway ${args.take(3).joinToString(" ")}: ${err.trim()}")
        }
        return out.trim()
    }

    private fun spawn(name: String, vararg args: String) {
        val log = File(logs, "$name.log")
        procs[name] = ProcessBuilder(bin, *args).redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .redirectError(ProcessBuilder.Redirect.appendTo(log)).start()
    }

    fun stop(name: String) {
        val p = procs.remove(name) ?: return
        p.destroy()
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
    }

    fun tmux(vararg args: String): String {
        val p = ProcessBuilder("tmux", "-L", tmux, *args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        return out.trim()
    }

    fun start(expose: String? = "--write", allowSessions: Boolean = false): Core {
        val shell = "env -i PATH=/usr/bin:/bin TERM=xterm LANG=C.UTF-8 PS1='$ ' bash --norc --noprofile"
        tmux("-f", "/dev/null", "new-session", "-d", "-s", "work", "-n", "shell", "-x", "80", "-y", "24", shell)
        // Panes and sessions made later get the same clean shell.
        tmux("set-option", "-g", "default-command", shell)
        tmux("select-pane", "-t", "work:0.0", "-T", "test-pane")
        spawn("hub", "hub", "serve", "--dir", hubDir.path, "--listen", "127.0.0.1:$port", "--public-url", url)
        waitFor("hub") { File(hubDir, "admin.sock").exists() }
        val code = cli("hub", "pair-code", "--dir", hubDir.path, "--host", HOST)
        cli("agent", "pair", "--dir", agentDir.path, code)
        startAgent(allowSessions)
        val args = arrayListOf("expose", "--tmux-name", tmux, "-t", "work:0.0")
        if (expose != null) args.add(expose)
        cli(*args.toTypedArray())
        return this
    }

    fun startAgent(allowSessions: Boolean = false) {
        val args = arrayListOf("agent", "run", "--dir", agentDir.path, "--tmux-name", tmux)
        if (allowSessions) args.add("--allow-sessions")
        spawn("agent", *args.toTypedArray())
        waitFor("agent online") { cli("hub", "agents", "--dir", hubDir.path).contains("$HOST\tonline") }
    }

    fun token(read: String = "$HOST/*", write: String? = "$HOST/*"): String {
        val args = arrayListOf("hub", "token", "create", "--dir", hubDir.path, "--name", "phone", "--read", read)
        if (write != null) { args.add("--write"); args.add(write) }
        return cli(*args.toTypedArray()).also { Env.secrets.add(it) }
    }

    fun revokeAll() {
        for (line in cli("hub", "token", "list", "--dir", hubDir.path).lines().drop(1)) {
            if (line.isNotBlank()) cli("hub", "token", "revoke", "--dir", hubDir.path, line.split(Regex("\\s+"))[0])
        }
    }

    fun paneId(): String = tmux("display-message", "-p", "-t", "work:0.0", "#{pane_id}")

    fun pane(): String = tmux("capture-pane", "-p", "-t", "work:0.0")

    fun command(): String = tmux("display-message", "-p", "-t", "work:0.0", "#{pane_current_command}")

    fun width(): Int = tmux("display-message", "-p", "-t", "work:0.0", "#{pane_width}").trim().toIntOrNull() ?: -1

    fun notify(kind: String, text: String) = cli("notify", "--dir", agentDir.path, "--pane", paneId(), "--kind", kind, "--text", text)

    fun close() {
        for (name in procs.keys.toList()) stop(name)
        tmux("kill-server")
        for (key in listOf("hub/hub.key", "agent/agent.key")) {
            val f = File(dir, key)
            if (f.exists()) Env.secrets.add(f.readText().trim())
        }
        dir.deleteRecursively()
    }
}

class Seen(val method: String, val path: String, val headers: Map<String, String>)

/**
 * A recording proxy in front of the hub, like a reverse proxy a user might run.
 * Modes make it misbehave on purpose for the security tests.
 */
class Relay(private val hub: String, logs: File) {
    var mode = "normal"
    val seen = java.util.Collections.synchronizedList(ArrayList<Seen>())
    private val log = File(logs, "relay.log")
    private val server = TinyServer { handle(it) }
    val url = server.url

    private fun handle(r: Request): Response {
        seen.add(Seen(r.method, r.uri, r.headers))
        log.appendText("${r.method} ${r.uri} headers=${r.headers.keys.sorted()} body=${r.body.size}\n")
        val reading = r.method == "GET" && r.path.startsWith("/v1/panes/")
        val json = mapOf("Content-Type" to "application/json")
        when {
            mode == "big" && reading -> return Response(200, ("{\"pane\":{},\"content\":\"" + "x".repeat(10 shl 20) + "\"}").toByteArray(), json)
            mode == "garbage" && reading -> return Response(200, byteArrayOf(0x6f, 0x6b, 0x20, 0xff.toByte(), 0xfe.toByte(), 0x1b, 0x5b, 0x33, 0x31, 0x6d, 0x07, 0x00, 0x0a), json)
            mode == "html" && reading -> return Response(200, "<html>gateway</html>".toByteArray(), mapOf("Content-Type" to "text/html"))
            mode == "control" && reading -> return Response(200, "{\"pane\":{\"address\":\"$HOST/work/0.0\",\"mode\":\"write\"},\"content\":\"ok \\u001b[31mred\\u0007 \\u0000nul\\r\\nend\\n\"}".toByteArray(), json)
        }
        val c = URL(hub + r.uri).openConnection() as HttpURLConnection
        c.requestMethod = r.method
        c.instanceFollowRedirects = false
        for ((k, v) in r.headers) if (k !in setOf("host", "connection", "content-length")) c.setRequestProperty(k, v)
        if (r.body.isNotEmpty()) { c.doOutput = true; c.outputStream.use { it.write(r.body) } }
        val status = c.responseCode
        val data = (if (status >= 400) c.errorStream else c.inputStream)?.readBytes() ?: ByteArray(0)
        val headers = HashMap<String, String>()
        c.getHeaderField("ETag")?.let { headers["ETag"] = it }
        c.getHeaderField("Content-Type")?.let { headers["Content-Type"] = it }
        c.disconnect()
        return Response(status, data, headers)
    }

    fun close() = server.close()
}
