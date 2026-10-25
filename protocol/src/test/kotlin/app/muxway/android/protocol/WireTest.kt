package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    private val address = "alice-laptop/work/0.1"

    @Test
    fun requestsRoundTrip() {
        val read = Wire.request(Wire.READ, Wire.read("r1", address, "\"a1\"")) as Wire.Read
        assertEquals("r1", read.id)
        assertEquals(address, read.address)
        assertEquals("\"a1\"", read.etag)
        val key = Wire.request(Wire.KEY, Wire.key("k1", address, "Enter")) as Wire.Key
        assertEquals("Enter", key.key)
        val text = Wire.request(Wire.TEXT, Wire.text("t1", address, "y", false)) as Wire.Text
        assertEquals("y", text.text)
        val typed = Wire.request(Wire.TEXT, Wire.text("t3", address, "echo hi\u0007\u001b[2J\n" + "a".repeat(5000), true)) as Wire.Text
        assertEquals(Input.MAX_TEXT_BYTES, typed.text.length)
        assertTrue(typed.text.startsWith("echo hi\naaa"))
        assertEquals("", text.heard)
        assertFalse(text.enter)
        val heard = Wire.request(Wire.TEXT, Wire.heardText("t2", address, "c1", true)) as Wire.Text
        assertEquals("", heard.text)
        assertEquals("c1", heard.heard)
        assertTrue(heard.enter)
    }

    @Test
    fun answersRoundTrip() {
        val tail = Wire.Tail("r1", address, "\"b2\"", listOf("$ make deploy", "Deploy to prod? (y/N)"), true, true)
        assertEquals(tail, Wire.tail(Wire.tail(tail)))
        val unchanged = Wire.Tail("r2", address, "\"b2\"", null, false, false)
        assertEquals(unchanged, Wire.tail(Wire.tail(unchanged)))
        val failed = Wire.Tail("r3", address, "", null, false, false, "refused")
        assertEquals(failed, Wire.tail(Wire.tail(failed)))
        assertEquals(Wire.Done("k1", true), Wire.done(Wire.done(Wire.Done("k1", true))))
        assertEquals(Wire.Done("k2", false, "read_only"), Wire.done(Wire.done(Wire.Done("k2", false, "read_only"))))
        assertEquals(Wire.Heard("c1", "yes deploy it"), Wire.heard(Wire.heard(Wire.Heard("c1", "yes deploy it"))))
        assertEquals(Wire.Heard("c2", "", "too_short"), Wire.heard(Wire.heard(Wire.Heard("c2", "", "too_short"))))
    }

    @Test
    fun aHostileRequestIsDropped() {
        assertNull(Wire.request(Wire.READ, ByteArray(0)))
        assertNull(Wire.request(Wire.READ, "nope".toByteArray()))
        assertNull(Wire.request(Wire.READ, "[1,2]".toByteArray()))
        assertNull(Wire.request("/pane/other", Wire.read("r1", address, "")))
        assertNull(Wire.request(Wire.READ, Wire.read("", address, "")))
        assertNull(Wire.request(Wire.READ, Wire.read("r1", "../../etc/passwd", "")))
        assertNull(Wire.request(Wire.READ, Wire.read("r1", "alice-laptop/work/0.1\u001b[2J", "")))
        assertNull(Wire.request(Wire.READ, Wire.read("r1", "a".repeat(300), "")))
        val big = "{\"id\":\"r1\",\"address\":\"$address\",\"pad\":\"${"x".repeat(Wire.MAX_BYTES)}\"}".toByteArray()
        assertNull(Wire.request(Wire.READ, big))
        // Wrong types become defaults, never a throw.
        val odd = Wire.request(Wire.KEY, "{\"id\":7,\"address\":\"$address\",\"key\":[\"Enter\"]}".toByteArray()) as Wire.Key
        assertEquals("7", odd.id)
        assertFalse(odd.key in Wire.KEYS)
        val raw = Wire.request(Wire.KEY, Wire.key("k", address, "\u001b[A")) as Wire.Key
        assertEquals("", raw.key)
    }

    @Test
    fun aHostileTailIsCappedAndCleaned() {
        assertNull(Wire.tail(ByteArray(0)))
        assertNull(Wire.tail("{\"id\":\"r1\"}".toByteArray()))
        assertNull(Wire.tail(ByteArray(2 shl 20) { 'x'.code.toByte() }))
        val flood = (1..10_000).map { "line $it" }
        assertNull(Wire.tail("{\"id\":\"r1\",\"address\":\"$address\",\"lines\":${org.json.JSONArray(flood)}}".toByteArray()))
        val lines = (1..1_000).map { "line $it \u0007\u001b[31m" }
        val doc = "{\"id\":\"r1\",\"address\":\"$address\",\"lines\":${org.json.JSONArray(lines)},\"writable\":\"yes\"}".toByteArray()
        val t = Wire.tail(doc)!!
        assertEquals(Wire.MAX_LINES, t.lines!!.size)
        assertEquals("line 1000 \u001b[31m", t.lines.last())
        assertFalse(t.writable)
        val long = Wire.tail(Wire.tail(Wire.Tail("r1", address, "", listOf("y".repeat(5000)), true, true)))!!
        assertEquals(Wire.MAX_LINE, long.lines!![0].length)
        val wrong = Wire.tail("{\"id\":\"r1\",\"address\":\"$address\",\"lines\":\"not a list\"}".toByteArray())!!
        assertNull(wrong.lines)
    }

    @Test
    fun aHostileHeardIsCleaned() {
        val h = Wire.heard("{\"id\":\"c1\",\"text\":\"rm -rf\\u0000 /\\u001b[2J\\n${"a".repeat(5000)}\"}".toByteArray())!!
        assertTrue(h.text.startsWith("rm -rf /\n"))
        assertEquals(Input.MAX_TEXT_BYTES, h.text.length)
        assertNull(Wire.heard("{\"text\":\"no id\"}".toByteArray()))
    }

    @Test
    fun theTailIsTheLastLinesWithColourAndNothingElse() {
        val content = (1..50).joinToString("\n") { "\u001b[32mline $it\u001b[0m\u001b]0;title\u0007\u001b[2J" } + "\n\n"
        val lines = Wire.lines(content)
        assertEquals(Wire.TAIL_LINES, lines.size)
        assertEquals("\u001b[32mline 11\u001b[0m", lines.first())
        assertEquals("\u001b[32mline 50\u001b[0m", lines.last())
        val back = Wire.tail(Wire.tail(Wire.Tail("r", address, "", lines, true, true, "", "claude")))!!
        assertEquals(lines, back.lines)
        assertEquals("claude", back.command)
        assertEquals("line 11", Ansi.plain(back.lines!![0]))
        assertEquals(listOf("one"), Wire.lines("one"))
        assertEquals(listOf(""), Wire.lines(""))
    }

    @Test
    fun noMessageCarriesATokenOrAHub() {
        val all = listOf(
            Wire.read("r", address, ""), Wire.key("k", address, "Enter"), Wire.text("t", address, "y", true),
            Wire.tail(Wire.Tail("r", address, "\"e\"", listOf("x"), true, true)), Wire.done(Wire.Done("k", true)), Wire.heard(Wire.Heard("c", "hi")),
        )
        for (m in all) {
            val s = String(m)
            assertFalse(s.contains("mxt_"))
            assertFalse(s.contains("http"))
            assertFalse(s.contains("token"))
        }
    }
}
