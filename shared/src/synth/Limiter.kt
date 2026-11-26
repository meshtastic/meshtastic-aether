package org.meshtastic.aether.synth

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Look-ahead stereo peak limiter. The audio is delayed by [lookahead] - 1 frames; the gain needed to hold the window's
 * largest peak at [THRESHOLD] is found by a sliding minimum, released slowly, then smoothed by a boxcar of
 * [lookahead] frames so it is already down when the peak arrives. Below the threshold the gain is exactly 1.
 */
internal class PeakLimiter(sampleRate: Int) {
    private val lookahead = max(2, (sampleRate * ATTACK_SECONDS).roundToInt())
    private val delayL = FloatArray(lookahead)
    private val delayR = FloatArray(lookahead)
    private val target = FloatArray(lookahead)
    private val released = FloatArray(lookahead)
    private var pos = 0
    private var releaseState = 1f
    private var boxSum = 0.0
    private val releaseCoef = exp(-1.0 / (sampleRate * RELEASE_SECONDS)).toFloat()

    /** Largest absolute input seen, to show a scenario really drove the limiter. */
    var peakIn = 0f

    /** Count of non-finite inputs replaced by silence; tests assert it stays zero. */
    var nonFiniteHits = 0

    /** Frames the output lags the input. */
    val latencyFrames: Int get() = lookahead - 1

    init {
        released.fill(1f)
        target.fill(1f)
        boxSum = lookahead.toDouble()
    }

    private fun sanitize(x: Float): Float {
        if (x != x || x == Float.POSITIVE_INFINITY || x == Float.NEGATIVE_INFINITY) {
            nonFiniteHits++
            return 0f
        }
        return x
    }

    /** Limits one stereo frame; the delayed, limited frame lands in [out] (0 left, 1 right). */
    fun process(l: Float, r: Float, out: FloatArray) {
        val inL = sanitize(l)
        val inR = sanitize(r)
        val peak = max(abs(inL), abs(inR))
        if (peak > peakIn) peakIn = peak

        target[pos] = if (peak > THRESHOLD) THRESHOLD / peak else 1f
        var windowMin = 1f
        for (i in 0 until lookahead) windowMin = min(windowMin, target[i])

        // Instant attack, exponential release; never above the window minimum.
        releaseState = if (windowMin < releaseState) windowMin else windowMin + (releaseState - windowMin) * releaseCoef
        val gainNow = min(releaseState, windowMin)
        boxSum += gainNow - released[pos]
        released[pos] = gainNow

        val next = (pos + 1) % lookahead
        // delay[next] is the oldest stored frame: written lookahead - 1 frames ago.
        val g = min(1f, (boxSum / lookahead).toFloat())
        delayL[pos] = inL
        delayR[pos] = inR
        val oldest = next
        out[0] = clampUnit(delayL[oldest] * g)
        out[1] = clampUnit(delayR[oldest] * g)
        pos = next
    }

    private fun clampUnit(x: Float): Float = if (x > 1f) 1f else if (x < -1f) -1f else x

    internal companion object {
        const val THRESHOLD = 0.9f
        const val ATTACK_SECONDS = 0.002
        const val RELEASE_SECONDS = 0.05
    }
}
