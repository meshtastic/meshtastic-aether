package org.meshtastic.aether.tools

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/** ITU-R BS.1770 K-weighting: a +4 dB high shelf near 1.7 kHz, then a high-pass near 38 Hz (libebur128's design). */
private const val SHELF_HZ = 1681.974450955533
private const val SHELF_GAIN_DB = 3.999843853973347
private const val SHELF_Q = 0.7071752369554196
private const val SHELF_VB_EXPONENT = 0.4996667741545416
private const val HIGH_PASS_HZ = 38.13547087602444
private const val HIGH_PASS_Q = 0.5003270373238773

/** A second-order high-pass has numerator 1, -2, 1. */
private const val HIGH_PASS_B1 = -2.0

/** BS.1770's offset that makes a 997 Hz full-scale sine in one channel read -3.01 LKFS. */
private const val LOUDNESS_OFFSET = -0.691

/** Gating blocks are 400 ms with 75 percent overlap, so they are built from four 100 ms sub-blocks. */
private const val SUB_BLOCKS_PER_BLOCK = 4
private const val ABSOLUTE_GATE_LUFS = -70.0
private const val RELATIVE_GATE_LU = -10.0

/** The bed pulse being looked for, and the neighbouring lags it must beat, in 100 ms steps. */
private const val PULSE_LAG = 200
private const val PULSE_NEAR_LAG = 170
private const val PULSE_FAR_LAG = 230

/** A rest is a run of 100 ms steps at least this far below the window's median level. */
private const val REST_BELOW_MEDIAN_DB = 40.0

/** The envelope metrics step every 100 ms. */
private const val STEPS_PER_SECOND = 10

/** A low-band ratio counts spectrum energy at or below this. */
const val LOW_BAND_HZ = 120.0

private const val DB_POWER = 10.0
private const val DB_AMPLITUDE = 20.0

/** One biquad section, direct form 1, in Double. */
private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }
}

private fun shelf(sampleRate: Int): Biquad {
    val k = tan(PI * SHELF_HZ / sampleRate)
    val vh = 10.0.pow(SHELF_GAIN_DB / DB_AMPLITUDE)
    val vb = vh.pow(SHELF_VB_EXPONENT)
    val a0 = 1 + k / SHELF_Q + k * k
    return Biquad(
        (vh + vb * k / SHELF_Q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / SHELF_Q + k * k) / a0,
        2 * (k * k - 1) / a0, (1 - k / SHELF_Q + k * k) / a0,
    )
}

private fun highPass(sampleRate: Int): Biquad {
    val k = tan(PI * HIGH_PASS_HZ / sampleRate)
    val a0 = 1 + k / HIGH_PASS_Q + k * k
    return Biquad(1.0, HIGH_PASS_B1, 1.0, 2 * (k * k - 1) / a0, (1 - k / HIGH_PASS_Q + k * k) / a0)
}

/** K-weights a stereo stream and keeps the per-100 ms mean square (channels summed, BS.1770 weights 1 and 1). */
internal class KWeighting(sampleRate: Int) {
    private val stages = Array(2) { arrayOf(shelf(sampleRate), highPass(sampleRate)) }
    private val subFrames = sampleRate / STEPS_PER_SECOND
    private var acc = 0.0
    private var fill = 0

    /** Mean square per 100 ms sub-block in this window. */
    val subBlocks = ArrayList<Double>()

    fun push(l: Double, r: Double) {
        val kl = stages[0][1].process(stages[0][0].process(l))
        val kr = stages[1][1].process(stages[1][0].process(r))
        acc += kl * kl + kr * kr
        if (++fill == subFrames) {
            subBlocks += acc / subFrames
            acc = 0.0
            fill = 0
        }
    }

    fun reset() = subBlocks.clear()
}

private fun lufs(meanSquare: Double): Double = LOUDNESS_OFFSET + DB_POWER * log10(meanSquare)

/** Gated integrated loudness of [subBlocks] (BS.1770-4), or negative infinity when nothing passes the gates. */
internal fun integratedLoudness(subBlocks: List<Double>): Double {
    val blocks = (SUB_BLOCKS_PER_BLOCK - 1 until subBlocks.size).map { j ->
        (j - SUB_BLOCKS_PER_BLOCK + 1..j).sumOf { subBlocks[it] } / SUB_BLOCKS_PER_BLOCK
    }
    val absolute = blocks.filter { it > 0 && lufs(it) > ABSOLUTE_GATE_LUFS }
    if (absolute.isEmpty()) return Double.NEGATIVE_INFINITY
    val gate = lufs(absolute.average()) + RELATIVE_GATE_LU
    val relative = absolute.filter { lufs(it) > gate }
    return if (relative.isEmpty()) Double.NEGATIVE_INFINITY else lufs(relative.average())
}

/**
 * How strongly the 100 ms level envelope repeats every 20 s and not at 17 or 23 s: its autocorrelation at 20 s minus
 * the larger of those at its neighbours. A bed dipping on a common 20 s tick scores near 1; a loop on another period,
 * or steady sound, scores at or below 0.
 */
internal fun pulse20(envelope: List<Double>): Double {
    val mean = envelope.average()
    val e = DoubleArray(envelope.size) { envelope[it] - mean }
    val energy = e.sumOf { it * it }
    if (envelope.size <= PULSE_FAR_LAG + PULSE_LAG || energy <= 0.0) return 0.0
    fun r(lag: Int): Double {
        var s = 0.0
        for (i in 0 until e.size - lag) s += e[i] * e[i + lag]
        return s / energy * e.size / (e.size - lag)
    }
    return r(PULSE_LAG) - maxOf(r(PULSE_NEAR_LAG), r(PULSE_FAR_LAG))
}

/** Longest run, in seconds, of 100 ms RMS steps at least [REST_BELOW_MEDIAN_DB] under the median step. */
internal fun longestRestSec(envelope: List<Double>): Double {
    if (envelope.isEmpty()) return 0.0
    val median = envelope.sorted()[envelope.size / 2]
    val threshold = median * 10.0.pow(-REST_BELOW_MEDIAN_DB / DB_AMPLITUDE)
    var run = 0
    var best = 0
    for (x in envelope) {
        run = if (x < threshold) run + 1 else 0
        if (run > best) best = run
    }
    return best.toDouble() / STEPS_PER_SECOND
}

/**
 * RMS per 100 ms of the per-frame channel power, kept for the window's envelope metrics. Power, not the signed channel
 * average, so channels in opposite polarity do not cancel into a false rest.
 */
internal class Envelope(sampleRate: Int) {
    private val subFrames = sampleRate / STEPS_PER_SECOND
    private var acc = 0.0
    private var fill = 0
    val steps = ArrayList<Double>()

    fun push(power: Double) {
        acc += power
        if (++fill == subFrames) {
            steps += sqrt(acc / subFrames)
            acc = 0.0
            fill = 0
        }
    }

    fun reset() = steps.clear()
}
