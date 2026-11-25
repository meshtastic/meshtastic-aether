package org.meshtastic.ambient

import org.meshtastic.sdk.InMemoryStorageProvider
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.transport.websocket.WebSocketTransport

// Scaffold probe: proves the SDK links on every target. Replaced by SdkMeshSource.
fun sdkProbe(url: String): RadioClient =
    RadioClient.Builder()
        .transport(WebSocketTransport(url))
        .storage(InMemoryStorageProvider())
        .build()
