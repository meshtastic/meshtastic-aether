package org.meshtastic.ambient.synth

import kotlin.math.abs
import kotlin.math.exp

/** Soft-knee master limiter: identity below [KNEE], then a tanh-shaped approach to 1.0 that never reaches past it. */
internal class SoftLimiter {
    /** Largest absolute input seen, to show a scenario really drove the limiter. */
    var peakIn = 0f

    /** Count of non-finite inputs replaced by silence; tests assert it stays zero. */
    var nonFiniteHits = 0

    fun process(x: Float): Float {
        if (x != x || x == Float.POSITIVE_INFINITY || x == Float.NEGATIVE_INFINITY) {
            nonFiniteHits++
            return 0f
        }
        val a = abs(x)
        if (a > peakIn) peakIn = a
        if (a <= KNEE) return x
        val over = (a - KNEE) / (1f - KNEE)
        val e = exp(-2f * over)
        val shaped = KNEE + (1f - KNEE) * ((1f - e) / (1f + e))
        val y = if (shaped > 1f) 1f else shaped
        return if (x < 0f) -y else y
    }

    private companion object {
        const val KNEE = 0.8f
    }
}
