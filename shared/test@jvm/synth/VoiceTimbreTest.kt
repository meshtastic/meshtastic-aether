package org.meshtastic.aether.synth

import org.meshtastic.aether.music.NoteEvent
import org.meshtastic.aether.music.Voice
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The palette voices added to the synth. Pitch is the strongest component near the requested frequency; decay is the
 * fall of that component, which for the modal voices is set by the note's ring time (ring60: seconds to fall 60 dB, so
 * 60 * dt / ring60 dB over dt) and for Glass by its envelope (decay time to fall 60 dB).
 */
class VoiceTimbreTest {
    private val rate = TEST_RATE
    private val newVoices = listOf(Voice.Marimba, Voice.Vibes, Voice.Kalimba, Voice.Glass, Voice.Harp, Voice.Gong, Voice.Bowed, Voice.Choir, Voice.Sub)

    private fun render(v: Voice, hz: Double, seconds: Double = 2.0, brightness: Float = 0.5f, seconds4: Int = 4): FloatArray {
        val s = Synth(rate)
        s.schedule(controls(0, texture(wet = 0f)))
        s.schedule(notes(note(v, 0, hz, 0.8f, seconds, brightness = brightness)))
        return mono(s.renderAll(seconds4 * rate))
    }

    private fun at(t: Double) = (t * rate).toInt()

    private fun db(m: FloatArray, from: Double, to: Double, hz: Double): Double = 20 * log10(goertzel(m, at(from), at(to), hz, rate) + 1e-12)

    private fun strongestHz(m: FloatArray, from: Double, to: Double, lo: Double, hi: Double): Double {
        var best = 0.0
        var bestHz = lo
        var f = lo
        while (f <= hi) {
            val g = goertzel(m, at(from), at(to), f, rate)
            if (g > best) {
                best = g
                bestHz = f
            }
            f *= 1.002
        }
        return bestHz
    }

    // Regression: a voice built on a wrong ratio, an octave error or a broken tuning put every note of that family off pitch.
    @Test
    fun `every new voice sounds its requested pitch as the strongest partial within half a percent`() {
        for (v in newVoices) for (hz in doubleArrayOf(165.0, 330.0, 660.0)) {
            val m = render(v, hz)
            val sustained = v == Voice.Bowed || v == Voice.Choir || v == Voice.Sub
            val found = if (sustained) strongestHz(m, 0.8, 1.8, hz * 0.7, hz * 1.45) else strongestHz(m, 0.1, 0.5, hz * 0.7, hz * 1.45)
            assertTrue(abs(found / hz - 1.0) < 0.005, "$v asked $hz sounded $found")
        }
    }

    @Test
    fun `the sub voice carries no energy an octave under its pitch`() {
        val m = render(Voice.Sub, 330.0)
        assertTrue(goertzel(m, at(0.5), at(1.5), 165.0, rate) < goertzel(m, at(0.5), at(1.5), 330.0, rate) * 0.02)
    }

    // ring60 for a 2 s note: Marimba 2 * 0.5 = 1 s, Vibes 2 * 1.6 = 3.2 s, Kalimba 2 * 0.8 = 1.6 s, Gong 2 * 3 = 6 s.
    // The fundamental falls 60 * 0.4 / ring60 dB between the windows 0.3-0.7 s and 0.7-1.1 s: 24, 7.5, 15 and 4 dB.
    @Test
    fun `the modal voices fall at the rate their ring time sets`() {
        val expected = mapOf(Voice.Marimba to 24.0, Voice.Vibes to 7.5, Voice.Kalimba to 15.0, Voice.Gong to 4.0)
        for ((v, drop) in expected) {
            val m = render(v, 330.0)
            val measured = db(m, 0.3, 0.7, 330.0) - db(m, 0.7, 1.1, 330.0)
            assertEquals(drop, measured, drop * 0.2 + 0.5, "$v fell $measured dB, expected $drop")
        }
    }

    // Regression: ring time must follow the note length, or a long note and a short one decay alike.
    @Test
    fun `a longer note rings longer on the modal voices`() {
        val short = render(Voice.Marimba, 330.0, seconds = 1.0)
        val long = render(Voice.Marimba, 330.0, seconds = 2.0)
        val shortDrop = db(short, 0.2, 0.4, 330.0) - db(short, 0.4, 0.6, 330.0)
        val longDrop = db(long, 0.2, 0.4, 330.0) - db(long, 0.4, 0.6, 330.0)
        assertEquals(2.0, shortDrop / longDrop, 0.4, "short $shortDrop long $longDrop")
    }

    // Glass decays its envelope in 2 * 1.1 = 2.2 s to fall 60 dB: 60 * 0.4 / 2.2 = 10.9 dB between the same windows.
    @Test
    fun `glass falls at the rate of its envelope decay time`() {
        val m = render(Voice.Glass, 330.0)
        val measured = db(m, 0.3, 0.7, 330.0) - db(m, 0.7, 1.1, 330.0)
        assertEquals(10.9, measured, 1.2)
    }

    // Regression: a harp that decays like the pluck is the same instrument twice.
    @Test
    fun `a harp string rings much longer than a pluck of the same length`() {
        val harp = render(Voice.Harp, 330.0)
        val pluck = render(Voice.Pluck, 330.0)
        val harpDrop = db(harp, 0.3, 0.7, 330.0) - db(harp, 1.4, 1.8, 330.0)
        val pluckDrop = db(pluck, 0.3, 0.7, 330.0) - db(pluck, 1.4, 1.8, 330.0)
        assertTrue(harpDrop > 3.0, "the harp does not decay: $harpDrop")
        assertTrue(harpDrop * 3 < pluckDrop, "harp fell $harpDrop dB and pluck $pluckDrop dB")
    }

    private fun rms(m: FloatArray, from: Double, to: Double): Double {
        var sum = 0.0
        for (i in at(from) until at(to)) sum += m[i].toDouble() * m[i]
        return kotlin.math.sqrt(sum / (at(to) - at(from)))
    }

    // Regression: a sustained voice whose envelope decays like a percussive one dies before the note ends.
    @Test
    fun `the bowed choir and sub voices hold their level through the note and die after it`() {
        for (v in listOf(Voice.Bowed, Voice.Choir, Voice.Sub)) {
            val m = render(v, 330.0, seconds = 3.0, seconds4 = 6)
            val early = rms(m, 1.0, 1.9)
            val late = rms(m, 1.9, 2.8)
            assertTrue(late > early * 0.5 && late < early * 2.0, "$v early $early late $late")
            val after = rms(m, 5.0, 6.0)
            assertTrue(after < early * 0.01, "$v still sounds after its release: $after")
        }
    }

    // Regression: a mesh Drone or Shimmer note rode the form's layer level and fell silent whenever the form rested.
    @Test
    fun `a mesh drone or shimmer note is audible while the form's layer level is zero and the bed's is not`() {
        for (v in listOf(Voice.Drone, Voice.Shimmer)) {
            fun peak(node: Long?): Float {
                val s = Synth(rate)
                s.schedule(controls(0, texture(wet = 0f, layers = mapOf(Voice.Drone to 0f, Voice.Shimmer to 0f))))
                s.schedule(
                    org.meshtastic.aether.music.ComposerOutput(
                        listOf(NoteEvent(40L * rate, v, 220.0, 0.8f, 0f, 0f, 2L * rate, 0.5f, node)),
                        emptyList(),
                    ),
                )
                return s.renderAll(44 * rate).peak(41 * rate, 42 * rate)
            }
            assertTrue(peak(7L) > 0.02f, "$v from a node is silent")
            assertTrue(peak(null) < 1.0e-4f, "$v from the bed ignored a zero layer level that had forty seconds to settle")
        }
    }
}
