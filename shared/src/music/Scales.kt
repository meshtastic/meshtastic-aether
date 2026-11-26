package org.meshtastic.aether.music

/**
 * Picks the scale at a chord boundary: the clock's mode is the base, `modeLean` moves it at most one step along the
 * brightness order (Lydian, Ionian, Mixolydian, Dorian, Aeolian, Phrygian; never past either end, so never Locrian), and
 * `scalePentatonicBias` opens it to the matching pentatonic. Both moves have hysteresis: they enter past one threshold and
 * leave only past a lower one, so a value hovering on an edge does not flicker. Call [pick] only at a chord boundary.
 */
class ScalePicker {
    private var leanStep = 0
    private var pentatonic = false

    /** The step the lean currently holds: -1 darker, 0 none, 1 brighter. */
    val leaning: Int get() = leanStep

    /** True while the scale is held open to a pentatonic. */
    val isPentatonic: Boolean get() = pentatonic

    fun pick(base: Mode, modeLean: Double, pentatonicBias: Double): Mode {
        val lean = if (modeLean.isFinite()) modeLean.coerceIn(-1.0, 1.0) else 0.0
        val bias = if (pentatonicBias.isFinite()) pentatonicBias.coerceIn(0.0, 1.0) else 0.0
        leanStep = when (leanStep) {
            1 -> if (lean < LEAN_EXIT) (if (lean < -LEAN_ENTER) -1 else 0) else 1
            -1 -> if (lean > -LEAN_EXIT) (if (lean > LEAN_ENTER) 1 else 0) else -1
            else -> if (lean > LEAN_ENTER) 1 else if (lean < -LEAN_ENTER) -1 else 0
        }
        pentatonic = if (pentatonic) bias > PENTATONIC_EXIT else bias > PENTATONIC_ENTER
        val at = (brightnessIndex(base) - leanStep).coerceIn(0, BRIGHT_TO_DARK.lastIndex)
        return if (pentatonic) {
            if (at <= LAST_MAJOR_INDEX) Mode.MajorPentatonic else Mode.MinorPentatonic
        } else {
            BRIGHT_TO_DARK[at]
        }
    }

    companion object {
        const val LEAN_ENTER = 0.6
        const val LEAN_EXIT = 0.35
        const val PENTATONIC_ENTER = 0.7
        const val PENTATONIC_EXIT = 0.45

        /** The six modes from brightest to darkest. */
        val BRIGHT_TO_DARK = listOf(Mode.Lydian, Mode.Ionian, Mode.Mixolydian, Mode.Dorian, Mode.Aeolian, Mode.Phrygian)

        /** Lydian, Ionian and Mixolydian have a major third. */
        private const val LAST_MAJOR_INDEX = 2

        private fun brightnessIndex(mode: Mode): Int = when (mode) {
            Mode.MajorPentatonic -> 1
            Mode.MinorPentatonic -> 4
            else -> BRIGHT_TO_DARK.indexOf(mode)
        }
    }
}
