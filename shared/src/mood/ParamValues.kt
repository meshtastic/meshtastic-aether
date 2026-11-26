package org.meshtastic.ambient.mood

/** One overlay row: [driver] is what moves it, or null when there is no data and the value is the neutral constant. */
data class ParamInfo(val name: String, val value: String, val unit: String, val driver: String?)

/** Which signal currently drives each parameter; null means no data. */
data class ParamDrivers(
    val rotationSpeed: String? = null,
    val rotationDirection: String? = null,
    val tilt: String? = null,
    val wobble: String? = null,
    val phraseSpeed: String? = null,
    val modeLean: String? = null,
    val scalePentatonicBias: String? = null,
)

/**
 * The pass-1 parameters the mesh turns, immutable. Every default is today's constant, so a mood with no data equals [NEUTRAL].
 * Direction is +1 clockwise and -1 counter-clockwise.
 */
data class ParamValues(
    val rotationPeriodSeconds: Double = RotationTargets.NEUTRAL_PERIOD_SECONDS,
    val rotationDirection: Int = 1,
    val tiltDegrees: Double = RotationTargets.NEUTRAL_TILT_DEGREES,
    val wobbleDegrees: Double = RotationTargets.NEUTRAL_WOBBLE_DEGREES,
    /** Phrase tempo band: 0.5, 0.75, 1.0 or 1.5. */
    val phraseSpeed: Double = 1.0,
    /** -1 (lean dark) to 1 (lean bright); moves the mode by at most one step. */
    val modeLean: Double = 0.0,
    /** 0 (modal) to 1 (open toward pentatonic). */
    val scalePentatonicBias: Double = 0.0,
    val drivers: ParamDrivers = ParamDrivers(),
) {
    /** The overlay table: one row per parameter. */
    fun describe(): List<ParamInfo> = listOf(
        ParamInfo("rotation speed", formatDecimal(rotationPeriodSeconds, 0), "s per turn", drivers.rotationSpeed),
        ParamInfo("rotation direction", if (rotationDirection >= 0) "clockwise" else "counter-clockwise", "", drivers.rotationDirection),
        ParamInfo("tilt", formatDecimal(tiltDegrees, 1), "deg", drivers.tilt),
        ParamInfo("wobble", formatDecimal(wobbleDegrees, 1), "deg", drivers.wobble),
        ParamInfo("phrase speed", formatDecimal(phraseSpeed, 2), "x", drivers.phraseSpeed),
        ParamInfo("mode lean", formatDecimal(modeLean, 2), "dark -1 to bright 1", drivers.modeLean),
        ParamInfo("pentatonic bias", formatDecimal(scalePentatonicBias, 2), "0 to 1", drivers.scalePentatonicBias),
    )

    companion object {
        val NEUTRAL = ParamValues()
    }
}
