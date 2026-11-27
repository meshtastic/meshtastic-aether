package org.meshtastic.aether.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * What an instrument's struck body is made of, fixed per instrument: [structure] 0..1 stretches the partials (0 a
 * slightly compressed series, 1 strongly stretched), [position] 0..1 is where along the body it is struck, and [modes]
 * is how many partials ring.
 */
internal class ModalBody(val structure: Float, val position: Float, val modes: Int)

// Stiffness, the stretch added per partial, is this curve of the structure: slightly negative at 0, 0.9 at 1.
private const val STIFFNESS_AT_ZERO = -0.05f
private const val STIFFNESS_RANGE = 0.95f

/** The stiffness itself shrinks with each partial, faster when it compresses than when it stretches. */
private const val COMPRESSION_DECAY = 0.93f
private const val STRETCH_DECAY = 0.98f

/** Time constants in a 60 dB fall: ln(1000). */
private const val TIME_CONSTANTS_PER_60_DB = 6.9
private const val MAX_Q = 60000.0

/** A stiffer body sounds darker: at full structure the brightness loses this share. */
private const val STRUCTURE_DARKENING = 0.3f

// Q kept per partial, from the brightness: 0.15 when dark, 1 when bright.
private const val Q_LOSS_RANGE = 0.85f
private const val Q_LOSS_FLOOR = 0.15f

/** Highest partial, just under Nyquist. */
private const val MAX_MODE_FREQUENCY = 0.499
private const val MODE_GAIN = 0.25

/**
 * Bank of band-pass state variable filters after the Plaits/Rings resonator (Emilie Gillet, MIT): partial frequencies
 * stretched upward by a stiffness term (the fundamental stays at f0), Q falling with partial number. The original reads
 * stiffness and the strike position from tables; here stiffness is an analytic curve of the body's `structure` and the
 * position weights are a string's `sin(n pi x)`.
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

    /** Fills the partial list for [body] at [f0Hz]; [ring60Sec] is how long the fundamental takes to fall 60 dB. */
    fun configure(f0Hz: Double, body: ModalBody, brightness: Float, ring60Sec: Float) {
        reset()
        val structure = body.structure
        count = min(body.modes, maxModes)
        var stiffness = (STIFFNESS_AT_ZERO + STIFFNESS_RANGE * structure * structure)
        val f0Norm = f0Hz / sampleRate
        val f0 = f0Norm

        // Time constant of a band-pass state variable filter is about Q / (pi f); pick Q for the fundamental.
        val tau = ring60Sec / TIME_CONSTANTS_PER_60_DB
        val q0 = clamp(tau * PI * f0Hz, 1.0, MAX_Q)
        var q = (q0 - 1.0) / f0Norm
        val darkened = brightness * (1.0f - structure * STRUCTURE_DARKENING)
        val qLoss = darkened * (2.0f - darkened) * Q_LOSS_RANGE + Q_LOSS_FLOOR

        var harmonic = f0
        var stretchFactor = 1.0f
        for (i in 0 until count) {
            var modeFrequency = harmonic * stretchFactor
            if (modeFrequency >= MAX_MODE_FREQUENCY) modeFrequency = MAX_MODE_FREQUENCY
            val attenuation = 1.0 - modeFrequency * 2.0
            val gTan = tan(PI * modeFrequency).toFloat()
            val r = 1.0f / (1.0 + modeFrequency * q).toFloat()
            g[i] = gTan
            rPlusG[i] = r + gTan
            h[i] = 1.0f / (1.0f + r * gTan + gTan * gTan)
            gain[i] = (abs(sin((i + 1) * PI * body.position)) * MODE_GAIN * attenuation).toFloat()
            stretchFactor += stiffness
            stiffness *= if (stiffness < 0f) COMPRESSION_DECAY else STRETCH_DECAY
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
