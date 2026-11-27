package org.meshtastic.aether.music

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Just intonation for one chord. Every pitch class starts on the key's 5-limit frame ([Theory.justRatio]); a chord tone
 * may move a syntonic comma (81/80) either way when that makes the chord purer. Purity is the summed Tenney harmonic
 * distance of every pair of chord tones, octaves ignored, so the 40/27 fifth and 32/27 third that the fixed frame puts
 * into ii, v and bVII are replaced by 3/2 and 6/5. Moving a tone costs a little, and moving the tonic or fifth of the
 * key costs more than any wolf saves, so the home pitches stay put and the hour chime and dawn swells, which sound
 * them, stay in tune with the bed. The fourth may move: G7 in C needs F at 27/20 for a pure D-F minor third.
 */
internal object Tuning {
    /** Exponents of 3 and 5 (2 is octave and ignored) for each degree of [Theory.justRatio]'s table. */
    private val THREE = intArrayOf(0, -1, 2, 1, 0, -1, 2, 1, 0, -1, 2, 1)
    private val FIVE = intArrayOf(0, -1, 0, -1, 1, 0, 1, 0, -1, 1, -1, 1)

    /** The syntonic comma 81/80 is 3^4 / 5 (times a power of 2). */
    private const val COMMA_THREES = 4
    private const val COMMA_FIVES = -1
    const val COMMA = 81.0 / 80.0

    /** Cost of moving a tone off the key frame, and of moving the key's tonic or fifth. */
    private const val MOVE_COST = 0.5
    private const val ANCHOR_COST = 100.0
    private val ANCHORS = setOf(0, 7)

    /**
     * A pair a syntonic comma off a fifth, fourth, third or sixth (40/27, 27/20, 32/27, 27/16, 81/64, 128/81) costs
     * this on top of its distance: those are the beating intervals the tuning exists to remove.
     */
    private const val WOLF_COST = 10.0
    private const val WOLF_THREES = 3
    private const val PYTHAGOREAN_THIRD_THREES = 4

    private val LOG2_3 = ln(3.0) / ln(2.0)
    private val LOG2_5 = ln(5.0) / ln(2.0)

    private const val SHIFTS = 3

    /** A cost must beat the best so far by this much to win, so ties keep the earlier (key frame) choice. */
    private const val TIE = 1e-9

    /**
     * Comma shifts (-1, 0 or +1) for the distinct pitch classes in [pitchClasses] (0..11 above the key), in the order
     * given. Ties keep the key frame.
     */
    fun shifts(pitchClasses: List<Int>): IntArray {
        val pcs = pitchClasses.map { it.mod(Theory.SEMITONES_PER_OCTAVE) }
        val n = pcs.size
        val best = IntArray(n)
        val trial = IntArray(n)
        var bestCost = Double.MAX_VALUE
        var combos = 1
        repeat(n) { combos *= SHIFTS }
        for (code in 0 until combos) {
            var c = code
            for (i in 0 until n) {
                trial[i] = c % SHIFTS - 1
                c /= SHIFTS
            }
            val cost = cost(pcs, trial)
            if (cost < bestCost - TIE) {
                bestCost = cost
                trial.copyInto(best)
            }
        }
        return best
    }

    private fun cost(pcs: List<Int>, shift: IntArray): Double {
        var total = 0.0
        for (i in pcs.indices) {
            if (shift[i] != 0) total += if (pcs[i] in ANCHORS) ANCHOR_COST else MOVE_COST
            for (j in i + 1 until pcs.size) {
                val threes = THREE[pcs[j]] - THREE[pcs[i]] + COMMA_THREES * (shift[j] - shift[i])
                val fives = FIVE[pcs[j]] - FIVE[pcs[i]] + COMMA_FIVES * (shift[j] - shift[i])
                total += abs(threes) * LOG2_3 + abs(fives) * LOG2_5
                if (wolf(threes, fives)) total += WOLF_COST
            }
        }
        return total
    }

    /** Whether 3^[threes] 5^[fives] is a comma off a consonance: 40/27, 27/20, 32/27, 27/16, 81/64 or 128/81. */
    private fun wolf(threes: Int, fives: Int): Boolean {
        val t = abs(threes)
        return (t == WOLF_THREES && (fives == 0 || fives == -threes.sign())) ||
            (t == PYTHAGOREAN_THIRD_THREES && fives == 0)
    }

    private fun Int.sign(): Int = if (this > 0) 1 else -1

    /** Ratio factor for a comma [shift]. */
    fun factor(shift: Int): Double = COMMA.pow(shift)
}
