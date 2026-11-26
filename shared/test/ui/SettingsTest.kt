package org.meshtastic.ambient.ui

import org.meshtastic.ambient.ble.BleRadio
import org.meshtastic.ambient.theme.PaletteChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    // Regression: a Bluetooth scan handle cannot be saved, so choosing one must not leave an older TCP link to auto-connect.
    @Test
    fun `choosing bluetooth clears the saved link instead of keeping the previous one`() {
        val store = MemoryStore()
        val settings = Settings(store)
        settings.link = LinkChoice.Tcp("a.local", 4403)
        settings.link = LinkChoice.Ble(BleRadio("Radio", -50, "AA:BB", Any()))
        assertNull(settings.link)
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
