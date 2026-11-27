package org.meshtastic.aether.synth

import kotlin.math.max

/** Resonance of the burst's low-pass: gentle, so the strike has no pitch of its own. */
private const val BURST_Q = 0.7f

/** A short low-passed noise burst that strikes a modal bank or plucks a string. */
internal class NoiseBurst(private val sampleRate: Int, private val rng: Rng) {
    private val filter = Svf()
    private var left = 0
    private var length = 1

    /** Arms a burst of [frames] (at least two) low-passed at [cutoffHz]. */
    fun start(frames: Int, cutoffHz: Double) {
        filter.reset()
        filter.setFq((cutoffHz / sampleRate.toDouble()).toFloat(), BURST_Q)
        length = max(2, frames)
        left = length
    }

    /** The next sample of a burst that fades linearly to nothing, a strike. */
    fun nextFading(): Float {
        var ex = 0f
        if (left > 0) {
            ex = rng.nextSigned() * (left.toFloat() / length)
            left--
        }
        return filter.lowPass(ex)
    }

    /** The next sample of a burst at full level throughout, a pluck. */
    fun nextFlat(): Float {
        var ex = 0f
        if (left > 0) {
            ex = rng.nextSigned()
            left--
        }
        return filter.lowPass(ex)
    }
}
