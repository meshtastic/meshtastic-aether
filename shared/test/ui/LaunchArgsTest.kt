package org.meshtastic.aether.ui

import org.meshtastic.aether.theme.PaletteChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaunchArgsTest {
    // Regression: the Projector look had no way in except the overlay, so a projector launch started dark.
    @Test
    fun `look names read in any case and the oled forms agree`() {
        assertEquals(PaletteChoice.Projector, parseLook("projector"))
        assertEquals(PaletteChoice.Projector, parseLook("Projector"))
        assertEquals(PaletteChoice.Standard, parseLook("STANDARD"))
        assertEquals(PaletteChoice.OledNight, parseLook("oled"))
        assertEquals(PaletteChoice.OledNight, parseLook("oled-night"))
        assertNull(parseLook("neon"))
        assertNull(parseLook(""))
    }

    @Test
    fun `a look flag rides along with a link and fullscreen`() {
        val a = parseLaunchArgs(arrayOf("--demo", "--look", "projector", "--fullscreen"))
        assertEquals(LinkChoice.Demo, a.link)
        assertEquals(PaletteChoice.Projector, a.look)
        assertTrue(a.fullscreen)
        assertNull(a.error)
        val b = parseLaunchArgs(arrayOf("--tcp", "node.local:4404", "--look", "oled"))
        assertEquals(LinkChoice.Tcp("node.local", 4404), b.link)
        assertEquals(PaletteChoice.OledNight, b.look)
    }

    // The MIDI output rides along with any link; an empty value is no output, not an output named "".
    @Test
    fun `a midi flag names the output and an empty one names none`() {
        val a = parseLaunchArgs(arrayOf("--demo", "--midi", "Surge XT"))
        assertEquals("Surge XT", a.midi)
        assertEquals(LinkChoice.Demo, a.link)
        assertNull(parseLaunchArgs(arrayOf("--demo", "--midi")).midi)
        assertNull(parseLaunchArgs(arrayOf("--demo")).midi)
    }

    @Test
    fun `an unreadable look or tcp value is an error and not a choice`() {
        val bad = parseLaunchArgs(arrayOf("--look", "neon"))
        assertNotNull(bad.error)
        assertTrue("projector" in bad.error)
        assertNull(bad.look)
        assertNull(parseLaunchArgs(arrayOf("--look")).look)
        assertNotNull(parseLaunchArgs(arrayOf("--look")).error)
        val tcp = parseLaunchArgs(arrayOf("--tcp", "host:70000"))
        assertNotNull(tcp.error)
        assertNull(tcp.link)
    }

    @Test
    fun `no arguments means no link no error and no look`() {
        val a = parseLaunchArgs(emptyArray())
        assertNull(a.link)
        assertNull(a.error)
        assertNull(a.look)
        assertEquals(false, a.fullscreen)
        assertEquals(LinkChoice.Replay, parseLaunchArgs(arrayOf("--replay")).link)
    }

    // A fullscreen launch with nothing saved defaults to Projector; a flag or a saved look wins.
    @Test
    fun `fullscreen defaults to projector only when no flag and nothing is saved`() {
        assertEquals(PaletteChoice.Projector, resolveLook(null, null, fullscreenLaunch = true))
        assertEquals(PaletteChoice.Standard, resolveLook(null, null, fullscreenLaunch = false))
        assertEquals(PaletteChoice.Standard, resolveLook(null, PaletteChoice.Standard, fullscreenLaunch = true))
        assertEquals(PaletteChoice.OledNight, resolveLook(PaletteChoice.OledNight, PaletteChoice.Standard, fullscreenLaunch = true))
        assertEquals(PaletteChoice.Projector, resolveLook(PaletteChoice.Projector, null, fullscreenLaunch = false))
    }

    // Regression: a saved link reconnected on launch with no way to pick another radio.
    @Test
    fun `ask is read with or without other flags and defaults off`() {
        assertTrue(parseLaunchArgs(arrayOf("--ask")).ask)
        assertTrue(parseLaunchArgs(arrayOf("--fullscreen", "--ask", "--look", "oled")).ask)
        assertEquals(false, parseLaunchArgs(arrayOf("--demo")).ask)
        assertTrue(parseLaunchArgs(arrayOf("--ask", "--tcp", "host:70000")).ask)
        assertTrue(parseLaunchArgs(arrayOf("--ask", "--tcp", "node.local")).ask)
        assertTrue(parseLaunchArgs(arrayOf("--ask", "--look", "neon")).ask)
    }

    @Test
    fun `the countdown link is dropped by ask a launch link an error or a tap first platform`() {
        val saved = LinkChoice.Tcp("node.local", 4403)
        assertEquals(saved, autoConnectLink(saved, ask = false, autoConnects = true, hasLaunchLink = false, hasLaunchError = false))
        assertNull(autoConnectLink(saved, ask = true, autoConnects = true, hasLaunchLink = false, hasLaunchError = false))
        assertNull(autoConnectLink(saved, ask = false, autoConnects = false, hasLaunchLink = false, hasLaunchError = false))
        assertNull(autoConnectLink(saved, ask = false, autoConnects = true, hasLaunchLink = true, hasLaunchError = false))
        assertNull(autoConnectLink(saved, ask = false, autoConnects = true, hasLaunchLink = false, hasLaunchError = true))
        assertNull(autoConnectLink(null, ask = false, autoConnects = true, hasLaunchLink = false, hasLaunchError = false))
    }

    @Test
    fun `serial opens a port at launch and a missing port is an error`() {
        assertEquals(LinkChoice.Serial("/dev/ttyACM0", "/dev/ttyACM0"), parseLaunchArgs(arrayOf("--serial", "/dev/ttyACM0")).link)
        val missing = parseLaunchArgs(arrayOf("--serial"))
        assertNull(missing.link)
        assertNotNull(missing.error)
        assertNotNull(parseLaunchArgs(arrayOf("--serial", "--fullscreen")).error)
    }
}
