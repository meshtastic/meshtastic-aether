package org.meshtastic.aether.ui

import org.meshtastic.aether.mesh.LinkState
import org.meshtastic.aether.mesh.MeshEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageReporterTest {
    private val lines = ArrayList<String>()
    private val r = CoverageReporter(write = { lines += it })
    private val min = 60_000L

    // The brief: one line per hour and one ten minutes after connect, so the first reading is early.
    @Test
    fun `writes nothing before the link is up then a line at ten minutes then one per hour`() {
        r.tick(0)
        r.tick(30 * min)
        assertEquals(0, lines.size, "no link yet")
        r.onEvent(MeshEvent.Link(LinkState.Connected, 100 * min), 100 * min)
        r.tick(109 * min)
        assertEquals(0, lines.size, "not yet ten minutes")
        r.tick(110 * min)
        assertEquals(1, lines.size)
        r.tick(169 * min)
        assertEquals(1, lines.size, "next one an hour after the first")
        r.tick(170 * min)
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.startsWith("coverage ") })
    }

    // Regression: a Connecting or Lost link must not start the early-reading clock.
    @Test
    fun `an unlinked state does not start the clock`() {
        r.onEvent(MeshEvent.Link(LinkState.Connecting, 0), 0)
        r.onEvent(MeshEvent.Link(LinkState.Lost, 1), 1)
        r.tick(24 * 60 * min)
        assertEquals(0, lines.size)
    }

    @Test
    fun `the line counts the events fed to it`() {
        r.onEvent(MeshEvent.Link(LinkState.Simulated, 0), 0)
        r.onEvent(MeshEvent.Environment(7L, 12f, null, 990f, null, null, 1), 1)
        r.onEvent(MeshEvent.Environment(8L, null, null, 991f, null, null, 2), 2)
        r.tick(10 * min)
        assertTrue("temp=1 " in lines.single() && "press=2 " in lines.single(), lines.single())
    }
}
