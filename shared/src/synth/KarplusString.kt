package org.meshtastic.aether.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.tan

/**
 * Port of the Plaits string (Emilie Gillet, MIT): a Karplus-Strong loop with a damping state variable filter, a DC
 * blocker, and either a curved-bridge or a dispersion non-linearity. Parameters are fixed per note by [configure].
 */
internal class KarplusString(private val sampleRate: Int, private val rng: Rng) {
    private val lineSize = sampleRate / 20 + 8
    private val string = DelayLine(lineSize)
    private val stretch = DelayLine(lineSize / 4 + 8)
    private val dampingFilter = Svf()
    private val dc = DcBlocker(1.0f - 20.0f / sampleRate)

    private var delay = 100f
    private var dispersionNoise = 0f
    private var curvedBridge = 0f
    private var useDispersion = false
    private var stretchPoint = 0f
    private var stretchCorrection = 1f
    private var noiseAmount = 0f
    private var noiseFilter = 0f
    private var bridgeCurving = 0f
    private var apGain = 0f

    fun reset() {
        string.clear()
        stretch.clear()
        dampingFilter.reset()
        dc.reset()
        dispersionNoise = 0f
        curvedBridge = 0f
    }

    /** [nonLinearity] in -1..1: negative curves the bridge, positive adds dispersion. [damping] 0..1, 1 rings for ever. */
    fun configure(f0Hz: Double, nonLinearity: Float, brightness0: Float, damping: Float) {
        reset()
        var brightness = brightness0
        val f0 = f0Hz / sampleRate
        val maxDelay = (lineSize - 4).toFloat()
        delay = clamp((1.0 / f0).toFloat(), 4.0f, maxDelay)
        useDispersion = nonLinearity > 0f
        val nl = abs(nonLinearity)

        var dampingCutoff = min(12.0f + damping * damping * 60.0f + brightness * 24.0f, 84.0f)
        var dampingF = min(f0 * semitonesToRatio(dampingCutoff.toDouble()), 0.499).toFloat()
        if (damping >= 0.95f) {
            val toInfinite = 20.0f * (damping - 0.95f)
            brightness += toInfinite * (1.0f - brightness)
            dampingF += toInfinite * (0.4999f - dampingF)
            dampingCutoff += toInfinite * (128.0f - dampingCutoff)
        }
        dampingFilter.setFq(dampingF, 0.5f)

        // Delay lost to the damping filter's phase at f0 (the original reads a table for this), as a fraction of a period.
        val w = tan(PI * f0) / tan(PI * dampingF.toDouble())
        val lag = atan2(2.0 * w, 1.0 - w * w) / (2.0 * PI)
        delay *= clamp((1.0 - lag).toFloat(), 0.5f, 1.0f)

        stretchPoint = nl * (2.0f - nl) * 0.225f
        stretchCorrection = clamp((160.0f / sampleRate) * delay, 1.0f, 2.1f)
        val noiseAmountSqrt = if (nl > 0.75f) 4.0f * (nl - 0.75f) else 0.0f
        noiseAmount = noiseAmountSqrt * noiseAmountSqrt * 0.1f
        noiseFilter = 0.06f + 0.94f * brightness * brightness
        bridgeCurving = nl * nl * 0.01f
        apGain = -0.618f * nl / (0.15f + abs(nl))
    }

    fun process(input: Float): Float {
        var d = delay
        val s0: Float
        if (useDispersion) {
            val noise = rng.nextSigned() * 0.5f
            dispersionNoise += noiseFilter * (noise - dispersionNoise)
            d *= 1.0f + dispersionNoise * noiseAmount
            val apDelay = d * stretchPoint
            val mainDelay = d - apDelay * (0.408f - stretchPoint * 0.308f) * stretchCorrection
            s0 = if (apDelay >= 4.0f && mainDelay >= 4.0f) {
                stretch.allpass(string.read(mainDelay), apDelay, apGain)
            } else {
                string.readHermite(d)
            }
        } else {
            d *= 1.0f - curvedBridge * bridgeCurving
            s0 = string.readHermite(d)
            val value = abs(s0) - 0.025f
            val sign = if (s0 > 0.0f) 1.0f else -1.5f
            curvedBridge = (abs(value) + value) * sign
        }
        var s = s0 + input
        s = clamp(s, -20.0f, 20.0f)
        s = dc.process(s)
        s = dampingFilter.lowPass(s)
        string.write(s)
        return s
    }
}
