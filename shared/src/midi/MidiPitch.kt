package org.meshtastic.aether.midi

import kotlin.math.ln
import kotlin.math.roundToInt

/** A note number and the 14-bit pitch bend that moves it to an exact pitch. */
class TunedNote(val key: Int, val bend: Int)

/** Pitch arithmetic from the MIDI 1.0 specification: note 69 is A4 at 440 Hz, bend 8192 is centred. */
object MidiPitch {
    const val CENTER_BEND = 8192
    const val MAX_BEND = 16383
    const val MAX_KEY = 127
    private const val A4_KEY = 69
    private const val A4_HZ = 440.0
    private const val SEMITONES_PER_OCTAVE = 12
    private const val CENTS_PER_SEMITONE = 100.0

    /** [hz] as a fractional note number. */
    fun noteNumber(hz: Double): Double = A4_KEY + SEMITONES_PER_OCTAVE * ln(hz / A4_HZ) / ln(2.0)

    /** The nearest note to [hz] and the bend that reaches it at [range] semitones, or null off the keyboard. */
    fun tune(hz: Double, range: Int): TunedNote? {
        val exact = if (hz > 0.0) noteNumber(hz) else Double.NaN
        if (!(exact > -HALF && exact < MAX_KEY + HALF)) return null
        val key = exact.roundToInt()
        val bend = CENTER_BEND + ((exact - key) / range * CENTER_BEND).roundToInt()
        return TunedNote(key, bend.coerceIn(0, MAX_BEND))
    }

    private const val HALF = 0.5

    /** The distance in cents between two bends at a bend range of [range] semitones. */
    fun bendCents(a: Int, b: Int, range: Int): Double =
        kotlin.math.abs(a - b).toDouble() / CENTER_BEND * range * CENTS_PER_SEMITONE
}
