package org.meshtastic.aether.synth

import kotlin.math.sin

/**
 * Port of the Plaits ensemble chorus (Emilie Gillet, MIT): three delay taps per channel, modulated by a slow 0.75 Hz
 * and a fast 6.57 Hz sine each, 120 degrees apart.
 */
internal class Ensemble(private val sampleRate: Int) {
    private val scale = sampleRate / REF_RATE
    private val lineL = DelayLine((LINE_AT_REF * scale).toInt() + LINE_GUARD)
    private val lineR = DelayLine((LINE_AT_REF * scale).toInt() + LINE_GUARD)
    private var phase1 = 0.0
    private var phase2 = 0.0

    var amount = 0f
    var depth = 0f

    fun clear() {
        lineL.clear()
        lineR.clear()
    }

    /** Processes [n] stereo samples in place. */
    fun process(left: FloatArray, right: FloatArray, n: Int) {
        val inc1 = SLOW_HZ / sampleRate
        val inc2 = FAST_HZ / sampleRate
        val dryAmount = 1.0f - amount * DRY_DUCK
        val a = (depth * SLOW_DEPTH * scale).toFloat()
        val b = (depth * FAST_DEPTH * scale).toFloat()
        val center = (CENTER_DELAY * scale).toFloat()
        for (i in 0 until n) {
            phase1 += inc1
            if (phase1 >= 1.0) phase1 -= 1.0
            phase2 += inc2
            if (phase2 >= 1.0) phase2 -= 1.0
            val slow0 = sin(TWO_PI * phase1).toFloat()
            val slow120 = sin(TWO_PI * (phase1 + ONE_THIRD)).toFloat()
            val slow240 = sin(TWO_PI * (phase1 + 2 * ONE_THIRD)).toFloat()
            val fast0 = sin(TWO_PI * phase2).toFloat()
            val fast120 = sin(TWO_PI * (phase2 + ONE_THIRD)).toFloat()
            val fast240 = sin(TWO_PI * (phase2 + 2 * ONE_THIRD)).toFloat()
            val mod1 = slow0 * a + fast0 * b
            val mod2 = slow120 * a + fast120 * b
            val mod3 = slow240 * a + fast240 * b

            lineL.write(left[i] + DENORMAL_FLOOR)
            lineR.write(right[i] + DENORMAL_FLOOR)

            val wetL = (lineL.read(mod1 + center) + lineL.read(mod2 + center) + lineR.read(mod3 + center)) * TAP_MIX
            val wetR = (lineR.read(mod1 + center) + lineR.read(mod2 + center) + lineL.read(mod3 + center)) * TAP_MIX
            left[i] = wetL * amount + left[i] * dryAmount
            right[i] = wetR * amount + right[i] * dryAmount
        }
    }

    private companion object {
        const val REF_RATE = 48000.0
        const val ONE_THIRD = 1.0 / 3.0

        // Plaits' values at its 48 kHz rate, in samples: line length, delay modulation depths and the taps' center.
        const val LINE_AT_REF = 511
        const val SLOW_DEPTH = 160.0f
        const val FAST_DEPTH = 16.0f
        const val CENTER_DELAY = 192.0

        /** Spare samples past the line's longest read. */
        const val LINE_GUARD = 8
        const val SLOW_HZ = 0.75
        const val FAST_HZ = 6.57

        /** How far the dry signal ducks at full [amount]. */
        const val DRY_DUCK = 0.5f

        /** Gain of each of the three summed taps. */
        const val TAP_MIX = 0.33f
    }
}
