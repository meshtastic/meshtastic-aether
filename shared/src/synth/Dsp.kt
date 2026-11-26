package org.meshtastic.ambient.synth

import kotlin.math.PI
import kotlin.math.pow

/** Amplitude injected into every feedback path so decaying state never reaches the denormal range. */
internal const val DENORMAL_FLOOR = 1.0e-18f

internal const val TWO_PI = 2.0 * PI

internal fun semitonesToRatio(semitones: Double): Double = 2.0.pow(semitones / 12.0)

internal fun clamp(x: Float, lo: Float, hi: Float): Float = if (x < lo) lo else if (x > hi) hi else x

internal fun clamp(x: Double, lo: Double, hi: Double): Double = if (x < lo) lo else if (x > hi) hi else x

/** xorshift32 white noise; no allocation, deterministic per seed. */
internal class Rng(seed: Int) {
    private var s: Int = if (seed == 0) 0x2545F491 else seed

    /** Uniform in [-1, 1). */
    fun nextSigned(): Float {
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        s = s xor (s shl 5)
        return s * (1.0f / 2147483648.0f)
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
        val c = (x1 - xm1) * 0.5f
        val v = x0 - x1
        val w = c + v
        val a = w + v + (x2 - x0) * 0.5f
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
