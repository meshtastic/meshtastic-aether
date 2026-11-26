package org.meshtastic.aether.synth

import org.meshtastic.aether.music.ComposerOutput
import org.meshtastic.aether.music.NoteEvent
import org.meshtastic.aether.music.Voice
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthTest {
    private val rate = TEST_RATE

    private fun allFinite(x: FloatArray): Boolean = x.all { it == it && abs(it) < Float.POSITIVE_INFINITY }

    // Regression: an unstable filter or feedback path at an extreme pitch or setting turns the output to NaN for good.
    @Test
    fun `sixty seconds of every voice at extreme pitches and textures stays finite and bounded`() {
        val synth = Synth(rate)
        val extremes = listOf(
            texture(brightness = 0f, size = 0f, wet = 0f, detune = 0f, shimmer = 0f, noise = 0f),
            texture(brightness = 1f, size = 1f, wet = 1f, detune = 1f, shimmer = 1f, noise = 1f),
        )
        val pitches = doubleArrayOf(16.0, 55.0, 440.0, 4000.0, 14000.0, 40000.0)
        val list = ArrayList<NoteEvent>()
        var t = 0L
        var k = 0
        while (t < 58L * rate) {
            for (v in Voice.entries) {
                val hz = pitches[(k + v.ordinal) % pitches.size]
                val vel = if (k % 3 == 0) 0f else if (k % 3 == 1) 1f else 0.5f
                val dur = if (k % 4 == 0) 1L else if (k % 4 == 1) 40L * rate else 3L * rate
                list += NoteEvent(t, v, hz, vel, if (k % 2 == 0) -1f else 1f, 1f, dur, if (k % 5 == 0) 0f else 1f)
                k++
            }
            t += rate / 2
        }
        synth.schedule(ComposerOutput(list, emptyList()))
        for (i in 0 until 12) synth.schedule(controls(i * 5L * rate, extremes[i % 2]))
        val out = synth.renderAll(60 * rate, 1000)
        assertTrue(allFinite(out), "non-finite sample in output")
        assertEquals(0, synth.nonFiniteHits, "non-finite values reached the limiter")
        assertTrue(out.peak() <= 1.0f, "peak ${out.peak()}")
    }

    // Regression: a missing or loosened limiter lets a thick stack of voices exceed full scale.
    @Test
    fun `output never exceeds one with every voice at full velocity`() {
        val synth = Synth(rate)
        synth.schedule(controls(0, texture(brightness = 1f, size = 1f, wet = 1f, detune = 1f, shimmer = 1f)))
        val list = ArrayList<NoteEvent>()
        val voices = listOf(Voice.Bell, Voice.Mallet, Voice.Pluck, Voice.Breath, Voice.Pad, Voice.Drone, Voice.Shimmer)
        for (i in 0 until Synth.VOICE_COUNT) {
            list += note(voices[i % voices.size], 10, 110.0 * (1 + i % 5), 1f, 6.0, pan = 0f, send = 1f, brightness = 1f)
        }
        synth.schedule(ComposerOutput(list, emptyList()))
        val out = synth.renderAll(8 * rate)
        assertTrue(out.peak() <= 1.0f, "peak ${out.peak()}")
        assertTrue(out.peak() > 0.2f, "scenario is silent: ${out.peak()}")
    }

    // Regression: with the +8 dB makeup a bed at the composer's own velocities must still leave headroom under the limiter for ornaments.
    @Test
    fun `a drone and four pad bed at the composer velocities stays under minus two dBFS`() {
        val synth = Synth(rate)
        synth.schedule(controls(0, texture(brightness = 0.5f, size = 0.5f, wet = 0.6f, detune = 0.5f)))
        synth.schedule(
            notes(
                note(Voice.Drone, 0, 110.0, 0.7f, 60.0, send = 0.5f),
                note(Voice.Pad, 0, 220.0, 0.5f, 60.0, send = 0.6f),
                note(Voice.Pad, 0, 275.0, 0.5f, 60.0, send = 0.6f),
                note(Voice.Pad, 0, 330.0, 0.5f, 60.0, send = 0.6f),
                note(Voice.Pad, 0, 412.5, 0.5f, 60.0, send = 0.6f),
            ),
        )
        val out = synth.renderAll(60 * rate)
        val peak = out.peak()
        assertTrue(peak > 0.02f, "bed is silent: $peak")
        assertTrue(peak < 0.7943f, "bed peak $peak is above -2 dBFS (0.7943)")
    }

    // Regression: the master sat about 9 dB under the -20 dBFS RMS target; a steady bed must land within 6 dB of it.
    @Test
    fun `a steady bed at the composer velocities has an rms within six dB of minus twenty dBFS`() {
        val synth = Synth(rate)
        synth.schedule(controls(0, texture(brightness = 0.5f, size = 0.5f, wet = 0.6f, detune = 0.5f)))
        synth.schedule(
            notes(
                note(Voice.Drone, 0, 110.0, 0.7f, 60.0, send = 0.5f),
                note(Voice.Pad, 0, 220.0, 0.5f, 60.0, send = 0.6f),
                note(Voice.Pad, 0, 275.0, 0.5f, 60.0, send = 0.6f),
                note(Voice.Pad, 0, 330.0, 0.5f, 60.0, send = 0.6f),
                note(Voice.Pad, 0, 412.5, 0.5f, 60.0, send = 0.6f),
            ),
        )
        val out = synth.renderAll(60 * rate)
        val db = 20 * kotlin.math.log10(out.rms(20 * rate, 50 * rate))
        assertTrue(db in -26.0..-14.0, "bed rms $db dBFS")
    }

    // Regression: a drone with a sub-octave at f/2 pushes the bed into inaudible low energy and eats headroom.
    @Test
    fun `a drone carries no energy an octave below its pitch`() {
        val synth = Synth(rate)
        synth.schedule(controls(0, texture(wet = 0f, detune = 0f)))
        synth.schedule(notes(note(Voice.Drone, 0, 220.0, 1f, 20.0)))
        val mono = mono(synth.renderAll(14 * rate))
        val at = goertzel(mono, 10 * rate, 14 * rate, 220.0, rate)
        val sub = goertzel(mono, 10 * rate, 14 * rate, 110.0, rate)
        assertTrue(sub < at * 0.02, "110 Hz $sub against 220 Hz $at")
    }

    // Regression: a voice whose envelope never idles or a tail that never ends keeps the piece from falling silent.
    @Test
    fun `every voice falls silent after its note and release`() {
        for (v in Voice.entries) {
            val synth = Synth(rate)
            synth.schedule(controls(0, texture(wet = 0f)))
            synth.schedule(notes(note(v, 100, 330.0, 1f, 2.0, send = 0f)))
            val out = synth.renderAll(20 * rate)
            assertTrue(out.peak(1000, 2 * rate) > 0.005f, "$v made no sound")
            assertTrue(out.peak(15 * rate, 20 * rate) < 1.0e-4f, "$v still sounds: ${out.peak(15 * rate, 20 * rate)}")
            assertEquals(0, synth.activeVoices, "$v voice still active")
        }
    }

    @Test
    fun `the reverb tail of a loud note falls under 1e-4 within the longest tail`() {
        val synth = Synth(rate)
        synth.schedule(controls(0, texture(size = 1f, wet = 1f)))
        synth.schedule(notes(note(Voice.Bell, 100, 440.0, 1f, 2.0, send = 1f)))
        val out = synth.renderAll(150 * rate, 2048)
        assertTrue(out.peak(5 * rate, 8 * rate) > 1.0e-3f, "no tail to speak of")
        assertTrue(out.peak(140 * rate, 150 * rate) < 1.0e-4f, "tail peak ${out.peak(140 * rate, 150 * rate)}")
    }

    private fun fullSynth(vararg events: NoteEvent): Synth {
        val synth = Synth(rate)
        synth.schedule(controls(0, texture(wet = 0f)))
        synth.schedule(ComposerOutput(events.toList(), emptyList()))
        return synth
    }

    private fun DoubleArray.has(hz: Double) = any { abs(it - hz) < 1e-6 }

    private fun DoubleArray.countIn(from: Double, to: Double) = count { it in from..to }

    private fun sustainedMallets(count: Int, atSeconds: Double = 0.0, firstHz: Double = 300.0, velocity: Float = 0.8f) =
        List(count) { note(Voice.Mallet, (atSeconds * rate).toLong(), firstHz + 10.0 * it, velocity, 60.0) }

    // Regression: stealing that does nothing drops the new note when all 24 voices are busy.
    @Test
    fun `a note arriving when every voice is busy still sounds by stealing`() {
        fun run(withBell: Boolean): FloatArray {
            val synth = Synth(rate)
            synth.schedule(controls(0, texture(wet = 0f, layers = mapOf(Voice.Mallet to 0.2f))))
            val list = ArrayList<NoteEvent>()
            for (i in 0 until Synth.VOICE_COUNT) list += note(Voice.Mallet, 0, 100.0 + 7 * i, 0.3f, 30.0)
            if (withBell) list += note(Voice.Bell, 2L * rate, 880.0, 1f, 2.0)
            synth.schedule(ComposerOutput(list, emptyList()))
            return synth.renderAll(4 * rate)
        }
        val with = run(true)
        val without = run(false)
        val from = 2 * rate + 200
        var diff = 0.0
        for (i in from * 2 until (from + 8000) * 2) diff += abs(with[i] - without[i])
        assertTrue(diff / 16000.0 > 0.005, "mean difference after the bell onset: ${diff / 16000.0}")
    }

    // Regression: stealing by loudness takes the quiet drone or pad bed first, the piece loses its floor.
    @Test
    fun `stealing takes a sounding ornament and leaves a quiet drone and pad alone`() {
        val synth = fullSynth(
            note(Voice.Drone, 0, 100.0, 0.2f, 40.0),
            note(Voice.Pad, 0, 200.0, 0.2f, 40.0),
            *sustainedMallets(22, atSeconds = 9.0).toTypedArray(),
            note(Voice.Bell, 10L * rate, 880.0, 1f, 2.0),
        )
        synth.renderAll(10 * rate + 2000)
        val pitches = synth.soundingPitches()
        assertEquals(24, pitches.size)
        assertTrue(pitches.has(100.0) && pitches.has(200.0), "a bed voice was stolen: ${pitches.toList()}")
        assertTrue(pitches.has(880.0), "the new note did not start")
        assertEquals(21, pitches.countIn(299.0, 600.0), "exactly one mallet should have been taken")
    }

    // Regression: the quietest-first rule took a younger releasing voice and left the one that had rung longest.
    @Test
    fun `stealing takes the oldest releasing ornament even when it is louder`() {
        val synth = fullSynth(
            *sustainedMallets(22).toTypedArray(),
            note(Voice.Mallet, 0, 701.0, 1f, 0.9),
            note(Voice.Mallet, (0.1 * rate).toLong(), 702.0, 0.001f, 1.0),
            note(Voice.Bell, (1.2 * rate).toLong(), 880.0, 1f, 2.0),
        )
        synth.renderAll((1.2 * rate).toInt() + 2000)
        val pitches = synth.soundingPitches()
        assertTrue(!pitches.has(701.0), "the older releasing mallet survived: ${pitches.toList()}")
        assertTrue(pitches.has(702.0), "the younger releasing mallet was taken")
        assertTrue(pitches.has(880.0))
        assertEquals(22, pitches.countIn(299.0, 600.0))
    }

    // Regression: a note still in its attack is quiet by level, so a loudness rule steals it at once and the swell never arrives.
    @Test
    fun `stealing skips voices that are still attacking`() {
        val breaths = List(23) { note(Voice.Breath, 0, 1000.0 + it, 0.5f, 10.0) }
        val synth = fullSynth(*breaths.toTypedArray(), note(Voice.Pluck, 0, 500.0, 1f, 40.0), note(Voice.Bell, rate.toLong(), 880.0, 1f, 2.0))
        synth.renderAll(rate + 2000)
        val pitches = synth.soundingPitches()
        assertTrue(!pitches.has(500.0), "the sounding pluck was not the victim: ${pitches.toList()}")
        assertTrue(pitches.has(880.0))
        assertEquals(23, pitches.countIn(999.0, 1100.0), "an attacking breath was stolen")
    }

    @Test
    fun `a note with nothing stealable is dropped and counted`() {
        val breaths = List(24) { note(Voice.Breath, 0, 1000.0 + it, 0.5f, 10.0) }
        val synth = fullSynth(*breaths.toTypedArray(), note(Voice.Bell, rate.toLong(), 880.0, 1f, 2.0))
        synth.renderAll(rate + 2000)
        val pitches = synth.soundingPitches()
        assertEquals(1, synth.droppedNotes)
        assertTrue(!pitches.has(880.0))
        assertEquals(24, pitches.countIn(999.0, 1100.0))
    }

    // Regression: a second steal chose the voice already fading and replaced its pending note, losing the first note.
    @Test
    fun `two notes stealing at the same frame both start on different voices`() {
        val synth = fullSynth(
            *sustainedMallets(24).toTypedArray(),
            note(Voice.Bell, 2L * rate, 880.0, 1f, 2.0),
            note(Voice.Bell, 2L * rate, 990.0, 1f, 2.0),
        )
        synth.renderAll(2 * rate + 2000)
        val pitches = synth.soundingPitches()
        assertEquals(24, pitches.size)
        assertTrue(pitches.has(880.0) && pitches.has(990.0), "a steal was lost: ${pitches.toList()}")
        assertEquals(22, pitches.countIn(299.0, 600.0))
        assertEquals(0, synth.droppedNotes)
    }

    // Regression: a missing layer level must mean full level, the composer lists only the layers it changes.
    @Test
    fun `a layer missing from the texture plays at full level`() {
        fun run(layers: Map<Voice, Float>): FloatArray {
            val synth = Synth(rate)
            synth.schedule(controls(0, texture(wet = 0f, layers = layers)))
            synth.schedule(notes(note(Voice.Drone, 0, 110.0, 1f, 10.0)))
            return synth.renderAll(3 * rate)
        }
        val none = run(emptyMap())
        val otherLayer = run(mapOf(Voice.Pad to 0f))
        val explicit = run(mapOf(Voice.Drone to 1f))
        assertTrue(none.peak() > 0.01f)
        assertTrue(none.contentEquals(otherLayer))
        assertTrue(none.contentEquals(explicit))
    }

    // Regression: the drop counter is bumped by the producer thread (ring full) and the render thread (pending full).
    @Test
    fun `dropped notes are not lost when two threads count them`() {
        val synth = Synth(rate)
        val total = 300000
        val one = notes(note(Voice.Bell, 1_000_000_000_000L, 440.0))
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val buf = FloatArray(128)
        val renderer = Thread {
            while (!done.get()) synth.render(buf, 64)
        }
        val producer = Thread {
            repeat(total) { synth.schedule(one) }
            done.set(true)
        }
        renderer.start()
        producer.start()
        producer.join()
        renderer.join()
        synth.render(buf, 64)
        assertEquals(total, synth.pendingNoteTotal + synth.droppedNotes)
    }

    // Regression: events applied at block edges instead of their frame move every note by up to a block.
    @Test
    fun `a note starts at its scheduled frame and not before`() {
        val synth = Synth(rate)
        synth.schedule(controls(0, texture(wet = 0f)))
        synth.schedule(notes(note(Voice.Mallet, 1234, 440.0, 1f, 1.0)))
        val out = synth.renderAll(rate, 1000)
        assertTrue(out.peak(0, 1234) < 1.0e-9f, "sound before the scheduled frame: ${out.peak(0, 1234)}")
        assertTrue(out.peak(1234, 1234 + 300) > 0.01f, "nothing right after the scheduled frame")
    }

    @Test
    fun `a note scheduled in the past plays at the next block`() {
        val synth = Synth(rate)
        synth.schedule(controls(0, texture(wet = 0f)))
        synth.renderAll(5000)
        synth.schedule(notes(note(Voice.Mallet, 10, 440.0, 1f, 1.0)))
        val out = synth.renderAll(2000)
        assertTrue(out.peak(0, 600) > 0.01f)
    }

    // Regression: state carried per block instead of per sample makes the audio depend on the block size the sink uses.
    @Test
    fun `the output does not depend on the block size`() {
        fun run(block: Int): FloatArray {
            val synth = Synth(rate)
            synth.schedule(controls(0, texture(wet = 0.5f, shimmer = 0.5f)))
            synth.schedule(
                notes(
                    note(Voice.Pad, 500, 220.0, 0.6f, 2.0, send = 0.5f),
                    note(Voice.Pluck, 7001, 330.0, 0.7f, 2.0, send = 0.4f),
                    note(Voice.Bell, 9000, 660.0, 0.7f, 2.0, send = 0.6f),
                ),
            )
            return synth.renderAll(3 * rate, block)
        }
        val a = run(64)
        val b = run(1000)
        val c = run(37)
        for (i in a.indices) {
            assertEquals(a[i], b[i], 1.0e-4f, "sample $i between block 64 and 1000")
            assertEquals(a[i], c[i], 1.0e-4f, "sample $i between block 64 and 37")
        }
    }

    @Test
    fun `the same events give the same samples`() {
        fun run(): FloatArray {
            val synth = Synth(rate)
            synth.schedule(notes(note(Voice.Breath, 0, 0.0, 0.8f, 1.0), note(Voice.Pluck, 100, 200.0)))
            return synth.renderAll(rate)
        }
        assertTrue(run().contentEquals(run()))
    }

    // Regression: a wrong pitch-to-phase unit or detune baked into the oscillator mistunes the sustained layers.
    @Test
    fun `a drone and a pad sound at the requested pitch`() {
        for (v in listOf(Voice.Drone, Voice.Pad)) {
            val synth = Synth(rate)
            synth.schedule(controls(0, texture(wet = 0f, detune = 0f)))
            synth.schedule(notes(note(v, 0, 220.0, 0.8f, 6.0)))
            val mono = mono(synth.renderAll(6 * rate))
            val at = goertzel(mono, 2 * rate, 5 * rate, 220.0, rate)
            val sharp = goertzel(mono, 2 * rate, 5 * rate, 220.0 * 1.06, rate)
            val flat = goertzel(mono, 2 * rate, 5 * rate, 220.0 / 1.06, rate)
            assertTrue(at > sharp * 4.0 && at > flat * 4.0, "$v: at $at sharp $sharp flat $flat")
        }
    }

    @Test
    fun `a pluck and a mallet sound at the requested pitch`() {
        for (v in listOf(Voice.Pluck, Voice.Mallet, Voice.Bell)) {
            val synth = Synth(rate)
            synth.schedule(controls(0, texture(wet = 0f)))
            synth.schedule(notes(note(v, 0, 330.0, 0.9f, 3.0)))
            val mono = mono(synth.renderAll(rate))
            val at = goertzel(mono, 2000, 24000, 330.0, rate)
            val sharp = goertzel(mono, 2000, 24000, 330.0 * 1.06, rate)
            val flat = goertzel(mono, 2000, 24000, 330.0 / 1.06, rate)
            assertTrue(at > sharp * 3.0 && at > flat * 3.0, "$v: at $at sharp $sharp flat $flat")
        }
    }

    // Regression: swapped channels or a gain law that leaks across the field breaks the node bearing to pan mapping.
    @Test
    fun `pan minus one is the left channel and plus one the right`() {
        fun run(pan: Float): FloatArray {
            val synth = Synth(rate)
            synth.schedule(controls(0, texture(wet = 0f)))
            synth.schedule(notes(note(Voice.Bell, 0, 440.0, 1f, 1.0, pan = pan)))
            return synth.renderAll(rate)
        }
        val left = run(-1f)
        val right = run(1f)
        val centre = run(0f)
        assertTrue(left.rms(channel = 1) < left.rms(channel = 0) * 0.01)
        assertTrue(right.rms(channel = 0) < right.rms(channel = 1) * 0.01)
        assertEquals(centre.rms(channel = 0), centre.rms(channel = 1), centre.rms(channel = 0) * 0.01)
        assertEquals(left.rms(channel = 0), centre.rms(channel = 0) * 1.4142, centre.rms(channel = 0) * 0.02)
    }

    // Regression: layer levels ignored, so the composer cannot thin the piece to near silence.
    @Test
    fun `a layer level of zero silences that voice after the smoothing`() {
        val synth = Synth(rate)
        synth.schedule(controls(0, texture(wet = 0f, layers = mapOf(Voice.Drone to 0f))))
        synth.schedule(notes(note(Voice.Drone, 0, 110.0, 1f, 40.0), note(Voice.Pad, 0, 220.0, 1f, 40.0)))
        val out = synth.renderAll(30 * rate)
        val drone = goertzel(mono(out), 25 * rate, 30 * rate, 110.0, rate)
        val pad = goertzel(mono(out), 25 * rate, 30 * rate, 220.0, rate)
        assertTrue(drone < pad * 0.05, "drone $drone pad $pad")
    }

    @Test
    fun `texture changes are smoothed and not a step`() {
        fun run(change: Boolean): FloatArray {
            val synth = Synth(rate)
            synth.schedule(controls(0, texture(wet = 0f, layers = mapOf(Voice.Drone to 1f))))
            synth.schedule(notes(note(Voice.Drone, 0, 110.0, 1f, 40.0)))
            if (change) synth.schedule(controls(10L * rate, texture(wet = 0f, layers = mapOf(Voice.Drone to 0f))))
            return synth.renderAll(14 * rate)
        }
        val steady = run(false)
        val changed = run(true)
        val soon = changed.rms(10 * rate, 10 * rate + rate / 10) / steady.rms(10 * rate, 10 * rate + rate / 10)
        val later = changed.rms(13 * rate, 14 * rate) / steady.rms(13 * rate, 14 * rate)
        assertTrue(soon > 0.9, "dropped at once to $soon")
        assertTrue(later < 0.4, "never faded, still at $later")
    }

    @Test
    fun `the noise texture makes sound with no notes and silence at zero`() {
        val quiet = Synth(rate)
        quiet.schedule(controls(0, texture(noise = 0f)))
        assertTrue(quiet.renderAll(rate).peak() < 1.0e-6f)
        val windy = Synth(rate)
        windy.schedule(controls(0, texture(noise = 1f)))
        assertTrue(windy.renderAll(10 * rate).peak(5 * rate, 10 * rate) > 0.005f)
    }

    // Regression: a full queue blocks or throws on the composer thread instead of dropping.
    @Test
    fun `scheduling more than the ring holds drops events and keeps rendering`() {
        val synth = Synth(rate)
        val many = List(10000) { note(Voice.Bell, 100L + it, 440.0, 0.1f, 0.2) }
        synth.schedule(ComposerOutput(many, emptyList()))
        assertTrue(synth.droppedNotes > 0)
        val out = synth.renderAll(rate)
        assertTrue(allFinite(out))
        assertTrue(out.peak() <= 1f)
    }

    @Test
    fun `a pitchless breath ignores the requested pitch`() {
        fun run(hz: Double): FloatArray {
            val synth = Synth(rate)
            synth.schedule(controls(0, texture(wet = 0f)))
            synth.schedule(notes(note(Voice.Breath, 0, hz, 0.8f, 2.0)))
            return synth.renderAll(2 * rate)
        }
        assertTrue(run(0.0).contentEquals(run(5000.0)))
    }
}
