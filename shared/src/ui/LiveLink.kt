package org.meshtastic.ambient.ui

import org.meshtastic.ambient.ble.BleRadio
import org.meshtastic.ambient.ble.createBleTransport
import org.meshtastic.ambient.createTcpTransport
import org.meshtastic.ambient.mesh.SdkMeshSource
import org.meshtastic.sdk.InMemoryStorageProvider
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.transport.websocket.WebSocketTransport

/** A live radio link: [source] feeds the engine, [connect] opens it and [disconnect] closes it. */
class LiveLink(transport: RadioTransport) {
    private val client: RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .autoReconnect()
        .build()

    val source = SdkMeshSource(client, nowMillis = ::nowMillis)

    suspend fun connect() = client.connect()

    suspend fun disconnect() = client.disconnect()

    companion object {
        fun webSocket(url: String): LiveLink = LiveLink(WebSocketTransport(url))

        fun ble(radio: BleRadio): LiveLink? = createBleTransport(radio)?.let { LiveLink(it) }

        fun tcp(host: String, port: Int): LiveLink? = createTcpTransport(host, port)?.let { LiveLink(it) }
    }
}
