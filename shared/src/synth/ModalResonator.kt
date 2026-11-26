package org.meshtastic.ambient.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Bank of band-pass state variable filters after the Plaits/Rings resonator (Emilie Gillet, MIT): partial frequencies
 * stretched upward by a stiffness term (the fundamental stays at f0), Q falling with partial number. The original reads stiffness and the strike position from
 * tables; here stiffness is an analytic curve of [configure]'s `structure` and the position weights are a string's
 * `sin(n pi x)`.
 */
internal class ModalResonator(private val sampleRate: Int, private val maxModes: Int = 24) {
    private val g = FloatArray(maxModes)
    private val rPlusG = FloatArray(maxModes)
    private val h = FloatArray(maxModes)
    private val s1 = FloatArray(maxModes)
    private val s2 = FloatArray(maxModes)
    private val gain = FloatArray(maxModes)
    private var count = 0
    private var floor = DENORMAL_FLOOR

    fun reset() {
        s1.fill(0f)
        s2.fill(0f)
    }

    /** Fills the partial list; [ring60Sec] is how long the fundamental takes to fall 60 dB. */
    fun configure(f0Hz: Double, structure: Float, brightness0: Float, ring60Sec: Float, position: Float, resolution: Int) {
        reset()
        count = min(resolution, maxModes)
        var stiffness = (-0.05f + 0.95f * structure * structure)
        val f0Norm = f0Hz / sampleRate
        val f0 = f0Norm

        // Time constant of a band-pass state variable filter is about Q / (pi f); pick Q for the fundamental.
        val tau = ring60Sec / 6.9
        val q0 = clamp(tau * PI * f0Hz, 1.0, 60000.0)
        var q = (q0 - 1.0) / f0Norm
        val brightness = brightness0 * (1.0f - structure * 0.3f)
        val qLoss = brightness * (2.0f - brightness) * 0.85f + 0.15f

        var harmonic = f0
        var stretchFactor = 1.0f
        for (i in 0 until count) {
            var modeFrequency = harmonic * stretchFactor
            if (modeFrequency >= 0.499) modeFrequency = 0.499
            val attenuation = 1.0 - modeFrequency * 2.0
            val gTan = tan(PI * modeFrequency).toFloat()
            val r = 1.0f / (1.0 + modeFrequency * q).toFloat()
            g[i] = gTan
            rPlusG[i] = r + gTan
            h[i] = 1.0f / (1.0f + r * gTan + gTan * gTan)
            gain[i] = (abs(sin((i + 1) * PI * position)) * 0.25 * attenuation).toFloat()
            stretchFactor += stiffness
            stiffness *= if (stiffness < 0f) 0.93f else 0.98f
            harmonic += f0
            q *= qLoss
        }
        for (i in count until maxModes) gain[i] = 0f
    }

    fun process(input: Float): Float {
        floor = -floor
        val x = input + floor
        var out = 0f
        for (i in 0 until count) {
            val hp = (x - rPlusG[i] * s1[i] - s2[i]) * h[i]
            val bp = g[i] * hp + s1[i]
            s1[i] = g[i] * hp + bp
            val lp = g[i] * bp + s2[i]
            s2[i] = g[i] * bp + lp
            out += gain[i] * bp
        }
        return out
    }
}
