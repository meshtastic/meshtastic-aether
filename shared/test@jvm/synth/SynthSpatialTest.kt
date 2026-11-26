package org.meshtastic.aether.synth

import org.meshtastic.aether.music.Voice
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SynthSpatialTest {
    private val rate = TEST_RATE

    private fun maxStep(x: FloatArray, channel: Int, fromFrame: Int, toFrame: Int): Float {
        var m = 0f
        for (i in fromFrame + 1 until toFrame) m = maxOf(m, abs(x[i * 2 + channel] - x[(i - 1) * 2 + channel]))
        return m
    }

    @Test
    fun `a centred note is the same in headphones as in stereo once the modes have crossed`() {
        fun render(headphones: Boolean): FloatArray {
            val synth = Synth(rate).also { it.headphones = headphones }
            synth.schedule(notes(note(Voice.Bowed, hz = 220.0, seconds = 9.0, velocity = 0.5f, pan = 0f)))
            return synth.renderAll(7 * rate)
        }
        val stereo = render(false)
        val headphones = render(true)
        var worst = 0f
        for (i in 5 * rate * 2 until 7 * rate * 2) worst = maxOf(worst, abs(stereo[i] - headphones[i]))
        assertTrue(worst < 1e-4f, "a centred voice differs by $worst between the modes")
    }

    // Regression: a mode flag that reaches nothing leaves every note exactly as the stereo pan made it.
    @Test
    fun `a note panned east sounds different in headphones than in stereo`() {
        fun render(headphones: Boolean): FloatArray {
            val synth = Synth(rate).also { it.headphones = headphones }
            synth.schedule(notes(note(Voice.Bowed, hz = 330.0, seconds = 9.0, velocity = 0.5f, pan = 0.7f)))
            return synth.renderAll(7 * rate)
        }
        val stereo = render(false)
        val headphones = render(true)
        var diff = 0f
        for (i in 5 * rate * 2 until 7 * rate * 2) diff = maxOf(diff, abs(stereo[i] - headphones[i]))
        assertTrue(diff > 0.01f, "the two modes differ by only $diff")
    }

    // Regression: switching without the 0.3 s crossfade steps the far ear by a delay's worth of signal, a click. The step
    // depends on where in the wave the switch lands, so the switch is tried at many points, on a slow sine whose ordinary steps are tiny.
    @Test
    fun `switching to headphones under a panned note adds no click wherever it lands in the wave`() {
        fun render(switchAt: Int?): FloatArray {
            val synth = Synth(rate)
            synth.schedule(notes(note(Voice.Sub, hz = 55.0, seconds = 9.0, velocity = 0.5f, pan = 0.7f)))
            val out = FloatArray(6 * rate * 2)
            val buf = FloatArray(64 * 2)
            var done = 0
            while (done < 6 * rate) {
                if (switchAt != null && done >= switchAt) synth.headphones = true
                synth.render(buf, 64)
                buf.copyInto(out, done * 2)
                done += 64
            }
            return out
        }
        val steady = render(null)
        val baseline = (0..1).maxOf { maxStep(steady, it, 2 * rate, 6 * rate) }
        var worst = 0f
        for (k in 0 until 8) {
            val at = 3 * rate + 64 * k
            val switched = render(at)
            for (ch in 0..1) worst = maxOf(worst, maxStep(switched, ch, at - 1, at + rate))
        }
        assertTrue(worst < 2.5f * baseline + 1e-3f, "the worst step was $worst against a steady $baseline")
    }

    @Test
    fun `every voice at hard pans in headphones stays finite and under the limiter ceiling`() {
        val synth = Synth(rate).also { it.headphones = true }
        val list = ArrayList<org.meshtastic.aether.music.NoteEvent>()
        var t = 0L
        var k = 0
        while (t < 14L * rate) {
            for (v in Voice.entries) {
                list += org.meshtastic.aether.music.NoteEvent(t, v, 110.0 * (1 + (k % 7)), 1f, if (k % 2 == 0) -0.7f else 0.7f, 0.5f, 3L * rate, 1f)
                k++
            }
            t += rate / 2
        }
        synth.schedule(org.meshtastic.aether.music.ComposerOutput(list, emptyList()))
        val out = synth.renderAll(16 * rate, 1000)
        assertTrue(out.all { it == it && abs(it) <= 1f }, "non-finite or over full scale")
    }
}
