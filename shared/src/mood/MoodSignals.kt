package org.meshtastic.aether.mood

/** One mood signal: its value (0..1, or -1..1 for a trend) and how far it is trusted, 0..1. */
data class MoodSignal(val value: Double, val confidence: Double) {
    /** True once any data backs the signal. */
    val known: Boolean get() = confidence > 0.0

    companion object {
        /** A 0..1 signal with no data: the middle of its range. */
        val NEUTRAL = MoodSignal(0.5, 0.0)

        /** A trend or magnitude with no data: zero. */
        val FLAT = MoodSignal(0.0, 0.0)
    }
}

/** Every signal one [MeshMood.advance] reads, each a value with its confidence. */
internal data class MoodSignals(
    val activity: MoodSignal,
    val activityTrend: MoodSignal,
    val reach: MoodSignal,
    val link: MoodSignal,
    val senders: MoodSignal,
    val warmth: MoodSignal,
    val wind: MoodSignal,
    val channel: MoodSignal,
    val pressureTrend: MoodSignal,
    val temperatureTrend: MoodSignal,
    val batteryTrend: MoodSignal,
)

/** The overlay's driver column: which signal moves each parameter and that signal's reading, by row name. */
internal fun driversFor(s: MoodSignals, choice: RotationTargets.DirectionChoice): ParamDrivers {
    val activityTrusted = s.activity.confidence >= TRUSTED_CONFIDENCE
    val tilt = tiltDriver(s)
    val wobble = wobbleDriver(s)
    val lean = leanDriver(s)
    val shown = HashMap<String, String>()
    if (s.activity.known) shown["turn time"] = reading(s.activity)
    if (activityTrusted) shown["phrase speed"] = reading(s.activity)
    directionReading(s, choice)?.let { shown["rotation direction"] = it }
    tilt?.let { shown["tilt"] = it.reading }
    wobble?.let { shown["wobble"] = it.reading }
    lean?.let { shown["mode lean"] = it.reading }
    if (s.senders.known) shown["pentatonic bias"] = reading(s.senders)
    if (s.link.known) shown["gravity"] = reading(s.link)
    return ParamDrivers(
        rotationSpeed = if (s.activity.known) "activity" else null,
        rotationDirection = choice.source.label,
        tilt = tilt?.label,
        wobble = wobble?.label,
        phraseSpeed = if (activityTrusted) "activity" else null,
        modeLean = lean?.label,
        scalePentatonicBias = if (s.senders.known) "senders" else null,
        gravity = if (s.link.known) "link quality" else null,
        readings = shown,
    )
}

private class Driver(val label: String, val reading: String)

private fun reading(signal: MoodSignal) = formatDecimal(signal.value, READING_DECIMALS)

private fun tiltDriver(s: MoodSignals): Driver? = when {
    s.pressureTrend.known -> Driver("pressure trend", reading(s.pressureTrend))
    s.reach.known -> Driver("reach", reading(s.reach))
    else -> null
}

private fun wobbleDriver(s: MoodSignals): Driver? = when {
    s.wind.known -> Driver("wind", reading(s.wind))
    s.channel.known -> Driver("channel use", reading(s.channel))
    s.senders.known -> Driver("senders", reading(s.senders))
    else -> null
}

private fun leanDriver(s: MoodSignals): Driver? = when {
    s.link.known && s.warmth.known -> Driver("link quality and warmth", "${reading(s.link)} and ${reading(s.warmth)}")
    s.link.known -> Driver("link quality", reading(s.link))
    s.warmth.known -> Driver("warmth", reading(s.warmth))
    else -> null
}

/** The trend that chose the direction, signed; null when none did. */
private fun directionReading(s: MoodSignals, choice: RotationTargets.DirectionChoice): String? {
    val trend = when (choice.source) {
        RotationTargets.DirectionSource.Pressure -> s.pressureTrend
        RotationTargets.DirectionSource.Temperature -> s.temperatureTrend
        RotationTargets.DirectionSource.Battery -> s.batteryTrend
        RotationTargets.DirectionSource.Activity -> s.activityTrend
        RotationTargets.DirectionSource.None -> null
    } ?: return null
    return (if (trend.value > 0) "+" else "") + reading(trend)
}

private const val READING_DECIMALS = 2
