package org.meshtastic.aether.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScalePickerTest {
    private val locrian = listOf(0, 1, 3, 5, 6, 8, 10)
    private val leans = listOf(-1e9, -3.0, -1.0, -0.7, -0.5, -0.2, 0.0, 0.2, 0.5, 0.7, 1.0, 3.0, 1e9, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
    private val biases = listOf(-1.0, 0.0, 0.3, 0.5, 0.8, 1.0, 5.0, Double.NaN)

    // Regression: a lean that stepped past Phrygian would land on Locrian, whose fifth is diminished.
    @Test
    fun `no input ever picks Locrian or a scale without a perfect fifth`() {
        for (base in Mode.entries) for (lean in leans) for (bias in biases) {
            val picked = ScalePicker().pick(base, lean, bias)
            assertFalse(picked.semitones == locrian, "$base $lean $bias")
            assertTrue(7 in picked.semitones, "$base $lean $bias gave ${picked.name}")
        }
    }

    @Test
    fun `a lean moves the base at most one step along the brightness order`() {
        val order = ScalePicker.BRIGHT_TO_DARK
        assertEquals(listOf(Mode.Lydian, Mode.Ionian, Mode.Mixolydian, Mode.Dorian, Mode.Aeolian, Mode.Phrygian), order)
        for ((i, base) in order.withIndex()) for (lean in leans) {
            val picked = ScalePicker().pick(base, lean, 0.0)
            assertTrue(picked in order)
            assertTrue(kotlin.math.abs(order.indexOf(picked) - i) <= 1, "$base lean $lean gave ${picked.name}")
        }
    }

    @Test
    fun `a bright lean steps toward Lydian and a dark one toward Phrygian`() {
        assertEquals(Mode.Ionian, ScalePicker().pick(Mode.Mixolydian, 0.9, 0.0))
        assertEquals(Mode.Dorian, ScalePicker().pick(Mode.Mixolydian, -0.9, 0.0))
        assertEquals(Mode.Mixolydian, ScalePicker().pick(Mode.Mixolydian, 0.0, 0.0))
    }

    @Test
    fun `the ends of the order hold`() {
        assertEquals(Mode.Lydian, ScalePicker().pick(Mode.Lydian, 1.0, 0.0))
        assertEquals(Mode.Phrygian, ScalePicker().pick(Mode.Phrygian, -1.0, 0.0))
    }

    // Regression: without hysteresis a lean hovering at the threshold re-picks the scale at every chord.
    @Test
    fun `a lean enters past 0 point 6 and leaves only below 0 point 35`() {
        val p = ScalePicker()
        assertEquals(Mode.Mixolydian, p.pick(Mode.Mixolydian, 0.6, 0.0), "0.6 is not past the threshold")
        assertEquals(Mode.Ionian, p.pick(Mode.Mixolydian, 0.65, 0.0))
        assertEquals(Mode.Ionian, p.pick(Mode.Mixolydian, 0.5, 0.0), "held inside the band")
        assertEquals(Mode.Ionian, p.pick(Mode.Mixolydian, 0.36, 0.0), "held just above the exit")
        assertEquals(Mode.Mixolydian, p.pick(Mode.Mixolydian, 0.34, 0.0), "released below the exit")
        assertEquals(Mode.Mixolydian, p.pick(Mode.Mixolydian, 0.5, 0.0), "not re-entered inside the band")
    }

    @Test
    fun `a dark lean has the same hysteresis`() {
        val p = ScalePicker()
        assertEquals(Mode.Dorian, p.pick(Mode.Mixolydian, -0.65, 0.0))
        assertEquals(Mode.Dorian, p.pick(Mode.Mixolydian, -0.4, 0.0))
        assertEquals(Mode.Mixolydian, p.pick(Mode.Mixolydian, -0.3, 0.0))
        assertEquals(Mode.Mixolydian, p.pick(Mode.Mixolydian, -0.5, 0.0))
    }

    @Test
    fun `a swing from bright to dark in one chord goes straight to the dark step`() {
        val p = ScalePicker()
        assertEquals(Mode.Ionian, p.pick(Mode.Mixolydian, 0.9, 0.0))
        assertEquals(Mode.Dorian, p.pick(Mode.Mixolydian, -0.9, 0.0))
    }

    @Test
    fun `the pentatonic bias opens at 0 point 7 and closes below 0 point 45`() {
        val p = ScalePicker()
        assertEquals(Mode.Mixolydian, p.pick(Mode.Mixolydian, 0.0, 0.7), "0.7 is not past the threshold")
        assertEquals(Mode.MajorPentatonic, p.pick(Mode.Mixolydian, 0.0, 0.75))
        assertEquals(Mode.MajorPentatonic, p.pick(Mode.Mixolydian, 0.0, 0.5))
        assertEquals(Mode.Mixolydian, p.pick(Mode.Mixolydian, 0.0, 0.4))
        assertEquals(Mode.Mixolydian, p.pick(Mode.Mixolydian, 0.0, 0.6))
    }

    @Test
    fun `the pentatonic keeps the major or minor quality of the scale it opens from`() {
        for (base in listOf(Mode.Lydian, Mode.Ionian, Mode.Mixolydian)) assertEquals(Mode.MajorPentatonic, ScalePicker().pick(base, 0.0, 1.0), base.name)
        for (base in listOf(Mode.Dorian, Mode.Aeolian, Mode.Phrygian)) assertEquals(Mode.MinorPentatonic, ScalePicker().pick(base, 0.0, 1.0), base.name)
    }

    @Test
    fun `a dark lean can turn a major base into the minor pentatonic`() {
        assertEquals(Mode.MinorPentatonic, ScalePicker().pick(Mode.Mixolydian, -0.9, 1.0))
        assertEquals(Mode.MajorPentatonic, ScalePicker().pick(Mode.Dorian, 0.9, 1.0))
    }
}
