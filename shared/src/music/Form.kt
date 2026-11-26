package org.meshtastic.aether.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/**
 * Long form: 20-60 minute sections on a ~110-minute energy arc, deciding which layers sound and how loudly.
 * With [pinnedFull] the arc is ignored: every layer sits at its full level and no rest ever falls (demo mode).
 */
class Form(seed: Long, private val pinnedFull: Boolean = false) {
    private val rng = Rng.stream(seed, 2)
    private val phase = rng.range(0.2, 0.4)
    private val restSeed = Rng.mix(seed xor 0x5E57)
    private val ends = ArrayList<Double>()
    private val mults = ArrayList<Double>()

    private fun extend(untilSec: Double) {
        while (ends.isEmpty() || ends.last() < untilSec + 3600.0) {
            val start = if (ends.isEmpty()) 0.0 else ends.last()
            ends += start + rng.range(20.0, 60.0) * 60.0
            val prevThin = mults.isNotEmpty() && mults.last() == THIN
            mults += when {
                mults.isEmpty() || prevThin -> rng.range(0.55, 1.0)
                rng.nextDouble() < 0.25 -> THIN
                else -> rng.range(0.55, 1.0)
            }
        }
    }

    /** Section lengths in minutes covering at least the first [untilSec] seconds. */
    fun sectionMinutes(untilSec: Double): List<Double> {
        extend(untilSec)
        return ends.mapIndexed { i, e -> (e - (if (i == 0) 0.0 else ends[i - 1])) / 60.0 }
    }

    /** 0 at the quietest point of the arc, 1 at the fullest. */
    fun energy(tSec: Double): Double =
        if (pinnedFull) PINNED_ENERGY else 0.5 - 0.5 * cos(2 * PI * (tSec / ARC_SECONDS + phase))

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
        val k = floor(tSec / ARC_SECONDS + phase + 0.5).toLong()
        if (k < 1) return 1.0
        val centre = (k - phase) * ARC_SECONDS
        val width = 120.0 + 120.0 * ((Rng.mix(restSeed + k) ushr 11) * (1.0 / (1L shl 53)))
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
        return mapOf(
            Voice.Drone to (r * 0.9 * smoothstep(0.0, 0.5, x)).toFloat(),
            Voice.Pad to (r * 0.8 * smoothstep(0.15, 0.6, x)).toFloat(),
            Voice.Bell to (r * 0.7 * smoothstep(0.3, 0.8, x)).toFloat(),
            Voice.Shimmer to (r * 0.5 * smoothstep(0.55, 0.95, x)).toFloat(),
        )
    }

    private fun smooth(u: Double) = smoothstep(0.0, 1.0, u)

    private fun smoothstep(lo: Double, hi: Double, x: Double): Double {
        val u = ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        return u * u * (3 - 2 * u)
    }

    private fun lerp(a: Double, b: Double, u: Double) = a + (b - a) * u

    companion object {
        const val ARC_SECONDS = 110.0 * 60.0
        private const val FADE = 120.0
        private const val THIN = 0.35
        private const val ENERGY_FLOOR = 0.12

        /** The energy a pinned form reports: inside the "full" band of the section label. */
        private const val PINNED_ENERGY = 0.7

        /** Below this a bed layer is not audible; the form only goes under it inside a rest. */
        const val FLOOR = 0.1f

        /** The loudest of the layers the synth scales: what the render can show of the form. */
        fun visible(levels: Map<Voice, Float>): Float =
            maxOf(levels.getValue(Voice.Drone), levels.getValue(Voice.Pad), levels.getValue(Voice.Shimmer))

        fun total(levels: Map<Voice, Float>): Float = levels.values.sum()
    }
}
