package app.muxway.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SizedByTest {
    private fun pane(extra: String) = Json.panes(
        """{"panes":[{"host":"alice-laptop","address":"alice-laptop/work/0.0","session":"work","window_index":0,"window":"shell",
        "pane_index":0,"pane_id":"%1","width":120,"height":30,"title":"","command":"bash","activity":1,"mode":"write"$extra}]}""",
    ).single()

    @Test
    fun theHolderIsReadWithItsNameLabelTimeAndWhetherItIsMine() {
        val p = pane(""","sized_by":{"token":"web","client":"Chrome","at":1767225600,"mine":false}""")
        assertEquals(SizedBy("web", "Chrome", 1767225600L, false), p.sizedBy)
        assertEquals(true, pane(""","sized_by":{"token":"phone","client":"","at":1,"mine":true}""").sizedBy?.mine)
    }

    @Test
    fun anOlderHubOrNobodyGivesNull() {
        assertNull(pane("").sizedBy)
    }

    @Test
    fun aHostileHolderIsKeptPrintableAndShort() {
        val p = pane(""","sized_by":{"token":"we\u0007b\u001b[31m","client":"${"x".repeat(100)}","at":-5,"mine":false}""")
        val s = p.sizedBy!!
        assertEquals(false, s.token.any { it < ' ' })
        assertEquals(Label.MAX, s.client.length)
        assertEquals(0L, s.at)
    }
}
