package app.muxway.android.protocol

/**
 * One row of a pane's mail sheet: what came in ([out] false) or what the pane sent. [who] is the
 * other pane, or null for a client that is not a pane (the owner's own mail, shown as *you*);
 * [cut] says the text is the feed's excerpt, since the token does not read the recipient's
 * mailbox; [unread] is null when nobody knows (an excerpt); [yours] marks the owner's mail to a
 * partner, from the feed.
 */
data class MailRow(
    val key: String,
    val out: Boolean,
    val who: End?,
    val at: Long,
    val actor: String,
    val text: String,
    val cut: Boolean = false,
    val id: Long = 0,
    val replyTo: Long = 0,
    val unread: Boolean? = null,
    val replied: Boolean = false,
    val yours: Boolean = false,
)

/**
 * The rows of a pane's mail sheet, as the web's mail window builds them: the pane's mailbox,
 * what it sent (from the feed, the whole text where the token reads the recipient's mailbox,
 * else the excerpt marked cut), and the owner's own mail to the panes of that conversation,
 * merged by time. Everything is one hub's: the feed entries carry the hub, the boxes are by address.
 */
object MailRows {
    /** What a mail may hold, the feed's excerpt, and how many of either are kept. */
    const val MAX_TEXT = 4000
    const val MAX_EXCERPT = 200
    const val MAX_LETTERS = 200
    const val MAX_ENTRIES = 100

    fun rows(self: Pane, box: List<Letter>, feed: List<TalkEntry>, partnerBoxes: Map<String, List<Letter>>): List<MailRow> {
        val rows = ArrayList<MailRow>()
        for (m in box) rows.add(MailRow("in${m.id}", false, m.from, m.at, m.actor, m.text, id = m.id, replyTo = m.replyTo, unread = m.unread, replied = m.replied))
        fun sent(e: TalkEntry, yours: Boolean = false): MailRow {
            val whole = partnerBoxes[e.to.address]?.firstOrNull { it.id == e.id }
            return MailRow(
                "out${e.hub}:${e.seq}", true, e.to, e.at, e.actor, whole?.text ?: e.text,
                cut = whole == null && e.bytes > e.text.length, id = e.id, replyTo = e.replyTo,
                unread = whole?.unread, replied = whole?.replied == true, yours = yours,
            )
        }
        val own = feed.filter { it.hub == self.hub && it.kind == "mail" && it.from != null && isSelf(self, it.from) && !isSelf(self, it.to) }
        for (e in own) rows.add(sent(e))
        // The owner's mail to the panes of this conversation, the sheet being where it was written.
        val partners = rows.mapNotNull { it.who?.address }.toSet()
        for (e in feed) if (e.hub == self.hub && e.kind == "mail" && e.from == null && e.to.address in partners) rows.add(sent(e, yours = true))
        return rows.sortedWith(compareBy<MailRow> { it.at }.thenBy { it.id })
    }

    private fun isSelf(self: Pane, end: End) = end.host == self.host && end.paneId == self.paneId

    /**
     * The panes of the conversation the token reads, whose mailboxes give the whole texts of what
     * went to them: the senders and the recipients that are in the listing, without the pane itself.
     */
    fun sentTo(self: Pane, box: List<Letter>, feed: List<TalkEntry>, listed: List<Pane>): List<String> {
        val out = LinkedHashSet<String>()
        for (m in box) m.from?.let { out.add(it.address) }
        for (e in feed) if (e.hub == self.hub && e.kind == "mail" && e.from != null && isSelf(self, e.from)) out.add(e.to.address)
        out.remove(self.address)
        return out.filter { a -> listed.any { it.hub == self.hub && it.address == a } }.sorted()
    }

    /** The panes the rows name, newest first, without the pane itself: the line's targets after it. */
    fun partners(rows: List<MailRow>, self: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (r in rows.asReversed()) r.who?.let { if (it.address != self) seen.add(it.address) }
        return seen.toList()
    }
}
