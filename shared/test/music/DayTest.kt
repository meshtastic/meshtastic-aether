package org.meshtastic.ambient.music

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayTest {
    private fun at(day: Int, hour: Int, minute: Int = 0) = Day.at(LocalMoment(day, hour * 60 + minute, 0L))

    @Test
    fun `Sunday midnight rolls into Monday by glide not by jump`() {
        val sunday = Theory.rootHz(Theory.keySemitones(7).toDouble())
        val monday = Theory.rootHz(Theory.keySemitones(1).toDouble())
        assertEquals(sunday, at(7, 23, 50).rootHz, 1e-9)
        assertEquals(monday, at(1, 0, 10).rootHz, 1e-9)
        val minutes = (1430..1439).map { Day.at(LocalMoment(7, it, 0L)) } + (0..10).map { Day.at(LocalMoment(1, it, 0L)) }
        val semitoneRatio = ln(2.0) / 12
        minutes.zipWithNext().forEach { (a, b) ->
            assertTrue(abs(ln(b.rootHz / a.rootHz)) < semitoneRatio, "one minute moved a whole semitone")
        }
        val midnight = at(1, 0, 0).rootHz
        assertTrue(midnight > minOf(sunday, monday) && midnight < maxOf(sunday, monday))
    }

    @Test
    fun `density never steps by more than a tenth across the midnight rollover`() {
        val all = (1439 downTo 1380).map { Day.at(LocalMoment(7, it, 0L)) }.reversed() +
            (0..60).map { Day.at(LocalMoment(1, it, 0L)) }
        all.zipWithNext().forEach { (a, b) -> assertTrue(abs(b.density - a.density) <= 0.1) }
        val wholeDay = (0 until 1440).map { Day.at(LocalMoment(3, it, 0L)).density }
        (wholeDay + wholeDay.first()).zipWithNext().forEach { (a, b) -> assertTrue(abs(a - b) <= 0.1) }
    }

    @Test
    fun `morning picks Lydian or Ionian`() {
        assertTrue(at(2, 9).mode in listOf(Mode.Lydian, Mode.Ionian))
        assertTrue(at(2, 10).mode in listOf(Mode.Lydian, Mode.Ionian))
    }

    @Test
    fun `afternoon picks Mixolydian or Dorian`() {
        assertTrue(at(2, 15).mode in listOf(Mode.Mixolydian, Mode.Dorian))
        assertTrue(at(2, 17).mode in listOf(Mode.Mixolydian, Mode.Dorian))
    }

    @Test
    fun `night picks Aeolian`() {
        assertEquals(Mode.Aeolian, at(2, 23).mode)
        assertEquals(Mode.Aeolian, at(2, 3).mode)
    }

    @Test
    fun `no moment of any day picks a mode outside the six`() {
        for (d in 1..7) for (m in 0 until 1440 step 7) {
            assertTrue(Day.at(LocalMoment(d, m, 0L)).mode in Mode.entries)
        }
    }

    @Test
    fun `late night is sparser lower and longer decaying than afternoon`() {
        val night = at(2, 3)
        val afternoon = at(2, 15)
        assertTrue(night.density < afternoon.density)
        assertTrue(night.registerOctaves < afternoon.registerOctaves)
        assertTrue(night.decayScale > afternoon.decayScale)
    }
}
