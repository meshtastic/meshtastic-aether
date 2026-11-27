package org.meshtastic.aether.music

import kotlin.math.PI
import kotlin.math.cos

/**
 * What the clock sets: [rootHz] (the weekday's key; it steps at midnight and the composer moves to it at a chord
 * boundary), [mode] by [brightness], and the density, register ([registerOctaves], 0 by day and down to -1 at night)
 * and [decayScale] (1 by day, 1.6 late).
 */
data class DayState(
    val rootHz: Double,
    val rootSemitones: Double,
    val mode: Mode,
    val brightness: Double,
    val density: Double,
    val registerOctaves: Double,
    val decayScale: Double,
)

object Day {
    const val MINUTES_PER_HOUR = 60
    const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

    /** How much longer tails get at the darkest hour: [DayState.decayScale] runs 1 to 1 + this. */
    const val DECAY_BOOST = 0.6

    /** The darkest hours (about 21:00 to 01:00) take Phrygian, the darkest of the modes. */
    const val PHRYGIAN_BELOW = 0.1

    private const val HOURS_PER_DAY = 24.0

    /** Brightness is a cosine over the day around [BRIGHTNESS_MEAN], highest at [BRIGHTEST_HOUR]. */
    private const val BRIGHTNESS_MEAN = 0.5
    private const val BRIGHTNESS_SWING = 0.45
    private const val BRIGHTEST_HOUR = 11.0

    /** Daylight is a 0..1 cosine over the day, highest in the mid-afternoon. */
    private const val DAYLIGHT_PEAK_HOUR = 15.0

    private const val LYDIAN_FROM = 0.85
    private const val IONIAN_FROM = 0.75
    private const val MIXOLYDIAN_FROM = 0.55
    private const val DORIAN_FROM = 0.35

    /** [LocalMoment.minuteOfDay] kept inside the day. */
    fun minuteOf(m: LocalMoment): Int = m.minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)

    fun at(m: LocalMoment): DayState {
        val minute = minuteOf(m)
        val semis = Theory.keySemitones(m.dayOfWeek).toDouble()
        val phase = 2 * PI * (minute / MINUTES_PER_DAY.toDouble())
        val brightness = BRIGHTNESS_MEAN + BRIGHTNESS_SWING * cos(phase - 2 * PI * BRIGHTEST_HOUR / HOURS_PER_DAY)
        val daylight = (1 + cos(phase - 2 * PI * DAYLIGHT_PEAK_HOUR / HOURS_PER_DAY)) / 2
        return DayState(
            rootHz = Theory.rootHz(semis),
            rootSemitones = semis,
            mode = modeFor(brightness),
            brightness = brightness,
            density = 0.2 + 0.6 * daylight,
            registerOctaves = -(1 - daylight),
            decayScale = 1.0 + DECAY_BOOST * (1 - daylight),
        )
    }

    fun modeFor(brightness: Double): Mode = when {
        brightness >= LYDIAN_FROM -> Mode.Lydian
        brightness >= IONIAN_FROM -> Mode.Ionian
        brightness >= MIXOLYDIAN_FROM -> Mode.Mixolydian
        brightness >= DORIAN_FROM -> Mode.Dorian
        brightness >= PHRYGIAN_BELOW -> Mode.Aeolian
        else -> Mode.Phrygian
    }
}
