package org.meshtastic.aether.ui

import org.meshtastic.aether.hudHeld
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppRulesTest {
    // Regression: the HUD closed under a focused control or a screen reader after 15 s.
    @Test
    fun `the hud is held by a control in use or a screen reader and not otherwise`() {
        assertFalse(hudHeld(controlInUse = false, screenReader = false))
        assertTrue(hudHeld(controlInUse = true, screenReader = false))
        assertTrue(hudHeld(controlInUse = false, screenReader = true))
    }

    @Test
    fun `a held hud never expires and a released one expires at 15 s and 60 s with drivers`() {
        val held = hudHeld(true, false)
        assertFalse(overlayExpired(10 * 60_000L, driversOpen = true, held = held))
        assertFalse(overlayExpired(14_999, driversOpen = false, held = false))
        assertTrue(overlayExpired(15_000, driversOpen = false, held = false))
        assertFalse(overlayExpired(59_999, driversOpen = true, held = false))
        assertTrue(overlayExpired(60_000, driversOpen = true, held = false))
    }
}
