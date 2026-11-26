package org.meshtastic.ambient.ui

import org.meshtastic.ambient.ble.BleRadio

/** What the person picked on the connect screen, or what the host asked for at launch. */
sealed interface LinkChoice {
    /** The scripted five-minute demo mesh; needs no radio. */
    data object Demo : LinkChoice

    /** The bundled capture, looped; reached only from a host flag, never from the connect screen. */
    data object Replay : LinkChoice

    /** A node's TCP phone API: [host] is a name or address, [port] is 1..65535 (4403 by default). */
    data class Tcp(val host: String, val port: Int) : LinkChoice

    /** A node's WebSocket endpoint, for example `ws://meshtastic.local:80/ws`. */
    data class WebSocket(val url: String) : LinkChoice

    /** A scanned Bluetooth radio; it cannot be saved, because the scan handle does not outlive the scan. */
    data class Ble(val radio: BleRadio) : LinkChoice

    companion object {
        const val DEFAULT_TCP_PORT = 4403
        private const val MAX_PORT = 65_535

        /** `host` or `host:port`; null for a blank host, a non-numeric port or a port outside 1..65535. */
        fun parseTcp(text: String): Tcp? {
            val value = text.trim()
            if (value.isEmpty()) return null
            val colon = value.lastIndexOf(':')
            if (colon < 0) return Tcp(value, DEFAULT_TCP_PORT)
            val host = value.substring(0, colon).trim()
            val port = value.substring(colon + 1).toIntOrNull() ?: return null
            return if (host.isEmpty() || port !in 1..MAX_PORT) null else Tcp(host, port)
        }
    }
}
