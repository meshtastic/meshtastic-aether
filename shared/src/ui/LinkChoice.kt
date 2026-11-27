package org.meshtastic.aether.ui

import org.meshtastic.aether.ble.BleRadio

/** What the person picked on the connect screen, or what the host asked for at launch. */
sealed interface LinkChoice {
    /** The scripted five-minute demo mesh; needs no radio. */
    data object Demo : LinkChoice

    /** The bundled capture, looped; reached only from a host flag, never from the connect screen. */
    data object Replay : LinkChoice

    /** A node's TCP phone API: [host] is a name or address, [port] is 1..65535 (4403 by default). */
    data class Tcp(val host: String, val port: Int) : LinkChoice

    /** A node's WebSocket endpoint, for example `wss://meshtastic.local/api/v1/ws`. */
    data class WebSocket(val url: String) : LinkChoice

    /** A radio on a USB serial port: [path] reopens it (a stable by-id path where Linux has one), [label] names it. */
    data class Serial(val path: String, val label: String) : LinkChoice

    /** A scanned Bluetooth radio; it cannot be saved, because the scan handle does not outlive the scan. */
    data class Ble(val radio: BleRadio) : LinkChoice

    companion object {
        const val DEFAULT_TCP_PORT = 4403
        private const val MAX_PORT = 65_535

        /** `host` or `host:port`; null for a blank host, a non-numeric port or a port outside 1..65535. */
        fun parseTcp(text: String): Tcp? {
            val value = text.trim()
            val colon = value.lastIndexOf(':')
            return when {
                value.isEmpty() -> null
                colon < 0 -> Tcp(value, DEFAULT_TCP_PORT)
                else -> tcpOrNull(value.substring(0, colon).trim(), value.substring(colon + 1).toIntOrNull())
            }
        }

        private fun tcpOrNull(host: String, port: Int?): Tcp? =
            if (host.isEmpty() || port == null || port !in 1..MAX_PORT) null else Tcp(host, port)
    }
}
