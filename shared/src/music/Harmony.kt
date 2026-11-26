package org.meshtastic.ambient.music

import kotlin.math.exp

/** A seventh chord stacked in thirds on a scale step of [mode]; [voicing] holds absolute scale steps, one per voice. */
class Chord(val mode: Mode, val rootStep: Int, val voicing: List<Int>) {
    val voiceSemitones: List<Int> get() = voicing.map(mode::semitoneOfStep)

    val rootSemitone: Int get() = mode.semitoneOfStep(rootStep)

    /** Pitch class (0..11) of chord role 0 root, 1 third, 2 fifth, 3 seventh, 4 ninth. */
    fun roleSemitone(role: Int): Int = mode.semitoneOfStep(rootStep + 2 * role).mod(12)
}

class Harmony(seed: Long, initialMode: Mode = Mode.Ionian) {
    private val rng = Rng.stream(seed, 1)

    var current: Chord = Chord(initialMode, 0, listOf(7, 9, 11, 13))
        private set

    /** The next chord in [mode]: a new root that is never diminished, voices led by the smallest total step motion. */
    fun next(mode: Mode): Chord {
        val prev = current
        val candidates = (0..6).filter { it != prev.rootStep && !diminished(mode, it) }
        val weights = candidates.map { root ->
            val mean = lead(prev.voicing, root).average()
            WEIGHTS[(root - prev.rootStep).mod(7)] * exp(-RECENTRE * maxOf(0.0, kotlin.math.abs(mean - CENTRE) - 2.0))
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
        current = Chord(mode, root, lead(prev.voicing, root))
        return current
    }

    private fun diminished(mode: Mode, root: Int): Boolean =
        (mode.semitoneOfStep(root + 4) - mode.semitoneOfStep(root)).mod(12) != 7

    /** Each voice holds or moves one scale step, at least one holds, and the voicing is kept near [CENTRE]. */
    private fun lead(prev: List<Int>, root: Int): List<Int> {
        val targets = IntArray(4) { (root + 2 * it).mod(7) }
        var best: List<Int> = prev
        var bestCost = Double.MAX_VALUE
        var bestRank = -1
        for (perm in PERMUTATIONS) {
            var cost = 0.0
            var held = false
            var stepwise = true
            val out = ArrayList<Int>(4)
            for (i in 0..3) {
                val t = targets[perm[i]]
                val step = (-3..3).map { prev[i] + it }.first { it.mod(7) == t }
                val move = kotlin.math.abs(step - prev[i])
                if (move > 1) stepwise = false
                cost += move
                if (move == 0) held = true
                cost += WANDER * maxOf(0.0, kotlin.math.abs(step - CENTRE) - BAND)
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
        /** Preference for the root moving by interval 0..6 steps: fourths and fifths over seconds and sevenths. */
        val WEIGHTS = doubleArrayOf(0.0, 2.0, 2.0, 3.0, 3.0, 2.0, 1.0)
        const val CENTRE = 10.0
        const val BAND = 5.0
        const val WANDER = 2.0

        /** Strictly stepwise leading can only drift, so roots whose voicing moves toward [CENTRE] are favoured. */
        const val RECENTRE = 0.7
        val PERMUTATIONS: List<IntArray> = buildList {
            fun go(prefix: List<Int>) {
                if (prefix.size == 4) add(prefix.toIntArray()) else (0..3).filter { it !in prefix }.forEach { go(prefix + it) }
            }
            go(emptyList())
        }
    }
}
