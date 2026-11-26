package org.meshtastic.ambient.synth

/**
 * Port of the Clouds pitch shifter (Emilie Gillet, MIT): two read taps sweeping a delay line half a window apart,
 * crossfaded by a triangle, one line per channel.
 */
internal class PitchShifter(sampleRate: Int) {
    private val windowSize = WINDOW_AT_32K * sampleRate / 32000.0
    private val lineL = DelayLine(windowSize.toInt() + 8)
    private val lineR = DelayLine(windowSize.toInt() + 8)
    /** Sweep position in 0..1; a Double so hours of accumulation keep the pitch exact. */
    internal var phase = 0.0
        private set

    var ratio = 2.0f

    fun clear() {
        lineL.clear()
        lineR.clear()
        phase = 0.0
    }

    /** Shifts one stereo frame; results land in [out] (index 0 left, 1 right). */
    fun process(l: Float, r: Float, out: FloatArray) {
        phase += (1.0 - ratio.toDouble()) / windowSize
        if (phase >= 1.0) phase -= 1.0
        if (phase <= 0.0) phase += 1.0
        val tri = (2.0 * (if (phase >= 0.5) 1.0 - phase else phase)).toFloat()
        val p = (phase * windowSize).toFloat()
        var half = p + (windowSize * 0.5).toFloat()
        if (half >= windowSize) half -= windowSize.toFloat()
        lineL.write(l + DENORMAL_FLOOR)
        lineR.write(r + DENORMAL_FLOOR)
        out[0] = lineL.read(p + 1.0f) * tri + lineL.read(half + 1.0f) * (1.0f - tri)
        out[1] = lineR.read(p + 1.0f) * tri + lineR.read(half + 1.0f) * (1.0f - tri)
    }

    private companion object {
        const val WINDOW_AT_32K = 1024.0
    }
}
