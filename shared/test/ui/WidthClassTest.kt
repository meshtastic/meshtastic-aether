package org.meshtastic.aether.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class WidthClassTest {
    // Regression: an off-by-one at a breakpoint puts a 600 dp tablet in the phone layout or an 840 dp window in one column.
    @Test
    fun `widths at the Material breakpoints land in the class that starts there`() {
        assertEquals(WidthClass.Compact, widthClass(599.dp))
        assertEquals(WidthClass.Medium, widthClass(600.dp))
        assertEquals(WidthClass.Medium, widthClass(839.dp))
        assertEquals(WidthClass.Expanded, widthClass(840.dp))
    }
}
