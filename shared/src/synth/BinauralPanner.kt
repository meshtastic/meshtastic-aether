package org.meshtastic.aether.synth

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A mono voice placed to one side for headphones: a head-shadow shelf per ear and an interaural time difference, the
 * spherical-head model, not measured HRIRs. The ear nearer the source hears it undelayed; the far one hears it up to
 * 0.66 ms later and with its highs cut. The near ear's highs rise by up to 2x. A voice straight ahead (`lateral` 0) comes out
 * as `mono / sqrt(2)` in both ears, the same as the constant-power pan at center, so switching modes changes nothing for it.
 *
 * Sources: Woodworth's interaural delay, `tau = (a / c)(alpha + sin alpha)` with `alpha = asin(lateral)`; and the
 * head-shadow filter of Brown and Duda (1998), `H(s) = (alpha(theta) s + beta) / (s + beta)` with `beta = 2c / a` and
 * `alpha(theta) = 1.05 + 0.95 cos(1.2 theta)` (theta in degrees from the ear's axis), taken relative to its value at
 * 90 degrees so that a centered source passes flat. State is preallocated and the coefficients are cached per side, so a steady chunk allocates nothing.
 */
internal class BinauralPanner(private val sampleRate: Int) {
    private val ring = FloatArray(RING)
    private var pos = 0
    private var delayPrev = 0.0

    // Filter state per ear: transposed direct form II, one delay each.
    private var zLeft = 0f
    private var zRight = 0f

    // Coefficients depend only on the source's side, which is fixed for a note: recomputed when it changes.
    private var cachedLateral = Float.NaN
    private var alpha = 0.0
    private var delayTarget = 0.0
    private var nearB0 = 1f
    private var nearB1 = 0f
    private var nearA1 = 0f
    private var farB0 = 1f
    private var farB1 = 0f
    private var farA1 = 0f

    fun reset() {
        ring.fill(0f)
        pos = 0
        delayPrev = 0.0
        zLeft = 0f
        zRight = 0f
        cachedLateral = Float.NaN
    }

    /**
     * Renders [n] frames of [mono] into [left] and [right] for a source at [lateral] (-1 hard left, 0 ahead, 1 hard
     * right). The far ear's delay moves linearly across the chunk from where the last chunk ended, so a moving source
     * never steps.
     */
    fun process(mono: FloatArray, n: Int, lateral: Float, left: FloatArray, right: FloatArray) {
        val ux = lateral.coerceIn(-1f, 1f)
        if (ux != cachedLateral) {
            // A fresh note starts at its delay: the ring is silent, so there is nothing to ramp from.
            val fresh = cachedLateral.isNaN()
            cachedLateral = ux
            alpha = asin(abs(ux.toDouble()))
            delayTarget = itdSamples(alpha, sampleRate)
            if (fresh) delayPrev = delayTarget
            val k = 2.0 * sampleRate
            val near = shelfRelative(HALF_PI - alpha)
            nearB0 = ((BETA + near * k) / (BETA + k)).toFloat()
            nearB1 = ((BETA - near * k) / (BETA + k)).toFloat()
            nearA1 = ((BETA - k) / (BETA + k)).toFloat()
            val far = shelfRelative(HALF_PI + alpha)
            farB0 = ((BETA + far * k) / (BETA + k)).toFloat()
            farB1 = ((BETA - far * k) / (BETA + k)).toFloat()
            farA1 = nearA1
        }
        val rightIsNear = ux >= 0f
        val lb0 = if (rightIsNear) farB0 else nearB0
        val lb1 = if (rightIsNear) farB1 else nearB1
        val la1 = if (rightIsNear) farA1 else nearA1
        val rb0 = if (rightIsNear) nearB0 else farB0
        val rb1 = if (rightIsNear) nearB1 else farB1
        val ra1 = if (rightIsNear) nearA1 else farA1
        val d0 = delayPrev
        for (i in 0 until n) {
            val x = mono[i]
            ring[pos and MASK] = x
            val d = rampedDelay(d0, delayTarget, i, n)
            val whole = d.toInt()
            val frac = (d - whole).toFloat()
            val a = ring[(pos - whole) and MASK]
            val b = ring[(pos - whole - 1) and MASK]
            val delayed = a + (b - a) * frac
            val inLeft = if (rightIsNear) delayed else x
            val inRight = if (rightIsNear) x else delayed

            val yl = lb0 * inLeft + zLeft
            zLeft = lb1 * inLeft - la1 * yl
            val yr = rb0 * inRight + zRight
            zRight = rb1 * inRight - ra1 * yr
            left[i] = yl * CENTRE_GAIN
            right[i] = yr * CENTRE_GAIN
            pos = (pos + 1) and MASK
        }
        delayPrev = delayTarget
    }

    private fun abs(x: Double) = if (x < 0.0) -x else x

    /** First-order shelf coefficients, `H(z) = (b0 + b1 z^-1) / (1 + a1 z^-1)`. */
    internal class Shelf(val b0: Float, val b1: Float, val a1: Float)

    internal companion object {
        const val RING = 128
        private const val MASK = RING - 1
        private const val HALF_PI = PI / 2

        /** Head radius in metres and the speed of sound in metres per second. */
        const val HEAD_RADIUS = 0.0875
        const val SPEED_OF_SOUND = 343.0

        /** Highest high-shelf gain relative to a source at 90 degrees. */
        const val MAX_SHELF = 2.0

        /** Gain of a centered voice in each ear: the constant-power center. */
        val CENTRE_GAIN = (1.0 / kotlin.math.sqrt(2.0)).toFloat()

        /** Woodworth: seconds of interaural delay for a source [alpha] radians off the median plane. */
        fun itdSeconds(alpha: Double): Double = HEAD_RADIUS / SPEED_OF_SOUND * (alpha + sin(alpha))

        /** The far ear's delay at frame [i] of a chunk of [n]: a straight line from [previous] (before the chunk) to [target] (its last frame). */
        fun rampedDelay(previous: Double, target: Double, i: Int, n: Int): Double = previous + (target - previous) * (i + 1) / n

        fun itdSamples(alpha: Double, sampleRate: Int): Double = min(itdSeconds(alpha) * sampleRate, (RING - 2).toDouble())

        /** Brown and Duda's `alpha(theta)` for [thetaRadians] from the ear's axis: 2 at 0, 0.756 at 90 degrees, 0.1 at 150. */
        fun shadowAlpha(thetaRadians: Double): Double = 1.05 + 0.95 * cos(1.2 * thetaRadians)

        /** The shelf's corner, `2c / a` radians per second (about 1.25 kHz). */
        private const val BETA = 2.0 * SPEED_OF_SOUND / HEAD_RADIUS

        /** High-frequency gain for an ear [thetaRadians] from the source, relative to a source at 90 degrees, capped at [MAX_SHELF]. */
        fun shelfRelative(thetaRadians: Double): Double = (shadowAlpha(thetaRadians) / shadowAlpha(HALF_PI)).coerceIn(0.0, MAX_SHELF)

        /** The bilinear shelf for an ear [thetaRadians] from the source, so a centered voice is flat; the render loop uses the same arithmetic. */
        fun shelf(thetaRadians: Double, sampleRate: Int): Shelf {
            val relative = shelfRelative(thetaRadians)
            val k = 2.0 * sampleRate
            val denominator = BETA + k
            return Shelf(((BETA + relative * k) / denominator).toFloat(), ((BETA - relative * k) / denominator).toFloat(), ((BETA - k) / denominator).toFloat())
        }
    }
}
