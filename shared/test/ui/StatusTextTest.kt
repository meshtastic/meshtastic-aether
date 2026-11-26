package org.meshtastic.aether.ui

import org.meshtastic.aether.mesh.LinkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusTextTest {
    // Regression: the status line said "Listening" while the overlay said "warming up" for the same state.
    @Test
    fun `the status line and the overlay name a link state the same way`() {
        assertEquals("Listening", linkStatusText(LinkState.WarmingUp))
        for (s in listOf(LinkState.Connecting, LinkState.WarmingUp)) assertEquals(linkStatusText(s), statusLineText(s, null))
        assertEquals("${linkStatusText(LinkState.Lost)} - retrying", statusLineText(LinkState.Lost, null))
        for (s in LinkState.entries) assertTrue(linkStatusText(s).first().isUpperCase(), "$s starts uppercase")
    }

    @Test
    fun `a settled link shows no status line and an error shows in its place`() {
        for (s in listOf(LinkState.Connected, LinkState.Simulated, LinkState.Replaying)) assertNull(statusLineText(s, null), "$s")
        assertEquals("Can't reach a:1 - retrying", statusLineText(LinkState.Connecting, "Can't reach a:1 - retrying"))
    }

    // Review item 14: "Can't reach host:port - retrying", never the exception text.
    @Test
    fun `an unreachable host reads as a retry line with the target`() {
        assertEquals("Can't reach meshtastic.local:4403 - retrying", unreachableText("meshtastic.local:4403"))
        assertEquals("Can't open the link - retrying", unreachableText(null))
    }

    @Test
    fun `a start failure keeps our own checks and hides anything else`() {
        assertEquals("TCP is not available on this target", startFailureText(IllegalStateException("TCP is not available on this target")))
        assertEquals("Could not start this link", startFailureText(RuntimeException("java.net.ConnectException: Connection refused")))
        assertEquals("Could not start this link", startFailureText(IllegalArgumentException()))
    }

    // Review item 24: the hint says Click where a keyboard and pointer are the usual input.
    @Test
    fun `the hint says click with a keyboard and tap without`() {
        assertEquals("Click or press H for controls", controlsHint(true))
        assertEquals("Tap for controls", controlsHint(false))
        assertFalse("Tap" in controlsHint(true))
        assertEquals("Press Select for controls", controlsHint(hasKeyboard = false, isTelevision = true))
        assertEquals("Press Select for controls", controlsHint(hasKeyboard = true, isTelevision = true))
    }

    // Regression: the retry line sat at fixed pixels for as long as the link was down (burn-in).
    @Test
    fun `the status line shows 10 s after a change then 10 s each minute`() {
        assertTrue(statusLineVisible(0))
        assertTrue(statusLineVisible(9_999))
        assertFalse(statusLineVisible(10_000))
        assertFalse(statusLineVisible(59_999))
        assertTrue(statusLineVisible(60_000))
        assertTrue(statusLineVisible(69_999))
        assertFalse(statusLineVisible(70_000))
        assertTrue(statusLineVisible(600_000))
        assertFalse(statusLineVisible(-1))
        val visibleSeconds = (0 until 600).count { statusLineVisible(it * 1_000L) }
        assertEquals(100, visibleSeconds)
    }

    @Test
    fun `the status line moves each period and returns after four`() {
        assertEquals(listOf(0, 1, 2, 3, 0), (0..4).map { statusLineShiftSteps(it * 60_000L + 5_000) })
        assertEquals(statusLineShiftSteps(0), statusLineShiftSteps(59_999))
    }
}
