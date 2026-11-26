package org.meshtastic.aether.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlsTest {
    // The key table from the brief: H overlay, F fullscreen, Esc, Up and Down volume, M mute, D disconnect.
    @Test
    fun `each documented key maps to its action and other keys map to none`() {
        val table = mapOf(
            Key.H to KeyAction.ToggleOverlay,
            Key.F to KeyAction.ToggleFullscreen,
            Key.Escape to KeyAction.Escape,
            Key.DirectionUp to KeyAction.VolumeUp,
            Key.DirectionDown to KeyAction.VolumeDown,
            Key.M to KeyAction.ToggleMute,
            Key.D to KeyAction.Disconnect,
        )
        for ((key, action) in table) assertEquals(action, keyActionFor(key), "key $key")
        for (other in listOf(Key.A, Key.Spacebar, Key.Enter, Key.DirectionLeft, Key.Zero)) assertNull(keyActionFor(other), "key $other")
    }

    // Regression: Esc must close the overlay before it leaves fullscreen, or one press does two things.
    @Test
    fun `escape closes the overlay first then leaves fullscreen then does nothing`() {
        val c = Controls()
        c.overlayOpen = true
        val first = c.apply(KeyAction.Escape, fullscreen = true, canFullscreen = true)
        assertFalse(c.overlayOpen)
        assertEquals(Effect.None, first.effect)
        assertTrue(first.consumed)
        val second = c.apply(KeyAction.Escape, fullscreen = true, canFullscreen = true)
        assertEquals(Effect.ToggleFullscreen, second.effect)
        val third = c.apply(KeyAction.Escape, fullscreen = false, canFullscreen = true)
        assertEquals(Effect.None, third.effect)
        assertFalse(third.consumed)
    }

    @Test
    fun `h toggles the overlay and d asks to disconnect`() {
        val c = Controls()
        c.apply(KeyAction.ToggleOverlay, false, false)
        assertTrue(c.overlayOpen)
        c.apply(KeyAction.ToggleOverlay, false, false)
        assertFalse(c.overlayOpen)
        assertEquals(Effect.Disconnect, c.apply(KeyAction.Disconnect, false, false).effect)
    }

    @Test
    fun `f without a fullscreen switch is not consumed`() {
        val r = Controls().apply(KeyAction.ToggleFullscreen, fullscreen = false, canFullscreen = false)
        assertFalse(r.consumed)
        assertEquals(Effect.None, r.effect)
        assertEquals(Effect.ToggleFullscreen, Controls().apply(KeyAction.ToggleFullscreen, false, true).effect)
    }

    @Test
    fun `volume steps by five percent and stays within zero and one`() {
        val c = Controls(volume = 0.5f)
        c.apply(KeyAction.VolumeUp, false, false)
        assertEquals(0.55f, c.volume, 1e-6f)
        c.apply(KeyAction.VolumeDown, false, false)
        c.apply(KeyAction.VolumeDown, false, false)
        assertEquals(0.45f, c.volume, 1e-6f)
        repeat(30) { c.apply(KeyAction.VolumeUp, false, false) }
        assertEquals(1f, c.volume)
        repeat(30) { c.apply(KeyAction.VolumeDown, false, false) }
        assertEquals(0f, c.volume)
    }

    // Regression: M must silence the output without losing the level, and moving the volume must unmute.
    @Test
    fun `mute silences the output and keeps the level and a volume key unmutes`() {
        val c = Controls(volume = 0.6f)
        c.apply(KeyAction.ToggleMute, false, false)
        assertEquals(0f, c.effectiveVolume)
        assertEquals(0.6f, c.volume)
        c.apply(KeyAction.ToggleMute, false, false)
        assertEquals(0.6f, c.effectiveVolume)
        c.apply(KeyAction.ToggleMute, false, false)
        c.apply(KeyAction.VolumeUp, false, false)
        assertFalse(c.muted)
        assertEquals(0.65f, c.effectiveVolume, 1e-6f)
    }

    @Test
    fun `only the volume keys repeat while held`() {
        assertEquals(setOf(KeyAction.VolumeUp, KeyAction.VolumeDown), KeyAction.entries.filter { it.repeats }.toSet())
    }

    // Regression: the master started at full the instant the engine did; it must rise from silence over about 3 s.
    @Test
    fun `the fade rises in a straight line from silence to full over three seconds`() {
        assertEquals(0f, fadeIn(0))
        assertEquals(0.5f, fadeIn(1_500), 1e-6f)
        assertEquals(1f, fadeIn(3_000))
        assertEquals(1f, fadeIn(60_000))
        assertEquals(0f, fadeIn(-100))
    }

    // Review item 9: 15 s idle closes the overlay, 60 s while Drivers is open; input restarts the idle clock.
    @Test
    fun `the overlay closes after fifteen idle seconds and sixty with drivers open`() {
        assertFalse(overlayExpired(14_999, driversOpen = false))
        assertTrue(overlayExpired(15_000, driversOpen = false))
        assertFalse(overlayExpired(15_000, driversOpen = true))
        assertFalse(overlayExpired(59_999, driversOpen = true))
        assertTrue(overlayExpired(60_000, driversOpen = true))
        assertFalse(overlayExpired(0, driversOpen = false))
    }
}
