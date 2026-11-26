package org.meshtastic.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CountdownTest {
    // The saved-link countdown is five seconds: it fires on the fifth tick and not before.
    @Test
    fun `a five second countdown fires on the fifth tick exactly once`() {
        val c = Countdown(5)
        repeat(4) { assertFalse(c.tick(), "tick ${it + 1}") }
        assertEquals(1, c.secondsLeft)
        assertTrue(c.tick())
        assertEquals(0, c.secondsLeft)
        assertFalse(c.tick())
    }

    // Regression: a cancel in the last second must still stop the connect.
    @Test
    fun `a cancelled countdown never fires`() {
        val c = Countdown(5)
        repeat(4) { c.tick() }
        c.cancel()
        assertTrue(c.cancelled)
        assertFalse(c.tick())
        assertEquals(1, c.secondsLeft)
    }

    @Test
    fun `backoff doubles from two seconds up to a minute and starts over after a reset`() {
        val b = Backoff()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), List(7) { b.next() })
        b.reset()
        assertEquals(2_000L, b.next())
    }
}
