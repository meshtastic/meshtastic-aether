package org.meshtastic.ambient.synth

/**
 * Port of the Clouds pitch shifter (Emilie Gillet, MIT): two read taps sweeping a delay line half a window apart,
 * crossfaded by a triangle, one line per channel.
 */
internal class PitchShifter(sampleRate: Int) {
    private val windowSize = (WINDOW_AT_32K * sampleRate / 32000.0).toFloat()
    private val lineL = DelayLine(windowSize.toInt() + 8)
    private val lineR = DelayLine(windowSize.toInt() + 8)
    private var phase = 0f

    var ratio = 2.0f

    fun clear() {
        lineL.clear()
        lineR.clear()
        phase = 0f
    }

    /** Shifts one stereo frame; results land in [out] (index 0 left, 1 right). */
    fun process(l: Float, r: Float, out: FloatArray) {
        phase += (1.0f - ratio) / windowSize
        if (phase >= 1.0f) phase -= 1.0f
        if (phase <= 0.0f) phase += 1.0f
        val tri = 2.0f * (if (phase >= 0.5f) 1.0f - phase else phase)
        val p = phase * windowSize
        var half = p + windowSize * 0.5f
        if (half >= windowSize) half -= windowSize
        lineL.write(l + DENORMAL_FLOOR)
        lineR.write(r + DENORMAL_FLOOR)
        out[0] = lineL.read(p + 1.0f) * tri + lineL.read(half + 1.0f) * (1.0f - tri)
        out[1] = lineR.read(p + 1.0f) * tri + lineR.read(half + 1.0f) * (1.0f - tri)
    }

    private companion object {
        const val WINDOW_AT_32K = 1024.0
    }
}
