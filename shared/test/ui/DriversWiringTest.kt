package org.meshtastic.ambient.ui

import org.meshtastic.ambient.mesh.LinkState
import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mood.ParamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriversWiringTest {
    // Regression: the coverage line could not say how many batteries moved, so the battery-trend driver's support was invisible.
    @Test
    fun `the coverage line ends with the count the engine supplies`() {
        val lines = mutableListOf<String>()
        val reporter = CoverageReporter(write = { lines += it }, extra = { " batt_moved=4" })
        reporter.onEvent(MeshEvent.Link(LinkState.Connected, 0L), 0L)
        reporter.tick(CoverageReporter.FIRST_MILLIS)
        assertEquals(1, lines.size)
        assertTrue(lines.single().endsWith(" batt_moved=4"), lines.single())
    }

    // Regression: the panel printed a bare "no data" for a parameter that is holding a sensible value.
    @Test
    fun `a driverless row shows its holding sentence`() {
        val row = ParamInfo("wobble", "3.0", "deg", driver = null, hold = "holding 3.0 deg - no wind reports")
        assertEquals("holding 3.0 deg - no wind reports", paramDriverText(row))
    }

    @Test
    fun `a driven row shows the driver with its own reading`() {
        val row = ParamInfo("turn time", "420", "s", driver = "activity", reading = "0.37")
        assertEquals("activity 0.37", paramDriverText(row))
    }
}
