package app.muxway.android.protocol

/** Wheel notches waiting to go: one direction, at the cell the finger was last at. */
class Notches(val up: Boolean, var x: Int, var y: Int, var count: Int) {
    val button get() = if (up) "wheelup" else "wheeldown"
}

/**
 * The notches of a drag gathered while a request is in flight, so a long scroll is a few
 * counted requests. The same direction merges into the last entry up to the protocol's cap,
 * the other direction starts one; past [limit] entries the rest is dropped, which beats
 * scrolling on after the hand has stopped. One thread at a time.
 */
class WheelQueue(private val limit: Int = 4) {
    private val pending = ArrayDeque<Notches>()

    val size get() = pending.size

    fun add(up: Boolean, x: Int, y: Int, count: Int = 1) {
        var left = count
        while (left > 0) {
            val last = pending.lastOrNull()
            if (last != null && last.up == up && last.count < Input.MAX_WHEEL) {
                val n = minOf(left, Input.MAX_WHEEL - last.count)
                last.count += n
                last.x = x
                last.y = y
                left -= n
            } else {
                if (pending.size >= limit) return
                val n = minOf(left, Input.MAX_WHEEL)
                pending.addLast(Notches(up, x, y, n))
                left -= n
            }
        }
    }

    fun next(): Notches? = pending.removeFirstOrNull()

    fun clear() = pending.clear()
}

object Wheel {
    /**
     * Sends the notches: one counted request where the hub takes a count, else one per notch.
     * A hub from before the field answers bad_input to a count; then the notches go one by one
     * and false comes back, so the rest of the session skips the count. A bad_input to a single
     * notch is the pane's and goes through.
     */
    fun deliver(api: Api, address: String, n: Notches, counted: Boolean): Boolean {
        var takes = counted
        if (counted && n.count > 1) {
            try {
                api.mouse(address, n.button, n.x, n.y, n.count)
                return true
            } catch (e: ApiException) {
                if (e.code != "bad_input") throw e
            }
            takes = false
        }
        repeat(n.count) { api.mouse(address, n.button, n.x, n.y) }
        return takes
    }
}
