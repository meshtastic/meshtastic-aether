package org.meshtastic.aether.music

import kotlin.math.pow

/** The major scale in semitones above the tonic; the six modes are its rotations. */
private val MAJOR_SCALE = listOf(0, 2, 4, 5, 7, 9, 11)

/** The major pentatonic drops fa and ti (0-based degrees) from the major scale. */
private val MAJOR_PENTATONIC_DROPS = setOf(3, 6)

/** The minor pentatonic drops the 2nd and 6th (0-based degrees) from natural minor. */
private val MINOR_PENTATONIC_DROPS = setOf(1, 5)

/** The major scale started on its 0-based [degree], measured from that note. */
private fun majorRotation(degree: Int): List<Int> = MAJOR_SCALE.indices.map { i ->
    (MAJOR_SCALE[(i + degree) % MAJOR_SCALE.size] - MAJOR_SCALE[degree]).mod(Theory.SEMITONES_PER_OCTAVE)
}

/** Each [Mode]'s semitones by ordinal, built once; an enum's own initializer cannot read its entries. */
private val MODE_SEMITONES: List<List<Int>> = Mode.entries.map { mode ->
    when (mode) {
        Mode.MajorPentatonic ->
            majorRotation(Mode.Ionian.ordinal).filterIndexed { i, _ -> i !in MAJOR_PENTATONIC_DROPS }
        Mode.MinorPentatonic ->
            majorRotation(Mode.Aeolian.ordinal).filterIndexed { i, _ -> i !in MINOR_PENTATONIC_DROPS }
        else -> majorRotation(mode.ordinal)
    }
}

/**
 * The eight scales in use (six modes and two pentatonics); Locrian is deliberately absent. Semitones above the tonic,
 * one octave. The six modes are declared in the order of the major-scale degree they start on.
 */
enum class Mode {
    Ionian,
    Dorian,
    Phrygian,
    Lydian,
    Mixolydian,
    Aeolian,
    MajorPentatonic,
    MinorPentatonic;

    val semitones: List<Int> get() = MODE_SEMITONES[ordinal]

    /** Notes per octave: 7 for the modes, 5 for the pentatonics. */
    val size: Int get() = semitones.size

    /** Semitones above the tonic of scale step [step]; steps run past the octave and below zero. */
    fun semitoneOfStep(step: Int): Int = semitones[step.mod(size)] + Theory.SEMITONES_PER_OCTAVE * step.floorDiv(size)
}

object Theory {
    const val SEMITONES_PER_OCTAVE = 12
    const val CENTS_PER_OCTAVE = 1200.0

    /** C3 in 12-TET at A4 = 440 Hz. */
    const val C3_HZ = 130.8127826502993

    /** Key root in semitones above C3 for Monday..Sunday: a walk round the circle of fifths. */
    val weekdayRootSemitones: List<Int> = listOf(0, 7, 2, 9, 4, 11, 5)

    /** [dayOfWeek] is 1 = Monday .. 7 = Sunday. */
    fun keySemitones(dayOfWeek: Int): Int = weekdayRootSemitones[(dayOfWeek - 1).mod(weekdayRootSemitones.size)]

    fun rootHz(semitonesAboveC3: Double): Double = C3_HZ * 2.0.pow(semitonesAboveC3 / SEMITONES_PER_OCTAVE)

    private val JUST_RATIOS = doubleArrayOf(
        1.0, 16.0 / 15, 9.0 / 8, 6.0 / 5, 5.0 / 4, 4.0 / 3,
        45.0 / 32, 3.0 / 2, 8.0 / 5, 5.0 / 3, 9.0 / 5, 15.0 / 8,
    )

    /** 5-limit just ratio for [degree] semitones above the root; octaves fold in, negatives descend. */
    fun justRatio(degree: Int): Double =
        JUST_RATIOS[degree.mod(SEMITONES_PER_OCTAVE)] * 2.0.pow(degree.floorDiv(SEMITONES_PER_OCTAVE))
}
