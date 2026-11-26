package org.meshtastic.aether.synth

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Port of the Clouds reverb (Emilie Gillet, MIT): the Griesinger topology from the Dattorro paper, four input
 * allpasses then two loops of two allpasses and a delay, with a smeared first allpass and two slowly modulated delays. The LFOs run 0..1 like the original's cosine oscillator,
 * so the modulated read offsets stay inside their delay lines.
 * Delay lengths scale from the original 32 kHz to [sampleRate]; the buffer is float instead of 12-bit.
 */
internal class Reverb(private val sampleRate: Int) {
    private val scale = sampleRate / REF_RATE
    private val len = IntArray(10)
    private val base = IntArray(10)
    private val buf: FloatArray
    private val mask: Int
    private var wp = 0

    private var acc = 0f
    private var prev = 0f

    private var lfo1 = 0f
    private var lfo2 = 0f
    private var lfo1Phase = 0.0
    private var lfo2Phase = 0.0

    private var lp1 = 0f
    private var lp2 = 0f
    private var floor = DENORMAL_FLOOR

    private var kap = 0.625f
    private var klp: Float
    private var krt = 0.8f
    private var gain = 0.3f

    private val offAp1Read = 10.0f * scale.toFloat()
    private val modAp1 = 60.0f * scale.toFloat()
    private val offAp1Write: Int
    private val offDel2 = 4680.0f * scale.toFloat()
    private val modDel2 = 100.0f * scale.toFloat()

    init {
        var total = 0
        for (i in 0 until 10) {
            len[i] = max(2, (LENGTHS[i] * scale).roundToInt())
            base[i] = total
            total += len[i] + 1
        }
        var size = 1
        while (size < total + 2) size = size shl 1
        buf = FloatArray(size)
        mask = size - 1
        // The original's one-pole coefficient is for 32 kHz; keep the cutoff when the rate changes.
        klp = (1.0 - (1.0 - LP).pow(REF_RATE / sampleRate)).toFloat()
        offAp1Write = (100.0 * scale).roundToInt().coerceAtMost(len[0] - 1)
        setRt60(20.0)
    }

    /** Sets the feedback so the tail falls 60 dB in about [seconds] (the loop gain applies twice per round trip). */
    fun setRt60(seconds: Double) {
        krt = 10.0.pow(-1.5 * LOOP_SECONDS / max(seconds, 0.1)).toFloat()
    }

    fun setInputGain(g: Float) {
        gain = g
    }

    fun clear() {
        buf.fill(0f)
        lp1 = 0f
        lp2 = 0f
    }

    /** Largest absolute value held in any delay or filter, and whether any of it is subnormal. */
    fun stateStats(): FloatArray {
        var peak = max(kotlin.math.abs(lp1), kotlin.math.abs(lp2))
        var denormals = 0f
        for (v in buf) {
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
            if (a != 0f && a < 1.17549435e-38f) denormals += 1f
        }
        return floatArrayOf(peak, denormals)
    }

    private fun tail(i: Int): Float = buf[(wp + base[i] + len[i] - 1) and mask]

    private fun readTail(i: Int, s: Float) {
        val r = tail(i)
        prev = r
        acc += r * s
    }

    private fun write(i: Int, offset: Int, s: Float) {
        buf[(wp + base[i] + offset) and mask] = acc
        acc *= s
    }

    private fun writeAllPass(i: Int, s: Float) {
        write(i, 0, s)
        acc += prev
    }

    private fun interpolate(i: Int, offset: Float, s: Float) {
        val oi = offset.toInt()
        val frac = offset - oi
        val a = buf[(wp + oi + base[i]) and mask]
        val b = buf[(wp + oi + base[i] + 1) and mask]
        val x = a + (b - a) * frac
        prev = x
        acc += x * s
    }

    private fun lowPass(state: Float): Float {
        val s = state + klp * (acc - state)
        acc = s
        return s
    }

    /** Reads [n] mono input samples and writes the stereo wet signal (no dry) to [outL] and [outR]. */
    fun process(input: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {
        val lfoInc1 = TWO_PI * LFO_1_HZ * 32.0 / sampleRate
        val lfoInc2 = TWO_PI * LFO_2_HZ * 32.0 / sampleRate
        for (k in 0 until n) {
            wp = (wp - 1) and mask
            if ((wp and 31) == 0) {
                lfo1Phase += lfoInc1
                lfo2Phase += lfoInc2
                if (lfo1Phase > TWO_PI) lfo1Phase -= TWO_PI
                if (lfo2Phase > TWO_PI) lfo2Phase -= TWO_PI
                lfo1 = (0.5 + 0.5 * sin(lfo1Phase)).toFloat()
                lfo2 = (0.5 + 0.5 * sin(lfo2Phase)).toFloat()
            }
            floor = -floor
            acc = 0f
            prev = 0f

            // Smear the first allpass inside its own loop.
            interpolate(0, offAp1Read + modAp1 * lfo1, 1.0f)
            write(0, offAp1Write, 0.0f)

            acc += input[k] * gain + floor

            readTail(0, kap)
            writeAllPass(0, -kap)
            readTail(1, kap)
            writeAllPass(1, -kap)
            readTail(2, kap)
            writeAllPass(2, -kap)
            readTail(3, kap)
            writeAllPass(3, -kap)
            val apout = acc

            // Main loop, first half.
            acc = apout + floor
            interpolate(9, offDel2 + modDel2 * lfo2, krt)
            lp1 = lowPass(lp1)
            readTail(4, -kap)
            writeAllPass(4, kap)
            readTail(5, kap)
            writeAllPass(5, -kap)
            write(6, 0, 2.0f)
            outL[k] = acc

            // Second half.
            acc = apout + floor
            readTail(6, krt)
            lp2 = lowPass(lp2)
            readTail(7, kap)
            writeAllPass(7, -kap)
            readTail(8, -kap)
            writeAllPass(8, kap)
            write(9, 0, 2.0f)
            outR[k] = acc
        }
    }

    private companion object {
        const val REF_RATE = 32000.0
        const val LP = 0.7
        const val LFO_1_HZ = 0.5
        const val LFO_2_HZ = 0.3
        val LENGTHS = intArrayOf(113, 162, 241, 399, 1653, 2038, 3411, 1913, 1663, 4782)

        /** Samples around one trip of the main loop at the original rate, over 32 kHz. */
        const val LOOP_SECONDS = (4680 + 1653 + 2038 + 3411 + 1913 + 1663) / 32000.0
    }
}
