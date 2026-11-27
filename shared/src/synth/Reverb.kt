package org.meshtastic.aether.synth

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Port of the Clouds reverb (Emilie Gillet, MIT): the Griesinger topology from the Dattorro paper, four input
 * allpasses then two loops of two allpasses and a delay, with a smeared first allpass and two slowly modulated delays.
 * The LFOs run 0..1 like the original's cosine oscillator, so the modulated read offsets stay inside their delay lines.
 * Delay lengths scale from the original 32 kHz to [sampleRate]; the buffer is float instead of 12-bit.
 */
internal class Reverb(private val sampleRate: Int) {
    private val scale = sampleRate / REF_RATE
    private val len = IntArray(LENGTHS.size)
    private val base = IntArray(LENGTHS.size)
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

    private var kap = DIFFUSION
    private var klp: Float
    private var krt = 0f

    /** Level of the mono input into the tank. */
    var inputGain = DEFAULT_INPUT_GAIN

    private val offAp1Read = AP1_SMEAR_READ * scale.toFloat()
    private val modAp1 = AP1_SMEAR_DEPTH * scale.toFloat()
    private val offAp1Write: Int
    private val offDel2 = DEL2_TAP * scale.toFloat()
    private val modDel2 = DEL2_DEPTH * scale.toFloat()

    init {
        var total = 0
        for (i in LENGTHS.indices) {
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
        offAp1Write = (AP1_SMEAR_WRITE * scale).roundToInt().coerceAtMost(len[AP1] - 1)
        setRt60(START_RT60)
    }

    /** Sets the feedback so the tail falls 60 dB in about [seconds] (the loop gain applies twice per round trip). */
    fun setRt60(seconds: Double) {
        krt = DECADE.pow(DECADES_PER_PASS * LOOP_SECONDS / max(seconds, MIN_RT60)).toFloat()
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
            if (a != 0f && a < SMALLEST_NORMAL) denormals += 1f
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
        val lfoInc1 = TWO_PI * LFO_1_HZ * LFO_PERIOD / sampleRate
        val lfoInc2 = TWO_PI * LFO_2_HZ * LFO_PERIOD / sampleRate
        for (k in 0 until n) {
            wp = (wp - 1) and mask
            if ((wp and LFO_PERIOD_MASK) == 0) {
                lfo1Phase += lfoInc1
                lfo2Phase += lfoInc2
                if (lfo1Phase > TWO_PI) lfo1Phase -= TWO_PI
                if (lfo2Phase > TWO_PI) lfo2Phase -= TWO_PI
                lfo1 = ((1.0 + sin(lfo1Phase)) / 2).toFloat()
                lfo2 = ((1.0 + sin(lfo2Phase)) / 2).toFloat()
            }
            floor = -floor
            acc = 0f
            prev = 0f

            // Smear the first allpass inside its own loop.
            interpolate(AP1, offAp1Read + modAp1 * lfo1, 1.0f)
            write(AP1, offAp1Write, 0.0f)

            acc += input[k] * inputGain + floor

            readTail(AP1, kap)
            writeAllPass(AP1, -kap)
            readTail(AP2, kap)
            writeAllPass(AP2, -kap)
            readTail(AP3, kap)
            writeAllPass(AP3, -kap)
            readTail(AP4, kap)
            writeAllPass(AP4, -kap)
            val apout = acc

            // Main loop, first half.
            acc = apout + floor
            interpolate(DEL2, offDel2 + modDel2 * lfo2, krt)
            lp1 = lowPass(lp1)
            readTail(DAP1A, -kap)
            writeAllPass(DAP1A, kap)
            readTail(DAP1B, kap)
            writeAllPass(DAP1B, -kap)
            write(DEL1, 0, 2.0f)
            outL[k] = acc

            // Second half.
            acc = apout + floor
            readTail(DEL1, krt)
            lp2 = lowPass(lp2)
            readTail(DAP2A, kap)
            writeAllPass(DAP2A, -kap)
            readTail(DAP2B, -kap)
            writeAllPass(DAP2B, kap)
            write(DEL2, 0, 2.0f)
            outR[k] = acc
        }
    }

    private companion object {
        const val REF_RATE = 32000.0
        const val LP = 0.7
        const val LFO_1_HZ = 0.5
        const val LFO_2_HZ = 0.3

        /** The LFOs advance once every this many samples, as in the original. */
        const val LFO_PERIOD = 32
        const val LFO_PERIOD_MASK = LFO_PERIOD - 1
        const val DIFFUSION = 0.625f
        const val DEFAULT_INPUT_GAIN = 0.3f
        const val START_RT60 = 20.0
        const val MIN_RT60 = 0.1

        // krt = 10^(-1.5 L / T): -60 dB is three decades of amplitude, spread over the loop's two passes.
        const val DECADE = 10.0
        const val DECADES_PER_PASS = -1.5

        /** Smallest normal Float; anything nonzero below it is subnormal. */
        const val SMALLEST_NORMAL = 1.17549435e-38f

        // Delay lines in buffer order: four input allpasses, then each loop's two allpasses and its delay.
        const val AP1 = 0
        const val AP2 = 1
        const val AP3 = 2
        const val AP4 = 3
        const val DAP1A = 4
        const val DAP1B = 5
        const val DEL1 = 6
        const val DAP2A = 7
        const val DAP2B = 8
        const val DEL2 = 9
        val LENGTHS = intArrayOf(113, 162, 241, 399, 1653, 2038, 3411, 1913, 1663, 4782)

        // Offsets and modulation depths in samples at 32 kHz: the smeared first allpass and the second loop's tap.
        const val AP1_SMEAR_READ = 10.0f
        const val AP1_SMEAR_DEPTH = 60.0f
        const val AP1_SMEAR_WRITE = 100.0
        const val DEL2_TAP = 4680.0f
        const val DEL2_DEPTH = 100.0f

        /** Samples around one trip of the main loop at the original rate, over 32 kHz. */
        const val LOOP_SECONDS = (4680 + 1653 + 2038 + 3411 + 1913 + 1663) / 32000.0
    }
}
