package org.meshtastic.ambient.music

/**
 * The four tempo bands. [speed] is 0.5, 0.75, 1 or 1.5 (1 is today); it divides every time the phrases spend:
 * motif step length, the answer delay and the answer gap, and the loop period. The 0.25 s grid is not touched.
 */
object Tempo {
    const val MIN_SPEED = 0.5
    const val MAX_SPEED = 1.5
    const val LOOP_SLOWING = 0.3
    const val MIN_LOOP_PERIOD_SECONDS = 17.0
    const val MAX_LOOP_PERIOD_SECONDS = 60.0

    fun clampSpeed(speed: Double): Double = if (speed.isFinite()) speed.coerceIn(MIN_SPEED, MAX_SPEED) else 1.0

    /** Seconds of a phrase interval (a motif gap, an answer delay or gap) at [speed]. */
    fun phraseSeconds(seconds: Double, speed: Double): Double = seconds / clampSpeed(speed)

    /**
     * Seconds between firings of a loop with prime [baseSeconds]: the base stretched up to 30% by a quiet day (density 0),
     * kept within 17..60 s, then divided by [speed].
     */
    fun loopPeriodSeconds(baseSeconds: Double, dayDensity: Double, speed: Double): Double =
        (baseSeconds * (1.0 + LOOP_SLOWING * (1 - dayDensity))).coerceIn(MIN_LOOP_PERIOD_SECONDS, MAX_LOOP_PERIOD_SECONDS) / clampSpeed(speed)
}
