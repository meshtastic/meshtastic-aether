package org.meshtastic.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConnectLinkTest {
    // Regression: the browser has no raw sockets and offering Network there leads to a dead pane.
    @Test
    fun `the screen opens on the pending kind then the last chosen then the first real link`() {
        val all = availableKinds(supportsTcp = true, bluetooth = true)
        val web = availableKinds(supportsTcp = false, bluetooth = false)
        assertEquals(LinkKind.WebSocket, initialKind(all, LinkKind.WebSocket, LinkKind.Demo))
        assertEquals(LinkKind.Demo, initialKind(all, null, LinkKind.Demo))
        assertEquals(LinkKind.Network, initialKind(all, null, null))
        assertEquals(LinkKind.WebSocket, initialKind(web, null, null))
        assertEquals(LinkKind.WebSocket, initialKind(web, LinkKind.Network, LinkKind.Bluetooth), "kinds the platform lacks are skipped")
    }

    @Test
    fun `the chooser offers only the kinds the platform can run with real links first and the demo last`() {
        assertEquals(listOf(LinkKind.WebSocket, LinkKind.Demo), availableKinds(supportsTcp = false, bluetooth = false))
        assertEquals(
            listOf(LinkKind.Network, LinkKind.Bluetooth, LinkKind.WebSocket, LinkKind.Demo),
            availableKinds(supportsTcp = true, bluetooth = true),
        )
        assertEquals(listOf(LinkKind.Bluetooth, LinkKind.WebSocket, LinkKind.Demo), availableKinds(supportsTcp = false, bluetooth = true))
    }

    // Regression: a signal word flipped at the wrong dBm; the edges belong to the stronger word.
    @Test
    fun `signal words change at minus 60 70 and 80 dBm`() {
        assertEquals("Strong", signalLabel(-60))
        assertEquals("Good", signalLabel(-61))
        assertEquals("Good", signalLabel(-70))
        assertEquals("Fair", signalLabel(-71))
        assertEquals("Fair", signalLabel(-80))
        assertEquals("Weak", signalLabel(-81))
        assertEquals("Signal unknown", signalLabel(null))
    }

    @Test
    fun `an address tail is the last five characters so same named radios differ`() {
        assertEquals("EE:FF", addressTail("AA:BB:CC:DD:EE:FF"))
        assertEquals("AB", addressTail("AB"))
    }

    // Regression: the URL was checked only by an exception after submit.
    @Test
    fun `a websocket url needs a ws or wss scheme and a host`() {
        assertNull(webSocketError("ws://meshtastic.local:80/api/v1/ws"))
        assertNull(webSocketError("  WSS://node.example/ws "))
        assertEquals(WS_BLANK_ERROR, webSocketError("  "))
        for (bad in listOf("http://node/ws", "node.local", "ws://", "ws:///ws")) assertEquals(WS_SCHEME_ERROR, webSocketError(bad), bad)
    }

    // Regression: Connect now used the saved link and not the fields on screen.
    @Test
    fun `the current link follows the visible fields`() {
        assertEquals(LinkChoice.Demo, currentLink(LinkKind.Demo, "", "", ""))
        assertEquals(LinkChoice.Tcp("h", 99), currentLink(LinkKind.Network, "h", "99", "ws://x"))
        assertNull(currentLink(LinkKind.Network, "", "99", ""))
        assertEquals(LinkChoice.WebSocket("ws://x/ws"), currentLink(LinkKind.WebSocket, "h", "1", " ws://x/ws "))
        assertNull(currentLink(LinkKind.WebSocket, "h", "1", "x"))
        assertNull(currentLink(LinkKind.Bluetooth, "h", "1", "ws://x"))
    }

    @Test
    fun `a saved link maps to the pane that edits it`() {
        assertEquals(LinkKind.Network, LinkChoice.Tcp("h", 1).kind())
        assertEquals(LinkKind.WebSocket, LinkChoice.WebSocket("ws://a").kind())
        assertNull(LinkChoice.Replay.kind())
        assertNotNull(LinkChoice.Demo.kind())
    }

    @Test
    fun `the countdown progress total is what it started with`() {
        val c = Countdown(5)
        val p = PendingConnect(LinkChoice.Demo, c)
        c.tick()
        assertEquals(5, p.totalSeconds)
    }
}
