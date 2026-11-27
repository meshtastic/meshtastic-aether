package org.meshtastic.aether.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/** One bed layer's entry on the energy arc: it rises from silence at [enter] to [gain] at [full]. */
private class Layer(val voice: Voice, val gain: Double, val enter: Double, val full: Double)

/**
 * Long form: 20-60 minute sections on a ~110-minute energy arc, deciding which layers sound and how loudly.
 * With [pinnedFull] the arc is ignored: every layer sits at its full level and no rest ever falls (demo mode).
 */
class Form(seed: Long, private val pinnedFull: Boolean = false) {
    private val rng = Rng.stream(seed, 2)
    private val phase = rng.range(PHASE_MIN, PHASE_MAX)
    private val restSeed = Rng.mix(seed xor REST_SALT)
    private val ends = ArrayList<Double>()
    private val mults = ArrayList<Double>()

    private fun extend(untilSec: Double) {
        while (ends.isEmpty() || ends.last() < untilSec + EXTEND_AHEAD_SECONDS) {
            val start = if (ends.isEmpty()) 0.0 else ends.last()
            ends += start + rng.range(SECTION_MIN_MINUTES, SECTION_MAX_MINUTES) * SECONDS_PER_MINUTE
            val prevThin = mults.isNotEmpty() && mults.last() == THIN
            mults += when {
                mults.isEmpty() || prevThin -> rng.range(MIN_MULT, 1.0)
                rng.nextDouble() < THIN_CHANCE -> THIN
                else -> rng.range(MIN_MULT, 1.0)
            }
        }
    }

    /** Section lengths in minutes covering at least the first [untilSec] seconds. */
    fun sectionMinutes(untilSec: Double): List<Double> {
        extend(untilSec)
        return ends.mapIndexed { i, e -> (e - (if (i == 0) 0.0 else ends[i - 1])) / SECONDS_PER_MINUTE }
    }

    /** 0 at the quietest point of the arc, 1 at the fullest. */
    fun energy(tSec: Double): Double =
        if (pinnedFull) PINNED_ENERGY else (1 - cos(2 * PI * (tSec / ARC_SECONDS + phase))) / 2

    private fun sectionMultiplier(t: Double): Double {
        extend(t)
        val k = ends.indexOfFirst { t < it }
        var m = mults[k]
        if (k > 0 && t < ends[k - 1] + FADE) m = lerp(mults[k - 1], m, smooth((t - (ends[k - 1] - FADE)) / (2 * FADE)))
        if (t > ends[k] - FADE) m = lerp(m, mults[k + 1], smooth((t - (ends[k] - FADE)) / (2 * FADE)))
        return m
    }

    /**
     * 1 away from the arc's quietest points; at each one the bed dips to silence over a window of 2-4 minutes
     * (seeded per arc), the only passage where [visible] falls below [FLOOR].
     */
    private fun rest(tSec: Double): Double {
        val k = floor(tSec / ARC_SECONDS + phase + HALF_ARC).toLong()
        if (k < 1) return 1.0
        val centre = (k - phase) * ARC_SECONDS
        val width = REST_MIN_SECONDS + REST_EXTRA_SECONDS * Rng.unitDouble(Rng.mix(restSeed + k))
        return smoothstep(0.0, width / 2, abs(tSec - centre))
    }

    /**
     * Level 0..1 for the layers the synth scales by `Texture.layerLevels` (Drone, Pad, Shimmer) plus Bell, which the
     * composer never applies: Drone, Pad, Bell and Shimmer enter in that order as energy rises. Energy is floored so
     * the drone is always audible outside a rest.
     */
    fun levels(tSec: Double): Map<Voice, Float> {
        val x = if (pinnedFull) 1.0 else (energy(tSec) * sectionMultiplier(tSec)).coerceIn(ENERGY_FLOOR, 1.0)
        val r = if (pinnedFull) 1.0 else rest(tSec)
        return LAYERS.associate { it.voice to (r * it.gain * smoothstep(it.enter, it.full, x)).toFloat() }
    }

    private fun smooth(u: Double) = smoothstep(0.0, 1.0, u)

    private fun smoothstep(lo: Double, hi: Double, x: Double): Double {
        val u = ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        return u * u * (3 - 2 * u)
    }

    private fun lerp(a: Double, b: Double, u: Double) = a + (b - a) * u

    companion object {
        const val ARC_SECONDS = 110.0 * 60.0
        const val SECONDS_PER_MINUTE = 60.0
        private const val FADE = 120.0
        private const val THIN = 0.35
        private const val THIN_CHANCE = 0.25
        private const val MIN_MULT = 0.55
        private const val ENERGY_FLOOR = 0.12
        private const val SECTION_MIN_MINUTES = 20.0
        private const val SECTION_MAX_MINUTES = 60.0

        /** Sections are drawn at least this far past any time asked about. */
        private const val EXTEND_AHEAD_SECONDS = 3600.0

        /** Where in its arc the piece starts, as a fraction of the arc. */
        private const val PHASE_MIN = 0.2
        private const val PHASE_MAX = 0.4

        /** Quiet points sit at whole arcs; adding half an arc before flooring finds the nearest one. */
        private const val HALF_ARC = 0.5
        private const val REST_SALT = 0x5E57L
        private const val REST_MIN_SECONDS = 120.0
        private const val REST_EXTRA_SECONDS = 120.0

        /** The energy a pinned form reports: inside the "full" band of the section label. */
        private const val PINNED_ENERGY = 0.7

        /** Below this a bed layer is not audible; the form only goes under it inside a rest. */
        const val FLOOR = 0.1f

        private val LAYERS = listOf(
            Layer(Voice.Drone, gain = 0.9, enter = 0.0, full = 0.5),
            Layer(Voice.Pad, gain = 0.8, enter = 0.15, full = 0.6),
            Layer(Voice.Bell, gain = 0.7, enter = 0.3, full = 0.8),
            Layer(Voice.Shimmer, gain = 0.5, enter = 0.55, full = 0.95),
        )

        /** The loudest of the layers the synth scales: what the render can show of the form. */
        fun visible(levels: Map<Voice, Float>): Float =
            maxOf(levels.getValue(Voice.Drone), levels.getValue(Voice.Pad), levels.getValue(Voice.Shimmer))

        fun total(levels: Map<Voice, Float>): Float = levels.values.sum()
    }
}
