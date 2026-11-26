package org.meshtastic.aether.synth

import kotlin.math.sin

private fun thisBlepSample(t: Double): Double = 0.5 * t * t

private fun nextBlepSample(t0: Double): Double {
    val t = 1.0 - t0
    return -0.5 * t * t
}

/** PolyBLEP saw and square after Plaits `Oscillator` and stmlib `polyblep.h`. Phase is a Double; frequencies are normalized. */
internal class PolyBlepOsc {
    private var phase = 0.0
    private var nextSample = 0.0
    private var high = true

    fun reset(startPhase: Double) {
        phase = startPhase
        nextSample = 0.0
        high = true
    }

    fun saw(frequency: Double): Float {
        val f = clamp(frequency, MIN_F, MAX_F)
        var thisSample = nextSample
        nextSample = 0.0
        phase += f
        if (phase >= 1.0) {
            phase -= 1.0
            val t = phase / f
            thisSample -= thisBlepSample(t)
            nextSample -= nextBlepSample(t)
        }
        nextSample += phase
        return (2.0 * thisSample - 1.0).toFloat()
    }

    fun square(frequency: Double, pulseWidth: Double): Float {
        val f = clamp(frequency, MIN_F, MAX_F)
        val pw = clamp(pulseWidth, f * 2.0, 1.0 - 2.0 * f)
        var thisSample = nextSample
        nextSample = 0.0
        phase += f
        if (high xor (phase >= pw)) {
            val t = (phase - pw) / f
            thisSample += thisBlepSample(t)
            nextSample += nextBlepSample(t)
            high = phase >= pw
        }
        if (phase >= 1.0) {
            phase -= 1.0
            val t = phase / f
            thisSample -= thisBlepSample(t)
            nextSample -= nextBlepSample(t)
            high = false
        }
        nextSample += if (phase < pw) 0.0 else 1.0
        return (2.0 * thisSample - 1.0).toFloat()
    }

    private companion object {
        const val MIN_F = 0.000001
        const val MAX_F = 0.25
    }
}

/** Sine oscillator with a Double phase. */
internal class SineOsc {
    private var phase = 0.0

    fun reset(startPhase: Double) {
        phase = startPhase
    }

    fun next(frequency: Double): Float {
        phase += frequency
        if (phase >= 1.0) phase -= 1.0
        return sin(TWO_PI * phase).toFloat()
    }

    /** Phase-modulated read for FM; [modulation] is in radians. */
    fun nextPm(frequency: Double, modulation: Double): Float {
        phase += frequency
        if (phase >= 1.0) phase -= 1.0
        return sin(TWO_PI * phase + modulation).toFloat()
    }
}

/** Two-operator FM: a sine carrier phase-modulated by a sine at `ratio` times the carrier. */
internal class FmPair {
    private val carrier = SineOsc()
    private val modulator = SineOsc()

    fun reset() {
        carrier.reset(0.0)
        modulator.reset(0.0)
    }

    fun next(frequency: Double, ratio: Double, index: Float): Float {
        val m = modulator.next(frequency * ratio)
        return carrier.nextPm(frequency, index * m.toDouble())
    }
}
