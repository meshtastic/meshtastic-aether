package org.meshtastic.ambient.music

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarmonyTest {
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
    fun `voices that do not hold move by at most two scale steps`() {
        for (seed in 1L..5L) run(Mode.Dorian, seed, 300).zipWithNext().forEach { (a, b) ->
            a.voicing.zip(b.voicing).forEach { (x, y) -> assertTrue(abs(x - y) <= 2, "moved $x to $y") }
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
        for (mode in Mode.entries) run(mode, 11L, 300).forEach { chord ->
            val root = chord.roleSemitone(0)
            assertEquals(7, (chord.roleSemitone(2) - root).mod(12), "${mode.name} root step ${chord.rootStep}")
        }
    }

    @Test
    fun `the progression keeps moving to a new root each time`() {
        run(Mode.Ionian, 2L, 200).zipWithNext().forEach { (a, b) -> assertTrue(a.rootStep != b.rootStep) }
    }

    @Test
    fun `voicing stays within three octaves over a long progression`() {
        run(Mode.Aeolian, 9L, 2000).forEach { chord ->
            chord.voiceSemitones.forEach { assertTrue(it in -5..40, "drifted to $it") }
        }
    }
}
