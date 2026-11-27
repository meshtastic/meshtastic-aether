package org.meshtastic.aether.music

import kotlin.math.min

/** How much a busy mesh lifts the pad layer above the form's level. */
private const val PAD_DENSITY_LIFT = 0.4f

// Each texture field is a base plus weighted 0..1 drivers: weather, the clock and the mesh mood.
private const val BRIGHTNESS_TEMPERATURE = 0.3
private const val BRIGHTNESS_DAY = 0.4
private const val BRIGHTNESS_MESH = 0.3
private const val REVERB_SIZE_BASE = 0.3
private const val REVERB_SIZE_HUMIDITY = 0.4
private const val REVERB_SIZE_DECAY = 0.3
private const val REVERB_WET_BASE = 0.3
private const val REVERB_WET_HUMIDITY = 0.4
private const val REVERB_WET_DECAY = 0.2
private const val DETUNE_BASE = 0.1
private const val DETUNE_LOW_PRESSURE = 0.5

/**
 * Detune with no pressure reading: the synth's own starting detune. Most meshes report no pressure (none of 212 nodes
 * in the documented live run), and a placeholder mid pressure held the detune at 0.35 for good.
 */
private const val DETUNE_NEUTRAL = 0.2
private const val SHIMMER_LUX = 0.5
private const val SHIMMER_FORM = 0.5
private const val NOISE_BASE = 0.1
private const val NOISE_WIND = 0.5
private const val NOISE_TENSION = 0.2

/** The drone's full form level: the bed at its loudest, where the noise texture sits at full strength. */
private const val FULL_BED = 0.9f

/** Temperature and humidity with no reading sit at the middle of their range. */
private const val NEUTRAL_READING = 0.5

private fun unit(x: Double): Float = x.coerceIn(0.0, 1.0).toFloat()

/**
 * The global sound character the synth fades toward: the form's layer levels colored by weather, clock and mood. A
 * weather quantity no node reports adds nothing: wind and lux appear on almost no real mesh, and a placeholder middle
 * reading kept a hiss and an octave shimmer under everything. The hiss belongs to the bed, so it thins with the form
 * and stops in a rest.
 */
internal class TextureMixer(private val score: Score, private val weather: WeatherGestures) {
    fun texture(day: DayState, tSec: Double, nowMs: Long): Texture {
        fun scaled(q: Quantity): Double = weather.reading(q, nowMs) ?: NEUTRAL_READING
        val levels = score.form.levels(tSec)
        val mood = score.mood
        val pad = min(1f, levels.getValue(Voice.Pad) * (1f + PAD_DENSITY_LIFT * mood.density.toFloat()))
        val decayN = (day.decayScale - 1.0) / Day.DECAY_BOOST
        val humidity = scaled(Quantity.Humidity)
        fun reported(q: Quantity, weight: Double): Double = weather.reading(q, nowMs)?.let { weight * it } ?: 0.0
        val bed = min(1f, Form.visible(levels) / FULL_BED)
        return Texture(
            rootHz = score.keyHz,
            brightness = unit(
                BRIGHTNESS_TEMPERATURE * scaled(Quantity.Temperature) + BRIGHTNESS_DAY * day.brightness +
                    BRIGHTNESS_MESH * mood.brightness,
            ),
            reverbSize = unit(REVERB_SIZE_BASE + REVERB_SIZE_HUMIDITY * humidity + REVERB_SIZE_DECAY * decayN),
            reverbWet = unit(REVERB_WET_BASE + REVERB_WET_HUMIDITY * humidity + REVERB_WET_DECAY * decayN),
            detune = weather.reading(Quantity.Pressure, nowMs)
                ?.let { unit(DETUNE_BASE + DETUNE_LOW_PRESSURE * (1 - it)) } ?: DETUNE_NEUTRAL.toFloat(),
            shimmer = unit(reported(Quantity.Lux, SHIMMER_LUX) + SHIMMER_FORM * levels.getValue(Voice.Shimmer)),
            noise = unit(NOISE_BASE + reported(Quantity.Wind, NOISE_WIND) + NOISE_TENSION * mood.tension) * bed,
            layerLevels = mapOf(
                Voice.Drone to levels.getValue(Voice.Drone),
                Voice.Pad to pad,
                Voice.Shimmer to levels.getValue(Voice.Shimmer),
            ),
        )
    }
}
