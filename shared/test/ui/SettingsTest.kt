package org.meshtastic.aether.ui

import org.meshtastic.aether.ble.BleRadio
import org.meshtastic.aether.theme.PaletteChoice
import org.meshtastic.aether.update.UpdateTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {
    // Regression: the saved link is what a relaunch reconnects to; a lossy encoding would reconnect to the wrong node.
    @Test
    fun `a saved tcp link reads back with the same host and port`() {
        val store = MemoryStore()
        Settings(store).link = LinkChoice.Tcp("mesh-node.local", 4410)
        assertEquals(LinkChoice.Tcp("mesh-node.local", 4410), Settings(store).link)
    }

    @Test
    fun `the demo and a websocket address survive a round trip`() {
        val store = MemoryStore()
        Settings(store).link = LinkChoice.Demo
        assertEquals(LinkChoice.Demo, Settings(store).link)
        Settings(store).link = LinkChoice.WebSocket("ws://10.0.0.5:80/ws?a=b|c")
        assertEquals(LinkChoice.WebSocket("ws://10.0.0.5:80/ws?a=b|c"), Settings(store).link)
    }

    // A serial radio is saved, unlike a Bluetooth pick, so the countdown can reopen it on the next launch.
    @Test
    fun `a serial port survives a round trip and a missing label falls back to the path`() {
        val store = MemoryStore()
        Settings(store).link = LinkChoice.Serial("/dev/serial/by-id/usb-RAK_4631-if00", "RAK4631")
        assertEquals(LinkChoice.Serial("/dev/serial/by-id/usb-RAK_4631-if00", "RAK4631"), Settings(store).link)
        assertEquals(LinkChoice.Serial("COM3", "COM3"), decodeLink("serial|COM3"))
        assertNull(decodeLink("serial|"))
    }

    // Regression: picking Bluetooth or the replay erased the saved TCP link, so the next launch had nothing to reconnect to.
    @Test
    fun `choosing a link that cannot be saved keeps the saved one`() {
        val settings = Settings(MemoryStore())
        settings.link = LinkChoice.Tcp("a.local", 4403)
        settings.link = LinkChoice.Ble(BleRadio("Radio", -50, "AA:BB", Any()))
        assertEquals(LinkChoice.Tcp("a.local", 4403), settings.link)
        settings.link = LinkChoice.Replay
        assertEquals(LinkChoice.Tcp("a.local", 4403), settings.link)
        settings.link = LinkChoice.WebSocket("ws://b.local/ws")
        assertEquals(LinkChoice.WebSocket("ws://b.local/ws"), settings.link)
    }

    @Test
    fun `an unsaveable choice with nothing saved leaves no link`() {
        val settings = Settings(MemoryStore())
        settings.link = LinkChoice.Replay
        assertNull(settings.link)
    }

    @Test
    fun `the headphone placement reads back and defaults to speakers`() {
        val store = MemoryStore()
        assertFalse(Settings(store).headphones)
        Settings(store).headphones = true
        assertTrue(Settings(store).headphones)
        Settings(store).headphones = false
        assertFalse(Settings(store).headphones)
    }

    // Regression: volume and mute were forgotten on every launch, so the app always started at full.
    @Test
    fun `volume and mute read back and default to full and unmuted`() {
        val store = MemoryStore()
        assertEquals(1f, Settings(store).volume)
        assertFalse(Settings(store).muted)
        Settings(store).volume = 0.35f
        Settings(store).muted = true
        assertEquals(0.35f, Settings(store).volume)
        assertTrue(Settings(store).muted)
        Settings(store).muted = false
        assertFalse(Settings(store).muted)
    }

    @Test
    fun `a corrupt or out of range saved volume reads as a valid level`() {
        val store = MemoryStore()
        for ((junk, expected) in listOf("loud" to 1f, "" to 1f, "NaN" to 1f, "Infinity" to 1f, "7" to 1f, "-2" to 0f)) {
            store.put("volume", junk)
            assertEquals(expected, Settings(store).volume, "junk '$junk'")
        }
    }

    // Regression: the Projector default for a fullscreen launch must yield to a look the person saved or flagged.
    @Test
    fun `the saved look is null until one is chosen`() {
        val store = MemoryStore()
        assertNull(Settings(store).savedPalette)
        Settings(store).palette = PaletteChoice.Standard
        assertEquals(PaletteChoice.Standard, Settings(store).savedPalette)
    }

    @Test
    fun `a corrupt saved link reads as no link`() {
        for (junk in listOf("", "tcp", "tcp||", "tcp|host|notaport", "tcp||4403", "ws|", "ws", "quic|x|1")) {
            val store = MemoryStore().also { it.put("link", junk) }
            assertNull(Settings(store).link, "junk '$junk'")
        }
    }

    @Test
    fun `the palette defaults to standard and reads back what was chosen`() {
        val store = MemoryStore()
        assertEquals(PaletteChoice.Standard, Settings(store).palette)
        Settings(store).palette = PaletteChoice.OledNight
        assertEquals(PaletteChoice.OledNight, Settings(store).palette)
        store.put("palette", "Neon")
        assertEquals(PaletteChoice.Standard, Settings(store).palette)
    }

    // Regression: an unset or unknown saved track must read as none, so the build's own channel stays in charge.
    @Test
    fun `the update track is unset until chosen and ignores an unknown saved value`() {
        val store = MemoryStore()
        assertNull(Settings(store).updateTrack)
        Settings(store).updateTrack = UpdateTrack.Snapshot
        assertEquals(UpdateTrack.Snapshot, Settings(store).updateTrack)
        store.put("update_track", "Nightly")
        assertNull(Settings(store).updateTrack)
    }

    // Port rules: the phone API default is 4403 and a TCP port is 1..65535.
    @Test
    fun `a tcp argument takes host and optional port and rejects the rest`() {
        assertEquals(LinkChoice.Tcp("node.local", 4403), LinkChoice.parseTcp("node.local"))
        assertEquals(LinkChoice.Tcp("10.0.0.7", 4404), LinkChoice.parseTcp(" 10.0.0.7:4404 "))
        assertEquals(LinkChoice.Tcp("h", 65535), LinkChoice.parseTcp("h:65535"))
        for (bad in listOf("", "  ", "host:", "host:abc", "host:0", "host:65536", ":4403", "host:-1")) {
            assertNull(LinkChoice.parseTcp(bad), "bad '$bad'")
        }
    }
}
