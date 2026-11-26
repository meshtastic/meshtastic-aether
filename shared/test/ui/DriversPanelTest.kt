package org.meshtastic.aether.ui

import org.meshtastic.aether.mood.ParamInfo
import org.meshtastic.aether.mood.ParamValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriversPanelTest {
    // Regression: the direction row has no unit, and a joined "value unit" would end in a space.
    @Test
    fun `a value without a unit shows alone and one with a unit shows both`() {
        assertEquals("clockwise", paramValueText(ParamInfo("rotation direction", "clockwise", "", null)))
        assertEquals("4.0 deg", paramValueText(ParamInfo("tilt", "4.0", "deg", null)))
    }

    @Test
    fun `a parameter with no driver reads no data and one with a driver names it`() {
        assertEquals("no data", paramDriverText(ParamInfo("tilt", "0.0", "deg", null)))
        assertEquals("pressure trend", paramDriverText(ParamInfo("tilt", "9.0", "deg", "pressure trend")))
    }

    // The overlay must list every parameter the mood turns, none missing and none blank.
    @Test
    fun `the neutral table gives a readable text for every row`() {
        for (p in ParamValues.NEUTRAL.describe()) {
            assertFalse(paramValueText(p).isBlank(), p.name)
            assertFalse(paramValueText(p).endsWith(" "), p.name)
            val text = paramDriverText(p)
            assertTrue(text == "no data" || text.startsWith("holding "), "${p.name}: $text")
        }
    }
}
