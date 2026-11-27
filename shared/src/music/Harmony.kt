package org.meshtastic.aether.music

import kotlin.math.exp

/** A seventh chord stacked in thirds on a scale step of [mode]; [voicing] holds absolute scale steps, one per voice. */
class Chord(val mode: Mode, val rootStep: Int, val voicing: List<Int>) {
    val voiceSemitones: List<Int> get() = voicing.map(mode::semitoneOfStep)

    val rootSemitone: Int get() = mode.semitoneOfStep(rootStep)

    /** Pitch class (0..11) of chord role 0 root, 1 third, 2 fifth, 3 seventh, 4 ninth. */
    fun roleSemitone(role: Int): Int = mode.semitoneOfStep(rootStep + 2 * role).mod(Theory.SEMITONES_PER_OCTAVE)

    companion object {
        /** Chord roles a node can play: root, third, fifth, seventh and ninth. */
        const val ROLES = 5
    }
}

class Harmony(seed: Long, initialMode: Mode = Mode.Ionian) {
    private val rng = Rng.stream(seed, 1)

    var current: Chord = Chord(initialMode, 0, INITIAL_VOICING)
        private set

    /**
     * The next chord in [mode]: a new root that is never diminished, voices led by the smallest total step motion.
     * Leaving a scale of another size (7 notes to a pentatonic or back) first rescales the previous voicing to the new
     * size.
     */
    fun next(mode: Mode): Chord {
        val n = mode.size
        val prev = rescaled(current, n)
        val scale = n / DIATONIC_NOTES.toDouble()
        val candidates = (0 until n).filter { it != prev.rootStep && (n != DIATONIC_NOTES || !diminished(mode, it)) }
        val weights = candidates.map { root ->
            val mean = lead(prev.voicing, root, n).average()
            val drift = maxOf(0.0, kotlin.math.abs(mean - CENTRE * scale) - 2.0 * scale)
            WEIGHTS[(root - prev.rootStep).mod(n)] * exp(-RECENTRE * drift)
        }
        var pick = rng.nextDouble() * weights.sum()
        var root = candidates.last()
        for (i in candidates.indices) {
            pick -= weights[i]
            if (pick < 0) {
                root = candidates[i]
                break
            }
        }
        current = Chord(mode, root, lead(prev.voicing, root, n))
        return current
    }

    private fun rescaled(chord: Chord, n: Int): Chord {
        val old = chord.mode.size
        if (old == n) return chord
        fun step(s: Int): Int = kotlin.math.round(s * n / old.toDouble()).toInt()
        return Chord(chord.mode, step(chord.rootStep).mod(n), chord.voicing.map(::step))
    }

    /** A root whose fifth (four scale steps up) is not a perfect fifth. */
    private fun diminished(mode: Mode, root: Int): Boolean {
        val fifth = mode.semitoneOfStep(root + FIFTH_STEPS) - mode.semitoneOfStep(root)
        return fifth.mod(Theory.SEMITONES_PER_OCTAVE) != PERFECT_FIFTH_SEMITONES
    }

    /**
     * Each voice holds or moves one scale step, at least one holds, and the voicing is kept near [CENTRE] (scaled to
     * [n] notes per octave).
     */
    private fun lead(prev: List<Int>, root: Int, n: Int): List<Int> {
        val scale = n / DIATONIC_NOTES.toDouble()
        val targets = IntArray(VOICES) { (root + 2 * it).mod(n) }
        var best: List<Int> = prev
        var bestCost = Double.MAX_VALUE
        var bestRank = -1
        for (perm in PERMUTATIONS) {
            var cost = 0.0
            var held = false
            var stepwise = true
            val out = ArrayList<Int>(VOICES)
            for (i in 0 until VOICES) {
                val t = targets[perm[i]]
                val step = (-REACH..REACH).map { prev[i] + it }.first { it.mod(n) == t }
                val move = kotlin.math.abs(step - prev[i])
                if (move > 1) stepwise = false
                cost += move
                if (move == 0) held = true
                cost += WANDER * maxOf(0.0, kotlin.math.abs(step - CENTRE * scale) - BAND * scale)
                out += step
            }
            val rank = (if (stepwise) 2 else 0) + (if (held) 1 else 0)
            if (rank > bestRank || (rank == bestRank && cost < bestCost)) {
                bestCost = cost
                bestRank = rank
                best = out
            }
        }
        return best
    }

    private companion object {
        /** Voices in a chord: the four notes of a seventh chord. */
        const val VOICES = 4
        const val DIATONIC_NOTES = 7
        const val FIFTH_STEPS = 4
        const val PERFECT_FIFTH_SEMITONES = 7

        /** How far, in scale steps, a voice looks for its next chord tone: every pitch class is within 3 steps. */
        const val REACH = 3

        /** The first chord: the tonic seventh an octave above the key root, scale steps 7, 9, 11 and 13. */
        val INITIAL_VOICING = listOf(7, 9, 11, 13)

        /** Preference for the root moving by interval 0..6 steps: fourths and fifths over seconds and sevenths. */
        val WEIGHTS = doubleArrayOf(0.0, 2.0, 2.0, 3.0, 3.0, 2.0, 1.0)
        const val CENTRE = 10.0
        const val BAND = 5.0
        const val WANDER = 2.0

        /** Strictly stepwise leading can only drift, so roots whose voicing moves toward [CENTRE] are favoured. */
        const val RECENTRE = 0.7
        val PERMUTATIONS: List<IntArray> = buildList {
            fun go(prefix: List<Int>) {
                if (prefix.size == VOICES) {
                    add(prefix.toIntArray())
                } else {
                    for (i in 0 until VOICES) if (i !in prefix) go(prefix + i)
                }
            }
            go(emptyList())
        }
    }
}
