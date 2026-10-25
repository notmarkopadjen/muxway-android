package app.muxway.android.wear

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A round watch is a circle, and a band across it is only as wide as the chord at whichever of
 * its edges lies farther from the middle. The screens ask how far each band must come in from the
 * edge to stay on the glass; a square watch asks nothing and keeps its corners.
 */
object Round {
    /** The inset for a band between [top] and [bottom], both fractions of a screen [size] across. */
    fun inset(size: Float, top: Float, bottom: Float): Float {
        if (size <= 0f) return 0f
        val r = size / 2f
        val d = r * 2f * maxOf(abs(0.5f - top), abs(0.5f - bottom)).coerceAtMost(0.5f)
        return r - sqrt((r * r - d * d).coerceAtLeast(0f))
    }
}

/**
 * The bands of a screen, walked from the top down: each one is as tall as it says and takes the
 * inset the glass leaves it. Heights are in dp, as is [height].
 */
class Bands(private val height: Float, private val round: Boolean) {
    private var at = 0f

    fun skip(tall: Float) {
        at += tall
    }

    /** The inset of the next band, [tall] dp high, which this then steps past. */
    fun next(tall: Float): Float {
        val inset = if (round && height > 0f) Round.inset(height, at / height, (at + tall) / height) else 0f
        at += tall
        return inset
    }
}
