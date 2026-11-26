package org.meshtastic.aether.mood

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

    /** No steady tilt and a 3 degree wobble, inside the driven 1 to 6 range (today's pitch swing was 8, past its own maximum). */
    const val NEUTRAL_TILT_DEGREES = 0.0
    const val NEUTRAL_WOBBLE_DEGREES = 3.0

    const val TILT_MIN_DEGREES = 4.0
    const val TILT_MAX_DEGREES = 14.0
    const val WOBBLE_MIN_DEGREES = 1.0
    const val WOBBLE_MAX_DEGREES = 6.0

    /** A pressure change of this many hPa per 3 h (very rapid in meteorology) is the full trend range. */
    const val PRESSURE_FULL_SCALE_HPA = 6.0

    /** A temperature change of this many degrees per 3 h (a fast morning warm-up) is the full trend range. */
    const val TEMPERATURE_FULL_SCALE_C = 5.0

    /** A battery level change of this many points per 3 h (a solar node charging) is the full trend range. */
    const val BATTERY_FULL_SCALE_PCT = 10.0

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

    /**
     * Wobble amplitude, 1 to 6 degrees when driven: from wind (0..1 of full scale) when it is reported, else from relative
     * senders (0..1), else the neutral 3 degrees. Each blends toward the next as its confidence falls.
     */
    fun wobbleDegrees(wind: Double, windConfidence: Double, senders: Double = 0.5, sendersConfidence: Double = 0.0): Double {
        val fromSenders = lerp(NEUTRAL_WOBBLE_DEGREES, wobbleFor(senders), clamp01(sendersConfidence))
        return lerp(fromSenders, wobbleFor(wind), clamp01(windConfidence))
    }

    private fun wobbleFor(unit: Double) = WOBBLE_MIN_DEGREES + (WOBBLE_MAX_DEGREES - WOBBLE_MIN_DEGREES) * clamp01(unit)

    /** Where the rotation direction comes from, in priority order. */
    enum class DirectionSource(val label: String?) { Pressure("pressure trend"), Temperature("temperature trend"), Battery("battery trend"), Activity("activity trend"), None(null) }

    /** The target direction (+1 or -1), how far past neutral its evidence is (0..1) and which signal supplied it. */
    data class DirectionChoice(val direction: Int, val strength: Double, val source: DirectionSource)

    /**
     * The sign of the first trend that has data and is not flat: pressure (rising clockwise), then temperature (warming
     * clockwise), then battery (charging clockwise, only nodes whose level moved), then the activity trend once its
     * strength reaches [RotationIntegrator.HYSTERESIS]. With none, clockwise at strength 0 (today's way).
     */
    fun chooseDirection(
        pressure: Double, pressureConfidence: Double,
        temperature: Double, temperatureConfidence: Double,
        battery: Double, batteryConfidence: Double,
        activity: Double, activityConfidence: Double,
    ): DirectionChoice = when {
        pressureConfidence > 0.0 && pressure != 0.0 -> DirectionChoice(sign(pressure), clamp01(abs(pressure)), DirectionSource.Pressure)
        temperatureConfidence > 0.0 && temperature != 0.0 -> DirectionChoice(sign(temperature), clamp01(abs(temperature)), DirectionSource.Temperature)
        batteryConfidence > 0.0 && battery != 0.0 -> DirectionChoice(sign(battery), clamp01(abs(battery)), DirectionSource.Battery)
        activityConfidence > 0.0 && abs(activity) >= RotationIntegrator.HYSTERESIS ->
            DirectionChoice(sign(activity), clamp01(abs(activity)), DirectionSource.Activity)
        else -> DirectionChoice(1, 0.0, DirectionSource.None)
    }

    /** [tilt] limited to 0..20 degrees; non-finite counts as 0. */
    fun cappedTilt(tilt: Double): Double = (if (tilt.isFinite()) tilt else 0.0).coerceIn(0.0, RotationIntegrator.MAX_OFFSET_DEGREES)

    /** [wobble] limited so that with the capped [tilt] it is at most 20 degrees; non-finite counts as 0. */
    fun cappedWobble(tilt: Double, wobble: Double): Double =
        (if (wobble.isFinite()) wobble else 0.0).coerceIn(0.0, RotationIntegrator.MAX_OFFSET_DEGREES - cappedTilt(tilt))
}
