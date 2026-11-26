package org.meshtastic.aether.music

import kotlin.math.pow

/** The eight scales in use (six modes and two pentatonics); Locrian is deliberately absent. Semitones above the tonic, one octave. */
enum class Mode(val semitones: List<Int>) {
    Ionian(listOf(0, 2, 4, 5, 7, 9, 11)),
    Dorian(listOf(0, 2, 3, 5, 7, 9, 10)),
    Phrygian(listOf(0, 1, 3, 5, 7, 8, 10)),
    Lydian(listOf(0, 2, 4, 6, 7, 9, 11)),
    Mixolydian(listOf(0, 2, 4, 5, 7, 9, 10)),
    Aeolian(listOf(0, 2, 3, 5, 7, 8, 10)),
    MajorPentatonic(listOf(0, 2, 4, 7, 9)),
    MinorPentatonic(listOf(0, 3, 5, 7, 10));

    /** Notes per octave: 7 for the modes, 5 for the pentatonics. */
    val size: Int get() = semitones.size

    /** Semitones above the tonic of scale step [step]; steps run past the octave and below zero. */
    fun semitoneOfStep(step: Int): Int = semitones[step.mod(size)] + 12 * step.floorDiv(size)
}

object Theory {
    /** C3 in 12-TET at A4 = 440 Hz. */
    const val C3_HZ = 130.8127826502993

    /** Key root in semitones above C3 for Monday..Sunday: a walk round the circle of fifths. */
    val weekdayRootSemitones: List<Int> = listOf(0, 7, 2, 9, 4, 11, 5)

    /** [dayOfWeek] is 1 = Monday .. 7 = Sunday. */
    fun keySemitones(dayOfWeek: Int): Int = weekdayRootSemitones[(dayOfWeek - 1).mod(7)]

    fun rootHz(semitonesAboveC3: Double): Double = C3_HZ * 2.0.pow(semitonesAboveC3 / 12.0)

    private val JUST_RATIOS = doubleArrayOf(
        1.0, 16.0 / 15, 9.0 / 8, 6.0 / 5, 5.0 / 4, 4.0 / 3,
        45.0 / 32, 3.0 / 2, 8.0 / 5, 5.0 / 3, 9.0 / 5, 15.0 / 8,
    )

    /** 5-limit just ratio for [degree] semitones above the root; octaves fold in, negatives descend. */
    fun justRatio(degree: Int): Double =
        JUST_RATIOS[degree.mod(12)] * 2.0.pow(degree.floorDiv(12))
}
