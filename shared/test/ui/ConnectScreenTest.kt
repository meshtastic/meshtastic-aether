package org.meshtastic.ambient.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConnectScreenTest {
    // Regression: an out of range port silently became 4403 and connected to the wrong thing.
    @Test
    fun `a port outside one to 65535 is an error and not the default`() {
        for (bad in listOf("0", "65536", "99999", "99999999999")) {
            assertEquals(TcpEntry.Invalid(PORT_ERROR), tcpEntry("node.local", bad), "port $bad")
        }
    }

    @Test
    fun `a blank port means 4403 and a good port is kept`() {
        assertEquals(TcpEntry.Valid(LinkChoice.Tcp("node.local", 4403)), tcpEntry("node.local", ""))
        assertEquals(TcpEntry.Valid(LinkChoice.Tcp("node.local", 4403)), tcpEntry(" node.local ", "  "))
        assertEquals(TcpEntry.Valid(LinkChoice.Tcp("h", 1)), tcpEntry("h", "1"))
        assertEquals(TcpEntry.Valid(LinkChoice.Tcp("h", 65535)), tcpEntry("h", "65535"))
    }

    @Test
    fun `a blank host is invalid`() {
        assertIs<TcpEntry.Invalid>(tcpEntry("  ", "4403"))
    }

    // Regression: the countdown said "Connecting to the demo in 5 s".
    @Test
    fun `the countdown starts the demo and connects to a node`() {
        assertEquals("Starting the demo in 5 s", PendingConnect(LinkChoice.Demo, Countdown(5)).text(5))
        assertEquals("Connecting to node.local:4403 in 3 s", PendingConnect(LinkChoice.Tcp("node.local", 4403), Countdown(5)).text(3))
        assertEquals("Connecting to ws://a/ws in 1 s", PendingConnect(LinkChoice.WebSocket("ws://a/ws"), Countdown(5)).text(1))
    }
}
