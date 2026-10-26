package app.muxway.android.protocol

import app.muxway.android.e2e.Request
import app.muxway.android.e2e.Response
import app.muxway.android.e2e.TinyServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class WheelTest {
    private val seen = java.util.Collections.synchronizedList(ArrayList<Request>())
    private var answer: (Request) -> Response = { Response(204) }
    private val server = TinyServer { seen.add(it); answer(it) }

    @After
    fun stop() = server.close()

    private fun api() = Api(server.url, "mxt_" + "A".repeat(43), "test")

    private fun body(i: Int) = JSONObject(String(seen[i].body)).getJSONObject("mouse")

    private fun counts(r: Request): Int = JSONObject(String(r.body)).getJSONObject("mouse").optInt("count", 1)

    @Test
    fun theSameDirectionMergesAtTheLastCellAndTheOtherStartsAnEntry() {
        val q = WheelQueue()
        q.add(false, 3, 4, 3)
        q.add(false, 5, 6, 7)
        q.add(true, 7, 8)
        assertEquals(2, q.size)
        val down = q.next()!!
        assertEquals(listOf("wheeldown", 5, 6, 10), listOf(down.button, down.x, down.y, down.count))
        val up = q.next()!!
        assertEquals(listOf("wheelup", 7, 8, 1), listOf(up.button, up.x, up.y, up.count))
        assertNull(q.next())
    }

    @Test
    fun anEntryStopsAtTheCapAndAFewEntriesAreKept() {
        val q = WheelQueue()
        q.add(true, 1, 1, 45)
        q.add(true, 1, 1, 10)
        assertEquals(2, q.size)
        assertEquals(50, q.next()!!.count)
        assertEquals(5, q.next()!!.count)
        repeat(6) { q.add(it % 2 == 0, 1, 1) }
        assertEquals(4, q.size)
        q.add(false, 1, 1, 200)
        assertEquals(4, q.size)
        assertEquals(listOf(1, 1, 1, 50), generateSequence { q.next() }.map { it.count }.toList())
        q.add(true, 1, 1, 2)
        q.clear()
        assertEquals(0, q.size)
    }

    @Test
    fun aCountGoesInOneRequestWhereTheHubTakesIt() {
        assertTrue(Wheel.deliver(api(), "h/w/0.0", Notches(false, 5, 3, 10), counted = true))
        assertEquals(1, seen.size)
        assertEquals(10, body(0).getInt("count"))
        assertTrue(Wheel.deliver(api(), "h/w/0.0", Notches(true, 5, 3, 1), counted = true))
        assertFalse(body(1).has("count"))
    }

    @Test
    fun anOlderHubGetsTheNotchesOneByOneAndIsRemembered() {
        answer = { r -> if (counts(r) > 1) json(400, """{"error":"bad_input"}""") else Response(204) }
        assertFalse(Wheel.deliver(api(), "h/w/0.0", Notches(false, 5, 3, 3), counted = true))
        assertEquals(4, seen.size)
        assertEquals(3, body(0).getInt("count"))
        for (i in 1..3) assertFalse(body(i).has("count"))
        assertFalse(Wheel.deliver(api(), "h/w/0.0", Notches(false, 5, 3, 2), counted = false))
        assertEquals(6, seen.size)
        assertFalse(body(4).has("count"))
    }

    @Test
    fun aPaneWithoutTheMouseRefusesTheSingleNotchTooAndThatGoesThrough() {
        answer = { json(400, """{"error":"bad_input"}""") }
        val e = assertThrows(ApiException::class.java) { Wheel.deliver(api(), "h/w/0.0", Notches(false, 5, 3, 3), counted = true) }
        assertEquals("bad_input", e.code)
        assertEquals(2, seen.size)
        answer = { json(400, """{"error":"bad_json"}""") }
        assertEquals("bad_json", assertThrows(ApiException::class.java) { Wheel.deliver(api(), "h/w/0.0", Notches(false, 5, 3, 3), counted = true) }.code)
        assertEquals(3, seen.size)
    }

    private fun json(status: Int, body: String) = Response(status, body.toByteArray(), mapOf("Content-Type" to "application/json"))
}
