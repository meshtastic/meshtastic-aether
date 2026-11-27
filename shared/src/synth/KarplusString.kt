package org.meshtastic.aether.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.tan

/** Lowest pitch the string line holds: one period of it is the line's length. */
private const val LOWEST_HZ = 20

/** Spare samples past the line's longest read. */
private const val LINE_GUARD = 8

/** The dispersion allpass line is a quarter of the string's. */
private const val STRETCH_LINE_DIVISOR = 4
private const val DC_BLOCKER_HZ = 20.0f
private const val START_DELAY = 100f

/** Shortest delay the four-point Hermite read and the allpass accept. */
private const val MIN_DELAY = 4.0f

/** Samples kept between the longest delay and the line's end, for the Hermite read's look-ahead. */
private const val HERMITE_MARGIN = 4

// Damping cutoff, in semitones above f0: a floor, the damping's and the brightness's share, and a ceiling.
private const val CUTOFF_FLOOR_ST = 12.0f
private const val CUTOFF_DAMPING_ST = 60.0f
private const val CUTOFF_BRIGHTNESS_ST = 24.0f
private const val CUTOFF_CEILING_ST = 84.0f
private const val MAX_DAMPING_F = 0.499
private const val DAMPING_Q = 0.5f

/** Above this damping the string glides to ringing for ever, over the remaining range scaled to 0..1. */
private const val ENDLESS_FROM = 0.95f
private const val ENDLESS_RAMP = 20.0f
private const val ENDLESS_DAMPING_F = 0.4999f

/** The damping filter's phase lag is taken off the delay, but never more than half of it. */
private const val MIN_LAG_CORRECTION = 0.5f

// Dispersion: where along the delay the allpass sits, and the correction for its group delay.
private const val STRETCH_POINT_SCALE = 0.225f
private const val STRETCH_CORRECTION_HZ = 160.0f
private const val MAX_STRETCH_CORRECTION = 2.1f
private const val AP_GROUP_DELAY = 0.408f
private const val AP_GROUP_DELAY_SLOPE = 0.308f
private const val AP_GAIN = -0.618f
private const val AP_GAIN_KNEE = 0.15f

/** Dispersion turns to noisy pitch jitter over the top quarter of the non-linearity, scaled to 0..1. */
private const val NOISE_FROM = 0.75f
private const val NOISE_RAMP = 4.0f
private const val NOISE_DEPTH = 0.1f
private const val NOISE_GAIN = 0.5f

// The jitter's one-pole smoothing coefficient, from the brightness.
private const val NOISE_FILTER_FLOOR = 0.06f
private const val NOISE_FILTER_RANGE = 0.94f

// Curved bridge: how far it shortens the delay, the level it starts at, and the asymmetry of its two sides.
private const val BRIDGE_CURVING_SCALE = 0.01f
private const val BRIDGE_THRESHOLD = 0.025f
private const val BRIDGE_NEGATIVE_SIDE = -1.5f

/** The loop's sample clamp, against runaway feedback. */
private const val LOOP_LIMIT = 20.0f

/**
 * Port of the Plaits string (Emilie Gillet, MIT): a Karplus-Strong loop with a damping state variable filter, a DC
 * blocker, and either a curved-bridge or a dispersion non-linearity. Parameters are fixed per note by [configure].
 */
internal class KarplusString(private val sampleRate: Int, private val rng: Rng) {
    private val lineSize = sampleRate / LOWEST_HZ + LINE_GUARD
    private val string = DelayLine(lineSize)
    private val stretch = DelayLine(lineSize / STRETCH_LINE_DIVISOR + LINE_GUARD)
    private val dampingFilter = Svf()
    private val dc = DcBlocker(1.0f - DC_BLOCKER_HZ / sampleRate)

    private var delay = START_DELAY
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

    /**
     * [nonLinearity] in -1..1: negative curves the bridge, positive adds dispersion. [damping] 0..1, 1 rings for ever.
     */
    fun configure(f0Hz: Double, nonLinearity: Float, brightness0: Float, damping: Float) {
        reset()
        var brightness = brightness0
        val f0 = f0Hz / sampleRate
        val maxDelay = (lineSize - HERMITE_MARGIN).toFloat()
        delay = clamp((1.0 / f0).toFloat(), MIN_DELAY, maxDelay)
        useDispersion = nonLinearity > 0f
        val nl = abs(nonLinearity)

        val dampingCutoff = min(
            CUTOFF_FLOOR_ST + damping * damping * CUTOFF_DAMPING_ST + brightness * CUTOFF_BRIGHTNESS_ST,
            CUTOFF_CEILING_ST,
        )
        var dampingF = min(f0 * semitonesToRatio(dampingCutoff.toDouble()), MAX_DAMPING_F).toFloat()
        if (damping >= ENDLESS_FROM) {
            val toInfinite = ENDLESS_RAMP * (damping - ENDLESS_FROM)
            brightness += toInfinite * (1.0f - brightness)
            dampingF += toInfinite * (ENDLESS_DAMPING_F - dampingF)
        }
        dampingFilter.setFq(dampingF, DAMPING_Q)

        // Delay lost to the damping filter's phase at f0 (the original reads a table for this), as a fraction of a
        // period.
        val w = tan(PI * f0) / tan(PI * dampingF.toDouble())
        val lag = atan2(2.0 * w, 1.0 - w * w) / (2.0 * PI)
        delay *= clamp((1.0 - lag).toFloat(), MIN_LAG_CORRECTION, 1.0f)

        stretchPoint = nl * (2.0f - nl) * STRETCH_POINT_SCALE
        stretchCorrection = clamp((STRETCH_CORRECTION_HZ / sampleRate) * delay, 1.0f, MAX_STRETCH_CORRECTION)
        val noiseAmountSqrt = if (nl > NOISE_FROM) NOISE_RAMP * (nl - NOISE_FROM) else 0.0f
        noiseAmount = noiseAmountSqrt * noiseAmountSqrt * NOISE_DEPTH
        noiseFilter = NOISE_FILTER_FLOOR + NOISE_FILTER_RANGE * brightness * brightness
        bridgeCurving = nl * nl * BRIDGE_CURVING_SCALE
        apGain = AP_GAIN * nl / (AP_GAIN_KNEE + abs(nl))
    }

    fun process(input: Float): Float {
        var d = delay
        val s0: Float
        if (useDispersion) {
            val noise = rng.nextSigned() * NOISE_GAIN
            dispersionNoise += noiseFilter * (noise - dispersionNoise)
            d *= 1.0f + dispersionNoise * noiseAmount
            val apDelay = d * stretchPoint
            val mainDelay = d - apDelay * (AP_GROUP_DELAY - stretchPoint * AP_GROUP_DELAY_SLOPE) * stretchCorrection
            s0 = if (apDelay >= MIN_DELAY && mainDelay >= MIN_DELAY) {
                stretch.allpass(string.read(mainDelay), apDelay, apGain)
            } else {
                string.readHermite(d)
            }
        } else {
            d *= 1.0f - curvedBridge * bridgeCurving
            s0 = string.readHermite(d)
            val value = abs(s0) - BRIDGE_THRESHOLD
            val sign = if (s0 > 0.0f) 1.0f else BRIDGE_NEGATIVE_SIDE
            curvedBridge = (abs(value) + value) * sign
        }
        var s = s0 + input
        s = clamp(s, -LOOP_LIMIT, LOOP_LIMIT)
        s = dc.process(s)
        s = dampingFilter.lowPass(s)
        string.write(s)
        return s
    }
}
