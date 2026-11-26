package org.meshtastic.ambient.music

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarmonyTest {
    private val diatonic = Mode.entries.filter { it.size == 7 }

    private fun run(mode: Mode, seed: Long, n: Int): List<Chord> {
        val h = Harmony(seed, mode)
        return listOf(h.current) + List(n) { h.next(mode) }
    }

    @Test
    fun `consecutive chords hold at least one common tone at the same pitch`() {
        for (mode in Mode.entries) for (seed in 1L..5L) {
            run(mode, seed, 200).zipWithNext().forEach { (a, b) ->
                val held = a.voiceSemitones.zip(b.voiceSemitones).count { (x, y) -> x == y }
                assertTrue(held >= 1, "${mode.name} seed $seed held none")
            }
        }
    }

    @Test
    fun `every voice either holds or moves exactly one scale step`() {
        for (mode in Mode.entries) for (seed in 1L..5L) run(mode, seed, 300).zipWithNext().forEach { (a, b) ->
            a.voicing.zip(b.voicing).forEach { (x, y) -> assertTrue(abs(x - y) <= 1, "${mode.name} seed $seed moved $x to $y") }
        }
    }

    @Test
    fun `every chord tone lies in the mode`() {
        for (mode in Mode.entries) run(mode, 7L, 200).forEach { chord ->
            chord.voiceSemitones.forEach { s -> assertTrue(s.mod(12) in mode.semitones, "${mode.name} has no $s") }
        }
    }

    @Test
    fun `chords follow a mode change by taking their tones from the new mode`() {
        val h = Harmony(3L, Mode.Lydian)
        val modes = listOf(Mode.Aeolian, Mode.Mixolydian, Mode.Dorian, Mode.Ionian)
        repeat(40) { i ->
            val mode = modes[i % modes.size]
            h.next(mode).voiceSemitones.forEach { assertTrue(it.mod(12) in mode.semitones) }
        }
    }

    @Test
    fun `chord roots always carry a perfect fifth so no diminished triad sounds`() {
        for (mode in diatonic) run(mode, 11L, 300).forEach { chord ->
            val root = chord.roleSemitone(0)
            assertEquals(7, (chord.roleSemitone(2) - root).mod(12), "${mode.name} root step ${chord.rootStep}")
        }
    }

    @Test
    fun `the progression keeps moving to a new root each time`() {
        run(Mode.Ionian, 2L, 200).zipWithNext().forEach { (a, b) -> assertTrue(a.rootStep != b.rootStep) }
    }

    @Test
    fun `voicing stays within three octaves over a long progression in every mode`() {
        for (mode in Mode.entries) for (seed in 1L..4L) run(mode, seed, 1500).forEach { chord ->
            chord.voiceSemitones.forEach { assertTrue(it in -5..40, "${mode.name} seed $seed drifted to $it") }
        }
    }

    // Regression: the seven note wrap in the voice-leading search found no root in a five note scale and the progression stalled or threw.
    @Test
    fun `a pentatonic progression keeps every voice within one step and every tone in the scale`() {
        for (mode in listOf(Mode.MajorPentatonic, Mode.MinorPentatonic)) for (seed in 1L..5L) {
            run(mode, seed, 300).zipWithNext().forEach { (a, b) ->
                assertTrue(a.rootStep != b.rootStep)
                a.voicing.zip(b.voicing).forEach { (x, y) -> assertTrue(abs(x - y) <= 1, "${mode.name} seed $seed moved $x to $y") }
                b.voiceSemitones.forEach { assertTrue(it.mod(12) in mode.semitones, "${mode.name} has no $it") }
            }
        }
    }

    // Regression: a switch between a seven and a five note scale carried the old step numbers over and threw the voicing an octave or more away.
    @Test
    fun `switching between a mode and a pentatonic keeps the voicing within three octaves`() {
        val h = Harmony(9L, Mode.Dorian)
        val sequence = listOf(Mode.Dorian, Mode.MinorPentatonic, Mode.Dorian, Mode.Aeolian, Mode.MinorPentatonic, Mode.Ionian, Mode.MajorPentatonic)
        repeat(700) { i ->
            val mode = sequence[(i / 5) % sequence.size]
            val chord = h.next(mode)
            chord.voiceSemitones.forEach { assertTrue(it in -5..40, "step $i ${mode.name} drifted to $it") }
            chord.voiceSemitones.forEach { assertTrue(it.mod(12) in mode.semitones) }
        }
    }
}
