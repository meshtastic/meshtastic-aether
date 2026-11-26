package org.meshtastic.ambient.mood

/**
 * One overlay row. [driver] is the signal that moves it, or null when there is no data and the value is the neutral
 * constant; [reading] is that signal's own value; [hold] is the sentence for the no-data case; [note] is an aside.
 */
data class ParamInfo(
    val name: String,
    val value: String,
    val unit: String,
    val driver: String?,
    val reading: String? = null,
    val hold: String? = null,
    val note: String? = null,
) {
    /** What the driver column says: the driver with its value and note, or the holding sentence, or "no data". */
    val driverText: String
        get() = if (driver != null) listOfNotNull(if (reading != null) "$driver $reading" else driver, note).joinToString(" - ") else hold ?: "no data"
}

/** Which signal currently drives each parameter; null means no data. [readings] holds each driver's own value by row name. */
data class ParamDrivers(
    val rotationSpeed: String? = null,
    val rotationDirection: String? = null,
    val tilt: String? = null,
    val wobble: String? = null,
    val phraseSpeed: String? = null,
    val modeLean: String? = null,
    val scalePentatonicBias: String? = null,
    val readings: Map<String, String> = emptyMap(),
)

/**
 * The pass-1 parameters the mesh turns, immutable. A mood with no data equals [NEUTRAL]: today's constants, except
 * the wobble, whose neutral sits inside its driven range. [rotationDirection] is the target, +1 clockwise and -1
 * counter-clockwise; [directionStrength] is how far past neutral the evidence for it is, 0..1.
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
    val directionStrength: Double = 0.0,
) {
    /**
     * The overlay table: one row per parameter. [turningDirection] is the way the cloud is actually turning now
     * (the scene knows it); without it the direction row says it shows the target.
     */
    fun describe(turningDirection: Int? = null): List<ParamInfo> {
        fun row(name: String, value: String, unit: String, driver: String?, hold: String, note: String? = null) =
            ParamInfo(name, value, unit, driver, drivers.readings[name], if (driver == null) "holding $hold" else null, note)
        val turnTime = formatDecimal(rotationPeriodSeconds, 0)
        val tilt = formatDecimal(tiltDegrees, 1)
        val wobble = formatDecimal(wobbleDegrees, 1)
        val speed = formatDecimal(phraseSpeed, 2)
        val lean = formatDecimal(modeLean, 2)
        val bias = formatDecimal(scalePentatonicBias, 2)
        val target = directionName(rotationDirection)
        val directionValue = if (turningDirection == null) "target $target" else directionName(turningDirection)
        val directionNote = if (turningDirection != null && turningDirection != rotationDirection) "target $target" else null
        return listOf(
            row("turn time", turnTime, "s", drivers.rotationSpeed, "$turnTime s - no activity data"),
            row("rotation direction", directionValue, "", drivers.rotationDirection, "$target - no trend data", directionNote),
            row("tilt", tilt, "deg", drivers.tilt, "$tilt deg - no pressure or hop data"),
            row(
                "wobble", wobble, "deg", drivers.wobble, "$wobble deg - no wind reports",
                if (drivers.wobble == "senders") "no wind reports" else null,
            ),
            row("phrase speed", speed, "x", drivers.phraseSpeed, "$speed x - no activity data"),
            row("mode lean", lean, if (modeLean > 0.05) "bright" else if (modeLean < -0.05) "dark" else "neutral", drivers.modeLean, "$lean - no link or temperature data"),
            row("pentatonic bias", bias, "", drivers.scalePentatonicBias, "$bias - no sender data"),
        )
    }

    private fun directionName(d: Int) = if (d >= 0) "clockwise" else "counter-clockwise"

    companion object {
        val NEUTRAL = ParamValues()
    }
}
