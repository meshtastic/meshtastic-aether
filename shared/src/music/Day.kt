package org.meshtastic.ambient.music

import kotlin.math.PI
import kotlin.math.cos

/**
 * What the clock sets: [rootHz] (weekday key, glided over the ten minutes around midnight), [mode] by [brightness],
 * and the density, register ([registerOctaves], 0 by day and down to -1 at night) and [decayScale] (1 by day, 1.6 late).
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
    private const val GLIDE_START = 1435

    fun at(m: LocalMoment): DayState {
        val minute = m.minuteOfDay.coerceIn(0, 1439)
        val today = Theory.keySemitones(m.dayOfWeek).toDouble()
        val yesterday = Theory.keySemitones((m.dayOfWeek + 5) % 7 + 1).toDouble()
        val tomorrow = Theory.keySemitones(m.dayOfWeek % 7 + 1).toDouble()
        val semis = when {
            minute >= GLIDE_START -> today + (tomorrow - today) * (minute - GLIDE_START) / 10.0
            minute < 5 -> yesterday + (today - yesterday) * (minute + 5) / 10.0
            else -> today
        }
        val phase = 2 * PI * (minute / 1440.0)
        val brightness = 0.5 + 0.45 * cos(phase - 2 * PI * 11.0 / 24.0)
        val daylight = 0.5 + 0.5 * cos(phase - 2 * PI * 15.0 / 24.0)
        return DayState(
            rootHz = Theory.rootHz(semis),
            rootSemitones = semis,
            mode = modeFor(brightness),
            brightness = brightness,
            density = 0.2 + 0.6 * daylight,
            registerOctaves = -(1 - daylight),
            decayScale = 1.0 + 0.6 * (1 - daylight),
        )
    }

    fun modeFor(brightness: Double): Mode = when {
        brightness >= 0.85 -> Mode.Lydian
        brightness >= 0.75 -> Mode.Ionian
        brightness >= 0.55 -> Mode.Mixolydian
        brightness >= 0.35 -> Mode.Dorian
        else -> Mode.Aeolian
    }
}
