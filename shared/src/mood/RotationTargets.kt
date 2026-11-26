package org.meshtastic.ambient.mood

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** Plain functions from mood signals to rotation targets; with no data every one returns today's constant. */
object RotationTargets {
    /** Today's yaw: one turn in ten minutes. */
    const val NEUTRAL_PERIOD_SECONDS = 600.0
    const val QUIET_PERIOD_SECONDS = 1200.0

    /** Hard floor: one turn never faster than this. */
    const val MIN_PERIOD_SECONDS = 150.0

    /** Today's pitch swing is 8 degrees about level: no steady tilt, an 8 degree wobble. */
    const val NEUTRAL_TILT_DEGREES = 0.0
    const val NEUTRAL_WOBBLE_DEGREES = 8.0

    const val TILT_MIN_DEGREES = 4.0
    const val TILT_MAX_DEGREES = 14.0
    const val WOBBLE_MIN_DEGREES = 1.0
    const val WOBBLE_MAX_DEGREES = 6.0

    /** A pressure change of this many hPa per 3 h (very rapid in meteorology) is the full trend range. */
    const val PRESSURE_FULL_SCALE_HPA = 6.0

    /** Wind speed that gives the largest wobble. */
    const val WIND_FULL_SCALE_MPS = 15.0

    /** One turn per 1200 s at activity 0 down to 150 s at 1, geometric between, blended to 600 s as [confidence] falls to 0. */
    fun periodSeconds(activity: Double, confidence: Double): Double {
        val c = clamp01(confidence)
        if (c <= 0.0) return NEUTRAL_PERIOD_SECONDS
        val mapped = QUIET_PERIOD_SECONDS * (MIN_PERIOD_SECONDS / QUIET_PERIOD_SECONDS).pow(clamp01(activity))
        return exp(lerp(ln(NEUTRAL_PERIOD_SECONDS), ln(mapped), c)).coerceAtLeast(MIN_PERIOD_SECONDS)
    }

    /** Tilt from the pressure trend magnitude (-1..1) when known, else from relative reach, else neutral; 4 to 14 degrees when driven. */
    fun tiltDegrees(pressureTrend: Double, pressureConfidence: Double, reach: Double, reachConfidence: Double): Double {
        val fromReach = lerp(
            NEUTRAL_TILT_DEGREES,
            TILT_MIN_DEGREES + (TILT_MAX_DEGREES - TILT_MIN_DEGREES) * clamp01(reach),
            clamp01(reachConfidence),
        )
        val fromPressure = TILT_MIN_DEGREES + (TILT_MAX_DEGREES - TILT_MIN_DEGREES) * clamp01(abs(pressureTrend))
        return lerp(fromReach, fromPressure, clamp01(pressureConfidence))
    }

    /** Wobble amplitude from wind (0..1 of full scale), 1 to 6 degrees when driven. */
    fun wobbleDegrees(wind: Double, confidence: Double): Double =
        lerp(NEUTRAL_WOBBLE_DEGREES, WOBBLE_MIN_DEGREES + (WOBBLE_MAX_DEGREES - WOBBLE_MIN_DEGREES) * clamp01(wind), clamp01(confidence))

    /** The sign of the pressure trend if known, else of the activity trend, else +1 (today's way). */
    fun direction(pressureTrend: Double, pressureConfidence: Double, activityTrend: Double, activityTrendConfidence: Double): Int = when {
        pressureConfidence > 0.0 -> sign(pressureTrend)
        activityTrendConfidence > 0.0 -> sign(activityTrend)
        else -> 1
    }

    /** How far the evidence behind [direction] is from neutral, 0..1; the integrator needs 0.25. */
    fun directionStrength(pressureTrend: Double, pressureConfidence: Double, activityTrend: Double, activityTrendConfidence: Double): Double = when {
        pressureConfidence > 0.0 -> clamp01(abs(pressureTrend))
        activityTrendConfidence > 0.0 -> clamp01(abs(activityTrend))
        else -> 0.0
    }

    /** [tilt] limited to 0..20 degrees; non-finite counts as 0. */
    fun cappedTilt(tilt: Double): Double = (if (tilt.isFinite()) tilt else 0.0).coerceIn(0.0, RotationIntegrator.MAX_OFFSET_DEGREES)

    /** [wobble] limited so that with the capped [tilt] it is at most 20 degrees; non-finite counts as 0. */
    fun cappedWobble(tilt: Double, wobble: Double): Double =
        (if (wobble.isFinite()) wobble else 0.0).coerceIn(0.0, RotationIntegrator.MAX_OFFSET_DEGREES - cappedTilt(tilt))
}
