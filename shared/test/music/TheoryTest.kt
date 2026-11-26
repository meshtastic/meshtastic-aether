package org.meshtastic.aether.music

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TheoryTest {
    @Test
    fun `mode interval sets equal the textbook semitone patterns`() {
        assertEquals(listOf(0, 2, 4, 5, 7, 9, 11), Mode.Ionian.semitones)
        assertEquals(listOf(0, 2, 3, 5, 7, 9, 10), Mode.Dorian.semitones)
        assertEquals(listOf(0, 1, 3, 5, 7, 8, 10), Mode.Phrygian.semitones)
        assertEquals(listOf(0, 2, 4, 6, 7, 9, 11), Mode.Lydian.semitones)
        assertEquals(listOf(0, 2, 4, 5, 7, 9, 10), Mode.Mixolydian.semitones)
        assertEquals(listOf(0, 2, 3, 5, 7, 8, 10), Mode.Aeolian.semitones)
        assertEquals(listOf(0, 2, 4, 7, 9), Mode.MajorPentatonic.semitones)
        assertEquals(listOf(0, 3, 5, 7, 10), Mode.MinorPentatonic.semitones)
    }

    // Regression: stepping past the octave of a five note scale used the seven note wrap and landed on the wrong pitch.
    @Test
    fun `a pentatonic scale wraps at five steps per octave`() {
        assertEquals(12, Mode.MajorPentatonic.semitoneOfStep(5))
        assertEquals(16, Mode.MajorPentatonic.semitoneOfStep(7))
        assertEquals(-3, Mode.MajorPentatonic.semitoneOfStep(-1))
        assertEquals(15, Mode.MinorPentatonic.semitoneOfStep(6))
        assertEquals(14, Mode.Ionian.semitoneOfStep(8))
    }

    @Test
    fun `no mode is Locrian because every mode keeps a perfect fifth above its root`() {
        val locrian = listOf(0, 1, 3, 5, 6, 8, 10)
        assertEquals(8, Mode.entries.size)
        for (mode in Mode.entries) {
            assertTrue(7 in mode.semitones, "${mode.name} lacks a perfect fifth")
            assertFalse(mode.semitones == locrian)
        }
    }

    @Test
    fun `just ratios are the five limit intervals and fold across octaves`() {
        assertEquals(1.0, Theory.justRatio(0), 1e-12)
        assertEquals(5.0 / 4, Theory.justRatio(4), 1e-12)
        assertEquals(3.0 / 2, Theory.justRatio(7), 1e-12)
        assertEquals(2.0, Theory.justRatio(12), 1e-12)
        assertEquals(3.0, Theory.justRatio(19), 1e-12)
        assertEquals(3.0 / 4, Theory.justRatio(-5), 1e-12)
    }

    @Test
    fun `the seven weekdays pick seven different keys`() {
        val roots = (1..7).map { Theory.keySemitones(it) }
        assertEquals(7, roots.toSet().size)
        assertTrue(roots.all { it in 0..11 })
    }

    @Test
    fun `key root frequency follows twelve tone equal temperament from C3`() {
        assertEquals(130.8128, Theory.rootHz(0.0), 1e-3)
        assertEquals(261.6256, Theory.rootHz(12.0), 1e-3)
        assertTrue(abs(Theory.rootHz(9.0) - 220.0) < 1e-9)
    }
}
