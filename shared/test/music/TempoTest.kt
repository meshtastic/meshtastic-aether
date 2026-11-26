package org.meshtastic.ambient.music

import kotlin.test.Test
import kotlin.test.assertEquals

class TempoTest {
    // Expected values are hand computed: base 17 s on a quiet day (density 0.2) is 17 * (1 + 0.3 * 0.8) = 21.08 s.
    @Test
    fun `a loop period stretches by band and speed one is today's period`() {
        assertEquals(21.08, Tempo.loopPeriodSeconds(17.0, 0.2, 1.0), 1e-9)
        assertEquals(42.16, Tempo.loopPeriodSeconds(17.0, 0.2, 0.5), 1e-9)
        assertEquals(21.08 / 0.75, Tempo.loopPeriodSeconds(17.0, 0.2, 0.75), 1e-9)
        assertEquals(21.08 / 1.5, Tempo.loopPeriodSeconds(17.0, 0.2, 1.5), 1e-9)
    }

    @Test
    fun `the day's own clamp to seventeen and sixty seconds applies before the band`() {
        assertEquals(60.0, Tempo.loopPeriodSeconds(59.0, 0.2, 1.0), 1e-9)
        assertEquals(120.0, Tempo.loopPeriodSeconds(59.0, 0.2, 0.5), 1e-9)
        assertEquals(40.0, Tempo.loopPeriodSeconds(59.0, 0.2, 1.5), 1e-9)
        assertEquals(17.0, Tempo.loopPeriodSeconds(10.0, 1.0, 1.0), 1e-9)
    }

    @Test
    fun `phrase intervals divide by the speed and a bad speed falls back to one`() {
        assertEquals(1.0, Tempo.phraseSeconds(1.5, 1.5), 1e-12)
        assertEquals(3.0, Tempo.phraseSeconds(1.5, 0.5), 1e-12)
        assertEquals(1.5, Tempo.phraseSeconds(1.5, Double.NaN), 1e-12)
        assertEquals(3.0, Tempo.phraseSeconds(1.5, 0.0), 1e-12)
        assertEquals(1.0, Tempo.phraseSeconds(1.5, 99.0), 1e-12)
    }
}
