package org.meshtastic.aether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalHostTest {
    // Regression: with Nearby devices denied the gate refused every TCP and WebSocket host, though the permission only
    // covers the local network (RFC 1918, RFC 3927 link-local, RFC 4193 unique-local, RFC 6762 mDNS).
    @Test
    fun `private link-local unique-local and mdns hosts are local`() {
        listOf(
            "10.0.0.5", "172.16.1.1", "172.31.255.254", "192.168.1.242", "169.254.10.20",
            "fe80::1", "[fd12:3456::1]", "meshtastic.local", "Meshtastic.LOCAL", "meshtastic",
        ).forEach { assertTrue(isLocalHost(it), it) }
    }

    @Test
    fun `public addresses and names are not local`() {
        listOf(
            "8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "2001:4860:4860::8888",
            "mesh.example.org", "localhost",
        ).forEach { assertFalse(isLocalHost(it), it) }
    }

    @Test
    fun `the websocket host drops the scheme port and path`() {
        assertEquals("meshtastic.local", webSocketHost("ws://meshtastic.local/api/v1/ws"))
        assertEquals("mesh.example.org", webSocketHost("wss://mesh.example.org:8443/api/v1/ws"))
        assertEquals("[fd00::2]", webSocketHost("ws://[fd00::2]:80/api/v1/ws"))
    }
}
