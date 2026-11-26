package org.meshtastic.ambient.music

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayTest {
    private fun at(day: Int, hour: Int, minute: Int = 0) = Day.at(LocalMoment(day, hour * 60 + minute, 0L))

    @Test
    fun `the key is the weekday's for the whole day and steps at midnight`() {
        val sunday = Theory.C3_HZ * 2.0.pow(5.0 / 12)
        val monday = Theory.C3_HZ
        for (minute in 0 until 1440) {
            assertEquals(sunday, Day.at(LocalMoment(7, minute, 0L)).rootHz, 1e-9, "Sunday minute $minute")
            assertEquals(monday, Day.at(LocalMoment(1, minute, 0L)).rootHz, 1e-9, "Monday minute $minute")
        }
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
    fun `night picks Aeolian and the darkest hour Phrygian`() {
        assertEquals(Mode.Phrygian, at(2, 23).mode)
        assertEquals(Mode.Aeolian, at(2, 3).mode)
        assertEquals(Mode.Aeolian, at(2, 20).mode)
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
