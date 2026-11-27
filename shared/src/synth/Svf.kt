package org.meshtastic.aether.synth

import kotlin.math.PI
import kotlin.math.tan

/** Zero-delay-feedback state variable filter after stmlib `Svf`. Frequencies are normalized (Hz / sampleRate). */
internal class Svf {
    private var g = 0f
    private var r = 1f
    private var h = 1f
    private var s1 = 0f
    private var s2 = 0f
    private var floor = DENORMAL_FLOOR

    fun reset() {
        s1 = 0f
        s2 = 0f
    }

    fun setFq(f: Float, q: Float) {
        g = tan(PI * clamp(f, MIN_F, MAX_F)).toFloat()
        r = 1.0f / q
        h = 1.0f / (1.0f + r * g + g * g)
    }

    fun lowPass(input: Float): Float = step(input, MODE_LP)

    fun bandPass(input: Float): Float = step(input, MODE_BP)

    fun highPass(input: Float): Float = step(input, MODE_HP)

    private fun step(input: Float, mode: Int): Float {
        floor = -floor
        val x = input + floor
        val hp = (x - r * s1 - g * s1 - s2) * h
        val bp = g * hp + s1
        s1 = g * hp + bp
        val lp = g * bp + s2
        s2 = g * bp + lp
        return when (mode) {
            MODE_LP -> lp
            MODE_BP -> bp
            else -> hp
        }
    }

    private companion object {
        const val MODE_LP = 0
        const val MODE_BP = 1
        const val MODE_HP = 2

        /** Cutoff range, kept short of Nyquist where the prewarp `tan` runs off. */
        const val MIN_F = 1.0e-5f
        const val MAX_F = 0.497f
    }
}

/** One-pole DC blocker after stmlib `DCBlocker`. */
internal class DcBlocker(private val pole: Float) {
    private var x = 0f
    private var y = 0f

    fun reset() {
        x = 0f
        y = 0f
    }

    fun process(input: Float): Float {
        val oldX = x
        x = input
        y = y * pole + x - oldX
        return y
    }
}
