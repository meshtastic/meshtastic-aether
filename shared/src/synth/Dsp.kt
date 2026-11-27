package org.meshtastic.aether.synth

import kotlin.math.PI
import kotlin.math.pow

/** Amplitude injected into every feedback path so decaying state never reaches the denormal range. */
internal const val DENORMAL_FLOOR = 1.0e-18f

internal const val TWO_PI = 2.0 * PI

private const val SEMITONES_PER_OCTAVE = 12.0

internal fun semitonesToRatio(semitones: Double): Double = 2.0.pow(semitones / SEMITONES_PER_OCTAVE)

internal fun clamp(x: Float, lo: Float, hi: Float): Float = if (x < lo) lo else if (x > hi) hi else x

internal fun clamp(x: Double, lo: Double, hi: Double): Double = if (x < lo) lo else if (x > hi) hi else x

/** xorshift32 white noise; no allocation, deterministic per seed. */
internal class Rng(seed: Int) {
    private var s: Int = if (seed == 0) ZERO_SEED_STAND_IN else seed

    /** Uniform in [-1, 1). */
    fun nextSigned(): Float {
        s = s xor (s shl SHIFT_A)
        s = s xor (s ushr SHIFT_B)
        s = s xor (s shl SHIFT_C)
        return s * INT_TO_UNIT
    }

    private companion object {
        /** xorshift never leaves the all-zero state, so a zero seed is swapped for this one. */
        const val ZERO_SEED_STAND_IN = 0x2545F491

        // Marsaglia's 13, 17, 5 triple for a full-period 32-bit xorshift.
        const val SHIFT_A = 13
        const val SHIFT_B = 17
        const val SHIFT_C = 5

        /** Scales an Int to [-1, 1): one over 2^31. */
        const val INT_TO_UNIT = 1.0f / 2147483648.0f
    }
}

/** Circular delay line after stmlib's `DelayLine`: `read(1)` is the sample just written. */
internal class DelayLine(private val size: Int) {
    private val line = FloatArray(size)
    private var wp = 0

    fun clear() {
        line.fill(0f)
    }

    fun write(x: Float) {
        line[wp] = x
        wp = if (wp == 0) size - 1 else wp - 1
    }

    private fun at(i: Int): Float = line[(wp + i) % size]

    fun read(delay: Float): Float {
        val di = delay.toInt()
        val frac = delay - di
        val a = at(di)
        val b = at(di + 1)
        return a + (b - a) * frac
    }

    /** Four-point Hermite read; [delay] must be at least 1. */
    fun readHermite(delay: Float): Float {
        val di = delay.toInt()
        val f = delay - di
        val xm1 = at(di - 1)
        val x0 = at(di)
        val x1 = at(di + 1)
        val x2 = at(di + 2)
        val c = (x1 - xm1) / 2
        val v = x0 - x1
        val w = c + v
        val a = w + v + (x2 - x0) / 2
        val bNeg = w + a
        return (((a * f) - bNeg) * f + c) * f + x0
    }

    /** Allpass in the delay line, after stmlib `Allpass`. */
    fun allpass(input: Float, delay: Float, gain: Float): Float {
        val r = read(delay)
        val w = input + r * gain
        write(w)
        return -w * gain + r
    }
}
