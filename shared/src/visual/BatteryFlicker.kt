package org.meshtastic.aether.visual

import kotlin.math.PI
import kotlin.math.cos

/** The low-battery flicker of an orb, from its battery percent; none at 30 percent and above or when unknown. */
object BatteryFlicker {
    /** At or above this battery percent an orb does not flicker. */
    const val FLICKER_BELOW_PCT = 30

    /** A flicker never runs faster than this. */
    const val MAX_FLICKER_HZ = 2.0

    /** The deepest dip of a flicker, as a fraction of the orb's brightness. */
    const val MAX_FLICKER_DEPTH = 0.12

    private fun lowness(batteryPct: Int?): Double {
        if (batteryPct == null || batteryPct >= FLICKER_BELOW_PCT) return 0.0
        return (1.0 - batteryPct.coerceAtLeast(0).toDouble() / FLICKER_BELOW_PCT).coerceIn(0.0, 1.0)
    }

    /** Flicker rate in Hz: 0 at 30 percent and above (and on external power or unknown), rising to 2 Hz at empty. */
    fun flickerHz(batteryPct: Int?): Double = MAX_FLICKER_HZ * lowness(batteryPct)

    /** Flicker depth 0..0.12, growing with how low the battery is. */
    fun flickerDepth(batteryPct: Int?): Double = MAX_FLICKER_DEPTH * lowness(batteryPct)

    /** Brightness multiplier 1-depth..1 at flicker [phase] (radians): a smooth cosine dip, never a flash. */
    fun flickerFactor(batteryPct: Int?, phase: Double): Double =
        1.0 - flickerDepth(batteryPct) * ((1 - cos(phase)) / 2)

    /** Advances a flicker phase by [dtSeconds] at [hz], wrapped to 0..2 pi. */
    fun advanceFlicker(phase: Double, hz: Double, dtSeconds: Double): Double =
        (phase + 2 * PI * hz * dtSeconds).mod(2 * PI)
}
